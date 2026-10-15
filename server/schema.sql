-- 云端账本 schema（SQLite；语法在 node:sqlite 与 Cloudflare D1 上都可用）。
--
-- 两条不可动摇的规则：
--   1. 金额一律是以「分」为单位的整数，禁止浮点。
--   2. raw_event 只追加，永不修改、永不删除；账目以 source_key 幂等。
--
-- 统计口径：只有 kind='expense' 且 status='normal' 且 is_internal_transfer=0 的账目计入支出。
-- 信用卡还款、余额宝转出这类「自己账户之间的移动」必须打上 is_internal_transfer，否则支出会被系统性高估。

CREATE TABLE IF NOT EXISTS raw_event (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    source        TEXT    NOT NULL,              -- notif | sms | email | csv | manual
    source_key    TEXT    NOT NULL UNIQUE,       -- 幂等键
    origin        TEXT    NOT NULL,              -- 通知包名或短信发件号码
    title         TEXT,
    text          TEXT,                          -- 已通过隐私闸门的归一化文本
    posted_at     INTEGER NOT NULL,              -- 事件发生时间，毫秒 UTC
    captured_at   INTEGER NOT NULL,
    parse_status  TEXT    NOT NULL,              -- parsed | unmatched | not_flow | rejected
    template_id   TEXT,
    device_id     TEXT REFERENCES device(id),
    payload_json  TEXT
);

CREATE INDEX IF NOT EXISTS idx_raw_status ON raw_event(parse_status, posted_at DESC);
CREATE INDEX IF NOT EXISTS idx_raw_origin ON raw_event(origin, posted_at DESC);

-- 设备鉴权。token 只存 sha256，不存明文。
--
-- 刻意不做 HMAC 签名：TLS 已经保护传输，而重放安全由 source_key 幂等键保证——
-- 同一请求重放一百次也只会产生一条账目。引入签名只会多一份要保管的密钥。
CREATE TABLE IF NOT EXISTS device (
    id            TEXT PRIMARY KEY,
    name          TEXT,
    token_hash    TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER
);

-- 账户。一个「账户」= 一个实际出资/入账的钱包或银行卡。
-- 唯一键必须带 bank：不同银行完全可能出现相同尾号，只按 tail4 去重会把工行尾号1234 和招行尾号1234 合成一个账户，
-- 之后的转账配对与账户归因就全错了。bank 用 NOT NULL DEFAULT '' 而不是可空，否则 SQLite 的 UNIQUE 不会约束 NULL。
CREATE TABLE IF NOT EXISTS account (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    kind       TEXT NOT NULL DEFAULT 'bank',     -- bank | credit | wallet | cash
    tail4      TEXT NOT NULL DEFAULT '',
    bank       TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    UNIQUE (bank, tail4, kind)
);

-- 账目。多源证据合并后的唯一权威记录。
CREATE TABLE IF NOT EXISTS txn (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    kind                 TEXT    NOT NULL,        -- expense | income | refund | transfer | unknown
    occurred_at          INTEGER NOT NULL,
    amount_cents         INTEGER NOT NULL,
    currency             TEXT    NOT NULL DEFAULT 'CNY',
    channel              TEXT,                    -- 支付宝 | 微信支付 | 银行名
    counterparty         TEXT,
    memo                 TEXT,
    account_id           INTEGER REFERENCES account(id),
    category_id          INTEGER REFERENCES category(id),
    status               TEXT    NOT NULL DEFAULT 'normal',  -- normal | needs_review | voided
    confidence           REAL    NOT NULL DEFAULT 0,
    is_internal_transfer INTEGER NOT NULL DEFAULT 0,
    transfer_peer_id     INTEGER REFERENCES txn(id),
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_txn_time ON txn(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_txn_match ON txn(amount_cents, occurred_at);
CREATE INDEX IF NOT EXISTS idx_txn_kind ON txn(kind, status);

-- 证据关联：一笔账目由哪些原始事件支撑。role 决定字段归属与「不重复合并」规则。
CREATE TABLE IF NOT EXISTS txn_evidence (
    txn_id       INTEGER NOT NULL REFERENCES txn(id),
    raw_event_id INTEGER NOT NULL REFERENCES raw_event(id),
    role         TEXT    NOT NULL,               -- channel_view | account_view | statement | manual_view
    posted_at    INTEGER NOT NULL,
    PRIMARY KEY (raw_event_id),
    UNIQUE (txn_id, role)
);

CREATE INDEX IF NOT EXISTS idx_evidence_txn ON txn_evidence(txn_id);

CREATE TABLE IF NOT EXISTS category (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT NOT NULL,
    parent_id INTEGER REFERENCES category(id),
    kind      TEXT NOT NULL DEFAULT 'expense',   -- expense | income
    UNIQUE (name, parent_id)
);

-- 商户名到类目的映射。用户纠正一次即永久生效，是分类准确率提升的唯一来源。
CREATE TABLE IF NOT EXISTS merchant_rule (
    pattern     TEXT PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES category(id),
    hits        INTEGER NOT NULL DEFAULT 0,
    updated_at  INTEGER NOT NULL
);

-- 预算信封。category_id 为 NULL 表示总预算。
CREATE TABLE IF NOT EXISTS budget (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    period           TEXT NOT NULL,               -- daily | weekly | monthly
    category_id      INTEGER REFERENCES category(id),
    limit_cents      INTEGER NOT NULL,
    rollover         INTEGER NOT NULL DEFAULT 0,
    alert_thresholds TEXT NOT NULL DEFAULT '0.5,0.8,1.0',
    created_at       INTEGER NOT NULL
);

-- 已发出的预算告警。用于避免同一周期同一档位反复播报。
CREATE TABLE IF NOT EXISTS budget_alert (
    budget_id   INTEGER NOT NULL REFERENCES budget(id),
    period_key  TEXT NOT NULL,
    level       TEXT NOT NULL,                    -- 0.5 | 0.8 | 1.0
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (budget_id, period_key, level)
);

-- 待人工确认队列：金额冲突、多候选无法判定、对账差异。
CREATE TABLE IF NOT EXISTS review_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT NOT NULL,                   -- ambiguous_match | amount_conflict | reconcile_diff
    raw_event_id INTEGER REFERENCES raw_event(id),
    txn_id       INTEGER REFERENCES txn(id),
    detail       TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    resolved_at  INTEGER,
    UNIQUE (kind, raw_event_id, txn_id)
);

-- 来源健康度。某个来源长期零命中即说明采集链路静默失效。
CREATE TABLE IF NOT EXISTS source_health (
    origin            TEXT PRIMARY KEY,
    hit_count         INTEGER NOT NULL DEFAULT 0,
    last_hit_at       INTEGER NOT NULL DEFAULT 0,
    unmatched_count   INTEGER NOT NULL DEFAULT 0,
    last_unmatched_at INTEGER NOT NULL DEFAULT 0
);

-- 设备心跳。云端超过阈值收不到心跳就告警，避免「以为在记，其实没记」。
CREATE TABLE IF NOT EXISTS heartbeat (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id           TEXT NOT NULL REFERENCES device(id),
    at                  INTEGER NOT NULL,
    battery             INTEGER,
    notification_ok     INTEGER,
    sms_ok              INTEGER,
    pending_upload      INTEGER
);

CREATE INDEX IF NOT EXISTS idx_heartbeat_device ON heartbeat(device_id, at DESC);

-- 对账记录：账本支出合计 vs 账单 CSV 合计。
CREATE TABLE IF NOT EXISTS reconcile_run (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    period_start    INTEGER NOT NULL,
    period_end      INTEGER NOT NULL,
    ledger_cents    INTEGER NOT NULL,
    statement_cents INTEGER NOT NULL,
    diff_ratio      REAL    NOT NULL,
    ok              INTEGER NOT NULL,
    created_at      INTEGER NOT NULL
);

-- 账单文件导入批次。file_hash 保证同一份账单不会被重复导入。
CREATE TABLE IF NOT EXISTS import_batch (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source      TEXT    NOT NULL,                 -- alipay | wechat | bank
    file_hash   TEXT    NOT NULL UNIQUE,
    row_count   INTEGER NOT NULL,
    imported_at INTEGER NOT NULL
);

-- 播报出站队列。P2 由 notifier 消费；先落库保证告警不会因进程重启而丢失。
CREATE TABLE IF NOT EXISTS notification_outbox (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT NOT NULL,                   -- txn | daily | budget | health | reconcile
    payload_json TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    sent_at      INTEGER,
    error        TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON notification_outbox(sent_at, created_at);
