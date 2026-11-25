package com.studyontv.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EdgeTtsTest {
    @Test
    fun secMsGecAlignsToFiveMinuteWindow() {
        val t0 = 1_700_000_100_000L
        val t1 = 1_700_000_399_000L
        val t2 = 1_700_000_400_000L
        val gec0 = EdgeTts.secMsGec(t0)
        assertEquals("0919602B5750900A-EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397", gec0)
        assertEquals(gec0, EdgeTts.secMsGec(t1))
        assertNotEquals(gec0, EdgeTts.secMsGec(t2))
    }

    @Test
    fun defaultVoiceMapsLanguages() {
        assertEquals("zh-HK-HiuGaaiNeural", EdgeTts.defaultVoice("zh-HK"))
        assertEquals("zh-HK-HiuGaaiNeural", EdgeTts.defaultVoice("yue-HK"))
        assertEquals("zh-TW-HsiaoChenNeural", EdgeTts.defaultVoice("zh-TW"))
        assertEquals("en-GB-SoniaNeural", EdgeTts.defaultVoice("en-GB"))
        assertEquals("en-US-JennyNeural", EdgeTts.defaultVoice("en-US"))
        assertEquals("zh-CN-XiaoxiaoNeural", EdgeTts.defaultVoice(""))
    }

    @Test
    fun escapeXmlHandlesAllSpecialCharacters() {
        assertEquals(
            "1 &lt; 2 &amp; 3 &gt; 0 &quot;ok&quot; &apos;yes&apos;",
            EdgeTts.escapeXml("1 < 2 & 3 > 0 \"ok\" 'yes'")
        )
    }

    @Test
    fun webSocketFrameEncodeDecodeCoversSmall126And127Lengths() {
        val sizes = listOf(0, 42, 125, 126, 3000, 65535, 70_000)
        for (size in sizes) {
            val payload = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            for (masked in listOf(true, false)) {
                val raw = EdgeTts.encodeFrame(0x2, payload, masked = masked, fin = true)
                val decoded = EdgeTts.readFrame(ByteArrayInputStream(raw))
                assertNotNull("Decoded frame null for size=$size masked=$masked", decoded)
                assertTrue(decoded!!.fin)
                assertEquals(0x2, decoded.opcode)
                assertArrayEquals(payload, decoded.payload)
            }
        }
    }

    @Test
    fun readMessageReassemblesFragmentedFramesWithInterleavedPingAndExtractsCloseReason() {
        val part1 = "Hello, ".toByteArray(Charsets.UTF_8)
        val part2 = "微软 ".toByteArray(Charsets.UTF_8)
        val part3 = "Edge TTS!".toByteArray(Charsets.UTF_8)
        val pingPayload = byteArrayOf(9, 8, 7)

        val streamBytes = ByteArrayOutputStream().apply {
            write(EdgeTts.encodeFrame(0x1, part1, masked = false, fin = false))
            // 分片中途穿插 Ping 控制帧（RFC 6455 §5.4）
            write(EdgeTts.encodeFrame(0x9, pingPayload, masked = false, fin = true))
            write(EdgeTts.encodeFrame(0x0, part2, masked = false, fin = false))
            write(EdgeTts.encodeFrame(0x0, part3, masked = false, fin = true))
            // Close 帧（code = 1008 Policy Violation，reason = "clock skew"）
            val closeBody = ByteArrayOutputStream().apply {
                write((1008 ushr 8) and 0xFF)
                write(1008 and 0xFF)
                write("clock skew".toByteArray(Charsets.UTF_8))
            }.toByteArray()
            write(EdgeTts.encodeFrame(0x8, closeBody, masked = false, fin = true))
        }.toByteArray()

        val input = ByteArrayInputStream(streamBytes)
        var sawPing = false
        var closeInfo: Pair<Int?, String>? = null

        val msg = EdgeTts.readMessage(input) { control ->
            if (control.opcode == 0x9) {
                sawPing = true
                assertArrayEquals(pingPayload, control.payload)
                true
            } else if (control.opcode == 0x8) {
                closeInfo = EdgeTts.parseCloseInfo(control.payload)
                false
            } else true
        }
        assertTrue(sawPing)
        assertNotNull(msg)
        assertEquals(0x1, msg!!.opcode)
        assertEquals("Hello, 微软 Edge TTS!", String(msg.payload, Charsets.UTF_8))

        val end = EdgeTts.readMessage(input) { control ->
            if (control.opcode == 0x8) {
                closeInfo = EdgeTts.parseCloseInfo(control.payload)
                false
            } else true
        }
        assertNull(end)
        assertEquals(1008 to "clock skew", closeInfo)
    }

    @Test
    fun parseHttpDateSupportsServerClockCalibration() {
        val ms = EdgeTts.parseHttpDate("Wed, 18 Nov 2026 05:30:00 GMT")
        assertNotNull(ms)
        assertEquals(1_794_979_800_000L, ms!!)
        assertNull(EdgeTts.parseHttpDate(""))
        assertNull(EdgeTts.parseHttpDate("not-a-date"))
    }

    @Test
    fun escapeXmlStripsIllegalControlCharsAndLoneSurrogatesWhilePreservingValidEmoji() {
        val loneHigh = '\uD83D'
        val loneLow = '\uDC00'
        val raw = "A\u0000B\u000BC\u000CD\t\n\r${loneHigh}E${loneLow}F📐𝜋<x>&y"
        assertEquals("ABCD\t\n\rEF📐𝜋&lt;x&gt;&amp;y", EdgeTts.escapeXml(raw))
    }

    @Test(expected = IllegalStateException::class)
    fun requireOnlineAllowedRejectsLocalOnlyMode() {
        EdgeTts.requireOnlineAllowed(localOnly = true)
    }

    @Test
    fun handshakeValidatesStatusCodeAndSecWebSocketAccept() {
        // RFC 6455 §1.3 official test vector
        val wsKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        assertEquals(expected, EdgeTts.expectedAccept(wsKey))
        assertEquals(101, EdgeTts.parseHttpStatusCode("HTTP/1.1 101 Switching Protocols"))
        assertEquals(403, EdgeTts.parseHttpStatusCode("HTTP/1.1 403 Forbidden"))
        assertNull(EdgeTts.parseHttpStatusCode("GARBAGE 101"))

        // Valid 101 + matching Sec-WebSocket-Accept passes
        EdgeTts.validateHandshakeResponse(
            "HTTP/1.1 101 Switching Protocols",
            mapOf("sec-websocket-accept" to expected),
            wsKey
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun handshakeRejectsMismatchedSecWebSocketAccept() {
        EdgeTts.validateHandshakeResponse(
            "HTTP/1.1 101 Switching Protocols",
            mapOf("sec-websocket-accept" to "wrong-accept"),
            "dGhlIHNhbXBsZSBub25jZQ=="
        )
    }

    @Test
    fun shouldRetryAfter403WhenCrossingFiveMinuteBoundaryEvenWithSmallSkew() {
        // tBefore = ...399s 与 tAfter = ...400s 仅差 1 秒，但跨越了 300s 分桶边界，必须允许重试
        val tBefore = 1_700_000_399_000L
        val tAfter = 1_700_000_400_000L
        assertTrue(EdgeTts.shouldRetryAfter403(tBefore, tAfter))
        // 同一个 5 分钟桶内且偏差 < 60s，不重复等待
        assertTrue(!EdgeTts.shouldRetryAfter403(1_700_000_110_000L, 1_700_000_120_000L))
        // 无服务端 Date 不重试
        assertTrue(!EdgeTts.shouldRetryAfter403(tBefore, null))
    }

    @Test
    fun orderAddressesIpv4FirstPromotesIpv4AheadOfIpv6() {
        val v6 = java.net.InetAddress.getByName("2620:1ec:33::10")
        val v4a = java.net.InetAddress.getByName("204.79.197.200")
        val v4b = java.net.InetAddress.getByName("13.107.21.200")
        val ordered = EdgeTts.orderAddressesIpv4First(listOf(v6, v4a, v4b))
        assertEquals(listOf(v4a, v4b, v6), ordered)
    }
}
