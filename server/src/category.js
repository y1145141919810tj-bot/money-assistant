/**
 * 确定性分类。只看商户、备注和原文里的关键词，不调用模型。
 * 用户之后的纠正写入 merchant_rule；这里的词表只负责第一次归类。
 */

export const DEFAULT_CATEGORIES = [
  { name: '餐饮', kind: 'expense' },
  { name: '交通', kind: 'expense' },
  { name: '购物', kind: 'expense' },
  { name: '居住', kind: 'expense' },
  { name: '通讯', kind: 'expense' },
  { name: '其他', kind: 'expense' },
  { name: '工资', kind: 'income' },
  { name: '其他收入', kind: 'income' },
  { name: '退款', kind: 'income' },
];

const EXPENSE_RULES = [
  { category: '餐饮', keywords: ['美团', '饿了么', '星巴克', '瑞幸', '麦当劳', '肯德基', '外卖', '餐饮', '食堂'] },
  { category: '交通', keywords: ['滴滴', '地铁', '公交', '加油', '停车', '高铁', '12306', '铁路'] },
  { category: '购物', keywords: ['淘宝', '天猫', '京东', '拼多多', '超市', '便利店', '盒马'] },
  { category: '居住', keywords: ['物业', '房租', '水费', '电费', '燃气', '租金'] },
  { category: '通讯', keywords: ['话费', '中国移动', '中国联通', '中国电信', '流量'] },
];

/**
 * @param {string} name 类目名。
 * @returns {'expense'|'income'} 类目所属方向。
 */
export function categoryKind(name) {
  if (name === '工资' || name === '其他收入' || name === '退款') return 'income';
  return 'expense';
}

/**
 * @param {object} draft 已解析的候选账目。
 * @returns {string|null} 类目名。内部转账不分类。
 */
export function classifyDraft(draft) {
  if (draft.kind === 'transfer') return null;
  const haystack = [draft.counterparty, draft.memo, draft.text, draft.channel]
    .filter((part) => part !== null && part !== undefined && String(part) !== '')
    .join(' ');

  if (draft.kind === 'expense' || draft.kind === 'unknown') {
    for (const rule of EXPENSE_RULES) {
      if (rule.keywords.some((keyword) => haystack.includes(keyword))) return rule.category;
    }
    if (draft.kind === 'expense') return '其他';
  }
  if (draft.kind === 'income') {
    if (haystack.includes('工资')) return '工资';
    return '其他收入';
  }
  if (draft.kind === 'refund') return '退款';
  return null;
}
