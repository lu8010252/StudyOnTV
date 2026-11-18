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

    fun isWave(bytes: ByteArray): Boolean =
        bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE"

    /** 只接受 16 位以内、1/2 声道的线性 PCM；其余返回 null 交给 MediaPlayer */
    fun parse(bytes: ByteArray): Pcm? {
        if (!isWave(bytes)) return null
        var position = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (position + 8 <= bytes.size) {
            val id = tag(bytes, position)
            val size = le32(bytes, position + 4)
            val body = position + 8
            if (size <= 0 || body + size > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    // 1 = PCM；0xFFFE = WAVE_FORMAT_EXTENSIBLE（子格式通常也是 PCM）
                    if (format != 1 && format != 0xFFFE) return null
                    if (channels !in 1..2 || bits != 16 || rate <= 0) return null
                    return Pcm(rate, channels, bits, bytes.copyOfRange(body, body + size))
                }
            }
            position = body + size + (size and 1)
        }
        return null
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
