package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavAudioTest {

    private fun le16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun buildWav(riffSize: Int, dataSize: Int, pcmBytes: ByteArray, sampleRate: Int = 24000, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(out, riffSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(out, 16)
        le16(out, 1) // PCM
        le16(out, channels)
        le32(out, sampleRate)
        le32(out, sampleRate * channels * 2)
        le16(out, channels * 2)
        le16(out, 16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(out, dataSize)
        out.write(pcmBytes)
        return out.toByteArray()
    }

    @Test
    fun parsesStandardPcmWav() {
        val pcmBytes = ByteArray(160) { it.toByte() }
        val wav = buildWav(36 + pcmBytes.size, pcmBytes.size, pcmBytes, sampleRate = 16000, channels = 1)
        val parsed = WavAudio.parse(wav)
        assertNotNull(parsed)
        assertEquals(16000, parsed!!.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(160, parsed.data.size)
        assertEquals(80L, parsed.frames)
    }

    @Test
    fun parsesStreamingWavWithSentinelSizes() {
        // #174: DeepSeek TTS returns RIFF size = 0xFFFFFFFF and data size = 0xFFFFFFFF
        val pcmBytes = ByteArray(240) { (it * 3).toByte() }
        val wav = buildWav(-1, -1, pcmBytes, sampleRate = 24000, channels = 1)
        val parsed = WavAudio.parse(wav)
        assertNotNull(parsed)
        assertEquals(24000, parsed!!.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(240, parsed.data.size)
        assertEquals(120L, parsed.frames)
    }

    @Test
    fun alignsOddTrailingByteToFrameBoundary() {
        val pcmBytes = ByteArray(101) { 1 } // 16-bit mono -> frameBytes = 2 -> aligned = 100
        val wav = buildWav(-1, -1, pcmBytes, sampleRate = 24000, channels = 1)
        val parsed = WavAudio.parse(wav)
        assertNotNull(parsed)
        assertEquals(100, parsed!!.data.size)
        assertEquals(50L, parsed.frames)
    }

    private fun buildExtensibleWav(fmtBodySize: Int, subFormat: ByteArray?, pcmBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(out, -1)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(out, fmtBodySize)
        le16(out, 0xFFFE) // WAVE_FORMAT_EXTENSIBLE
        le16(out, 1) // mono
        le32(out, 24000)
        le32(out, 48000)
        le16(out, 2)
        le16(out, 16)
        if (fmtBodySize >= 40) {
            le16(out, 22) // cbSize
            le16(out, 16) // wValidBitsPerSample
            le32(out, 4)  // dwChannelMask (FRONT_CENTER)
            out.write(subFormat ?: ByteArray(16))
            if (fmtBodySize > 40) out.write(ByteArray(fmtBodySize - 40))
        } else if (fmtBodySize > 16) {
            out.write(ByteArray(fmtBodySize - 16))
        }
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(out, pcmBytes.size)
        out.write(pcmBytes)
        return out.toByteArray()
    }

    @Test
    fun parsesExtensibleWavWithPcmSubFormatGuid() {
        val pcmGuid = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
        )
        val pcmBytes = ByteArray(120) { it.toByte() }
        val wav = buildExtensibleWav(40, pcmGuid, pcmBytes)
        val parsed = WavAudio.parse(wav)
        assertNotNull(parsed)
        assertEquals(24000, parsed!!.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(120, parsed.data.size)
    }

    @Test
    fun rejectsExtensibleWavWithNonPcmSubFormatOrTruncatedFmt() {
        // KSDATAFORMAT_SUBTYPE_IEEE_FLOAT (first byte = 0x03 instead of 0x01)
        val floatGuid = byteArrayOf(
            0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
        )
        val pcmBytes = ByteArray(120) { it.toByte() }
        assertNull(WavAudio.parse(buildExtensibleWav(40, floatGuid, pcmBytes)))
        assertNull(WavAudio.parse(buildExtensibleWav(18, null, pcmBytes)))
    }

    @Test
    fun rejectsNonWav() {
        assertNull(WavAudio.parse("<html>not wav</html>".toByteArray()))
    }
}
