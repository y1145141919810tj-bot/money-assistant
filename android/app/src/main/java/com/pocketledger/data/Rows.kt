package com.pocketledger.data

import com.pocketledger.core.CaptureSource
import com.pocketledger.core.TxnKind

/**
 * 一笔已确认账目（UI 与后续上报共用）。
 *
 * @property occurredAt 事件发生时间，毫秒 UTC。
 */
data class TxnRow(
    val occurredAt: Long,
    val kind: TxnKind,
    val amountCents: Long,
    val channel: String?,
    val accountTail: String?,
    val counterparty: String?,
    val memo: String?,
    val source: CaptureSource,
    val templateId: String?,
    val confidence: Double,
)

/**
 * 一条未识别条目：像流水但没命中任何模板，用于补齐模板库。
 *
 * @property text 已归一化的原文，是补模板的唯一线索。
 */
data class UnmatchedRow(
    val postedAt: Long,
    val source: CaptureSource,
    val origin: String,
    val text: String,
)

/**
 * 单个来源的健康度。某个来源长期零命中而历史日均大于零，说明采集链路已失效。
 */
data class HealthRow(
    val origin: String,
    val hitCount: Int,
    val lastHitAt: Long,
    val unmatchedCount: Int,
    val lastUnmatchedAt: Long,
)

/**
 * 计数摘要。
 *
 * @property txnCount 已确认账目数。
 * @property unmatchedCount 未识别条目数。
 * @property parsedEventCount 已落库的原始事件数。
 */
data class LedgerCounts(
    val txnCount: Int,
    val unmatchedCount: Int,
    val parsedEventCount: Int,
)

/**
 * 一条待上报到家里电脑的原始事件。
 *
 * [text] 已是标题与正文合并后的归一化文本，所以上报时不再另附 title，
 * 避免服务端把标题拼第二次，导致幂等键对不上。
 */
data class PendingEvent(
    val id: Long,
    val source: String,
    val sourceKey: String,
    val origin: String,
    val title: String?,
    val text: String,
    val postedAt: Long,
)

/** 单次入库的结果。 */
enum class IngestOutcome {
    /** 解析成功并写入账目。 */
    INSERTED,

    /** 像流水但未命中模板，作为原始证据落库待补模板。 */
    UNMATCHED,

    /** 幂等键已存在，本次为重复上报或重复通知。 */
    DUPLICATE,

    /** 判定为非流水或含凭据内容，未落库。 */
    DROPPED,
}
