package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class LanServerTest {

    @Test
    fun decodePathSegmentFollowsRfc3986AndPreservesPlus() {
        assertEquals("plain-id_1", LanServer.decodePathSegment("plain-id_1"))
        // Literal '+' must stay '+' (unlike form-urlencoded URLDecoder)
        assertEquals("math+1+2", LanServer.decodePathSegment("math+1+2"))
        assertEquals("math+1 2", LanServer.decodePathSegment("math%2B1%202"))
        // Chinese UTF-8 percent-encoding
        assertEquals("语文 01#测试", LanServer.decodePathSegment("%E8%AF%AD%E6%96%87%2001%23%E6%B5%8B%E8%AF%95"))
        // Invalid percent sequences must be rejected with IllegalArgumentException
        for (bad in listOf("%", "%2", "%ZZ", "abc%2G")) {
            try {
                LanServer.decodePathSegment(bad)
                fail("Expected IllegalArgumentException for '$bad'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("编码无效"))
            }
        }
    }

    @Test
    fun readExactBodyThrowsEofOnTruncatedStream() {
        val payload = "{\"q\":\"你好世界\"}".toByteArray(Charsets.UTF_8)
        assertEquals("{\"q\":\"你好世界\"}", LanServer.readExactBody(ByteArrayInputStream(payload), payload.size))

        val truncated = payload.copyOf(payload.size - 3)
        try {
            LanServer.readExactBody(ByteArrayInputStream(truncated), payload.size)
            fail("Expected EOFException on truncated stream")
        } catch (e: EOFException) {
            assertTrue(e.message!!.contains("未接收完整"))
        }
    }
}
