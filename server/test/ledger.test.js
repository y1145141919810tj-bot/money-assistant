import assert from 'node:assert/strict';
import { test } from 'node:test';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDatabase, get } from '../src/db.js';
import { createLedger } from '../src/ledger/ledger.js';
import { loadTemplateSet } from '../src/parse/templates.js';
import { monthRange } from '../src/period.js';

const templates = loadTemplateSet(
  join(dirname(fileURLToPath(import.meta.url)), '../../templates/templates.json'),
);

const OCT_15 = Date.UTC(2026, 9, 15, 4, 0, 0);

function ledger() {
  const db = openDatabase(':memory:');
  return {
    db,
    ledger: createLedger({ db, now: () => OCT_15 }),
  };
}

function ingest(book, event) {
  return book.ledger.ingestEvent(event, { templateSet: templates });
}

test('same source key is stored once', () => {
  const book = ledger();
  const event = {
    source: 'sms',
    origin: '95588',
    text: '您尾号1234的储蓄卡账户8月5日12:30支出(消费)人民币100.00元，余额1234.56元。',
    posted_at: OCT_15,
  };
  const first = ingest(book, event);
  const second = ingest(book, event);
  assert.equal(first.action, 'created');
  assert.equal(second.action, 'duplicate');
  assert.equal(get(book.db, 'SELECT COUNT(*) AS n FROM txn').n, 1);
});

test('verification-code sms is dropped and not stored', () => {
  const book = ledger();
  const result = ingest(book, {
    source: 'sms',
    origin: '95588',
    text: '您的验证码是 123456，请勿告诉他人。',
    posted_at: OCT_15,
  });
  assert.equal(result.action, 'dropped');
  assert.equal(get(book.db, 'SELECT COUNT(*) AS n FROM raw_event').n, 0);
});

test('alipay notification and bank sms of the same payment merge', () => {
  const book = ledger();
  const created = ingest(book, {
    source: 'notif',
    origin: 'com.eg.android.AlipayGphone',
    text: '支付成功12.00元',
    posted_at: OCT_15,
  });
  const merged = ingest(book, {
    source: 'sms',
    origin: '95588',
    text: '您尾号1234的储蓄卡账户8月5日12:30支出(美团)人民币12.00元，余额100.00元。',
    posted_at: OCT_15 + 60_000,
  });
  assert.equal(created.action, 'created');
  assert.equal(merged.action, 'merged');
  assert.equal(merged.txnId, created.txnId);
  const txn = get(
    book.db,
    `SELECT t.channel, t.kind, t.amount_cents, c.name AS category, a.tail4
       FROM txn t
       LEFT JOIN category c ON c.id = t.category_id
       LEFT JOIN account a ON a.id = t.account_id
      WHERE t.id = ?`,
    [created.txnId],
  );
  assert.equal(txn.channel, '支付宝');
  assert.equal(txn.kind, 'expense');
  assert.equal(Number(txn.amount_cents), 1200);
  assert.equal(txn.category, '餐饮');
  assert.equal(txn.tail4, '1234');
  assert.equal(get(book.db, 'SELECT COUNT(*) AS n FROM txn').n, 1);
  assert.equal(get(book.db, 'SELECT COUNT(*) AS n FROM txn_evidence').n, 2);
});

test('transfer between own accounts is excluded from spending', () => {
  const book = ledger();
  ingest(book, {
    source: 'sms',
    origin: '95588',
    text: '您尾号1234的储蓄卡账户8月5日12:30支出(还款)人民币200.00元，余额800.00元。',
    posted_at: OCT_15,
  });
  ingest(book, {
    source: 'sms',
    origin: '95555',
    text: '您尾号5678的储蓄卡账户8月5日12:31收入(还款)人民币200.00元，余额5000.00元。',
    posted_at: OCT_15 + 60_000,
  });
  const report = book.ledger.summary(monthRange('2026-10', 8));
  assert.equal(Number(report.totals.expense_cents), 0);
  assert.equal(Number(report.totals.income_cents), 0);
  assert.equal(
    Number(get(book.db, "SELECT COUNT(*) AS n FROM txn WHERE kind = 'transfer' AND is_internal_transfer = 1").n),
    2,
  );
});

test('monthly summary groups spending by category and day', () => {
  const book = ledger();
  ingest(book, {
    source: 'sms',
    origin: '95588',
    text: '您尾号1234的储蓄卡账户8月5日12:30支出(美团)人民币32.50元，余额100.00元。',
    posted_at: OCT_15,
  });
  ingest(book, {
    source: 'sms',
    origin: '95588',
    text: '您尾号1234的储蓄卡账户8月5日18:00收入(工资)人民币1000.00元，余额1100.00元。',
    posted_at: OCT_15 + 6 * 60 * 60 * 1000,
  });
  const report = book.ledger.summary(monthRange('2026-10', 8));
  assert.equal(Number(report.totals.expense_cents), 3250);
  assert.equal(Number(report.totals.income_cents), 100000);
  assert.equal(report.byCategory[0].category, '餐饮');
  assert.equal(Number(report.byCategory[0].cents), 3250);
  assert.equal(report.byDay.length, 1);
  assert.equal(report.byDay[0].day, '2026-10-15');
  assert.equal(Number(report.byDay[0].expense_cents), 3250);
  assert.equal(Number(report.byDay[0].income_cents), 100000);
});
