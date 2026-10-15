package com.pocketledger.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {

    @Test
    fun `strips thousand separators`() {
        assertEquals(123456L, AmountParser.toCents("1,234.56"))
    }

    @Test
    fun `accepts integer amounts`() {
        assertEquals(10000L, AmountParser.toCents("100"))
    }

    @Test
    fun `rejects zero and negative`() {
        assertNull(AmountParser.toCents("0.00"))
        assertNull(AmountParser.toCents("0"))
    }

    @Test
    fun `rejects non numeric text`() {
        assertNull(AmountParser.toCents("abc"))
        assertNull(AmountParser.toCents(""))
        assertNull(AmountParser.toCents(null))
    }

    @Test
    fun `rejects absurd values instead of booking them`() {
        assertNull(AmountParser.toCents("999999999999"))
    }

    @Test
    fun `formats cents with two decimals`() {
        assertEquals("1234.56", AmountParser.format(123456))
        assertEquals("0.05", AmountParser.format(5))
        assertEquals("-12.00", AmountParser.format(-1200))
    }
}
