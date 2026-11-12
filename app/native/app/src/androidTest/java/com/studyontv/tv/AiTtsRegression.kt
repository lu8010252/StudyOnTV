package com.studyontv.tv

import android.content.Context
import android.speech.tts.TextToSpeech
import org.json.JSONObject

/**
 * #106 / #87 / #81 回归：AI 密钥不回显、朗读语言回落、朗读/音频失败回报结构。
 *
 * 只做纯逻辑断言：不联网、不伪造 TTS 引擎（真实引擎有没有都只断言“失败被捕获、不抛出”）。
 */
object AiTtsRegression {
    fun run(context: Context) {
        val grading = gradingSettings(context)
        speechSettings(grading)
        grading.close()
        context.deleteDatabase("ai-tts-regression.db")
        languageFallback()
        failureNotice(context)
    }

    /** #106：Key 不回显、空白保留旧 Key、clearKey 生效、AI 模式校验与 http 限制 */
    private fun gradingSettings(context: Context): LocalStore {
        val name = "ai-tts-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        val defaults = GradingService.settings(store)
        check(!defaults.has("apiKey")) { "GET /api/grading 不能回显 API Key" }
        check(!defaults.getBoolean("hasKey") && defaults.getString("mode") == "manual")

        val saved = GradingService.save(store, JSONObject().put("mode", "ai").put("provider", "custom")
            .put("endpoint", "https://api.example.test/v1").put("model", "demo-model")
            .put("apiKey", "sk-secret-value").put("passScore", 70))
        check(!saved.has("apiKey")) { "save 的返回值不能带 Key" }
        check(saved.getBoolean("hasKey"))
        check(saved.getInt("passScore") == 70)
        check(saved.getString("chatUrl") == "https://api.example.test/v1/chat/completions")
        check(!saved.toString().contains("sk-secret-value")) { "任何返回给面板的 JSON 都不能含 Key" }
        check(JSONObject(store.get("grading_settings", "{}")).getString("apiKey") == "sk-secret-value")

        // 表单里密钥留空表示沿用
        val kept = GradingService.save(store, JSONObject().put("model", "demo-model-2").put("apiKey", "   "))
        check(kept.getString("model") == "demo-model-2")
        check(!kept.has("apiKey") && kept.getBoolean("hasKey"))
        check(JSONObject(store.get("grading_settings", "{}")).getString("apiKey") == "sk-secret-value") { "空白 Key 应保留旧值" }

        // clearKey 真的清掉
        val cleared = GradingService.save(store, JSONObject().put("mode", "manual").put("clearKey", true))
        check(!cleared.getBoolean("hasKey") && !cleared.has("apiKey"))
        check(!JSONObject(store.get("grading_settings", "{}")).has("apiKey")) { "clearKey 后存储里也不能留 Key" }
        check(!cleared.toString().contains("sk-secret-value"))

        // AI 模式缺 Key（公网）应拒绝，且不写入
        try {
            GradingService.save(store, JSONObject().put("mode", "ai").put("endpoint", "https://api.example.test/v1").put("model", "m"))
            error("AI 模式没有 Key 时应拒绝")
        } catch (_: IllegalArgumentException) { }
        check(JSONObject(store.get("grading_settings", "{}")).getString("mode") == "manual")

        // 局域网 http（本地模型）允许不用 Key
        val local = GradingService.save(store, JSONObject().put("mode", "ai").put("provider", "ollama")
            .put("endpoint", "http://127.0.0.1:11434/v1").put("model", "qwen2.5:7b").put("passScore", 60))
        check(!local.getBoolean("hasKey")) { "局域网模型不需要 Key" }
        check(local.getString("chatUrl") == "http://127.0.0.1:11434/v1/chat/completions")

        // 公网 http 必须拒绝（Key 不能明文上互联网）
        try {
            GradingService.save(store, JSONObject().put("endpoint", "http://api.example.test/v1"))
            error("公网 http 接口应被拒绝")
        } catch (_: IllegalArgumentException) { }
        // 及格分范围
        try {
            GradingService.save(store, JSONObject().put("passScore", 101))
            error("超范围及格分应被拒绝")
        } catch (_: IllegalArgumentException) { }
        check(GradingService.save(store, JSONObject()).getInt("passScore") == 60)
        return store
    }

    /** 复用 FeatureRegression 已覆盖的 SpeechReader Key 语义，这里只补“不回显 Key”与路由校验 */
    private fun speechSettings(store: LocalStore) {
        SpeechReader.save(store, JSONObject().put("mode", "system").put("azureKey", "azure-secret"))
        val settings = SpeechReader.settings(store)
        check(!settings.has("azureKey") && settings.getBoolean("azureKeySet")) { "speech settings 不能回显 Key" }
        check(!settings.toString().contains("azure-secret"))
        // 留空沿用
        SpeechReader.save(store, JSONObject().put("mode", "azure").put("azureKey", ""))
        check(JSONObject(store.get("speech_settings", "{}")).getString("azureKey") == "azure-secret")
        check(SpeechReader.recognitionLanguage("zh-HK") == "zh-HK") { "粤语题目按粤语识别（#126）" }
        check(SpeechReader.recognitionLanguage("ja-JP") in SpeechReader.whitelist) { "白名单外语言必须降级到白名单内的语言" }
    }

    /** 语言回落：缺失 / 不支持时回落默认语言并标记降级，两种情况都失败时不抛异常 */
    private fun languageFallback() {
        check(SpeechReader.recognitionLanguage("zh-CN") == "zh-CN")
        check(SpeechReader.recognitionLanguage("en-GB") == "en-GB")
        check(SpeechReader.recognitionLanguage("") in SpeechReader.whitelist) { "没标语言的题目按白名单内的语言识别" }
        check(SpeechReader.recognitionLanguage("").let { it == SpeechReader.defaultLanguage() || it == "zh-CN" }) { "系统语言在白名单内时跟随系统（粤语 / 繁体系统不再强转普通话）" }
        check(SpeechReader.recognitionLanguage("ja-JP") != "ja-JP") { "白名单外的语言不能原样静默使用" }

        val missing = SpeechReader.resolveLanguage("ja-JP", "zh-CN") {
            if (it == "ja-JP") TextToSpeech.LANG_MISSING_DATA else TextToSpeech.LANG_AVAILABLE
        }
        check(missing.accepted && missing.degraded) { "LANG_MISSING_DATA 必须回落并标记降级" }
        check(missing.requested == "ja-JP" && missing.language == "zh-CN" && missing.reason == "fallback")

        val unsupported = SpeechReader.resolveLanguage("xx-XX", "en-US") {
            if (it == "xx-XX") TextToSpeech.LANG_NOT_SUPPORTED else TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        check(unsupported.accepted && unsupported.degraded && unsupported.language == "en-US")

        val ok = SpeechReader.resolveLanguage("zh-CN", "en-US") { TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE }
        check(ok.accepted && !ok.degraded && ok.language == "zh-CN" && ok.reason == "available")

        val none = SpeechReader.resolveLanguage("ja-JP", "ko-KR") { TextToSpeech.LANG_NOT_SUPPORTED }
        check(!none.accepted && none.degraded && none.reason == "unavailable") { "两种语言都不可用时必须明确失败" }

        val blank = SpeechReader.resolveLanguage("", "zh-CN") { TextToSpeech.LANG_AVAILABLE }
        check(blank.accepted && !blank.degraded && blank.language == "zh-CN") { "空语言直接用默认语言，不算降级" }

        val unknown = SpeechReader.resolveLanguage("ja-JP", "zh-CN") { null }
        check(unknown.accepted && !unknown.degraded) { "引擎未就绪（无法判断）时不能误报降级" }

        // 引擎被回收 / setLanguage 抛异常：必须被捕获，不把异常抛给朗读流程
        val broken = SpeechReader.resolveLanguage("ja-JP", "zh-CN") { throw IllegalStateException("engine gone") }
        check(broken.accepted && !broken.degraded)

        val default = SpeechReader.defaultLanguage()
        check(default.isNotBlank()) { "设备默认语言必须可读" }
        check(SpeechReader.resolveLanguage("ja-JP") { tag -> if (tag == default) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED }.language == default)
    }

    /** 朗读/播放是 main.post 异步执行的：在主线程之外轮询等待状态变化 */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(150)
        }
        return condition()
    }

    /** 失败信息结构（桥方法 speechStatus / SOTV_SPEECH_NOTICE 都用它）+ 无引擎时不崩 */
    private fun failureNotice(context: Context) {
        val notice = SpeechReader.failureNotice("system", "language-unavailable", "ja-JP", "", true, "这台设备的系统语音不支持 ja-JP")
        check(!notice.getBoolean("ok"))
        check(notice.getString("reason") == "language-unavailable")
        check(notice.getString("requested") == "ja-JP")
        check(notice.getBoolean("degraded") && notice.getString("message").contains("ja-JP"))
        check(notice.getLong("at") > 0)
        for (key in listOf("ok", "mode", "requested", "language", "degraded", "reason", "message", "at")) check(notice.has(key)) { "失败结构缺字段 $key" }
        check(JSONObject(notice.toString()).getString("reason") == "language-unavailable") { "结构必须能被桥直接序列化" }

        val idle = SpeechReader.status()
        check(idle.getBoolean("ok") && idle.getString("reason") == "idle")
        check(idle.has("language") && idle.has("degraded") && idle.has("mode"))

        // 非法音频地址：同步回报（不静默、不抛异常）
        SpeechReader.playAudio(context, "ftp://example.test/a.mp3")
        val failed = SpeechReader.status()
        check(!failed.getBoolean("ok")) { "playAudio 失败必须回报" }
        check(failed.getString("reason") == "unsupported-url")
        check(failed.getString("mode") == "audio")

        // 回调式回报：桥拿到的就是同一个结构
        var pushed: JSONObject? = null
        SpeechReader.playAudio(context, "file:///no/such.mp3") { pushed = it }
        check(pushed != null && !pushed!!.getBoolean("ok")) { "桥回调必须收到失败信息" }
        check(pushed!!.getString("reason") == "unsupported-url")

        // 注：MediaPlayer 的 setOnErrorListener 走的是同一个 audioFailed()/notify(mode=audio)，
        // 结构上面已断言；模拟器上 MediaHTTP 会阻塞十几秒（无真实 mp3 服务），这里不伪造音频服务器。

        // 空文本：no-op，不改变状态也不抛异常
        val audioStatus = SpeechReader.status().toString()
        SpeechReader.read(context, "   ", "zh-CN")
        Thread.sleep(200)
        check(SpeechReader.status().toString() == audioStatus) { "空文本朗读不应该改变状态" }

        // 真实引擎可能存在也可能不存在：只要求“失败被捕获且不抛出”
        try {
            SpeechReader.read(context, "测试朗读", "ja-JP")
            SpeechReader.read(context, "test reading", "en-GB")
            SpeechReader.stop()
        } catch (e: Throwable) {
            error("朗读失败必须被捕获，不能抛到调用方：${e.javaClass.simpleName}: ${e.message}")
        }
        // 状态必须是可读的 JSON（无论引擎在不在）
        check(SpeechReader.status().has("reason"))
        check(SpeechReader.defaultLanguage().isNotBlank())

        // #87：语言一定不支持时必须有明确状态（降级或失败），不能静默
        val before = SpeechReader.status().optLong("at")
        SpeechReader.read(context, "语言回落测试", "xx-XX")
        val changed = waitFor(12000) { SpeechReader.status().optLong("at") != before }
        if (!changed) {
            // 这台设备 12 秒内没把 TTS 引擎起起来：至少不能崩，状态保持可读
            check(SpeechReader.status().has("reason")) { "引擎未就绪时状态也要可读" }
        } else {
            val status = SpeechReader.status()
            val reason = status.getString("reason")
            check(reason != "idle") { "朗读结束后必须有明确状态：$status" }
            if (reason == "engine-unavailable") {
                // 没有 TTS 引擎的机型：失败必须被捕获并写成可读文案
                check(!status.getBoolean("ok") && status.getString("message").isNotEmpty()) { "引擎缺失要报错：$status" }
            } else {
                check(reason in setOf("language-fallback", "language-unavailable", "utterance-error")) {
                    "不支持的 xx-XX 必须标记降级，实际 reason=$reason ($status)"
                }
                check(status.getBoolean("degraded")) { "语言回落必须标 degraded=true：$status" }
                check(status.getString("requested").isNotEmpty()) { "要保留请求的语言便于排查：$status" }
                if (!status.getBoolean("ok")) check(status.getString("message").isNotEmpty()) { "失败必须带可读文案：$status" }
            }
        }
        SpeechReader.stop()
    }
}
