import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { startServer } from '../src/http/server.js';

const OCT_15 = Date.UTC(2026, 9, 15, 4, 0, 0);

async function boot() {
  const dir = mkdtempSync(join(tmpdir(), 'pocketledger-'));
  const started = await startServer({
    dataDir: dir,
    host: '127.0.0.1',
    port: 0,
    databasePath: join(dir, 'ledger.sqlite'),
    tokenFile: join(dir, 'device.token'),
    env: { DEVICE_ID: 'phone', DEVICE_TOKEN: 'test-token', DEVICE_NAME: '测试手机' },
  });
  return started;
}

test('batch ingest requires the device token and then serves the month', async () => {
  const started = await boot();
  try {
    const base = `http://127.0.0.1:${started.port}`;
    const denied = await fetch(`${base}/v1/report/monthly?month=2026-10`);
    assert.equal(denied.status, 401);

    const posted = await fetch(`${base}/v1/events:batch`, {
      method: 'POST',
      headers: {
        authorization: 'Bearer test-token',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        events: [
          {
            source: 'notif',
            origin: 'com.eg.android.AlipayGphone',
            text: '支付成功12.00元',
            posted_at: OCT_15,
          },
          {
            source: 'sms',
            origin: '95588',
            text: '您尾号1234的储蓄卡账户8月5日12:30支出(美团)人民币12.00元，余额100.00元。',
            posted_at: OCT_15 + 30_000,
          },
        ],
      }),
    });
    assert.equal(posted.status, 200);
    const accepted = await posted.json();
    assert.equal(accepted.accepted, 2);
    assert.equal(accepted.results[0].action, 'created');
    assert.equal(accepted.results[1].action, 'merged');

    const again = await fetch(`${base}/v1/events:batch`, {
      method: 'POST',
      headers: {
        authorization: 'Bearer test-token',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        events: [
          {
            source: 'notif',
            origin: 'com.eg.android.AlipayGphone',
            text: '支付成功12.00元',
            posted_at: OCT_15,
          },
        ],
      }),
    });
    const replay = await again.json();
    assert.equal(replay.results[0].action, 'duplicate');

    const reportRes = await fetch(`${base}/v1/report/monthly?month=2026-10`, {
      headers: { authorization: 'Bearer test-token' },
    });
    assert.equal(reportRes.status, 200);
    const report = await reportRes.json();
    assert.equal(report.month, '2026-10');
    assert.equal(report.totals.expense_cents, 1200);
    assert.equal(report.byCategory[0].category, '餐饮');
    assert.equal(report.recent.length, 1);
    assert.equal(report.recent[0].channel, '支付宝');
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
    started.db.close();
  }
});
