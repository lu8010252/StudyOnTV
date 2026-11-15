package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地 TTS 引擎探测（#128：电视端点了朗读没声音）。
 *
 * 家长在面板上点「检测本地语音」时才跑一次，用来区分三种情况：
 *   - 根本没装 TTS 引擎（引擎列表为空）
 *   - 有引擎，但缺中文语言包（setLanguage 返回 LANG_MISSING_DATA）
 *   - 引擎不支持这种语言（LANG_NOT_SUPPORTED）
 * 分别给出不同的提示和下一步，而不是只有一句「没有本地语音」。
 *
 * 注意：TextToSpeech 必须在带 Looper 的线程创建，否则初始化回调永远不来；
 * 这里统一 post 到主线程初始化，调用方线程用 latch 等结果。
 */
object TtsProbe {
    const val TIMEOUT_MS = 3000L
    /** 初始化失败或超时的 langStatus，和 TextToSpeech 的 LANG_* 负数区分开 */
    const val STATUS_INIT_FAILED = -99

    /**
     * 从系统设置读取默认 TTS 引擎包名（Settings.Secure.TTS_DEFAULT_SYNTH）。
     * TextToSpeech.getDefaultEngine() 是 instance 方法，不能在静态上下文用。
     */
    fun defaultEngine(context: Context): String = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver, "tts_default_synth"
        ) ?: ""
    } catch (_: Throwable) { "" }

    /**
     * 已安装的 TTS 引擎：只查 PackageManager，不初始化引擎，很快。
     * Android 11+ 需要在 AndroidManifest 的 queries 里声明 TTS_SERVICE，否则这里一定是空列表。
     */
    fun installedEngines(context: Context): JSONArray {
        val pm = context.packageManager
        val default = defaultEngine(context)
        val out = JSONArray()
        val seen = HashSet<String>()
        try {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            for (info in pm.queryIntentServices(intent, 0)) {
                val name = info.serviceInfo?.packageName ?: continue
                if (!seen.add(name)) continue
                val label = try { info.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
                out.put(JSONObject().put("name", name).put("label", label.ifEmpty { name }).put("default", name == default))
            }
        } catch (e: Exception) {
            Diag.warn("Speech", "query tts engines failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
        return out
    }

    /**
     * 探测默认引擎能不能朗读 `language`。会阻塞最多 `timeoutMs`，只能在后台线程调用。
     * 返回 {ok, engine, language, langStatus, hint}，hint 直接给家长看。
     */
    fun probe(context: Context, language: String = "zh-CN", timeoutMs: Long = TIMEOUT_MS): JSONObject {
        val app = context.applicationContext
        val engine = defaultEngine(app)
        val tag = SpeechReader.normalizeLanguageTag(language)
        val result = JSONObject().put("engine", engine).put("language", tag).put("ok", false)
            .put("langStatus", STATUS_INIT_FAILED)
        val latch = CountDownLatch(1)
        fun finish(status: Int, ok: Boolean, hint: String) {
            result.put("ok", ok).put("langStatus", status).put("hint", hint)
            latch.countDown()
        }
        Handler(Looper.getMainLooper()).post {
            var tts: TextToSpeech? = null
            try {
                tts = TextToSpeech(app) { status ->
                    val engineRef = tts
                    try {
                        if (status != TextToSpeech.SUCCESS || engineRef == null) {
                            finish(STATUS_INIT_FAILED, false,
                                "系统语音引擎没启动起来（status=$status）。到电视的 设置 → 无障碍 → 文字转语音 里检查一下引擎，或改用 DeepSeek 语音")
                        } else {
                            val langStatus = try { engineRef.setLanguage(Locale.forLanguageTag(tag)) }
                            catch (_: Exception) { STATUS_INIT_FAILED }
                            when {
                                langStatus >= TextToSpeech.LANG_AVAILABLE -> finish(langStatus, true,
                                    if (engine.isEmpty()) "本地语音可用" else "本地语音可用（$engine）")
                                langStatus == TextToSpeech.LANG_MISSING_DATA -> finish(langStatus, false,
                                    "引擎已安装（$engine），但缺少 $tag 语言包。可以点「去安装语言包」，或改用 DeepSeek 语音")
                                langStatus == TextToSpeech.LANG_NOT_SUPPORTED -> finish(langStatus, false,
                                    "引擎（$engine）不支持 $tag。可以换一个语音引擎，或改用 DeepSeek 语音")
                                else -> finish(langStatus, false,
                                    "本地语音不可用（langStatus=$langStatus），可以改用 DeepSeek 语音")
                            }
                        }
                    } finally {
                        try { engineRef?.shutdown() } catch (_: Exception) { }
                        tts = null
                    }
                }
            } catch (e: Exception) {
                Diag.warn("Speech", "tts probe create failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
                try { tts?.shutdown() } catch (_: Exception) { }
                finish(STATUS_INIT_FAILED, false, "这台设备没有可用的系统语音引擎，可以改用 DeepSeek 语音")
            }
        }
        val done = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) finish(STATUS_INIT_FAILED, false, "检测超时：语音引擎没有响应，可以改用 DeepSeek 语音")
        Diag.info("Speech", "tts probe engine=${result.optString("engine")} lang=$tag ok=${result.optBoolean("ok")} status=${result.optInt("langStatus")} timeout=${!done}")
        return result
    }

    /** 打开系统的「安装语音数据」页面；部分电视没有这个入口，返回 false 由面板给替代说明 */
    fun openInstallVoiceData(context: Context): Boolean = try {
        context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Diag.warn("Speech", "cannot open install tts data: ${e.javaClass.simpleName}")
        false
    }
}
