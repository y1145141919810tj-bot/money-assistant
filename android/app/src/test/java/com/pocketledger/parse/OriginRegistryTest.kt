package com.pocketledger.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginRegistryTest {

    @Test
    fun `matches app clone package names`() {
        assertTrue(OriginRegistry.packageMatches("com.tencent.mm", "com.tencent.mm"))
        assertTrue(OriginRegistry.packageMatches("com.tencent.mm.chat", "com.tencent.mm"))
        assertTrue(OriginRegistry.packageMatches("com.tencent.mm:push", "com.tencent.mm"))
        assertFalse(OriginRegistry.packageMatches("com.tencent.mmclone", "com.tencent.mm"))
    }

    @Test
    fun `recognises bank senders with country code`() {
        assertTrue(OriginRegistry.looksLikeBankSender("95588"))
        assertTrue(OriginRegistry.looksLikeBankSender("+8695588"))
        assertTrue(OriginRegistry.looksLikeBankSender("1069000012345"))
        assertFalse(OriginRegistry.looksLikeBankSender("13800138000"))
    }

    @Test
    fun `matches sender with country code prefix`() {
        assertTrue(OriginRegistry.senderMatches("+8695588", "95588"))
        assertTrue(OriginRegistry.senderMatches("95588", "*"))
        assertFalse(OriginRegistry.senderMatches("95533", "95588"))
    }

    @Test
    fun `inspects payment packages and structured bank text only`() {
        assertTrue(OriginRegistry.shouldInspect("com.tencent.mm", "晚上一起吃饭吗"))
        assertTrue(OriginRegistry.shouldInspect("com.example.chat", "您尾号1234的卡支出100.00元"))
        assertFalse(OriginRegistry.shouldInspect("com.example.chat", "晚上一起吃饭吗"))
        assertFalse(OriginRegistry.shouldInspect("com.example.chat", ""))
    }
}
