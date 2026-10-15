package com.pocketledger.parse

import com.pocketledger.core.CaptureSource
import com.pocketledger.core.ParseStatus
import com.pocketledger.core.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模板库行为测试。用例即为验收标准：这些文案必须命中，且不能误收非流水内容。
 *
 * 模板库是抓取成功率的唯一瓶颈，所以测试直接读 `src/main/assets/templates.json`，
 * 保证被测的就是发版时打进 APK 的那一份。
 */
class TemplateEngineTest {

    private val engine: TemplateEngine by lazy {
        val candidates = listOf(
            File("src/main/assets/templates.json"),
            File("app/src/main/assets/templates.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("templates.json not found; cwd=${File(".").absolutePath}")
        val set = TemplateSet.fromJson(file.readText())
        println("loaded template set version=${set.version} templates=${set.templates.size}")
        TemplateEngine(set)
    }

    private fun sms(sender: String, body: String, postedAt: Long = POSTED_AT) =
        engine.parse(CaptureSource.SMS, sender, null, body, postedAt, postedAt)

    private fun notif(pkg: String, title: String?, body: String, postedAt: Long = POSTED_AT) =
        engine.parse(CaptureSource.NOTIFICATION, pkg, title, body, postedAt, postedAt)

    @Test
    fun `parses icbc expense with balance and bank name`() {
        val draft = sms(
            "95588",
            "【工商银行】您尾号1234的储蓄卡账户8月5日12:30支出(消费)人民币100.00元，余额1234.56元。",
        )
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.EXPENSE, draft.kind)
        assertEquals(10000L, draft.amountCents)
        assertEquals(123456L, draft.balanceCents)
        assertEquals("1234", draft.accountTail)
        assertEquals("工商银行", draft.channel)
        assertEquals("bank-tail-flow", draft.templateId)
    }

    @Test
    fun `parses ccb expense with chinese time separators`() {
        val draft = sms(
            "95533",
            "【建设银行】您尾号1234的储蓄卡账户8月5日12时30分快捷支付支出(消费)人民币100.00元，余额1234.56元。",
        )
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(10000L, draft.amountCents)
        assertEquals("建设银行", draft.channel)
    }

    @Test
    fun `parses cmb account style without card tail keyword`() {
        val draft = sms(
            "95555",
            "【招商银行】您账户1234于8月5日12:30支出人民币100.00元，余额1234.56元。",
        )
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.EXPENSE, draft.kind)
        assertEquals(10000L, draft.amountCents)
        assertEquals("1234", draft.accountTail)
        assertEquals("招商银行", draft.channel)
        assertEquals("bank-account-flow", draft.templateId)
    }

    @Test
    fun `parses income with memo`() {
        val draft = sms(
            "95588",
            "【工商银行】您尾号1234的储蓄卡账户8月5日12:30收入(工资)人民币1000.00元，余额2234.56元。",
        )
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.INCOME, draft.kind)
        assertEquals(100000L, draft.amountCents)
        assertEquals("工资", draft.memo)
    }

    @Test
    fun `classifies refund separately from income`() {
        val draft = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30退款(退货)人民币100.00元。")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.REFUND, draft.kind)
    }

    @Test
    fun `handles full width punctuation and thousand separators`() {
        val draft = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30支出（消费）人民币1,234.56元")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(123456L, draft.amountCents)
    }

    @Test
    fun `parses wechat payment notification`() {
        val draft = notif("com.tencent.mm", "微信支付", "已支付¥12.00")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.EXPENSE, draft.kind)
        assertEquals(1200L, draft.amountCents)
        assertEquals("微信支付", draft.channel)
    }

    @Test
    fun `parses alipay payment notification`() {
        val draft = notif("com.eg.android.AlipayGphone", "支付宝", "支付成功 12.00元")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(TxnKind.EXPENSE, draft.kind)
        assertEquals(1200L, draft.amountCents)
        assertEquals("支付宝", draft.channel)
    }

    @Test
    fun `parses app clone package name`() {
        val draft = notif("com.tencent.mm.chat", "微信支付", "已支付¥12.00")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(1200L, draft.amountCents)
    }

    @Test
    fun `wechat chat message is not a flow and stays out of the ledger`() {
        val draft = notif("com.tencent.mm", "张三", "晚上一起吃饭吗")
        assertEquals(ParseStatus.NOT_FLOW, draft.status)
        assertNull(draft.amountCents)
    }

    @Test
    fun `credential bearing bank sms is rejected before parsing`() {
        val draft = sms(
            "95588",
            "【工商银行】您尾号1234的储蓄卡账户8月5日12:30支出(消费)人民币100.00元，验证码123456，请勿泄露。",
        )
        assertEquals(ParseStatus.REJECTED, draft.status)
        assertNull(draft.amountCents)
    }

    @Test
    fun `personal sms with bank-ish wording is dropped`() {
        val draft = sms("13800138000", "我转给你100.00元")
        assertEquals(ParseStatus.NOT_FLOW, draft.status)

        val spoofed = sms("13800138000", "尾号1234的账户支出100.00元")
        assertEquals(ParseStatus.NOT_FLOW, spoofed.status)
        assertNull(spoofed.amountCents)
    }

    @Test
    fun `amount is not swallowed by the optional memo group`() {
        val draft = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30支出88.00元")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(8800L, draft.amountCents)
        assertNull(draft.memo)
    }

    @Test
    fun `paren free multi verb wording still yields the amount`() {
        val draft = sms("95555", "您账户1234于8月5日12:30消费支出人民币66.50元")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals(6650L, draft.amountCents)
        assertEquals(TxnKind.EXPENSE, draft.kind)
    }

    @Test
    fun `unknown bank sender falls back to generic channel name`() {
        val draft = sms("1069000012345", "您尾号9999的账户支出88.00元")
        assertEquals(ParseStatus.PARSED, draft.status)
        assertEquals("银行", draft.channel)
        assertEquals(8800L, draft.amountCents)
    }

    @Test
    fun `bank app notification is kept for template harvesting`() {
        val draft = notif("com.icbc", "工商银行", "您尾号1234的账户支出100.00元")
        assertEquals(ParseStatus.UNMATCHED, draft.status)
        assertNull(draft.amountCents)
        assertTrue(draft.text.contains("尾号1234的账户支出100.00元"))
    }

    @Test
    fun `unrelated app notification is not inspected`() {
        val draft = notif("com.example.shopping", "促销", "满100.00元减20元，立即支付")
        assertEquals(ParseStatus.NOT_FLOW, draft.status)
    }

    @Test
    fun `identical input yields identical idempotency key`() {
        val first = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30支出人民币100.00元")
        val second = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30支出人民币100.00元")
        assertEquals(first.sourceKey, second.sourceKey)

        val later = sms("95588", "您尾号1234的储蓄卡账户8月5日12:30支出人民币100.00元", postedAt = POSTED_AT + 1)
        assertNotEquals(first.sourceKey, later.sourceKey)
    }

    private companion object {
        /** 2026-08-05 12:30:00 UTC+8。 */
        const val POSTED_AT = 1785904200000L
    }
}
