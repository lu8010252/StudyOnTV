package com.studyontv.tv

/**
 * 解析 PCM WAV，用于云端朗读（DeepSeek 返回的就是 WAV）。
 *
 * 华为 / EMUI 等设备上 MediaPlayer 对这类文件可能直接报
 * `IOException: Prepare failed.: status=0x1`（家长反馈：扣了钱却读不出来），
 * 因此自己解析 PCM 交给 AudioTrack 播放，不依赖系统解码器；解析不了时调用方仍回退 MediaPlayer。
 */
object WavAudio {
    class Pcm(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val data: ByteArray) {
        val frameBytes: Int get() = channels * bitsPerSample / 8
        val frames: Long get() = if (frameBytes > 0) data.size.toLong() / frameBytes else 0
    }

    /** WAVE_FORMAT_EXTENSIBLE (0xFFFE) 对应的标准线性 PCM SubFormat GUID */
    private val KSDATAFORMAT_SUBTYPE_PCM = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    fun isWave(bytes: ByteArray): Boolean =
        bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE"

    /** 只接受 16 位以内、1/2 声道的线性 PCM；其余返回 null 交给 MediaPlayer */
    fun parse(bytes: ByteArray): Pcm? {
        if (!isWave(bytes)) return null
        var position = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var isPcmFormat = false
        while (position + 8 <= bytes.size) {
            val id = tag(bytes, position)
            val sizeUnsigned = le32(bytes, position + 4).toLong() and 0xFFFFFFFFL
            val body = position + 8
            if (id == "data") {
                if (!isPcmFormat) return null
                if (channels !in 1..2 || bits != 16 || rate <= 0) return null
                val available = (bytes.size - body).coerceAtLeast(0)
                if (available <= 0) return null
                // #174：DeepSeek 流式 WAV 的 RIFF/data chunk size 为 0xFFFFFFFF（哨兵值）或大于实际长度，
                // 此时读到字节流末尾并按采样帧对齐即可，不再提前 break 返回 null。
                val rawLen = if (sizeUnsigned == 0L || sizeUnsigned == 0xFFFFFFFFL || body.toLong() + sizeUnsigned > bytes.size.toLong()) {
                    available
                } else {
                    sizeUnsigned.toInt()
                }
                val frameBytes = channels * bits / 8
                val alignedLen = if (frameBytes > 0) (rawLen / frameBytes) * frameBytes else rawLen
                if (alignedLen <= 0) return null
                return Pcm(rate, channels, bits, bytes.copyOfRange(body, body + alignedLen))
            }
            if (sizeUnsigned <= 0L || body.toLong() + sizeUnsigned > bytes.size.toLong()) break
            val size = sizeUnsigned.toInt()
            if (id == "fmt " && size >= 16) {
                val format = le16(bytes, body)
                channels = le16(bytes, body + 2)
                rate = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
                isPcmFormat = when (format) {
                    1 -> true
                    0xFFFE -> size >= 40 && matchesGuid(bytes, body + 24, KSDATAFORMAT_SUBTYPE_PCM)
                    else -> false
                }
            }
            position = body + size + (size and 1)
        }
        return null
    }

    private fun matchesGuid(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || offset + expected.size > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }

    private fun tag(bytes: ByteArray, offset: Int): String =
        if (offset + 4 > bytes.size) "" else String(bytes, offset, 4, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        if (offset + 2 > bytes.size) 0 else (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
