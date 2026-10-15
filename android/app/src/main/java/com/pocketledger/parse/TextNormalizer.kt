package com.pocketledger.parse

/**
 * 匹配前的文本归一化。银行与支付的文案在标点、全角、千分位上并不统一，
 * 不归一化就必须为每种写法各写一条模板。
 */
object TextNormalizer {

    private val THOUSANDS = Regex("(\\d),(\\d{3})")
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param raw 通知或短信原文。
     * @returns 全角转半角、去千分位逗号、空白折叠后的文本。
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> ch - 0xFEE0
                    in 'Ａ'..'Ｚ' -> ch - 0xFEE0
                    in 'ａ'..'ｚ' -> ch - 0xFEE0
                    '（' -> '('
                    '）' -> ')'
                    '：' -> ':'
                    '，' -> ','
                    '。' -> '.'
                    '；' -> ';'
                    '！' -> '!'
                    '？' -> '?'
                    '［' -> '['
                    '］' -> ']'
                    '「' -> '('
                    '」' -> ')'
                    '　' -> ' '
                    '￥' -> '¥'
                    '圆' -> '元'
                    else -> ch
                },
            )
        }
        var text = sb.toString()
        // 1,234,567 -> 1234567；最多三轮覆盖千亿级金额。
        repeat(3) { text = text.replace(THOUSANDS, "$1$2") }
        return text.replace(WHITESPACE, " ").trim()
    }
}
