package com.pocketledger.parse

/**
 * 廉价预筛：先判断文本「像不像一笔收支」，再决定是否跑正则与入库。
 *
 * 手机上每天会有大量无关通知。没有这层闸门，微信聊天与营销推送都会被写进落库路径。
 */
object Prefilter {

    /** 带两位小数的金额，或 `¥` 前缀的整数金额（部分通知不带小数）。 */
    private val AMOUNT = Regex("(\\d+\\.\\d{2})|([¥￥]\\s*\\d+)")

    private val DIRECTION = Regex(
        "支出|收入|消费|扣款|转出|转入|入账|存入|到账|已支付|支付成功|付款成功|已付款" +
            "|成功付款|收款|已收款|退款|退货|取现|工资|代扣",
    )

    /**
     * @param normalized 已归一化的通知或短信文本。
     * @returns true 表示同时出现金额与收支动词，值得进入模板匹配。
     */
    fun looksLikeFlow(normalized: String): Boolean =
        AMOUNT.containsMatchIn(normalized) && DIRECTION.containsMatchIn(normalized)
}
