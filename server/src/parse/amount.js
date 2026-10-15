/**
 * 金额解析与格式化。与设备端 Kotlin 的 AmountParser 逐条对应。
 *
 * 全程用 BigInt 做整数运算，避免 JS 浮点误差进入账本（`0.1 + 0.2` 这类问题在账本里是不可接受的）。
 */

/** 单笔金额上限（1 亿元）。超过此值一定不是真实流水，判为解析失败而不是记入账本。 */
const MAX_CENTS = 10000000000n;

/**
 * @param {string|null|undefined} raw 从模板捕获组得到的金额文本，可含千分位逗号与多余小数点。
 * @returns {number|null} 分为单位的整数；无法解析或超出合理范围时返回 null。
 */
export function toCents(raw) {
  if (raw === null || raw === undefined) return null;
  let cleaned = String(raw).replace(/,/g, '').trim();
  if (cleaned === '') return null;
  const firstDot = cleaned.indexOf('.');
  if ((cleaned.match(/\./g) ?? []).length > 1) {
    cleaned = `${cleaned.slice(0, firstDot)}.${cleaned.slice(firstDot + 1).replace(/\./g, '')}`;
  }
  const match = /^(\d+)(?:\.(\d+))?$/.exec(cleaned);
  if (!match) return null;

  const fraction = match[2] ?? '';
  // 与 Kotlin 的 movePointRight(2).setScale(0, HALF_UP) 等价：看第三位小数决定是否进位。
  let cents = BigInt(match[1] + fraction.slice(0, 2).padEnd(2, '0'));
  const thirdDigit = fraction.length > 2 ? fraction.charCodeAt(2) - 48 : 0;
  if (thirdDigit >= 5) cents += 1n;

  if (cents <= 0n || cents > MAX_CENTS) return null;
  return Number(cents);
}

/**
 * @param {number} cents 分为单位的金额。
 * @returns {string} 形如 `12.00` 的展示文本，负数保留前导减号。
 */
export function formatCents(cents) {
  const value = Math.trunc(cents ?? 0);
  const sign = value < 0 ? '-' : '';
  const abs = Math.abs(value);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}
