package com.pocketledger.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerAndFilterTest {

    @Test
    fun `normalizes full width and thousand separators`() {
        val raw = "【工商银行】您尾号１２３４的储蓄卡：支出（消费）人民币1,234.56元"
        assertEquals(
            "【工商银行】您尾号1234的储蓄卡:支出(消费)人民币1234.56元",
            TextNormalizer.normalize(raw),
        )
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("a b c", TextNormalizer.normalize("a\n b\tc"))
    }

    @Test
    fun `detects flow like text`() {
        assertTrue(Prefilter.looksLikeFlow("已支付¥12.00"))
        assertTrue(Prefilter.looksLikeFlow("尾号1234支出100.00元"))
        assertTrue(Prefilter.looksLikeFlow("收款到账 ¥5"))
    }

    @Test
    fun `rejects text without both amount and direction`() {
        assertFalse(Prefilter.looksLikeFlow("晚上一起吃饭吗"))
        assertFalse(Prefilter.looksLikeFlow("订单号12345678"))
        assertFalse(Prefilter.looksLikeFlow("已支付"))
    }

    @Test
    fun `credential bearing text is treated as sensitive`() {
        assertTrue(PiiFilter.isSensitive("您尾号1234的账户支出100.00元，验证码123456"))
        assertTrue(PiiFilter.isSensitive("动态密码 7788"))
        assertFalse(PiiFilter.isSensitive("您尾号1234的储蓄卡账户支出100.00元"))
    }
}
