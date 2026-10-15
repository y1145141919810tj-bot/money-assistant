import { createHash } from 'node:crypto';

/**
 * 幂等键构造。与设备端 Kotlin 的 SourceKey 必须产出完全相同的字符串。
 *
 * 设备端先算出 key 上报，云端也会独立重算一遍：只有两边一致，重复上报才真的被识别为重复。
 *
 * @param {string} source 捕获来源（notif | sms | email | csv | manual）。
 * @param {string} origin 通知包名或短信发件号码。
 * @param {number} postedAt 事件发生时间，毫秒 UTC。
 * @param {string} normalized 已归一化的文本（用原文会因标点差异导致同一笔产生两个键）。
 * @returns {string} 形如 `notif:com.tencent.mm:1730000000000:<sha1>` 的幂等键。
 */
export function sourceKeyOf(source, origin, postedAt, normalized) {
  return `${source}:${origin}:${postedAt}:${sha1(normalized)}`;
}

/**
 * @param {string} text 任意文本。
 * @returns {string} 小写十六进制 SHA-1 摘要。
 */
export function sha1(text) {
  return createHash('sha1').update(text, 'utf8').digest('hex');
}
