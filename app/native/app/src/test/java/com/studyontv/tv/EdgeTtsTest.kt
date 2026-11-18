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
        assertEquals(64, gec0.length)
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
}
