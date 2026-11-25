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

    internal data class ProbeOutcome(val langStatus: Int, val ok: Boolean, val hint: String)

    internal fun evaluateInitStatus(
        initStatus: Int,
        engine: String,
        tag: String,
        checkLanguage: () -> Int
    ): ProbeOutcome {
        if (initStatus != TextToSpeech.SUCCESS) {
            return ProbeOutcome(
                STATUS_INIT_FAILED,
                false,
                "系统语音引擎没启动起来（status=$initStatus）。到电视的 设置 → 无障碍 → 文字转语音 里检查一下引擎，或改用 DeepSeek 语音"
            )
        }
        val langStatus = try { checkLanguage() } catch (_: Exception) { STATUS_INIT_FAILED }
        return when {
            langStatus >= TextToSpeech.LANG_AVAILABLE -> ProbeOutcome(
                langStatus,
                true,
                if (engine.isEmpty()) "本地语音可用" else "本地语音可用（$engine）"
            )
            langStatus == TextToSpeech.LANG_MISSING_DATA -> ProbeOutcome(
                langStatus,
                false,
                "引擎已安装（$engine），但缺少 $tag 语言包。可以点「去安装语言包」，或改用 DeepSeek 语音"
            )
            langStatus == TextToSpeech.LANG_NOT_SUPPORTED -> ProbeOutcome(
                langStatus,
                false,
                "引擎（$engine）不支持 $tag。可以换一个语音引擎，或改用 DeepSeek 语音"
            )
            else -> ProbeOutcome(
                langStatus,
                false,
                "本地语音不可用（langStatus=$langStatus），可以改用 DeepSeek 语音"
            )
        }
    }

    /**
     * 单次探测的线程安全状态机：
     * - 结果通过锁内一次性提交（先到者胜出），每次构造独立 JSONObject，杜绝跨线程并发修改；
     * - 兼容构造期间同步触发 onInit 的 ROM（先记 earlyInitStatus，attachEngine 后立即求值并释放）；
     * - 保证任意时序（正常/同步回调/超时/超时后迟到回调）下引擎最多且必然 shutdown 一次。
     */
    internal class ProbeCoordinator<E : Any>(
        private val engineName: String,
        private val languageTag: String,
        private val checkLanguage: (E) -> Int,
        private val shutdown: (E) -> Unit
    ) {
        private val lock = Any()
        private val latch = CountDownLatch(1)
        private var engine: E? = null
        private var earlyInitStatus: Int? = null
        private var settled: JSONObject? = null
        private var shutdownDone = false

        fun isSettled(): Boolean = synchronized(lock) { settled != null }

        private fun buildResult(status: Int, ok: Boolean, hint: String): JSONObject =
            JSONObject()
                .put("engine", engineName)
                .put("language", languageTag)
                .put("ok", ok)
                .put("langStatus", status)
                .put("hint", hint)

        private fun complete(status: Int, ok: Boolean, hint: String): Boolean {
            val won = synchronized(lock) {
                if (settled != null) false
                else {
                    settled = buildResult(status, ok, hint)
                    true
                }
            }
            if (won) latch.countDown()
            return won
        }

        private fun takeEngineForShutdown(): E? = synchronized(lock) {
            if (shutdownDone) return null
            val target = engine ?: return null
            engine = null
            shutdownDone = true
            target
        }

        private fun shutdownIfPresent() {
            val target = takeEngineForShutdown() ?: return
            try { shutdown(target) } catch (_: Exception) { }
        }

        private fun evaluateAndFinish(target: E, status: Int) {
            try {
                val outcome = evaluateInitStatus(status, engineName, languageTag) { checkLanguage(target) }
                complete(outcome.langStatus, outcome.ok, outcome.hint)
            } finally {
                shutdownIfPresent()
            }
        }

        fun attachEngine(created: E) {
            var pendingStatus: Int? = null
            var shouldShutdown = false
            synchronized(lock) {
                engine = created
                if (settled != null) {
                    shouldShutdown = true
                } else if (earlyInitStatus != null) {
                    pendingStatus = earlyInitStatus
                    earlyInitStatus = null
                }
            }
            when {
                shouldShutdown -> shutdownIfPresent()
                pendingStatus != null -> evaluateAndFinish(created, pendingStatus!!)
            }
        }

        fun onInit(status: Int) {
            var attached: E? = null
            var alreadySettled = false
            synchronized(lock) {
                if (settled != null) {
                    alreadySettled = true
                } else if (engine == null) {
                    earlyInitStatus = status
                    return
                } else {
                    attached = engine
                }
            }
            if (alreadySettled) {
                shutdownIfPresent()
                return
            }
            evaluateAndFinish(attached!!, status)
        }

        fun onCreateFailed() {
            complete(STATUS_INIT_FAILED, false, "这台设备没有可用的系统语音引擎，可以改用 DeepSeek 语音")
            shutdownIfPresent()
        }

        fun awaitResult(timeoutMs: Long, postCleanup: (() -> Unit) -> Unit): Pair<JSONObject, Boolean> {
            val finished = try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            val timedOut = if (!finished) {
                val wonTimeout = complete(STATUS_INIT_FAILED, false, "检测超时：语音引擎没有响应，可以改用 DeepSeek 语音")
                if (wonTimeout) {
                    postCleanup { shutdownIfPresent() }
                }
                wonTimeout
            } else {
                false
            }
            val snapshot = synchronized(lock) { settled!! }
            return JSONObject(snapshot.toString()) to timedOut
        }
    }

    /**
     * 探测默认引擎能不能朗读 `language`。会阻塞最多 `timeoutMs`，只能在后台线程调用。
     * 返回 {ok, engine, language, langStatus, hint}，hint 直接给家长看。
     */
    fun probe(context: Context, language: String = "zh-CN", timeoutMs: Long = TIMEOUT_MS): JSONObject {
        val app = context.applicationContext
        val engine = defaultEngine(app)
        val tag = SpeechReader.normalizeLanguageTag(language)
        val main = Handler(Looper.getMainLooper())
        val coordinator = ProbeCoordinator<TextToSpeech>(
            engineName = engine,
            languageTag = tag,
            checkLanguage = { tts -> tts.setLanguage(Locale.forLanguageTag(tag)) },
            shutdown = { tts -> tts.shutdown() }
        )
        main.post {
            if (coordinator.isSettled()) return@post
            try {
                val created = TextToSpeech(app) { status -> coordinator.onInit(status) }
                coordinator.attachEngine(created)
            } catch (e: Exception) {
                Diag.warn("Speech", "tts probe create failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
                coordinator.onCreateFailed()
            }
        }
        val (result, timedOut) = coordinator.awaitResult(timeoutMs) { cleanup -> main.post(cleanup) }
        Diag.info("Speech", "tts probe engine=${result.optString("engine")} lang=$tag ok=${result.optBoolean("ok")} status=${result.optInt("langStatus")} timeout=$timedOut")
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
