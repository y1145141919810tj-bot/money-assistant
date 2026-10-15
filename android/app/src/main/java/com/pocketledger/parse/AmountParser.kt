package com.pocketledger.parse

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额解析与格式化。全程使用 [BigDecimal] 与「分」整数，避免 `0.1 + 0.2` 类误差进入账本。
 */
object AmountParser {

    /** 单笔金额上限（1 亿元）。超过此值一定不是真实流水，判为解析失败而不是记入账本。 */
    private const val MAX_CENTS = 10_000_000_000L

    /**
     * @param raw 从模板捕获组得到的金额文本，可含千分位逗号与多余小数点。
     * @returns 分为单位的整数；无法解析或超出合理范围时返回 null。
     */
    fun toCents(raw: String?): Long? {
        val cleaned = raw?.replace(",", "")?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val normalized = if (cleaned.count { it == '.' } > 1) {
            val head = cleaned.substringBefore('.')
            head + "." + cleaned.substringAfter('.').replace(".", "")
        } else {
            cleaned
        }
        val cents = try {
            BigDecimal(normalized)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (e: NumberFormatException) {
            // 捕获组可能包含非数字前缀，视为金额缺失。
            return null
        } catch (e: ArithmeticException) {
            // 位数超出 Long 或小数位异常，视为金额缺失。
            return null
        }
        if (cents <= 0 || cents > MAX_CENTS) return null
        return cents
    }

    /**
     * @param cents 分为单位的金额。
     * @returns 形如 `12.00` 的展示文本，负数保留前导减号。
     */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = if (cents < 0) -cents else cents
        val fraction = (abs % 100).toString().padStart(2, '0')
        return "$sign${abs / 100}.$fraction"
    }
}
