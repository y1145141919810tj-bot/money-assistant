/**
 * 月报用的时间边界。账本时间戳是毫秒 UTC，展示按东八区切日、切月。
 */

/**
 * @param {string} month `YYYY-MM`。
 * @returns {{ year: number, month: number }|null} 解析结果；格式非法时为 null。
 */
export function parseMonth(month) {
  if (!/^\d{4}-\d{2}$/.test(String(month ?? ''))) return null;
  const year = Number(month.slice(0, 4));
  const mon = Number(month.slice(5, 7));
  if (mon < 1 || mon > 12) return null;
  return { year, month: mon };
}

/**
 * @param {string} month `YYYY-MM`。
 * @param {number} [offsetHours] 时区偏移。
 * @returns {{ from: number, to: number }|null} 半开区间 `[from, to)`，毫秒 UTC。
 */
export function monthRange(month, offsetHours = 8) {
  const parsed = parseMonth(month);
  if (!parsed) return null;
  const offsetMs = offsetHours * 60 * 60 * 1000;
  const from = Date.UTC(parsed.year, parsed.month - 1, 1) - offsetMs;
  const to = Date.UTC(parsed.year, parsed.month, 1) - offsetMs;
  return { from, to };
}

/**
 * @param {number} [now] 当前时间，毫秒 UTC。
 * @param {number} [offsetHours] 时区偏移。
 * @returns {string} 当前所在的 `YYYY-MM`。
 */
export function currentMonth(now = Date.now(), offsetHours = 8) {
  const shifted = new Date(now + offsetHours * 60 * 60 * 1000);
  const month = String(shifted.getUTCMonth() + 1).padStart(2, '0');
  return `${shifted.getUTCFullYear()}-${month}`;
}
