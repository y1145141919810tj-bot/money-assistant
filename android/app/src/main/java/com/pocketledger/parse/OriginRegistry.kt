package com.pocketledger.parse

/**
 * 来源识别：决定一条通知/短信是否值得解析。
 *
 * 隐私约束：不在白名单里、且不含结构化银行特征的通知，一律不看内容就丢弃。
 */
object OriginRegistry {

    /** 支付与银行 App。覆盖应用分身场景（包名可能带 `:xxx` 或 `.xxx` 后缀）。 */
    val PAYMENT_PACKAGES = listOf(
        "com.tencent.mm", // 微信
        "com.eg.android.AlipayGphone", // 支付宝
        "com.unionpay", // 云闪付
        "com.unionpay.mobilepay",
        "com.icbc", // 工商银行
        "com.chinamworld.main", // 建设银行
        "com.android.bankabc", // 农业银行
        "com.chinamworld.bocmbci", // 中国银行
        "cmb.pb", // 招商银行
        "com.bankcomm.Bankcomm", // 交通银行
        "com.yitong.mbank.psbc", // 邮储银行
        "com.spdbccc.app", // 浦发银行
        "com.ecitic.bank.mobile", // 中信银行
        "cn.com.cmbc.newmbank", // 民生银行
        "com.cebbank.mobile.cemb", // 光大银行
        "com.cgbchina.xpt", // 广发银行
        "com.pingan.pabank", // 平安银行
        "com.cib.bank", // 兴业银行
    )

    /** 银行短信的结构化特征。命中即认为该条值得进入解析，不依赖发件号码白名单。 */
    private val STRUCTURED_HINTS = Regex("尾号\\d{4}|信用卡|储蓄卡|借记卡|储蓄账户|账户余额|余额")

    /**
     * @param candidate 实际包名。
     * @param prefix 白名单中的包名。
     * @returns 完全相等或带分身后缀时匹配。
     */
    fun packageMatches(candidate: String, prefix: String): Boolean =
        candidate == prefix || candidate.startsWith("$prefix:") || candidate.startsWith("$prefix.")

    /**
     * @param packageName 通知包名。
     * @returns 是否属于支付/银行 App 白名单。
     */
    fun isPaymentPackage(packageName: String): Boolean =
        PAYMENT_PACKAGES.any { packageMatches(packageName, it) }

    /**
     * @param packageName 通知包名。
     * @param normalized 已归一化的通知文本。
     * @returns 是否值得解析。白名单包名一律解析；其他包名只有在文本含银行结构化特征时才解析。
     */
    fun shouldInspect(packageName: String, normalized: String): Boolean =
        isPaymentPackage(packageName) || STRUCTURED_HINTS.containsMatchIn(normalized)

    /**
     * @param address 短信发件号码，可能带国家码前缀（如 `+8695588`）。
     * @param expected 模板中登记的发件号码，或 `*` 表示任意。
     * @returns 号码尾部匹配时返回 true；`*` 表示匹配任意号码。
     */
    fun senderMatches(address: String, expected: String): Boolean {
        if (expected == "*") return true
        val a = address.filter { it.isDigit() }
        val b = expected.filter { it.isDigit() }
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.endsWith(b)
    }

    /**
     * @param address 短信发件号码。
     * @returns 是否像银行/运营商的服务号（5 位特服号、106 聚合通道、400 号码）。
     */
    fun looksLikeBankSender(address: String): Boolean {
        var digits = address.filter { it.isDigit() }
        if (digits.length >= 7 && digits.startsWith("86")) digits = digits.substring(2)
        if (digits.length < 5) return false
        return digits.startsWith("95") ||
            digits.startsWith("106") ||
            digits.startsWith("400") ||
            (digits.length == 6 && digits.startsWith("9"))
    }
}
