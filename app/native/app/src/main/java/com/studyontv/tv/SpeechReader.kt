package com.studyontv.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Activity 与悬浮层共用同一朗读通路；云端失败静默回落到系统。
 *
 * 失败不再静默（#87 / #106）：
 * - `setLanguage` 返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED 时先回落设备默认语言，再不行记诊断并回报；
 * - `UtteranceProgressListener` 的 onError 记诊断、用默认语言补读一次，onDone 清理状态；
 * - `playAudio` 的 MediaPlayer 报错会记诊断并回报（Toast + 桥），不再只是 release。
 * 回报有两条路：`status()`（桥方法 `speechStatus` 拉取）与 `notice` 回调（桥推给网页的 SOTV_SPEECH_NOTICE）。
 */
object SpeechReader {
    /** 语音识别白名单：白名单外的题目语言不静默当中文，而是降级到设备默认语言并记诊断（#81） */
    private val RECOGNITION_LANGUAGES = listOf("zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 题目可以标注的朗读语言；"" 表示跟随设备系统语言（#126 粤语系统被读成国语的根因是写死 zh-CN） */
    val LANGUAGES = listOf("", "zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 语音识别支持的语种（白名单外再降级，供回归测试断言） */
    val whitelist: List<String> get() = RECOGNITION_LANGUAGES
    /** 设备级朗读语言覆盖："" 跟随题目、"system" 跟随系统，其余为固定语言标签 */
    private val OVERRIDES = LANGUAGES + listOf("system")

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor()
    /** 下一分段的预合成放到独立线程，绝不堵塞当前分段在 worker 上的 PCM 播放。 */
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    /**
     * 云端合成结果缓存：题目重绘、回填草稿都会再次触发自动朗读，同一段文字没必要再合成一次
     * （DeepSeek 按字符计费，用户反馈「读不出来还在扣钱」）。只留最近几段。
     */
    private val synthCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 6
    }
    /** 云端 WAV 走 AudioTrack 播放（华为等设备的 MediaPlayer 播不了，用户反馈），stopNow 暂停打断、playPcm finally 唯一负责释放 */
    private val trackLock = Any()
    @Volatile private var track: AudioTrack? = null
    private var audioFile: File? = null
    private var system: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val tokenLock = Any()
    @Volatile private var token = ""
    @Volatile private var tokenExpires = 0L
    @Volatile private var tokenKey = ""
    /** 最近一次朗读 / 播放的结果，供 speechStatus 桥与回归测试断言 */
    @Volatile private var last: JSONObject? = null
    @Volatile private var speaking = false
    /** 最近一次朗读活跃时间（elapsedRealtime），用于 MediaPause.enforce 避免把自家朗读误判成后台视频而静音（#168） */
    @Volatile private var lastSpokeAt = 0L

    private fun markSpeaking(active: Boolean) {
        speaking = active
        lastSpokeAt = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
    }
    /** 桥的回报回调（只在一次朗读 / 播放期间持有，stopNow 释放，避免长期抓住 WebView） */
    private var notice: ((JSONObject) -> Unit)? = null
    private var cachedContext: Context? = null
    @Volatile private var currentText = ""
    @Volatile private var currentLanguage = ""
    /** 单次朗读的语速覆盖：听力题可以按题指定，-1 表示跟随全局 speechRate */
    @Volatile private var currentRate = -1.0
    @Volatile private var lastUtteranceId = ""
    @Volatile private var retriedDefault = false
    @Volatile private var retriedSpeakError = false
    // 走媒体流而不是无障碍流：华为智慧屏、TCL 这类电视上无障碍流常常没有输出，
    // 表现就是「点了朗读没声音」；媒体流一定会经过电视的扬声器 / HDMI。
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** DeepSeek 语音（合成 + 转写）的两种音色，见 https://api-docs.deepseek.com/zh-cn/guides/tts */
    val DEEPSEEK_VOICES = listOf("stella", "tide")
    /** 语音转写通路：system 用系统识别器、deepseek 上传给 DeepSeek、auto 填了 Key 就走 DeepSeek */
    val RECOGNIZE_MODES = listOf("system", "deepseek", "auto")

    /** 自动朗读的内容范围：只读题干、题干和选项、题干选项再加答案（答案在作答后读，作答前不剧透） */
    val READ_SCOPES = listOf("question", "questionOptions", "questionOptionsAnswer")

    fun settings(store: LocalStore): JSONObject {
        val raw = JSONObject(store.get("speech_settings", "{}"))
        val ownDeepseek = raw.optString("deepseekKey").trim()
        val effectiveDeepseek = deepseekKey(raw, store)
        return raw.apply {
            if (!has("mode")) put("mode", "auto")
            if (!has("readLanguage")) put("readLanguage", "")
            if (!has("edgeVoice")) put("edgeVoice", "")
            if (!has("speechRate")) put("speechRate", 1.0)
            if (!has("deepseekVoice")) put("deepseekVoice", "stella")
            if (!has("recognizeMode")) put("recognizeMode", "system")
            if (optString("readScope") !in READ_SCOPES) put("readScope", "question")
            for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) { put(key + "Set", optString(key).isNotEmpty()); remove(key) }
            put("deepseekInherited", ownDeepseek.isEmpty() && effectiveDeepseek.isNotEmpty())
            put("deepseekReady", effectiveDeepseek.isNotEmpty())
        }
    }
    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val next = JSONObject(store.get("speech_settings", "{}"))
        val mode = input.optString("mode", "system")
        require(mode in listOf("auto", "system", "edge", "deepseek", "azure", "aliyun")) { "未知朗读通路" }
        next.put("mode", mode).put("autoRead", input.optBoolean("autoRead"))
        if (input.has("readScope")) {
            val scope = input.optString("readScope", "question")
            require(scope in READ_SCOPES) { "未知朗读内容" }
            next.put("readScope", scope)
        }
        if (input.has("recognizeMode")) {
            val recognize = input.optString("recognizeMode", "system")
            require(recognize in RECOGNIZE_MODES) { "未知语音识别通路" }
            next.put("recognizeMode", recognize)
        }
        if (input.has("deepseekVoice")) {
            val voice = input.optString("deepseekVoice").trim().ifEmpty { "stella" }
            require(voice in DEEPSEEK_VOICES) { "不支持的 DeepSeek 音色" }
            next.put("deepseekVoice", voice)
        }
        // #126：设备级朗读语言。空 = 跟随题目设置，"system" = 跟随设备系统语言（粤语系统读粤语）
        if (input.has("readLanguage")) {
            val readLanguage = input.optString("readLanguage").trim()
            require(readLanguage in OVERRIDES) { "不支持的朗读语言" }
            next.put("readLanguage", readLanguage)
        }
        if (input.has("edgeVoice")) {
            val edgeVoice = input.optString("edgeVoice").trim()
            require(edgeVoice.isEmpty() || edgeVoice in EdgeTts.VOICES) { "不支持的 Edge 音色" }
            next.put("edgeVoice", edgeVoice)
        }
        if (input.has("speechRate")) {
            val rate = input.optDouble("speechRate", 1.0)
            require(rate.isFinite() && rate in 0.7..1.5) { "朗读语速需在 0.7 到 1.5 之间" }
            next.put("speechRate", rate)
        }
        for (key in listOf("azureRegion", "azureVoice", "aliyunAppKey", "aliyunAccessKeyId", "aliyunVoice")) if (input.has(key)) {
            require(input.getString(key).length <= 200); next.put(key, input.getString(key).trim())
        }
        require(next.optString("azureRegion").matches(Regex("[a-z0-9-]*"))) { "Azure 区域格式错误" }
        applySpeechKeyMutations(next, input)
        // 在朗读里填写 DeepSeek Key 时，若「模型 API」选的是 DeepSeek 且还没填 Key，自动同步过去，避免家长填两遍
        val newDeepseekKey = input.optString("deepseekKey").trim()
        if (newDeepseekKey.isNotEmpty()) {
            try {
                val grading = JSONObject(store.get("grading_settings", "{}"))
                val provider = grading.optString("provider", "deepseek")
                if (provider == "deepseek" && grading.optString("apiKey").trim().isEmpty()) {
                    grading.put("provider", "deepseek").put("apiKey", newDeepseekKey)
                    store.set("grading_settings", grading.toString())
                }
            } catch (_: Exception) { }
        }
        store.set("speech_settings", next.toString())
        synchronized(tokenLock) { token = ""; tokenExpires = 0L; tokenKey = "" }
        synchronized(synthCache) { synthCache.clear() }
        return settings(store)
    }

    /**
     * 朗读。`language` 是题目语言；引擎不支持时先回落设备默认语言，再不行回报失败。
     * `notice` 由桥传入，失败时回调（网页里有 window.SOTV_SPEECH_NOTICE 就能显示提示）。
     * `rate` 是本次朗读的语速（听力题的 listenRate），-1 表示用朗读设置里的全局语速。
     */
    fun read(context: Context, text: String, language: String = "", notice: ((JSONObject) -> Unit)? = null, rate: Double = -1.0) {
        if (text.isBlank()) return
        val app = context.applicationContext
        main.post {
            val config = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Throwable) { JSONObject() }
            // 听力题按题指定语速：写进本次配置副本，云端合成与缓存键都会跟着用这个语速
            currentRate = if (rate.isFinite() && rate in 0.7..1.5) rate else -1.0
            if (currentRate > 0) config.put("speechRate", currentRate)
            val mode = config.optString("mode", "auto")
            val chosen = when {
                LocalOnlyGuard.enabled -> "system"
                mode == "auto" -> when {
                    deepseekKey(config).isNotEmpty() -> "deepseek"
                    config.optString("azureKey").isNotEmpty() -> "azure"
                    config.optString("aliyunSecret").isNotEmpty() -> "aliyun"
                    TtsProbe.defaultEngine(app).isNotEmpty() -> "system"
                    else -> "edge"
                }
                // 电视没有内置语音引擎时，如果家长已在「模型 API」配过 DeepSeek Key，自动兜底使用 DeepSeek 朗读
                mode == "system" && TtsProbe.defaultEngine(app).isEmpty() && deepseekKey(config).isNotEmpty() -> "deepseek"
                else -> mode
            }
            // 系统 TTS 使用 QUEUE_FLUSH 覆盖旧播报，避免在部分固件（如 OPPO）上连续调用 system.stop() + speak() 导致后续题目静默丢弃
            stopNow(stopSystem = chosen != "system")
            val request = generation
            this.notice = notice
            cachedContext = app
            val clean = safeTruncate(text, 10000)
            // #126：家长可以在朗读设置里指定设备朗读语言；留空就跟随每道题的语言标注
            val spoken = when (val override = config.optString("readLanguage").trim()) {
                "system" -> ""          // 跟随设备系统语言
                "" -> language          // 跟随题目
                else -> override
            }
            if (chosen == "system") systemRead(app, clean, spoken, request)
            else cloudPart(app, cloudChunks(clean, chosen), 0, spoken, config, chosen, request)
        }
    }

    /** 本次朗读实际使用的语速：听力题按题指定优先，否则取朗读设置里的全局值 */
    private fun rateOf(config: JSONObject): Double {
        val raw = if (currentRate in 0.7..1.5) currentRate else config.optDouble("speechRate", 1.0)
        return raw.takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
    }

    /** 题目音频。老电视的 https / mp3 兼容性差时不再“点了没反应”：记录诊断 + Toast + 回报给前端 */
    fun playAudio(context: Context, url: String, notice: ((JSONObject) -> Unit)? = null) {
        val app = context.applicationContext
        // 地址本身不合法：不排队到主线程，直接同步回报（回归测试可断言 status()）
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            recordFailure("audio", "unsupported-url", "", "", false, "音频地址不正确，无法播放", notice)
            return
        }
        if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) {
            recordFailure("audio", "local-only-blocked", "", "", false, "仅本地隐私版仅允许播放本机或局域网内的音频", notice)
            return
        }
        main.post {
            stopNow(); val request = generation
            this.notice = notice
            cachedContext = app
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes); setDataSource(url)
                    setOnPreparedListener { if (request == generation) { markSpeaking(true); start() } else release() }
                    setOnCompletionListener {
                        release(); if (request == generation) { markSpeaking(false); player = null; notify(true, "audio", "done", "", "", false, "") }
                    }
                    setOnErrorListener { _, what, extra ->
                        release(); if (request == generation) { markSpeaking(false); player = null }
                        audioFailed(app, request, "player-error", "音频无法播放（what=$what extra=$extra），请检查网络或改用朗读")
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                player?.release(); player = null
                audioFailed(app, request, e.javaClass.simpleName, "音频无法播放（${e.javaClass.simpleName} ${e.message?.take(80).orEmpty()}），请检查网络或改用朗读")
            }
        }
    }

    fun stop() { main.post { stopNow() } }

    /** 需要直接弹在电视屏幕上（而不是只写诊断）的失败：老电视上「点了朗读没声音」最常见的就是这几类 */
    private val TOAST_REASONS = setOf("engine-unavailable", "language-unavailable", "language-check-failed", "utterance-error", "cloud-unavailable")

    /** 当前/最近一次朗读结果：{ok,mode,requested,language,degraded,reason,message,at}，桥方法 speechStatus 直接返回它 */
    fun status(): JSONObject = last?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("ok", true).put("mode", "").put("requested", "").put("language", "").put("degraded", false)
        .put("reason", "idle").put("message", "").put("at", 0L)

    fun speaking(): Boolean = speaking

    /** 正在朗读或刚结束朗读不久（Android AudioManager.isMusicActive 在停播后仍会残留 1–2 秒） */
    fun speakingRecently(graceMs: Long = 2500L): Boolean {
        if (speaking) return true
        val at = lastSpokeAt
        if (at <= 0L) return false
        val now = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { return false }
        return now - at < graceMs
    }

    /** 失败信息结构（纯函数，回归测试可直接断言结构；不含题目 / 答案明文） */
    fun failureNotice(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String): JSONObject =
        JSONObject().put("ok", false).put("mode", mode).put("requested", requested).put("language", language)
            .put("degraded", degraded).put("reason", reason).put("message", message).put("at", System.currentTimeMillis())

    /**
     * BCP47 标签标准化：`zh-Hans-CN` / `zh-Hant-TW` 这类带文字扩展的标签，部分 TTS 引擎的
     * `setLanguage` 会直接返回 LANG_MISSING_DATA，表现就是「明明装了中文语音包却读不出来」(#128)。
     */
    fun normalizeLanguageTag(raw: String): String {
        val tag = raw.trim()
        return when {
            tag.isEmpty() -> "zh-CN"
            tag.startsWith("zh-Hans") || tag == "zh" || tag == "zh-CN" -> "zh-CN"
            tag.startsWith("zh-Hant-TW") || tag == "zh-TW" -> "zh-TW"
            tag.startsWith("zh-Hant-HK") || tag == "zh-HK" || tag.startsWith("zh-Hant-MO") -> "zh-HK"
            tag.startsWith("en-US") || tag == "en" -> "en-US"
            tag.startsWith("en-GB") || tag.startsWith("en-") -> "en-GB"
            else -> tag
        }
    }

    /** 设备默认语言（TTS 语言缺失时的回落目标），已做 BCP47 标准化 */
    fun defaultLanguage(): String = normalizeLanguageTag(Locale.getDefault().toLanguageTag())

    /** 语言回落结果：degraded=true 表示请求的语言用不了，已经换成别的（或彻底失败，accepted=false） */
    data class LanguagePlan(val requested: String, val language: String, val accepted: Boolean, val degraded: Boolean, val reason: String)

    /**
     * 系统语音语言回落（纯函数，不碰引擎，instrumentation 可直接断言）。
     *
     * `available(tag)` 由调用方提供：真实场景就是 `system.setLanguage(Locale.forLanguageTag(tag))` 的返回值
     * （TextToSpeech.LANG_* 常量）；返回 null 表示“无法判断”（引擎未就绪 / 旧实现），按可用处理。
     */
    fun resolveLanguage(requested: String, fallback: String = defaultLanguage(), available: (String) -> Int?): LanguagePlan {
        val want = requested.trim()
        val primary = want.ifEmpty { fallback }
        val status = ask(available, primary)
        if (status == null || status >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, primary, true, false, "available")
        if (primary == fallback) return LanguagePlan(want, primary, false, true, "unavailable")
        val fallbackStatus = ask(available, fallback)
        if (fallbackStatus == null || fallbackStatus >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, fallback, true, true, "fallback")
        return LanguagePlan(want, primary, false, true, "unavailable")
    }

    /** available 抛异常（引擎被回收 / 还没就绪）时按“无法判断”处理，不把异常抛给调用方 */
    private fun ask(available: (String) -> Int?, tag: String): Int? = try { available(tag) } catch (_: Exception) { null }

    /** 语音识别语言：白名单内原样用；白名单外降级到设备默认语言并记一条诊断（不再静默变中文） */
    fun recognitionLanguage(requested: String): String {
        val want = requested.trim()
        // 题目没标语言：跟随设备系统语言（粤语 / 繁体系统的孩子用粤语识别），不支持时才用中文
        if (want.isEmpty()) return defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        if (want in RECOGNITION_LANGUAGES) return want
        val fallback = defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        Diag.warn("Speech", "recognition language $want not supported, degraded to $fallback")
        return fallback
    }

    internal fun currentGeneration(): Int = generation

    internal fun bumpGeneration(): Int {
        pending = null
        notice = null
        return ++generation
    }

    internal fun notify(
        ok: Boolean,
        mode: String,
        reason: String,
        requested: String,
        language: String,
        degraded: Boolean,
        message: String,
        request: Int? = null
    ) {
        if (request != null && request != generation) return
        val payload = failureNotice(mode, reason, requested, language, degraded, message).put("ok", ok)
        last = payload
        // 成功只更新状态；只有失败才写诊断 + 推给网页（避免正常朗读也被当成提示）
        if (ok) return
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        // 页面上没有 SOTV_SPEECH_NOTICE 时（老版本网页）至少让电视屏幕上有个提示，不能点了没反应
        if (reason in TOAST_REASONS) main.post {
            if (request != null && request != generation) return@post
            try { cachedContext?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } } catch (_: Exception) { }
        }
        val callback = notice ?: return
        val copy = JSONObject(payload.toString())
        main.post {
            if (request != null && request != generation) return@post
            try { callback(copy) } catch (_: Exception) { }
        }
    }

    /** 失败回报：写状态 + 诊断，并回调桥（可在任意线程调用） */
    private fun recordFailure(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String, callback: ((JSONObject) -> Unit)?) {
        val payload = failureNotice(mode, reason, requested, language, degraded, message)
        last = payload
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        try { callback?.invoke(JSONObject(payload.toString())) } catch (_: Exception) { }
    }

    private fun audioFailed(context: Context, request: Int, reason: String, message: String) {
        if (request != generation) return
        markSpeaking(false)
        notify(false, "audio", reason, "", "", false, message)
        try { Toast.makeText(context, "音频无法播放，请检查网络或改用朗读", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    private fun stopNow(stopSystem: Boolean = true) {
        generation++; pending = null
        if (stopSystem) system?.stop()
        synchronized(trackLock) {
            track?.let {
                try { it.pause(); it.flush() } catch (_: Exception) { }
            }
        }
        player?.release(); player = null; audioFile?.delete(); audioFile = null
        markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""; retriedDefault = false; retriedSpeakError = false
        notice = null
    }

    /** UtteranceProgressListener：朗读结束清理状态，合成失败记诊断并用默认语言补读一次 */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { markSpeaking(true) }
        override fun onDone(utteranceId: String?) {
            if (utteranceId != lastUtteranceId) return
            markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) {
            Diag.warn("Speech", "utterance error id=$utteranceId code=$errorCode language=${currentLanguage.ifEmpty { "-" }}")
            val request = utteranceId?.split("-")?.getOrNull(1)?.toIntOrNull() ?: return
            if (request != generation) return
            markSpeaking(false)
            val text = currentText; val context = cachedContext
            val fallback = defaultLanguage()
            if (!retriedDefault && context != null && text.isNotEmpty() && currentLanguage != fallback) {
                retriedDefault = true
                Diag.warn("Speech", "retry reading with default language $fallback (was ${currentLanguage.ifEmpty { "-" }})")
                main.post { speakSystem(context, text, fallback, request, "system") }
                return
            }
            notify(false, "system", "utterance-error", currentLanguage, currentLanguage, true,
                "系统语音播放失败（code=$errorCode），可以换一种语言或改用云端语音")
        }
    }

    private fun systemRead(context: Context, text: String, language: String, request: Int, mode: String = "system", cloudError: String = "") {
        if (request != generation) return
        cachedContext = context.applicationContext
        val speak = { if (request == generation) speakSystem(context, text, language, request, mode, cloudError) }
        if (ready) speak()
        else {
            pending = speak
            if (system == null) system = TextToSpeech(context) { status ->
                main.post {
                    ready = status == TextToSpeech.SUCCESS
                    val action = pending; pending = null
                    if (ready) system?.setOnUtteranceProgressListener(progress)
                    else { system?.shutdown(); system = null; Diag.warn("Speech", "tts init failed status=$status") }
                    // 初始化失败时 speakSystem 会看到 system==null 并回报“系统语音不可用”
                    action?.invoke()
                }
            }
        }
    }

    private fun speakSystem(context: Context, text: String, language: String, request: Int, mode: String, cloudError: String = "") {
        val engine = system
        if (engine == null) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError（且本机无内置系统语音）"
            } else {
                "这台电视没有内置的系统语音，读不出来；家长可以在面板「模型 API」填写 DeepSeek Key 后自动使用云端语音"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "engine-unavailable", language, "", cloudError.isNotEmpty(), msg)
            return
        }
        val plan = try {
            resolveLanguage(language) { tag -> try { engine.setLanguage(Locale.forLanguageTag(tag)) } catch (_: Exception) { null } }
        } catch (e: Exception) {
            Diag.warn("Speech", "setLanguage failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            notify(false, mode, "language-check-failed", language, "", false, cloudError.ifEmpty { "系统语音不可用（${e.javaClass.simpleName}）" })
            return
        }
        if (plan.reason == "fallback") Diag.info("Speech", "language ${plan.requested} unavailable, fell back to ${plan.language}")
        if (!plan.accepted) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError（且系统语音不支持 ${plan.requested}）"
            } else {
                "这台设备的系统语音不支持 ${plan.requested}，装个语音包或配置 DeepSeek 语音再试"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "language-unavailable", plan.requested, plan.language, true, msg)
            return
        }
        try { engine.setAudioAttributes(attributes) } catch (_: Exception) { }
        try {
            val rate = rateOf(currentSettings())
            engine.setSpeechRate(rate.toFloat())
        } catch (_: Exception) { }
        currentText = text; currentLanguage = plan.language
        if (plan.degraded) retriedDefault = true
        // 按句子攒到 180 字再排队朗读：电视内置 TTS 引擎读几千字的长句常常中途断掉（用户反馈「朗读不完整」），
        // 拆成短句用 QUEUE_ADD 串起来更稳，句间停顿也更自然。
        val max = minOf(180, TextToSpeech.getMaxSpeechInputLength())
        val parts = packedSentences(text, max).ifEmpty { listOf(text) }
        lastUtteranceId = "sotv-$request-${parts.size - 1}"
        parts.forEachIndexed { index, part ->
            val rc = engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "sotv-$request-$index")
            if (rc == TextToSpeech.ERROR) {
                Diag.warn("Speech", "engine.speak returned ERROR on part $index, reinitializing TTS")
                try { engine.shutdown() } catch (_: Exception) { }
                system = null; ready = false
                if (!retriedSpeakError) {
                    retriedSpeakError = true
                    systemRead(context, text, language, request, mode, cloudError)
                } else {
                    notify(false, mode, "speak-error", plan.requested, plan.language, plan.degraded,
                        "系统语音引擎无响应，请重试或改用云端语音")
                }
                return
            }
        }
        markSpeaking(true)
        notify(true, mode, if (plan.degraded) "language-fallback" else "ok", plan.requested.ifEmpty { plan.language }, plan.language, plan.degraded, "")
    }

    private fun currentSettings(): JSONObject = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }

    internal fun safeTruncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    internal fun chunkCodePointSafe(text: String, limit: Int): List<String> {
        if (text.isEmpty() || limit <= 0) return if (text.isEmpty()) emptyList() else listOf(text)
        val out = ArrayList<String>()
        var pos = 0
        while (pos < text.length) {
            var end = minOf(pos + limit, text.length)
            if (end < text.length && end > pos &&
                Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
            ) {
                if (end - 1 > pos) end-- else end++
            }
            out.add(text.substring(pos, end))
            pos = end
        }
        return out
    }

    /**
     * 云端合成的分段。DeepSeek 一次最多读 1 万字，按句子攒到 400 字再请求，避免长题被切成十几段；
     * 其他通路按代理对安全切分 150 码元，绝不劈裂 emoji 或 Unicode 数学符号。
     */
    fun cloudChunks(text: String, mode: String = "azure"): List<String> =
        if (mode == "deepseek") packedSentences(text, 400) else chunkCodePointSafe(text, 150)

    private val SENTENCE_ENDS = "。！？!?；;\n"

    private fun packedSentences(text: String, limit: Int): List<String> {
        val pieces = ArrayList<String>(); val current = StringBuilder()
        fun flush() { val piece = current.toString().trim(); if (piece.isNotEmpty()) pieces.add(piece); current.setLength(0) }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                current.append(ch).append(text[i + 1])
                i += 2
                if (current.length >= limit) flush()
            } else {
                current.append(ch)
                i++
                if (ch in SENTENCE_ENDS || current.length >= limit) flush()
            }
        }
        flush()
        val packed = ArrayList<String>(); val buffer = StringBuilder()
        for (piece in pieces) {
            if (buffer.isNotEmpty() && buffer.length + piece.length > limit) { packed.add(buffer.toString()); buffer.setLength(0) }
            buffer.append(piece)
        }
        if (buffer.isNotEmpty()) packed.add(buffer.toString())
        return packed.ifEmpty { listOf(text) }
    }

    internal fun resolveDeepseekKey(config: JSONObject, gradingJsonProvider: () -> String): String {
        val own = config.optString("deepseekKey").trim()
        if (own.isNotEmpty()) return own
        if (!config.optBoolean("reuseGradingKey", true)) return ""
        return try {
            val grading = JSONObject(gradingJsonProvider())
            val provider = grading.optString("provider", "deepseek").trim()
            val endpoint = grading.optString("endpoint", "https://api.deepseek.com/v1").trim()
            if (provider == "deepseek" || endpoint.contains("api.deepseek.com", ignoreCase = true)) {
                grading.optString("apiKey").trim()
            } else ""
        } catch (_: Exception) { "" }
    }

    /**
     * DeepSeek 的 Key：朗读设置里填了就用它；没填时自动复用「模型 API」里同一家服务商（DeepSeek）的 Key，
     * 家长不用为了朗读再填一遍。
     */
    fun deepseekKey(config: JSONObject = currentSettings(), store: LocalStore? = null): String =
        resolveDeepseekKey(config) { (store ?: DeviceRuntime.store).get("grading_settings", "{}") }

    internal fun resolveRecognizeKey(localOnly: Boolean, speechJson: String, gradingJsonProvider: () -> String): String {
        if (localOnly) return ""
        val config = try { JSONObject(speechJson) } catch (_: Exception) { JSONObject() }
        return if (config.optString("recognizeMode", "system") == "system") "" else resolveDeepseekKey(config, gradingJsonProvider)
    }

    /** 语音转写要用的 DeepSeek Key；空串表示继续用系统识别器（电视上常常没有） */
    fun recognizeKey(store: LocalStore): String =
        resolveRecognizeKey(LocalOnlyGuard.enabled, store.get("speech_settings", "{}")) { store.get("grading_settings", "{}") }

    /** DeepSeek 语音合成：返回 wav 字节（metrics 见 https://api-docs.deepseek.com/zh-cn/guides/tts） */
    private fun deepseekBytes(config: JSONObject, text: String, language: String): ByteArray {
        val key = deepseekKey(config)
        require(key.isNotEmpty()) { "还没填 DeepSeek API Key" }
        // 接口没有语种参数，语种由文本自动判断；只传音色和格式
        val voice = config.optString("deepseekVoice").trim().takeIf { it in DEEPSEEK_VOICES } ?: "stella"
        val body = JSONObject().put("text", text).put("voice_id", voice).put("format", "wav").toString()
        return postBytes("https://api.deepseek.com/v1/tools/tts", key, body, "语音合成")
    }

    /** 先清空旧密钥，再写入本次显式提交的新密钥，确保 clearKeys 与新密钥同传时幂等一致。 */
    internal fun applySpeechKeyMutations(next: JSONObject, input: JSONObject) {
        if (input.optBoolean("clearKeys")) {
            next.remove("azureKey")
            next.remove("aliyunSecret")
            next.remove("deepseekKey")
        }
        for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
            if (input.has(key) && input.optString(key).isNotBlank()) {
                val raw = input.getString(key)
                require(raw.length <= 500)
                next.put(key, raw.trim())
            }
        }
    }

    internal fun requireCloudHttpAllowed(what: String, localOnly: Boolean = LocalOnlyGuard.enabled) {
        if (localOnly) throw IllegalStateException("仅本地隐私版（EU Local-Only）已禁用云端$what")
    }

    internal fun openCloudConnection(url: String, what: String, localOnly: Boolean = LocalOnlyGuard.enabled): HttpURLConnection {
        requireCloudHttpAllowed(what, localOnly)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }
    }

    private fun postBytes(url: String, key: String, body: String, what: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection(url, what)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = try { connection.errorStream?.use { input -> String(input.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null }
                throw IllegalStateException(formatDeepSeekError(what, code, detail))
            }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    out.write(buffer, 0, size)
                    require(out.size() <= 4_000_000) { "DeepSeek $what 返回的数据过大" }
                }
                out.toByteArray().also { require(it.size > 44) { "DeepSeek $what 返回的数据是空的" } }
            }
        } finally { connection.disconnect() }
    }

    internal fun formatDeepSeekError(what: String, code: Int, rawDetail: String?): String {
        val parsed = try {
            val j = JSONObject(rawDetail.orEmpty())
            j.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { j.optString("message").orEmpty() }
                .ifBlank { j.optString("error").orEmpty() }
        } catch (_: Exception) { rawDetail.orEmpty().trim() }
        val lower = parsed.lowercase(Locale.US)
        val friendly = when {
            code == 402 || lower.contains("insufficient balance") || lower.contains("insufficient_quota") || lower.contains("quota") ->
                "DeepSeek 账户余额不足，请先到 DeepSeek 开放平台充值"
            code == 401 || code == 403 || lower.contains("invalid_api_key") || lower.contains("authentication") ->
                "DeepSeek API Key 无效或无权限（需使用 DeepSeek 官方 Key）"
            code == 404 ->
                "当前接口不支持 DeepSeek $what（需使用官方 API Key，不支持第三方中转站）"
            code == 413 ->
                "内容过长，超出 DeepSeek $what 单次限制"
            code == 429 || lower.contains("rate limit") || lower.contains("too many requests") ->
                "DeepSeek 请求过于频繁，请稍后再试"
            code in listOf(500, 502, 503, 504) || lower.contains("overloaded") || lower.contains("unavailable") ->
                "DeepSeek 服务暂时繁忙，请稍后再试"
            else -> null
        }
        if (friendly != null) return friendly
        val extra = parsed.take(120).let { if (it.isNotEmpty()) "：$it" else "" }
        return "DeepSeek $what 失败（HTTP $code）$extra"
    }

    /**
     * DeepSeek 语音识别：上传 16 位 PCM WAV，返回识别出的文字。
     * 接口限制音频 ≤8 MiB、≤120 秒，一次整段识别（https://api-docs.deepseek.com/zh-cn/guides/asr）。
     * 会阻塞，调用方放到后台线程。
     */
    fun transcribe(key: String, wav: ByteArray): String {
        requireCloudHttpAllowed("语音识别")
        require(key.isNotEmpty()) { "还没填 DeepSeek API Key" }
        require(wav.size > 44) { "没有录到声音" }
        require(wav.size <= 8 * 1024 * 1024) { "录音太长，请分段说" }
        val boundary = "----sotv" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"answer.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection("https://api.deepseek.com/v1/tools/asr", "语音识别")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(head.size + wav.size + tail.size)
            connection.outputStream.use { it.write(head); it.write(wav); it.write(tail) }
            val code = connection.responseCode
            val body = try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            } catch (_: Exception) { "" }
            if (code !in 200..299) throw IllegalStateException(formatDeepSeekError("语音识别", code, body))
            return JSONObject(body).optString("text").trim().also { require(it.isNotEmpty()) { "没有听清，请再说一遍" } }
        } finally { connection.disconnect() }
    }

    private val diskLock = Any()

    internal fun isValidCachedAudio(mode: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return when (mode) {
            "deepseek" -> bytes.size > 44 && WavAudio.isWave(bytes)
            else -> {
                if (bytes.size <= 128) return false
                val head = firstNonWhitespaceByte(bytes) ?: return false
                if (head == '{'.code || head == '<'.code || head == '['.code) return false
                hasMp3Signature(bytes)
            }
        }
    }

    private fun firstNonWhitespaceByte(bytes: ByteArray): Int? {
        val limit = minOf(bytes.size, 64)
        for (i in 0 until limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b != ' '.code && b != '\t'.code && b != '\r'.code && b != '\n'.code) return b
        }
        return null
    }

    private fun hasMp3Signature(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return true
        }
        val scanLimit = minOf(bytes.size - 1, 64)
        for (i in 0 until scanLimit) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                return true
            }
        }
        return false
    }

    private fun diskFileInDir(dir: File, cacheKey: String): File? {
        if (cacheKey.isEmpty()) return null
        if (!dir.exists()) dir.mkdirs()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.bin")
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "tts_cache")

    internal fun readDiskCacheFromDir(dir: File, mode: String, cacheKey: String): ByteArray? = synchronized(diskLock) {
        val f = diskFileInDir(dir, cacheKey) ?: return null
        if (!f.exists()) return null
        val data = try { f.readBytes() } catch (_: Exception) { null }
        if (data == null || !isValidCachedAudio(mode, data)) {
            try { f.delete() } catch (_: Exception) { }
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        return data
    }

    internal fun writeDiskCacheToDir(dir: File, cacheKey: String, bytes: ByteArray) {
        synchronized(diskLock) {
            if (cacheKey.isEmpty() || bytes.size > 512 * 1024) return
            val f = diskFileInDir(dir, cacheKey) ?: return
            val tmp = File(f.parentFile, "${f.name}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                        return
                    }
                }
                val parent = f.parentFile ?: return
                val files = parent.listFiles { file -> file.isFile && file.name.endsWith(".bin") } ?: return
                var total = files.sumOf { it.length() }
                if (total > 20L * 1024 * 1024) {
                    for (old in files.sortedBy { it.lastModified() }) {
                        if (total <= 16L * 1024 * 1024) break
                        val len = old.length()
                        if (old.delete()) total -= len
                    }
                }
            } catch (_: Exception) {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun readDiskCache(context: Context, mode: String, cacheKey: String): ByteArray? =
        readDiskCacheFromDir(cacheDir(context), mode, cacheKey)

    private fun writeDiskCache(context: Context, cacheKey: String, bytes: ByteArray) =
        writeDiskCacheToDir(cacheDir(context), cacheKey, bytes)

    private fun cacheKeyFor(mode: String, config: JSONObject, language: String, part: String): String {
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return when (mode) {
            "deepseek" -> "${deepseekKey(config).hashCode()}|" + config.optString("deepseekVoice") + "|" + part
            "edge" -> "edge|${config.optString("edgeVoice")}|$language|$rate|$part"
            else -> ""
        }
    }
    internal fun synthWithCacheCore(
        cacheDir: File,
        mode: String,
        cacheKey: String,
        synthesize: () -> ByteArray
    ): ByteArray {
        if (cacheKey.isNotEmpty()) {
            synchronized(synthCache) { synthCache[cacheKey] }?.let { cached ->
                if (isValidCachedAudio(mode, cached)) return cached
                synchronized(synthCache) { synthCache.remove(cacheKey) }
            }
            readDiskCacheFromDir(cacheDir, mode, cacheKey)?.let {
                synchronized(synthCache) { synthCache[cacheKey] = it }
                return it
            }
        }
        val bytes = synthesize()
        if (!isValidCachedAudio(mode, bytes)) {
            throw IllegalStateException("云端朗读返回的不是音频：${bodySnippet(bytes)}")
        }
        if (cacheKey.isNotEmpty() && bytes.size <= 512 * 1024) {
            synchronized(synthCache) { synthCache[cacheKey] = bytes }
            writeDiskCacheToDir(cacheDir, cacheKey, bytes)
        }
        return bytes
    }

    private fun synthWithCache(context: Context, mode: String, config: JSONObject, language: String, part: String): ByteArray {
        val cacheKey = cacheKeyFor(mode, config, language, part)
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return synthWithCacheCore(cacheDir(context), mode, cacheKey) {
            when (mode) {
                "deepseek" -> deepseekBytes(config, part, language)
                "edge" -> EdgeTts.synthesize(part, config.optString("edgeVoice"), language, rate)
                "azure" -> azure(config, part, language)
                else -> aliyun(config, part)
            }
        }
    }

    private fun cloudPart(context: Context, parts: List<String>, index: Int, language: String, config: JSONObject, mode: String, request: Int) {
        if (index >= parts.size || generation != request) return
        worker.execute {
            if (generation != request) return@execute
            try {
                val bytes = synthWithCache(context, mode, config, language, parts[index])
                if (index + 1 < parts.size && mode in listOf("edge", "deepseek")) {
                    prefetchWorker.execute {
                        if (generation == request) try { synthWithCache(context, mode, config, language, parts[index + 1]) } catch (_: Exception) { }
                    }
                }
                // DeepSeek 返回 WAV：自己解析 PCM 用 AudioTrack 直接在内存播放，不落临时文件，
                // 绕开华为等设备上 MediaPlayer 的 "Prepare failed.: status=0x1"
                val pcm = if (mode == "deepseek") WavAudio.parse(bytes) else null
                if (mode == "deepseek" && pcm == null && !WavAudio.isWave(bytes)) {
                    throw IllegalStateException("云端朗读返回的不是音频：${bodySnippet(bytes)}")
                }
                if (pcm != null) {
                    main.post {
                        if (generation != request) return@post
                        playPcm(pcm, request,
                            onStarted = if (index == 0) {
                                { notify(true, mode, "ok", language, language, false, "", request) }
                            } else null,
                            onDone = { cloudPart(context, parts, index + 1, language, config, mode, request) },
                            onError = {
                                systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                            })
                    }
                    return@execute
                }
                val file = File.createTempFile("reading-", if (mode == "deepseek") ".wav" else ".mp3", context.cacheDir)
                file.writeBytes(bytes)
                main.post {
                    if (generation != request) { file.delete(); return@post }
                    try {
                        audioFile = file
                        player = MediaPlayer().apply {
                            setAudioAttributes(attributes); setDataSource(file.absolutePath)
                            setOnCompletionListener { mp ->
                                mp.release()
                                file.delete()
                                if (generation == request) {
                                    markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    cloudPart(context, parts, index + 1, language, config, mode, request)
                                }
                            }
                            setOnErrorListener { mp, what, extra ->
                                mp.release()
                                file.delete()
                                if (generation == request) {
                                    markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    Diag.warn("Speech", "cloud audio error mode=$mode what=$what extra=$extra, fallback to system")
                                    systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                                }
                                true
                            }
                            prepare(); markSpeaking(true); start()
                            if (index == 0) notify(true, mode, "ok", language, language, false, "", request)
                        }
                    } catch (e: Exception) {
                        Diag.warn("Speech", "cloud playback failed mode=$mode ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}, fallback to system")
                        player?.release(); player = null; file.delete(); audioFile = null
                        if (generation == request) {
                            systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                        }
                    }
                }
            } catch (e: Exception) {
                if (generation != request) return@execute
                // 云端合成失败：记诊断并告诉屏幕上的人原因，再回落系统语音（若电视没有系统语音，保留原始云端中文报错）
                val errMsg = (e.message ?: e.javaClass.simpleName).take(120)
                Diag.warn("Speech", "cloud synth failed mode=$mode ${e.javaClass.simpleName}: $errMsg, fallback to system")
                val cloudNotice = if (errMsg.startsWith("DeepSeek") || errMsg.startsWith("云端")) errMsg else "云端朗读失败：$errMsg"
                notify(false, mode, "cloud-unavailable", language, "", true, cloudNotice, request)
                main.post {
                    if (generation != request) return@post
                    systemRead(context, parts.drop(index).joinToString(""), language, request, mode, cloudNotice)
                }
            }
        }
    }

    /** 云端返回体不是音频时，把开头一小段带进错误消息，家长至少能看到接口说了什么 */
    private fun bodySnippet(bytes: ByteArray): String =
        try {
            String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).replace(Regex("\\s+"), " ").trim().take(160)
        } catch (_: Exception) { "" }

    internal fun validatePcmBufferSize(minBytes: Int): Int {
        require(minBytes > 0) { "AudioTrack minBufferSize invalid ($minBytes)" }
        return maxOf(minBytes, 16 * 1024)
    }

    internal fun validateAudioTrackState(state: Int) {
        if (state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack not initialized (state=$state)")
        }
    }

    internal fun validatePcmWrite(written: Int, isCurrent: Boolean): Int {
        if (written > 0) return written
        if (!isCurrent) return 0
        throw IllegalStateException("AudioTrack.write failed (code=$written)")
    }

    /**
     * 直接播放 16 位 PCM WAV（DeepSeek 朗读）。
     *
     * 不经过 MediaPlayer / 系统解码器：华为、EMUI 平板上 MediaPlayer 对这类 WAV 会直接
     * `Prepare failed.: status=0x1`。播放仍在 [worker] 里顺序进行，上一段播完再合成下一段。
     */
    private fun playPcm(
        pcm: WavAudio.Pcm,
        request: Int,
        onStarted: (() -> Unit)? = null,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        worker.execute {
            var audioTrack: AudioTrack? = null
            try {
                if (request != generation) return@execute
                val mask = if (pcm.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(mask).build()
                val minBytes = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                val bufferBytes = validatePcmBufferSize(minBytes)
                val created = AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE)
                try {
                    validateAudioTrackState(created.state)
                } catch (e: Exception) {
                    try { created.release() } catch (_: Exception) { }
                    throw e
                }
                synchronized(trackLock) {
                    if (request != generation) {
                        try { created.release() } catch (_: Exception) { }
                        return@execute
                    }
                    audioTrack = created
                    track = created
                }
                markSpeaking(true)
                created.play()
                if (request == generation) onStarted?.invoke()
                var offset = 0
                while (offset < pcm.data.size && request == generation) {
                    val written = created.write(pcm.data, offset, minOf(16 * 1024, pcm.data.size - offset))
                    val advance = validatePcmWrite(written, request == generation)
                    if (advance == 0) break
                    offset += advance
                }
                // 等缓冲区里的最后一段播完再进入下一句；playbackHeadPosition 是无符号 32 位帧计数
                val durationMs = pcm.frames * 1000L / pcm.sampleRate.coerceAtLeast(1)
                val deadline = System.currentTimeMillis() + durationMs + 3000L
                while (request == generation && (created.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < pcm.frames &&
                    System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                if (request == generation) main.post { if (request == generation) onDone() }
            } catch (e: Exception) {
                val message = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                Diag.warn("Speech", "pcm playback failed $message")
                main.post { if (request == generation) onError(message) }
            } finally {
                if (request == generation) markSpeaking(false)
                synchronized(trackLock) {
                    if (track === audioTrack) track = null
                    try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) { }
                    try { audioTrack?.release() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun request(url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ByteArray {
        val connection = openCloudConnection(url, "语音合成")
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 10000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body) } }
            require(connection.responseCode in 200..299)
            return connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; bytes.write(buffer, 0, size); require(bytes.size() <= 2_000_000) }
                bytes.toByteArray().also { require(it.isNotEmpty()) }
            }
        } finally { connection.disconnect() }
    }
    private fun xml(text: String) = EdgeTts.escapeXml(text)
    private fun azure(config: JSONObject, text: String, language: String): ByteArray {
        val region = config.getString("azureRegion")
        require(region.matches(Regex("[a-z0-9-]+")))
        // SSML 的 xml:lang 不能为空，题目没标语言时按设备系统语言读；其它语系按中文处理
        val tag = language.ifBlank { defaultLanguage() }.let { if (it.startsWith("zh") || it.startsWith("en")) it else "zh-CN" }
        val voice = config.optString("azureVoice").ifBlank { when {
            tag.startsWith("en") -> "en-US-JennyNeural"
            tag == "zh-HK" -> "zh-HK-HiuMaanNeural"
            tag == "zh-TW" -> "zh-TW-HsiaoChenNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        } }
        val ssml = "<speak version='1.0' xml:lang='${xml(tag)}'><voice name='${xml(voice)}'>${xml(text)}</voice></speak>"
        return request("https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
            mapOf("Ocp-Apim-Subscription-Key" to config.getString("azureKey"), "Content-Type" to "application/ssml+xml",
                "X-Microsoft-OutputFormat" to "audio-16khz-32kbitrate-mono-mp3"), ssml.toByteArray())
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    private fun aliyun(config: JSONObject, text: String): ByteArray {
        val identity = config.getString("aliyunAccessKeyId") + config.getString("aliyunSecret")
        val currentToken = synchronized(tokenLock) {
            if (token.isEmpty() || identity != tokenKey || System.currentTimeMillis() / 1000 >= tokenExpires - 120) {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
                val parameters = sortedMapOf("AccessKeyId" to config.getString("aliyunAccessKeyId"), "Action" to "CreateToken", "Format" to "JSON",
                    "Version" to "2019-02-28", "SignatureMethod" to "HMAC-SHA1", "SignatureVersion" to "1.0", "SignatureNonce" to UUID.randomUUID().toString(), "Timestamp" to timestamp)
                val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                val mac = javax.crypto.Mac.getInstance("HmacSHA1")
                mac.init(javax.crypto.spec.SecretKeySpec((config.getString("aliyunSecret") + "&").toByteArray(), "HmacSHA1"))
                val signature = MediaStore.encodeBase64(mac.doFinal(("GET&%2F&" + encode(query)).toByteArray()))
                val result = JSONObject(String(request("https://nls-meta.cn-shanghai.aliyuncs.com/?$query&Signature=${encode(signature)}"), Charsets.UTF_8)).getJSONObject("Token")
                token = result.getString("Id"); tokenExpires = result.getLong("ExpireTime"); tokenKey = identity
            }
            token
        }
        val voice = config.optString("aliyunVoice").ifBlank { "xiaoyun" }
        return request("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts?appkey=${encode(config.getString("aliyunAppKey"))}&token=${encode(currentToken)}&text=${encode(text)}&format=mp3&sample_rate=16000&voice=${encode(voice)}")
    }
}
