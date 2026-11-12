package com.studyontv.tv

import android.content.Context
import android.media.AudioAttributes
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

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var system: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var token = ""
    private var tokenExpires = 0L
    private var tokenKey = ""
    /** 最近一次朗读 / 播放的结果，供 speechStatus 桥与回归测试断言 */
    @Volatile private var last: JSONObject? = null
    @Volatile private var speaking = false
    /** 桥的回报回调（只在一次朗读 / 播放期间持有，stopNow 释放，避免长期抓住 WebView） */
    private var notice: ((JSONObject) -> Unit)? = null
    private var cachedContext: Context? = null
    private var currentText = ""
    private var currentLanguage = ""
    private var lastUtteranceId = ""
    private var retriedDefault = false
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun settings(store: LocalStore): JSONObject = JSONObject(store.get("speech_settings", "{}"))
        .apply {
            if (!has("mode")) put("mode", "system")
            if (!has("readLanguage")) put("readLanguage", "")
            for (key in listOf("azureKey", "aliyunSecret")) { put(key + "Set", optString(key).isNotEmpty()); remove(key) }
        }
    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val next = JSONObject(store.get("speech_settings", "{}"))
        val mode = input.optString("mode", "system")
        require(mode in listOf("auto", "system", "azure", "aliyun")) { "未知朗读通路" }
        next.put("mode", mode).put("autoRead", input.optBoolean("autoRead"))
        // #126：设备级朗读语言。空 = 跟随题目设置，"system" = 跟随设备系统语言（粤语系统读粤语）
        if (input.has("readLanguage")) {
            val readLanguage = input.optString("readLanguage").trim()
            require(readLanguage in OVERRIDES) { "不支持的朗读语言" }
            next.put("readLanguage", readLanguage)
        }
        for (key in listOf("azureRegion", "azureVoice", "aliyunAppKey", "aliyunAccessKeyId", "aliyunVoice")) if (input.has(key)) {
            require(input.getString(key).length <= 200); next.put(key, input.getString(key).trim())
        }
        require(next.optString("azureRegion").matches(Regex("[a-z0-9-]*"))) { "Azure 区域格式错误" }
        for (key in listOf("azureKey", "aliyunSecret")) if (input.has(key) && input.optString(key).isNotBlank()) {
            require(input.getString(key).length <= 500); next.put(key, input.getString(key).trim())
        }
        if (input.optBoolean("clearKeys")) { next.remove("azureKey"); next.remove("aliyunSecret") }
        store.set("speech_settings", next.toString())
        token = ""; tokenExpires = 0
        return settings(store)
    }

    /**
     * 朗读。`language` 是题目语言；引擎不支持时先回落设备默认语言，再不行回报失败。
     * `notice` 由桥传入，失败时回调（网页里有 window.SOTV_SPEECH_NOTICE 就能显示提示）。
     */
    fun read(context: Context, text: String, language: String = "", notice: ((JSONObject) -> Unit)? = null) {
        if (text.isBlank()) return
        val app = context.applicationContext
        main.post {
            stopNow(); val request = generation
            this.notice = notice
            cachedContext = app
            val clean = text.take(10000)
            // DeviceRuntime 未就绪（早期初始化 / instrumentation）时按 system 通路，不能因此崩掉进程
            val config = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Throwable) { JSONObject() }
            val mode = config.optString("mode", "system")
            val chosen = if (mode == "auto") when {
                config.optString("azureKey").isNotEmpty() -> "azure"
                config.optString("aliyunSecret").isNotEmpty() -> "aliyun"
                else -> "system"
            } else mode
            // #126：家长可以在朗读设置里指定设备朗读语言；留空就跟随每道题的语言标注
            val spoken = when (val override = config.optString("readLanguage").trim()) {
                "system" -> ""          // 跟随设备系统语言
                "" -> language          // 跟随题目
                else -> override
            }
            if (chosen == "system") systemRead(app, clean, spoken, request)
            else cloudPart(app, clean.chunked(150), 0, spoken, config, chosen, request)
        }
    }

    /** 题目音频。老电视的 https / mp3 兼容性差时不再“点了没反应”：记录诊断 + Toast + 回报给前端 */
    fun playAudio(context: Context, url: String, notice: ((JSONObject) -> Unit)? = null) {
        val app = context.applicationContext
        // 地址本身不合法：不排队到主线程，直接同步回报（回归测试可断言 status()）
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            recordFailure("audio", "unsupported-url", "", "", false, "音频地址不正确，无法播放", notice)
            return
        }
        main.post {
            stopNow(); val request = generation
            this.notice = notice
            cachedContext = app
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes); setDataSource(url)
                    setOnPreparedListener { if (request == generation) start() else release() }
                    setOnCompletionListener {
                        release(); if (request == generation) { player = null; notify(true, "audio", "done", "", "", false, "") }
                    }
                    setOnErrorListener { _, what, extra ->
                        release(); if (request == generation) player = null
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

    /** 当前/最近一次朗读结果：{ok,mode,requested,language,degraded,reason,message,at}，桥方法 speechStatus 直接返回它 */
    fun status(): JSONObject = last?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("ok", true).put("mode", "").put("requested", "").put("language", "").put("degraded", false)
        .put("reason", "idle").put("message", "").put("at", 0L)

    fun speaking(): Boolean = speaking

    /** 失败信息结构（纯函数，回归测试可直接断言结构；不含题目 / 答案明文） */
    fun failureNotice(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String): JSONObject =
        JSONObject().put("ok", false).put("mode", mode).put("requested", requested).put("language", language)
            .put("degraded", degraded).put("reason", reason).put("message", message).put("at", System.currentTimeMillis())

    /** 设备默认语言（TTS 语言缺失时的回落目标） */
    fun defaultLanguage(): String = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "zh-CN"

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

    private fun notify(ok: Boolean, mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String) {
        val payload = failureNotice(mode, reason, requested, language, degraded, message).put("ok", ok)
        last = payload
        // 成功只更新状态；只有失败才写诊断 + 推给网页（避免正常朗读也被当成提示）
        if (ok) return
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        val callback = notice ?: return
        val copy = JSONObject(payload.toString())
        main.post { try { callback(copy) } catch (_: Exception) { } }
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
        speaking = false
        notify(false, "audio", reason, "", "", false, message)
        try { Toast.makeText(context, "音频无法播放，请检查网络或改用朗读", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    private fun stopNow() {
        generation++; pending = null; system?.stop()
        player?.release(); player = null; audioFile?.delete(); audioFile = null
        speaking = false; currentText = ""; currentLanguage = ""; lastUtteranceId = ""; retriedDefault = false
        notice = null
    }

    /** UtteranceProgressListener：朗读结束清理状态，合成失败记诊断并用默认语言补读一次 */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { speaking = true }
        override fun onDone(utteranceId: String?) {
            if (utteranceId != lastUtteranceId) return
            speaking = false; currentText = ""; currentLanguage = ""; lastUtteranceId = ""
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) {
            speaking = false
            Diag.warn("Speech", "utterance error id=$utteranceId code=$errorCode language=${currentLanguage.ifEmpty { "-" }}")
            val request = utteranceId?.split("-")?.getOrNull(1)?.toIntOrNull() ?: return
            if (request != generation) return
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

    private fun systemRead(context: Context, text: String, language: String, request: Int, mode: String = "system") {
        cachedContext = context.applicationContext
        val speak = { if (request == generation) speakSystem(context, text, language, request, mode) }
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

    private fun speakSystem(context: Context, text: String, language: String, request: Int, mode: String) {
        val engine = system
        if (engine == null) {
            notify(false, mode, "engine-unavailable", language, "", false, "系统语音不可用，无法朗读")
            return
        }
        val plan = try {
            resolveLanguage(language) { tag -> try { engine.setLanguage(Locale.forLanguageTag(tag)) } catch (_: Exception) { null } }
        } catch (e: Exception) {
            Diag.warn("Speech", "setLanguage failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            notify(false, mode, "language-check-failed", language, "", false, "系统语音不可用（${e.javaClass.simpleName}）")
            return
        }
        if (plan.reason == "fallback") Diag.info("Speech", "language ${plan.requested} unavailable, fell back to ${plan.language}")
        if (!plan.accepted) {
            notify(false, mode, "language-unavailable", plan.requested, plan.language, true,
                "这台设备的系统语音不支持 ${plan.requested}，装个语音包或换一种语言再试")
            return
        }
        try { engine.setAudioAttributes(attributes) } catch (_: Exception) { }
        currentText = text; currentLanguage = plan.language; retriedDefault = plan.degraded
        val parts = text.chunked(minOf(3000, TextToSpeech.getMaxSpeechInputLength()))
        lastUtteranceId = "sotv-$request-${parts.size - 1}"
        parts.forEachIndexed { index, part ->
            engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "sotv-$request-$index")
        }
        speaking = true
        notify(true, mode, if (plan.degraded) "language-fallback" else "ok", plan.requested.ifEmpty { plan.language }, plan.language, plan.degraded, "")
    }

    private fun cloudPart(context: Context, parts: List<String>, index: Int, language: String, config: JSONObject, mode: String, request: Int) {
        if (index >= parts.size || generation != request) return
        worker.execute {
            if (generation != request) return@execute
            try {
                val bytes = if (mode == "azure") azure(config, parts[index], language) else aliyun(config, parts[index])
                val file = File.createTempFile("reading-", ".mp3", context.cacheDir)
                file.writeBytes(bytes)
                main.post {
                    if (generation != request) { file.delete(); return@post }
                    try {
                        audioFile = file
                        player = MediaPlayer().apply {
                            setAudioAttributes(attributes); setDataSource(file.absolutePath)
                            setOnCompletionListener {
                                release(); player = null; file.delete(); audioFile = null
                                cloudPart(context, parts, index + 1, language, config, mode, request)
                            }
                            setOnErrorListener { _, what, extra ->
                                release(); player = null; file.delete(); audioFile = null
                                Diag.warn("Speech", "cloud audio error mode=$mode what=$what extra=$extra, fallback to system")
                                systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                                true
                            }
                            prepare(); start()
                        }
                    } catch (e: Exception) {
                        Diag.warn("Speech", "cloud playback failed mode=$mode ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}, fallback to system")
                        player?.release(); player = null; file.delete(); audioFile = null
                        systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                    }
                }
            } catch (e: Exception) {
                // 显式选择的云端通路失败一律静默回落系统语音（只记诊断，不打断答题）
                Diag.warn("Speech", "cloud synth failed mode=$mode ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}, fallback to system")
                main.post { systemRead(context, parts.drop(index).joinToString(""), language, request, mode) }
            }
        }
    }

    private fun request(url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
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
    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
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
        if (token.isEmpty() || identity != tokenKey || System.currentTimeMillis() / 1000 >= tokenExpires - 120) {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val parameters = sortedMapOf("AccessKeyId" to config.getString("aliyunAccessKeyId"), "Action" to "CreateToken", "Format" to "JSON",
                "Version" to "2019-02-28", "SignatureMethod" to "HMAC-SHA1", "SignatureVersion" to "1.0", "SignatureNonce" to UUID.randomUUID().toString(), "Timestamp" to timestamp)
            val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec((config.getString("aliyunSecret") + "&").toByteArray(), "HmacSHA1"))
            val signature = android.util.Base64.encodeToString(mac.doFinal(("GET&%2F&" + encode(query)).toByteArray()), android.util.Base64.NO_WRAP)
            val result = JSONObject(String(request("https://nls-meta.cn-shanghai.aliyuncs.com/?$query&Signature=${encode(signature)}"), Charsets.UTF_8)).getJSONObject("Token")
            token = result.getString("Id"); tokenExpires = result.getLong("ExpireTime"); tokenKey = identity
        }
        val voice = config.optString("aliyunVoice").ifBlank { "xiaoyun" }
        return request("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts?appkey=${encode(config.getString("aliyunAppKey"))}&token=${encode(token)}&text=${encode(text)}&format=mp3&sample_rate=16000&voice=${encode(voice)}")
    }
}
