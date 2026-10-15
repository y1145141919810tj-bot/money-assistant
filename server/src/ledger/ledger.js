import { categoryKind, classifyDraft, DEFAULT_CATEGORIES } from '../category.js';
import { all, get, lastId, run } from '../db.js';
import { parseEvent } from '../parse/templates.js';

/** 来源到证据角色的映射。角色决定字段归属，也决定「同一类证据只能有一条」。 */
export const ROLE_BY_SOURCE = {
  notif: 'channel_view',
  sms: 'account_view',
  email: 'statement',
  csv: 'statement',
  manual: 'manual_view',
};

/**
 * 每种角色的合并时间窗口。渠道侧视图与账户侧视图之间最多差几分钟；
 * 账单是月度导出，覆盖整个周期，所以窗口放到 36 小时以容纳跨日。
 */
export const WINDOW_MS = {
  channel_view: 5 * 60 * 1000,
  account_view: 5 * 60 * 1000,
  statement: 36 * 60 * 60 * 1000,
  manual_view: 5 * 60 * 1000,
};

/**
 * 候选判定的「决定性差距」。
 *
 * 两笔金额相同时（比如 30 秒内买了两瓶一样的水），短信只属于其中一笔。
 * 若最近候选与次近候选的时间差小于此值，就无法判定归属——但我们仍然合并到最近的一笔，
 * 同时记一条待确认项。理由是：不合并会凭空多出一笔账导致重复计数，合并错只是账户归因错，
 * 两种错误的代价不对称。
 */
const DECISIVE_GAP_MS = {
  channel_view: 30 * 1000,
  account_view: 30 * 1000,
  statement: 30 * 60 * 1000,
  manual_view: 30 * 1000,
};

/** 默认配置。部署相关的取值一律从这里覆盖，不散落在逻辑里。 */
export const DEFAULT_CONFIG = {
  /** 内部转账自动配对的最小金额（分）。低于此值不自动配对，见 pairInternalTransfer 的说明。 */
  transferMinCents: 10000,
  /** 内部转账配对的时间窗口（毫秒）。 */
  transferWindowMs: 10 * 60 * 1000,
  /** 展示与按日聚合使用的时区偏移（小时）。 */
  timezoneOffsetHours: 8,
  /** 来源静默判定阈值（毫秒）。 */
  sourceStaleMs: 7 * 24 * 60 * 60 * 1000,
  /** 设备心跳静默判定阈值（毫秒）。 */
  deviceSilentMs: 6 * 60 * 60 * 1000,
};

/** @returns {'out'|'in'|'transfer'|'unknown'} 统计口径上的方向桶。 */
export function bucketOf(kind) {
  if (kind === 'expense') return 'out';
  if (kind === 'income' || kind === 'refund') return 'in';
  if (kind === 'transfer') return 'transfer';
  return 'unknown';
}

/** 方向桶必须一致才允许合并；unknown 视为任意可匹配。 */
function bucketsCompatible(a, b) {
  if (a === 'unknown' || b === 'unknown') return true;
  return a === b;
}

/**
 * 创建账本操作集合。
 *
 * @param {object} options 选项。
 * @param {import('node:sqlite').DatabaseSync} options.db 数据库。
 * @param {Function} [options.now] 当前时间函数，测试可注入。
 * @param {object} [options.config] 覆盖 [DEFAULT_CONFIG]。
 */
export function createLedger({ db, now = () => Date.now(), config = {} }) {
  const cfg = { ...DEFAULT_CONFIG, ...config };
  const categoryIds = new Map();

  function ensureNamedCategory(name, kind) {
    const key = `${kind}:${name}`;
    const cached = categoryIds.get(key);
    if (cached !== undefined) return cached;
    const existing = get(db, 'SELECT id FROM category WHERE name = ? AND kind = ?', [name, kind]);
    const id = existing
      ? Number(existing.id)
      : lastId(
          run(db, 'INSERT INTO category (name, parent_id, kind) VALUES (?, NULL, ?)', [name, kind]),
        );
    categoryIds.set(key, id);
    categoryIds.set(`id:${id}`, name);
    return id;
  }

  for (const item of DEFAULT_CATEGORIES) ensureNamedCategory(item.name, item.kind);

  /** @returns {number|null} 类目 id。 */
  function categoryIdFor(draft) {
    const name = classifyDraft(draft);
    if (!name) return null;
    return ensureNamedCategory(name, categoryKind(name));
  }

  /**
   * 已有具体类目时不被「其他」盖掉；反过来，后来的短信若带出商户，可以替换「其他」。
   *
   * @returns {number|null}
   */
  function preferredCategory(currentId, incomingId) {
    if (incomingId == null) return currentId == null ? null : Number(currentId);
    if (currentId == null) return Number(incomingId);
    const currentName = categoryIds.get(`id:${Number(currentId)}`);
    const incomingName = categoryIds.get(`id:${Number(incomingId)}`);
    if (
      (currentName === '其他' || currentName === '其他收入') &&
      incomingName &&
      incomingName !== currentName
    ) {
      return Number(incomingId);
    }
    return Number(currentId);
  }

  /**
   * 解析并入库一条捕获事件。
   *
   * @param {object} event `{ source, origin, title, text, posted_at, source_key? }`。
   * @param {object} [ctx] `{ deviceId, capturedAt }`。
   * @returns {object} `{ action, txnId?, rawEventId?, warnings? }`。
   */
  function ingestEvent(event, ctx = {}) {
    const draft = parseEvent(ctx.templateSet, {
      source: event.source,
      origin: event.origin,
      title: event.title ?? null,
      text: event.text ?? '',
      postedAt: Number(event.posted_at ?? event.postedAt),
      capturedAt: ctx.capturedAt ?? now(),
    });
    return recordDraft(draft, { ...ctx, providedSourceKey: event.source_key ?? null });
  }

  /**
   * 写入一条已解析的候选账目。
   *
   * @param {object} draft 解析结果。
   * @param {object} [ctx] `{ deviceId, providedSourceKey, payloadJson }`。
   * @returns {object} 本次入库结果。
   */
  function recordDraft(draft, ctx = {}) {
    const timestamp = now();
    if (draft.status === 'not_flow' || draft.status === 'rejected') {
      return { action: 'dropped', reason: draft.status };
    }

    const existing = get(db, 'SELECT id FROM raw_event WHERE source_key = ?', [draft.sourceKey]);
    if (existing) return { action: 'duplicate', rawEventId: Number(existing.id) };

    const rawId = lastId(
      run(
        db,
        `INSERT INTO raw_event
           (source, source_key, origin, title, text, posted_at, captured_at, parse_status, template_id, device_id, payload_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          draft.source,
          draft.sourceKey,
          draft.origin,
          draft.title,
          draft.text,
          draft.postedAt,
          draft.capturedAt,
          draft.status,
          draft.templateId,
          ctx.deviceId ?? null,
          ctx.payloadJson ?? null,
        ],
      ),
    );

    const warnings = [];
    if (ctx.providedSourceKey && ctx.providedSourceKey !== draft.sourceKey) {
      // 设备端与云端的归一化或幂等键算法已经漂移。不静默接受，因为这意味着去重会失效。
      warnings.push('source_key_mismatch');
      recordReview(
        'source_key_mismatch',
        rawId,
        null,
        `device=${ctx.providedSourceKey} cloud=${draft.sourceKey}`,
      );
    }

    bumpSourceHealth(draft);

    if (draft.status !== 'parsed') {
      return { action: 'unmatched', rawEventId: rawId, warnings };
    }

    draft.categoryId = categoryIdFor(draft);
    const role = ROLE_BY_SOURCE[draft.source] ?? 'manual_view';
    const result = matchOrCreate(draft, role, rawId);
    return { ...result, warnings };
  }

  /** 寻找可合并的已有账目；找不到就新建。 */
  function matchOrCreate(draft, role, rawId) {
    const roleWindow = WINDOW_MS[role] ?? WINDOW_MS.manual_view;
    const maxWindow = Math.max(roleWindow, WINDOW_MS.statement);

    const candidates = all(
      db,
      `SELECT t.id, t.occurred_at, t.amount_cents, t.kind, t.channel, t.account_id, t.category_id, t.confidence,
              (SELECT GROUP_CONCAT(e.role) FROM txn_evidence e WHERE e.txn_id = t.id) AS roles
         FROM txn t
        WHERE t.amount_cents = ? AND t.status = 'normal' AND ABS(t.occurred_at - ?) <= ?`,
      [draft.amountCents, draft.postedAt, maxWindow],
    );

    const eligible = [];
    for (const candidate of candidates) {
      const roles = String(candidate.roles ?? '')
        .split(',')
        .filter(Boolean);
      // 同一类证据只能有一条：否则两笔金额相同的真实消费会被并成一笔。
      if (roles.includes(role)) continue;
      if (!bucketsCompatible(bucketOf(draft.kind), bucketOf(candidate.kind))) continue;
      const allowed = Math.max(roleWindow, ...roles.map((r) => WINDOW_MS[r] ?? roleWindow));
      const delta = Math.abs(Number(candidate.occurred_at) - draft.postedAt);
      if (delta > allowed) continue;
      eligible.push({ txn: candidate, delta });
    }
    eligible.sort((a, b) => a.delta - b.delta);

    if (eligible.length === 0) return createTxn(draft, role, rawId);

    const best = eligible[0];
    const gap = DECISIVE_GAP_MS[role] ?? DECISIVE_GAP_MS.manual_view;
    if (eligible.length > 1 && eligible[1].delta - best.delta < gap) {
      recordReview(
        'ambiguous_match',
        rawId,
        Number(best.txn.id),
        `${eligible.length} candidates within window; merged into closest by time`,
      );
    }
    return mergeInto(best.txn, draft, role, rawId);
  }

  function createTxn(draft, role, rawId) {
    const timestamp = now();
    const accountId = role === 'account_view' ? ensureAccount(draft) : null;
    const id = lastId(
      run(
        db,
        `INSERT INTO txn
           (kind, occurred_at, amount_cents, currency, channel, counterparty, memo, account_id, category_id,
            status, confidence, is_internal_transfer, created_at, updated_at)
         VALUES (?, ?, ?, 'CNY', ?, ?, ?, ?, ?, 'normal', ?, 0, ?, ?)`,
        [
          draft.kind,
          draft.postedAt,
          draft.amountCents,
          draft.channel,
          draft.counterparty,
          draft.memo,
          accountId,
          draft.categoryId ?? null,
          draft.confidence,
          timestamp,
          timestamp,
        ],
      ),
    );
    run(db, 'INSERT INTO txn_evidence (txn_id, raw_event_id, role, posted_at) VALUES (?, ?, ?, ?)', [
      id,
      rawId,
      role,
      draft.postedAt,
    ]);
    const pairedWith = pairInternalTransfer(id);
    return { action: 'created', txnId: id, rawEventId: rawId, pairedWith };
  }

  function mergeInto(txn, draft, role, rawId) {
    const timestamp = now();
    run(db, 'INSERT INTO txn_evidence (txn_id, raw_event_id, role, posted_at) VALUES (?, ?, ?, ?)', [
      Number(txn.id),
      rawId,
      role,
      draft.postedAt,
    ]);

    // 字段归属：渠道侧视图提供渠道与交易对方，账户侧视图提供账户，账单提供交易对方。
    let channel = null;
    let counterparty = null;
    let memo = null;
    let accountId = null;
    if (role === 'channel_view') {
      channel = draft.channel;
      counterparty = draft.counterparty;
      memo = draft.memo;
    } else if (role === 'account_view') {
      // 账户短信只补账户，不覆盖渠道。渠道名来自支付宝/微信通知。
      accountId = draft.accountTail ? ensureAccount(draft) : null;
      memo = draft.memo;
    } else if (role === 'statement') {
      counterparty = draft.counterparty;
      memo = draft.memo;
    }

    const categoryId = preferredCategory(txn.category_id, draft.categoryId);
    run(
      db,
      `UPDATE txn SET
         channel = COALESCE(?, channel),
         counterparty = COALESCE(?, counterparty),
         memo = COALESCE(?, memo),
         account_id = COALESCE(?, account_id),
         category_id = ?,
         confidence = ?,
         updated_at = ?
       WHERE id = ?`,
      [
        channel,
        counterparty,
        memo,
        accountId,
        categoryId,
        Math.max(Number(txn.confidence ?? 0), draft.confidence),
        timestamp,
        Number(txn.id),
      ],
    );

    const pairedWith = pairInternalTransfer(Number(txn.id));
    return { action: 'merged', txnId: Number(txn.id), rawEventId: rawId, pairedWith };
  }

  /**
   * 解析或复用账户。
   *
   * 唯一键是 (bank, tail4, kind)：不同银行会出现相同尾号，只按尾号去重会把两家银行的卡合成一个账户。
   * 卡种从原文里的「信用卡」判断，避免同一家银行的储蓄卡与信用卡撞在一起。
   */
  function ensureAccount(draft) {
    if (!draft.accountTail) return null;
    const bank = draft.channel ?? '';
    const kind = /信用卡/.test(draft.text ?? '') ? 'credit' : 'bank';
    const existing = get(db, 'SELECT id FROM account WHERE bank = ? AND tail4 = ? AND kind = ?', [
      bank,
      draft.accountTail,
      kind,
    ]);
    if (existing) return Number(existing.id);
    const name = `${bank || '银行'} 尾号${draft.accountTail}`;
    return lastId(
      run(db, 'INSERT INTO account (name, kind, tail4, bank, created_at) VALUES (?, ?, ?, ?, ?)', [
        name,
        kind,
        draft.accountTail,
        bank,
        now(),
      ]),
    );
  }

  /**
   * 配对「自己账户之间的资金移动」，例如信用卡还款、余额宝转出。
   *
   * 不做这一步，每次还款都会被算成一次支出，月度支出凭空翻倍。
   *
   * 三道限制都是为了压制误判：金额必须达到 transferMinCents（小额同额收支更可能是两笔无关交易）、
   * 方向必须是一进一出、两边必须是不同的已知账户。低于金额门槛的候选不自动配对，
   * 宁可留给人工确认，也不要把真实消费从统计里抹掉。
   *
   * @returns {number|null} 配对的对手账目 id。
   */
  function pairInternalTransfer(txnId) {
    const txn = get(
      db,
      'SELECT id, kind, amount_cents, occurred_at, account_id, is_internal_transfer FROM txn WHERE id = ?',
      [txnId],
    );
    if (!txn) return null;
    if (Number(txn.is_internal_transfer) === 1) return null;
    if (txn.account_id === null || txn.account_id === undefined) return null;
    if (Number(txn.amount_cents) < cfg.transferMinCents) return null;

    const bucket = bucketOf(txn.kind);
    if (bucket !== 'out' && bucket !== 'in') return null;

    const rows = all(
      db,
      `SELECT id, kind FROM txn
        WHERE id <> ? AND amount_cents = ? AND status = 'normal' AND is_internal_transfer = 0
          AND account_id IS NOT NULL AND account_id <> ?
          AND kind IN ('expense', 'income')
          AND ABS(occurred_at - ?) <= ?
        ORDER BY ABS(occurred_at - ?) ASC`,
      [txnId, txn.amount_cents, txn.account_id, txn.occurred_at, cfg.transferWindowMs, txn.occurred_at],
    );

    const opposite = bucket === 'out' ? 'in' : 'out';
    const peer = rows.find((row) => bucketOf(row.kind) === opposite);
    if (!peer) return null;

    const timestamp = now();
    run(
      db,
      `UPDATE txn SET kind = 'transfer', is_internal_transfer = 1, transfer_peer_id = ?, updated_at = ? WHERE id = ?`,
      [Number(peer.id), timestamp, txnId],
    );
    run(
      db,
      `UPDATE txn SET kind = 'transfer', is_internal_transfer = 1, transfer_peer_id = ?, updated_at = ? WHERE id = ?`,
      [txnId, timestamp, Number(peer.id)],
    );
    return Number(peer.id);
  }

  function bumpSourceHealth(draft) {
    const unmatched = draft.status === 'unmatched' ? 1 : 0;
    run(
      db,
      `INSERT INTO source_health (origin, hit_count, last_hit_at, unmatched_count, last_unmatched_at)
       VALUES (?, 0, 0, 0, 0)
       ON CONFLICT(origin) DO NOTHING`,
      [draft.origin],
    );
    run(
      db,
      `UPDATE source_health
          SET hit_count = hit_count + 1,
              last_hit_at = ?,
              unmatched_count = unmatched_count + ?,
              last_unmatched_at = CASE WHEN ? = 1 THEN ? ELSE last_unmatched_at END
        WHERE origin = ?`,
      [draft.postedAt, unmatched, unmatched, draft.postedAt, draft.origin],
    );
  }

  function recordReview(kind, rawEventId, txnId, detail) {
    run(
      db,
      `INSERT INTO review_item (kind, raw_event_id, txn_id, detail, created_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(kind, raw_event_id, txn_id) DO NOTHING`,
      [kind, rawEventId, txnId, detail, now()],
    );
  }

  /** @returns {object} 区间内的收支合计与前若干渠道分布。 */
  function summary({ from, to }) {
    const totals = get(
      db,
      `SELECT
         COALESCE(SUM(CASE WHEN kind = 'expense' THEN amount_cents END), 0) AS expense_cents,
         COALESCE(SUM(CASE WHEN kind = 'income'  THEN amount_cents END), 0) AS income_cents,
         COALESCE(SUM(CASE WHEN kind = 'refund'  THEN amount_cents END), 0) AS refund_cents,
         COALESCE(SUM(CASE WHEN kind = 'transfer' THEN amount_cents END), 0) AS transfer_cents,
         COUNT(*) AS txn_count
       FROM txn
       WHERE status = 'normal' AND is_internal_transfer = 0 AND occurred_at >= ? AND occurred_at < ?`,
      [from, to],
    );
    const byChannel = all(
      db,
      `SELECT COALESCE(channel, '未知') AS channel, SUM(amount_cents) AS cents, COUNT(*) AS count
         FROM txn
        WHERE status = 'normal' AND is_internal_transfer = 0 AND kind = 'expense'
          AND occurred_at >= ? AND occurred_at < ?
        GROUP BY COALESCE(channel, '未知')
        ORDER BY cents DESC`,
      [from, to],
    );
    const byCategory = all(
      db,
      `SELECT COALESCE(c.name, '未分类') AS category, SUM(t.amount_cents) AS cents, COUNT(*) AS count
         FROM txn t
         LEFT JOIN category c ON c.id = t.category_id
        WHERE t.status = 'normal' AND t.is_internal_transfer = 0 AND t.kind = 'expense'
          AND t.occurred_at >= ? AND t.occurred_at < ?
        GROUP BY COALESCE(c.name, '未分类')
        ORDER BY cents DESC`,
      [from, to],
    );
    const zone = `${cfg.timezoneOffsetHours >= 0 ? '+' : ''}${cfg.timezoneOffsetHours} hours`;
    const byDay = all(
      db,
      `SELECT strftime('%Y-%m-%d', occurred_at / 1000, 'unixepoch', ?) AS day,
              COALESCE(SUM(CASE WHEN kind = 'expense' THEN amount_cents END), 0) AS expense_cents,
              COALESCE(SUM(CASE WHEN kind = 'income' THEN amount_cents END), 0) AS income_cents,
              COALESCE(SUM(CASE WHEN kind = 'refund' THEN amount_cents END), 0) AS refund_cents
         FROM txn
        WHERE status = 'normal' AND is_internal_transfer = 0
          AND occurred_at >= ? AND occurred_at < ?
        GROUP BY day
        ORDER BY day ASC`,
      [zone, from, to],
    );
    return { from, to, totals, byChannel, byCategory, byDay };
  }

  /** @returns {Array<object>} 按发生时间倒序的账目。 */
  function listTxns({ limit = 100, from = 0, to = Number.MAX_SAFE_INTEGER } = {}) {
    return all(
      db,
      `SELECT t.id, t.kind, t.occurred_at, t.amount_cents, t.channel, t.counterparty, t.memo,
              t.status, t.confidence, t.is_internal_transfer, a.name AS account_name,
              (SELECT GROUP_CONCAT(e.role) FROM txn_evidence e WHERE e.txn_id = t.id) AS roles
         FROM txn t
         LEFT JOIN account a ON a.id = t.account_id
        WHERE t.occurred_at >= ? AND t.occurred_at < ?
        ORDER BY t.occurred_at DESC
        LIMIT ?`,
      [from, to, limit],
    );
  }

  /** @returns {Array<object>} 未识别条目，用于补齐模板库。 */
  function unmatched(limit = 100) {
    return all(
      db,
      `SELECT id, source, origin, text, posted_at, device_id
         FROM raw_event
        WHERE parse_status = 'unmatched'
        ORDER BY posted_at DESC
        LIMIT ?`,
      [limit],
    );
  }

  /** @returns {Array<object>} 未处理的待确认项。 */
  function reviewItems(limit = 100) {
    return all(
      db,
      `SELECT id, kind, raw_event_id, txn_id, detail, created_at
         FROM review_item
        WHERE resolved_at IS NULL
        ORDER BY created_at DESC
        LIMIT ?`,
      [limit],
    );
  }

  /**
   * @param {number} id 待确认项 id。
   * @returns {boolean} 是否确实更新了一条。
   */
  function resolveReview(id) {
    return run(db, 'UPDATE review_item SET resolved_at = ? WHERE id = ? AND resolved_at IS NULL', [
      now(),
      id,
    ]).changes > 0;
  }

  /** @returns {Array<object>} 各来源健康度。 */
  function sourceHealth() {
    return all(
      db,
      `SELECT origin, hit_count, last_hit_at, unmatched_count, last_unmatched_at
         FROM source_health
        ORDER BY last_hit_at DESC`,
    );
  }

  /**
   * 找出「曾经有命中、最近却不响了」的来源。
   *
   * 这是整套系统最重要的反脆弱检查：微信支付提醒被静音、银行改了短信号码、
   * 通知使用权被系统更新清掉，都会表现为某个来源长期零命中。没有这条检查，
   * 你可能几个月后才发现什么都没记上。
   *
   * @returns {Array<object>} 静默来源。
   */
  function staleSources({ at = now(), staleMs = cfg.sourceStaleMs } = {}) {
    return all(
      db,
      `SELECT origin, hit_count, last_hit_at
         FROM source_health
        WHERE hit_count > 0 AND last_hit_at < ?
        ORDER BY last_hit_at ASC`,
      [at - staleMs],
    );
  }

  /**
   * @param {object} heartbeat `{ deviceId, battery, notificationOk, smsOk, pendingUpload }`。
   * @returns {object} 本次心跳记录与即时告警。
   */
  function recordHeartbeat(heartbeat) {
    const timestamp = now();
    run(
      db,
      `INSERT INTO heartbeat (device_id, at, battery, notification_ok, sms_ok, pending_upload)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        heartbeat.deviceId,
        timestamp,
        heartbeat.battery ?? null,
        heartbeat.notificationOk === undefined ? null : heartbeat.notificationOk ? 1 : 0,
        heartbeat.smsOk === undefined ? null : heartbeat.smsOk ? 1 : 0,
        heartbeat.pendingUpload ?? null,
      ],
    );
    run(db, 'UPDATE device SET last_seen_at = ? WHERE id = ?', [timestamp, heartbeat.deviceId]);
    const alerts = [];
    if (heartbeat.notificationOk === false) alerts.push('notification_permission_lost');
    if (heartbeat.smsOk === false) alerts.push('sms_permission_lost');
    return { at: timestamp, alerts };
  }

  /**
   * @returns {Array<object>} 超过 silentMs 未上报心跳的设备。
   */
  function silentDevices({ at = now(), silentMs = cfg.deviceSilentMs } = {}) {
    return all(
      db,
      `SELECT d.id, d.name, d.last_seen_at,
              (SELECT MAX(at) FROM heartbeat h WHERE h.device_id = d.id) AS last_heartbeat_at
         FROM device d
        WHERE d.last_seen_at IS NOT NULL AND d.last_seen_at < ?`,
      [at - silentMs],
    );
  }

  return {
    config: cfg,
    ingestEvent,
    recordDraft,
    summary,
    listTxns,
    unmatched,
    reviewItems,
    resolveReview,
    sourceHealth,
    staleSources,
    recordHeartbeat,
    silentDevices,
  };
}
