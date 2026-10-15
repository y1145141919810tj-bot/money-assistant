import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { get, openDatabase, run, inTransaction } from '../db.js';
import { createLedger } from '../ledger/ledger.js';
import { loadTemplateSet } from '../parse/templates.js';
import { currentMonth, monthRange } from '../period.js';

const MAX_BODY = 1_000_000;
const MAX_BATCH = 200;
const SOURCES = new Set(['notif', 'sms', 'email', 'csv', 'manual']);

/**
 * @param {string} token 设备令牌明文。
 * @returns {string} sha256 十六进制。
 */
export function tokenHash(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

/**
 * 确保这台电脑上有且仅使用一份设备令牌。
 * 明文只落在本机 `device.token`，数据库里只存哈希。
 *
 * @param {import('node:sqlite').DatabaseSync} db 数据库。
 * @param {object} paths `{ tokenFile }`。
 * @param {object} [env] 覆盖用的环境变量。
 * @returns {{ id: string, token: string, created: boolean }}
 */
export function ensureDevice(db, { tokenFile, now = () => Date.now() }, env = process.env) {
  const id = env.DEVICE_ID || 'phone';
  const fromEnv = env.DEVICE_TOKEN;
  let token = fromEnv;
  let created = false;
  if (!token) {
    try {
      token = readFileSync(tokenFile, 'utf8').trim();
    } catch (error) {
      // 文件不存在是首次启动，不是错误。
      if (error.code !== 'ENOENT') throw error;
    }
  }
  if (!token) {
    token = randomBytes(24).toString('hex');
    mkdirSync(dirname(tokenFile), { recursive: true });
    writeFileSync(tokenFile, `${token}\n`, { encoding: 'utf8', mode: 0o600 });
    created = true;
  }
  const hash = tokenHash(token);
  const existing = get(db, 'SELECT id, token_hash FROM device WHERE id = ?', [id]);
  if (!existing) {
    run(db, 'INSERT INTO device (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)', [
      id,
      env.DEVICE_NAME || '手机',
      hash,
      now(),
    ]);
  } else if (existing.token_hash !== hash) {
    run(db, 'UPDATE device SET token_hash = ? WHERE id = ?', [hash, id]);
  }
  return { id, token, created };
}

/**
 * @param {object} options `{ db, ledger, templateSet }`。
 * @returns {import('node:http').Server}
 */
export function createHttpServer({ db, ledger, templateSet }) {
  return createServer(async (req, res) => {
    try {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      if (req.method === 'GET' && url.pathname === '/health') {
        sendJson(res, 200, { ok: true });
        return;
      }

      const device = authenticate(db, req.headers.authorization);
      if (!device) {
        sendJson(res, 401, { error: 'unauthorized' });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/v1/events:batch') {
        const body = await readJson(req);
        const events = body.events;
        if (!Array.isArray(events) || events.length === 0 || events.length > MAX_BATCH) {
          sendJson(res, 400, { error: `events must be an array of 1..${MAX_BATCH}` });
          return;
        }
        const results = [];
        for (const event of events) {
          const problem = validateEvent(event);
          if (problem) {
            sendJson(res, 400, { error: problem });
            return;
          }
          results.push(
            inTransaction(db, () =>
              ledger.ingestEvent(event, { templateSet, deviceId: device.id }),
            ),
          );
        }
        sendJson(res, 200, { accepted: results.length, results });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/v1/heartbeat') {
        const body = await readJson(req);
        const result = ledger.recordHeartbeat({
          deviceId: device.id,
          battery: body.battery,
          notificationOk: body.notification_ok,
          smsOk: body.sms_ok,
          pendingUpload: body.pending_upload,
        });
        sendJson(res, 200, result);
        return;
      }

      if (req.method === 'GET' && url.pathname === '/v1/report/monthly') {
        const month = url.searchParams.get('month') || currentMonth(undefined, ledger.config.timezoneOffsetHours);
        const range = monthRange(month, ledger.config.timezoneOffsetHours);
        if (!range) {
          sendJson(res, 400, { error: 'month must be YYYY-MM' });
          return;
        }
        const report = ledger.summary(range);
        report.month = month;
        report.recent = ledger.listTxns({ from: range.from, to: range.to, limit: 40 });
        sendJson(res, 200, report);
        return;
      }

      if (req.method === 'GET' && url.pathname === '/v1/txns') {
        const from = Number(url.searchParams.get('from') ?? 0);
        const to = Number(url.searchParams.get('to') ?? Number.MAX_SAFE_INTEGER);
        const limit = Math.min(Number(url.searchParams.get('limit') ?? 100), 200);
        if (!Number.isFinite(from) || !Number.isFinite(to) || !Number.isFinite(limit)) {
          sendJson(res, 400, { error: 'from, to and limit must be numbers' });
          return;
        }
        sendJson(res, 200, { txns: ledger.listTxns({ from, to, limit }) });
        return;
      }

      sendJson(res, 404, { error: 'not found' });
    } catch (error) {
      const status = error.status ?? 500;
      if (status >= 500) console.error(error);
      sendJson(res, status, { error: status >= 500 ? 'internal error' : error.message });
    }
  });
}

/**
 * 启动家里电脑上的账本服务。
 *
 * @param {object} [options] 路径与监听地址。
 * @returns {Promise<{ server: import('node:http').Server, port: number, device: object }>}
 */
export async function startServer(options = {}) {
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const dataDir = options.dataDir ?? `${root}/data`;
  mkdirSync(dataDir, { recursive: true });
  const db = openDatabase(options.databasePath ?? `${dataDir}/ledger.sqlite`);
  const templateSet = loadTemplateSet(
    options.templatePath ?? fileURLToPath(new URL('../../../templates/templates.json', import.meta.url)),
  );
  const device = ensureDevice(
    db,
    { tokenFile: options.tokenFile ?? `${dataDir}/device.token` },
    options.env ?? process.env,
  );
  const ledger = createLedger({ db });
  const server = createHttpServer({ db, ledger, templateSet });
  const host = options.host ?? process.env.HOST ?? '0.0.0.0';
  const port = Number(options.port ?? process.env.PORT ?? 8787);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  });
  const address = server.address();
  return { server, db, port: typeof address === 'object' && address ? address.port : port, device, host };
}

function authenticate(db, header) {
  const match = /^Bearer\s+(\S+)$/.exec(String(header ?? ''));
  if (!match) return null;
  return get(db, 'SELECT id, name FROM device WHERE token_hash = ?', [tokenHash(match[1])]);
}

function validateEvent(event) {
  if (!event || typeof event !== 'object') return 'event must be an object';
  if (!SOURCES.has(event.source)) return 'event.source is invalid';
  if (typeof event.origin !== 'string' || event.origin === '') return 'event.origin is required';
  if (typeof event.text !== 'string') return 'event.text is required';
  const posted = Number(event.posted_at ?? event.postedAt);
  if (!Number.isFinite(posted)) return 'event.posted_at is required';
  return null;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(httpError(413, 'payload too large'));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(httpError(400, 'invalid json'));
      }
    });
    req.on('error', reject);
  });
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function sendJson(res, status, body) {
  const json = JSON.stringify(body, (_key, value) => (typeof value === 'bigint' ? Number(value) : value));
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(json);
}

function invokedDirectly() {
  const entry = process.argv[1];
  if (!entry) return false;
  return import.meta.url === pathToFileURL(entry).href;
}

if (invokedDirectly()) {
  const started = await startServer();
  const shownHost = started.host === '0.0.0.0' ? '本机所有网卡' : started.host;
  console.log(`零钱管家账本正在监听 ${shownHost}:${started.port}`);
  if (started.device.created) {
    console.log('首次启动，已生成设备令牌。把它贴进手机 App 的「连接电脑」：');
    console.log(started.device.token);
  } else {
    console.log(`使用已有设备令牌（${started.device.id}）。明文在 data/device.token。`);
  }
}
