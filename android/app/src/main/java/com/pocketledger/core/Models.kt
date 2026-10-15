package com.pocketledger.core

/**
 * 捕获来源。[wire] 是落库与上报使用的稳定字符串，不要随意改名。
 */
enum class CaptureSource(val wire: String) {
    NOTIFICATION("notif"),
    SMS("sms"),
    EMAIL("email"),
    CSV("csv"),
    MANUAL("manual"),
    ;

    companion object {
        /** @param wire 落库字符串；未知值降级为 [MANUAL] 而不是抛异常。 */
        fun fromWire(wire: String): CaptureSource = entries.firstOrNull { it.wire == wire } ?: MANUAL
    }
}

/**
 * 账目类型。`transfer` 与 `refund` 必须与 `expense` 分开统计，
 * 否则信用卡还款与退款会污染支出合计。
 */
enum class TxnKind(val wire: String) {
    EXPENSE("expense"),
    INCOME("income"),
    REFUND("refund"),
    TRANSFER("transfer"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** @param wire 落库字符串；未知值降级为 [UNKNOWN]。 */
        fun fromWire(wire: String): TxnKind = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/**
 * 一次捕获的解析结果状态。
 * [NOT_FLOW] 与 [REJECTED] 的条目一律不落库；[UNMATCHED] 会落库，用于补齐模板库。
 */
enum class ParseStatus(val wire: String) {
    PARSED("parsed"),
    UNMATCHED("unmatched"),
    NOT_FLOW("not_flow"),
    REJECTED("rejected"),
    ;

    companion object {
        /** @param wire 落库字符串；未知值降级为 [NOT_FLOW] 而不是抛异常。 */
        fun fromWire(wire: String): ParseStatus = entries.firstOrNull { it.wire == wire } ?: NOT_FLOW
    }
}

/**
 * 一条待入库的候选记录。
 *
 * 金额一律用「分」的整数表示，禁止浮点。`null` 金额只在 [ParseStatus.UNMATCHED] 时出现。
 *
 * @property sourceKey 幂等键，跨重传、重装、重放保证同一笔只入库一次。
 * @property origin 通知包名或短信发件号码。
 * @property postedAt 事件发生时间（通知 postTime / 短信时间戳），毫秒 UTC。
 * @property capturedAt 被本机收到的时间，毫秒 UTC。
 */
data class Draft(
    val source: CaptureSource,
    val sourceKey: String,
    val origin: String,
    val title: String?,
    val text: String,
    val postedAt: Long,
    val capturedAt: Long,
    val kind: TxnKind,
    val amountCents: Long?,
    val balanceCents: Long?,
    val accountTail: String?,
    val channel: String?,
    val counterparty: String?,
    val memo: String?,
    val templateId: String?,
    val confidence: Double,
    val status: ParseStatus,
)
