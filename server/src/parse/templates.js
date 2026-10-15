import { readFileSync } from 'node:fs';
import { normalize } from './normalize.js';
import { toCents } from './amount.js';
import {
  isPaymentPackage,
  isSensitive,
  looksLikeBankSender,
  looksLikeFlow,
  packageMatches,
  senderMatches,
} from './filters.js';
import { sourceKeyOf } from './source-key.js';

/** @returns {number|null} 捕获组下标；缺失或非法时为 null。 */
function groupIndex(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

/**
 * @param {Array} match RegExp 匹配结果。
 * @param {number|null} index 捕获组下标。
 * @returns {string|null} 捕获组文本；下标非法或捕获组为空时为 null。
 */
function group(match, index) {
  if (index === null || index === undefined || index < 0) return null;
  const value = match[index];
  if (value === undefined) return null;
  return String(value).trim() === '' ? null : value;
}

/**
 * @param {string} templateChannel 模板登记的渠道名，可能为空。
 * @param {string} source 捕获来源。
 * @param {string} origin 通知包名或短信发件号码。
 * @returns {string|null} 渠道名。短信在模板与发件号码都未登记时回退为「银行」。
 */
function resolveChannel(set, templateChannel, source, origin) {
  if (templateChannel) return templateChannel;
  return (
    set.channelForSender(origin) ?? set.channelForPackage(origin) ?? (source === 'sms' ? '银行' : null)
  );
}

/** 模板库：模板列表 + 收支动词映射 + 来源到渠道名的映射。 */
export class TemplateSet {
  constructor(root) {
    this.version = root.version ?? 1;
    this.directionMap = root.directionMap ?? {};
    this.packageChannels = root.packageChannels ?? {};
    this.senderChannels = root.senderChannels ?? {};
    this.templates = (root.templates ?? []).map((item) => {
      const extract = item.extract ?? {};
      return {
        id: item.id,
        note: item.note ?? '',
        senders: item.senders ?? [],
        packages: item.packages ?? [],
        regex: new RegExp(item.regex),
        accountTailGroup: groupIndex(extract.accountTailGroup),
        directionGroup: groupIndex(extract.directionGroup) ?? -1,
        amountGroup: groupIndex(extract.amountGroup) ?? -1,
        balanceGroup: groupIndex(extract.balanceGroup),
        memoGroup: groupIndex(extract.memoGroup),
        counterpartyGroup: groupIndex(extract.counterpartyGroup),
        channel: item.channel ?? '',
        confidence: item.confidence ?? 0.8,
      };
    });
  }

  /**
   * @param {object} template 模板。
   * @param {string} source 捕获来源。
   * @param {string} origin 通知包名或短信发件号码。
   * @returns {boolean} 模板是否适用于此来源。通配发件号码额外要求号码像银行服务号。
   */
  appliesTo(template, source, origin) {
    if (source === 'sms') {
      if (!template.senders.some((sender) => senderMatches(origin, sender))) return false;
      if (template.senders.includes('*') && !looksLikeBankSender(origin)) return false;
      return true;
    }
    if (source === 'notif') {
      return template.packages.some((prefix) => packageMatches(origin, prefix));
    }
    return false;
  }

  /** @returns {string|null} 包名对应的渠道名。 */
  channelForPackage(packageName) {
    for (const [prefix, channel] of Object.entries(this.packageChannels)) {
      if (packageMatches(packageName, prefix)) return channel;
    }
    return null;
  }

  /** @returns {string|null} 发件号码对应的银行名。 */
  channelForSender(address) {
    for (const [number, channel] of Object.entries(this.senderChannels)) {
      if (senderMatches(address, number)) return channel;
    }
    return null;
  }
}

/**
 * @param {string} path 模板库 JSON 路径。
 * @returns {TemplateSet}
 */
export function loadTemplateSet(path) {
  return new TemplateSet(JSON.parse(readFileSync(path, 'utf8')));
}

/**
 * 解析一条通知或短信。与设备端 Kotlin 的 TemplateEngine.parse 逐条对应。
 *
 * 云端会独立重算一遍，而不是信任设备端上报的解析结果：这样模板库热更新后，
 * 历史未识别条目可以在云侧重解析，也不需要给设备端发版。
 *
 * @param {TemplateSet} set 模板库。
 * @param {object} event 捕获事件。
 * @returns {object} 候选账目（draft）。
 */
export function parseEvent(set, event) {
  const { source, origin, postedAt, capturedAt = Date.now() } = event;
  const title = event.title ?? null;
  const body = event.text ?? event.body ?? '';

  const parts = [];
  if (title !== null && String(title).trim() !== '') parts.push(String(title));
  if (body !== null && String(body).trim() !== '') parts.push(String(body));
  const text = normalize(parts.join(' '));

  const draft = {
    source,
    sourceKey: sourceKeyOf(source, origin, postedAt, text),
    origin,
    title,
    text,
    postedAt,
    capturedAt,
    status: 'not_flow',
    kind: 'unknown',
    amountCents: null,
    balanceCents: null,
    accountTail: null,
    channel: resolveChannel(set, '', source, origin),
    counterparty: null,
    memo: null,
    templateId: null,
    confidence: 0,
  };

  if (isSensitive(text)) {
    draft.status = 'rejected';
    return draft;
  }

  for (const template of set.templates) {
    if (!set.appliesTo(template, source, origin)) continue;
    const match = template.regex.exec(text);
    if (!match) continue;
    const amount = toCents(group(match, template.amountGroup));
    if (amount === null) continue;

    const directionWord = group(match, template.directionGroup) ?? '';
    draft.status = 'parsed';
    draft.kind = set.directionMap[directionWord] ?? 'unknown';
    draft.amountCents = amount;
    draft.balanceCents = toCents(group(match, template.balanceGroup));
    draft.accountTail = group(match, template.accountTailGroup);
    draft.channel = resolveChannel(set, template.channel, source, origin);
    draft.counterparty = group(match, template.counterpartyGroup);
    draft.memo = group(match, template.memoGroup);
    draft.templateId = template.id;
    draft.confidence = template.confidence;
    return draft;
  }

  if (!looksLikeFlow(text)) draft.status = 'not_flow';
  else if (source === 'sms' && looksLikeBankSender(origin)) draft.status = 'unmatched';
  else if (source === 'notif' && isPaymentPackage(origin)) draft.status = 'unmatched';
  else draft.status = 'not_flow';
  return draft;
}
