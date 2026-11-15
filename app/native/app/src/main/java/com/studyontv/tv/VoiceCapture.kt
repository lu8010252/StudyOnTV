package com.studyontv.tv

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream

/**
 * 录音 → 16 kHz 单声道 16 位 WAV → DeepSeek 语音识别。
 *
 * 电视上的「语音输入」原来只走系统识别器（RecognizerIntent），华为智慧屏、TCL 这类没装语音助手的机器上
 * 根本没有可用的识别器，点了一直没反应。这里改成自己录音、上传给 DeepSeek（家长面板填了 Key 才启用，
 * 没填仍然走系统识别器）。接口限制：≤8 MiB、≤120 秒，见 https://api-docs.deepseek.com/zh-cn/guides/asr
 */
object VoiceCapture {
    private const val RATE = 16000
    /** 录到 60 秒自动停，留足 8 MiB / 120 秒的余量 */
    private const val MAX_SECONDS = 60
    private const val MAX_BYTES = RATE * 2 * MAX_SECONDS

    /** 有没有可用的麦克风（没权限 / 没硬件时返回 false，调用方回落到系统识别器） */
    fun recorder(): AudioRecord? {
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            val min = try { AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) } catch (_: Exception) { -1 }
            if (min <= 0) return null
            val record = try { AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400)) } catch (_: Exception) { null }
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
            try { record?.release() } catch (_: Exception) { }
        }
        return null
    }

    /** 16 位单声道 PCM 套 WAV 头（DeepSeek 按文件内容判断格式，扩展名无所谓） */
    fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun le32(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff); out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff) }
        fun le16(value: Int) { out.write(value and 0xff); out.write((value ushr 8) and 0xff) }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 一次录音 + 识别。回调都在主线程：
     * - `onState("recording")` 开始录，`onState("checking")` 已停止、正在等识别结果；
     * - `onResult(文字)` 识别成功；`onError(原因)` 失败（附带可读的中文原因）。
     */
    class Session(
        private val key: String,
        private val onState: (String) -> Unit,
        private val onResult: (String) -> Unit,
        private val onError: (String) -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var running = false
        @Volatile private var cancelled = false
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        var active = false
            private set

        /** 开始录音；没有麦克风时返回 false（调用方回落到系统识别器） */
        fun start(): Boolean {
            if (active) return true
            val rec = VoiceCapture.recorder() ?: return false
            record = rec
            running = true; cancelled = false; active = true
            thread = Thread {
                val pcm = ByteArrayOutputStream()
                try {
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        fail("麦克风没能打开"); return@Thread
                    }
                    main.post { onState("recording") }
                    val chunk = ByteArray(6400)
                    while (running && pcm.size() < MAX_BYTES) {
                        val size = rec.read(chunk, 0, chunk.size)
                        if (size > 0) pcm.write(chunk, 0, size) else if (size < 0) break
                    }
                } catch (e: Exception) {
                    fail("录音失败：${e.javaClass.simpleName}"); return@Thread
                } finally {
                    try { rec.stop() } catch (_: Exception) { }
                    try { rec.release() } catch (_: Exception) { }
                    record = null
                }
                if (cancelled) { active = false; return@Thread }
                val bytes = pcm.toByteArray()
                if (bytes.size < RATE / 2) { fail("没听到声音，请按一下再说"); return@Thread }
                main.post { onState("checking") }
                try {
                    val text = SpeechReader.transcribe(key, VoiceCapture.wav(bytes))
                    active = false
                    main.post { onResult(text) }
                } catch (e: Exception) {
                    fail((e.message ?: e.javaClass.simpleName).take(120))
                }
            }.apply { isDaemon = true; start() }
            return true
        }

        /** 停止录音并开始识别 */
        fun stop() { running = false }

        /** 放弃这次录音（退出页面 / 取消），不识别也不回调 */
        fun cancel() {
            cancelled = true; running = false
            try { record?.stop() } catch (_: Exception) { }
        }

        private fun fail(message: String) {
            active = false
            main.post { onError(message) }
        }
    }
}
