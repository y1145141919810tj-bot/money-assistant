/**
 * 文本归一化。与设备端 Kotlin 的 TextNormalizer 逐条对应。
 *
 * 两边必须产出完全相同的字符串，否则同一个 source_key 会在设备端与云端算出两个值，
 * 幂等就失效，同一笔钱会被记两次。`templates/fixtures.json` 是这条约定的验收依据。
 */

const FULLWIDTH_LOWER = 0xff41;
const FULLWIDTH_UPPER = 0xff21;
const FULLWIDTH_DIGIT = 0xff10;
const FULLWIDTH_OFFSET = 0xfee0;

const PUNCTUATION = new Map([
  ['（', '('],
  ['）', ')'],
  ['：', ':'],
  ['，', ','],
  ['。', '.'],
  ['；', ';'],
  ['！', '!'],
  ['？', '?'],
  ['［', '['],
  ['］', ']'],
  ['「', '('],
  ['」', ')'],
  ['　', ' '],
  ['￥', '¥'],
  ['圆', '元'],
]);

/**
 * @param {string} raw 通知或短信原文。
 * @returns {string} 全角转半角、去千分位逗号、空白折叠后的文本。
 */
export function normalize(raw) {
  if (raw === null || raw === undefined) return '';
  let out = '';
  for (const ch of String(raw)) {
    const code = ch.codePointAt(0);
    if (
      (code >= FULLWIDTH_DIGIT && code <= FULLWIDTH_DIGIT + 9) ||
      (code >= FULLWIDTH_UPPER && code <= FULLWIDTH_UPPER + 25) ||
      (code >= FULLWIDTH_LOWER && code <= FULLWIDTH_LOWER + 25)
    ) {
      out += String.fromCodePoint(code - FULLWIDTH_OFFSET);
      continue;
    }
    out += PUNCTUATION.get(ch) ?? ch;
  }
  // 1,234,567 -> 1234567；与 Kotlin 一样跑三轮，覆盖千亿级金额。
  for (let i = 0; i < 3; i += 1) out = out.replace(/(\d),(\d{3})/g, '$1$2');
  return out.replace(/\s+/g, ' ').trim();
}
