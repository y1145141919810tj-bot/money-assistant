package com.pocketledger.parse

/**
 * 隐私闸门：命中即整条丢弃，不落库、不上传。
 *
 * 通知栏里会混入登录验证码与私聊内容。这些既不是收支，也不该进入账本，
 * 所以必须在解析之前拦截，而不是等入库之后再过滤。
 */
object PiiFilter {

    private val SENSITIVE_KEYWORDS = listOf(
        "验证码",
        "校验码",
        "动态码",
        "动态密码",
        "短信密码",
        "登录码",
        "一次性密码",
        "口令",
    )

    /**
     * @param normalized 已归一化的通知或短信文本。
     * @returns true 表示该条包含凭据类内容，必须整条丢弃。
     */
    fun isSensitive(normalized: String): Boolean = SENSITIVE_KEYWORDS.any { normalized.contains(it) }
}
