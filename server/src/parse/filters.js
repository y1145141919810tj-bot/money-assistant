/**
 * 三道廉价闸门：隐私、预筛、来源识别。与设备端 Kotlin 的同名实现逐条对应。
 *
 * 顺序是刻意的：先丢凭据内容，再判断像不像流水，最后才跑正则。
 */

const SENSITIVE_KEYWORDS = [
  '验证码',
  '校验码',
  '动态码',
  '动态密码',
  '短信密码',
  '登录码',
  '一次性密码',
  '口令',
];

/**
 * @param {string} normalized 已归一化的文本。
 * @returns {boolean} true 表示包含凭据类内容，必须整条丢弃。
 */
export function isSensitive(normalized) {
  return SENSITIVE_KEYWORDS.some((keyword) => normalized.includes(keyword));
}

/** 带两位小数的金额，或 `¥` 前缀的整数金额（部分通知不带小数）。 */
const AMOUNT_PATTERN = /(\d+\.\d{2})|([¥￥]\s*\d+)/;

const DIRECTION_PATTERN =
  /支出|收入|消费|扣款|转出|转入|入账|存入|到账|已支付|支付成功|付款成功|已付款|成功付款|收款|已收款|退款|退货|取现|工资|代扣/;

/**
 * @param {string} normalized 已归一化的文本。
 * @returns {boolean} true 表示同时出现金额与收支动词，值得进入模板匹配。
 */
export function looksLikeFlow(normalized) {
  return AMOUNT_PATTERN.test(normalized) && DIRECTION_PATTERN.test(normalized);
}

/** 支付与银行 App。与设备端名单保持一致。 */
export const PAYMENT_PACKAGES = [
  'com.tencent.mm',
  'com.eg.android.AlipayGphone',
  'com.unionpay',
  'com.unionpay.mobilepay',
  'com.icbc',
  'com.chinamworld.main',
  'com.android.bankabc',
  'com.chinamworld.bocmbci',
  'cmb.pb',
  'com.bankcomm.Bankcomm',
  'com.yitong.mbank.psbc',
  'com.spdbccc.app',
  'com.ecitic.bank.mobile',
  'cn.com.cmbc.newmbank',
  'com.cebbank.mobile.cemb',
  'com.cgbchina.xpt',
  'com.pingan.pabank',
  'com.cib.bank',
];

const STRUCTURED_HINTS = /尾号\d{4}|信用卡|储蓄卡|借记卡|储蓄账户|账户余额|余额/;

/**
 * @param {string} candidate 实际包名。
 * @param {string} prefix 白名单中的包名。
 * @returns {boolean} 完全相等或带分身后缀时匹配。
 */
export function packageMatches(candidate, prefix) {
  return candidate === prefix || candidate.startsWith(`${prefix}:`) || candidate.startsWith(`${prefix}.`);
}

/**
 * @param {string} packageName 通知包名。
 * @returns {boolean} 是否属于支付/银行 App 白名单。
 */
export function isPaymentPackage(packageName) {
  return PAYMENT_PACKAGES.some((prefix) => packageMatches(packageName, prefix));
}

/**
 * @param {string} packageName 通知包名。
 * @param {string} normalized 已归一化的通知文本。
 * @returns {boolean} 是否值得解析。白名单包名一律解析；其他包名只有含银行结构化特征时才解析。
 */
export function shouldInspect(packageName, normalized) {
  return isPaymentPackage(packageName) || STRUCTURED_HINTS.test(normalized);
}

/**
 * @param {string} address 短信发件号码，可能带国家码前缀。
 * @param {string} expected 模板中登记的发件号码，或 `*` 表示任意。
 * @returns {boolean} 号码尾部匹配时返回 true。
 */
export function senderMatches(address, expected) {
  if (expected === '*') return true;
  const a = String(address ?? '').replace(/\D/g, '');
  const b = String(expected ?? '').replace(/\D/g, '');
  if (a === '' || b === '') return false;
  return a === b || a.endsWith(b);
}

/**
 * @param {string} address 短信发件号码。
 * @returns {boolean} 是否像银行/运营商的服务号。
 */
export function looksLikeBankSender(address) {
  let digits = String(address ?? '').replace(/\D/g, '');
  if (digits.length >= 7 && digits.startsWith('86')) digits = digits.slice(2);
  if (digits.length < 5) return false;
  return (
    digits.startsWith('95') ||
    digits.startsWith('106') ||
    digits.startsWith('400') ||
    (digits.length === 6 && digits.startsWith('9'))
  );
}
