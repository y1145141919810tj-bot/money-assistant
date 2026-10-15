package com.pocketledger.ui

import com.pocketledger.core.TxnKind

/**
 * 列表展示行。
 *
 * @property head 首行：时间 · 渠道或来源。
 * @property amount 右侧金额文本，已带方向符号。
 * @property detail 次行：交易对方、备注、来源、模板与置信度；未识别条目这里放原文。
 * @property kind 决定金额颜色。
 * @property unmatched true 表示该行来自「未识别」列表。
 */
data class LedgerRow(
    val head: String,
    val amount: String,
    val detail: String,
    val kind: TxnKind,
    val unmatched: Boolean,
)
