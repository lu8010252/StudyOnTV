package com.studyontv.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Locale

open class DeviceBridge(protected val activity: Activity, private val connectionAllowed: Boolean = false, private val web: android.webkit.WebView? = null) {
    @JavascriptInterface
    fun answerInput(voice: Boolean) {
        activity.runOnUiThread { web?.let { InputSupport.open(activity, it, voice) } }
    }

    @JavascriptInterface
    fun answerText(draft: String, prompt: String) {
        activity.runOnUiThread { web?.let { InputSupport.openText(activity, it, draft, prompt) } }
    }

    @JavascriptInterface
    fun apiToken(): String = AdminAuth.deviceToken

    /** 连接页（家长已验证）里的按钮：权限设置 / 修改密码 */
    @JavascriptInterface
    fun openPermissions() {
        if (!connectionAllowed) return
        activity.runOnUiThread { activity.startActivity(Intent(activity, OnboardingActivity::class.java)) }
    }

    /** 网页在 HTTP 局域网环境下拿不到 Clipboard API，交给原生写剪贴板最稳 */
    @JavascriptInterface
    fun copyText(value: String): Boolean {
        if (!connectionAllowed) return false
        return try {
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return false
            manager.setPrimaryClip(android.content.ClipData.newPlainText("题了个题", value))
            true
        } catch (e: Exception) {
            Diag.warn("UI", "copyText failed: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun changePassword() {
        if (!connectionAllowed) return
        activity.runOnUiThread { (activity as? ParentActivity)?.changePassword() }
    }

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject().put("platform", if (isTv(activity)) "android-tv" else "android")
        .put("model", Build.MODEL).put("version", BuildConfig.VERSION_NAME).put("night", TvUi.systemNight(activity)).toString()

    @JavascriptInterface
    fun lanInfo(): String = if (connectionAllowed) DeviceRuntime.connection(activity).toString() else ""

    @JavascriptInterface
    fun openParent() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, ParentActivity::class.java))
        }
    }

    @JavascriptInterface
    fun exitToLauncher() {
        val status = DeviceRuntime.learning.status()
        if (status.getString("reason").isNotEmpty() || (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))) return
        activity.runOnUiThread {
            GateService.hideGates()
            // 答题结束回到「上一个任务」，绝不能主动跳桌面，否则正在播放的视频会被顶掉（#54 / #63）。
            // GateActivity 是独立任务（singleInstance + 独立 taskAffinity），finish 之后系统会自动
            // 把下面的任务（云视听 / 腾讯视频）带回前台；主界面则直接把整个任务压到后台。
            if (activity is GateActivity) activity.finish()
            else activity.moveTaskToBack(true)
        }
    }

    /**
     * 答题解锁后回到解题前的画面。
     *
     * 先收起管控页并把自己的任务退到后台，正常情况下之前那个应用就会回到前台；
     * 如果几百毫秒后前台仍是我们自己或桌面（部分机型会把答题页当成独立任务，
     * finish 之后露出的是桌面），再显式把记下的应用拉起来。
     */
    @JavascriptInterface
    fun resumePrevious() {
        // 不检查 connectionAllowed：和学习页 / GateActivity 共用同一个桥，
        // 管控页里 connectionAllowed 是 false，检查了会导致解锁后无法还原画面。
        // 这个调用只会收起管控页并恢复自己记下的受控应用，不暴露任何设备信息。
        activity.runOnUiThread {
            GateService.hideGates()
            val target = GateService.rememberedActive()
            if (activity is GateActivity) activity.finish() else activity.moveTaskToBack(true)
            if (target == null || target == activity.packageName || Foreground.isLauncher(target)) return@runOnUiThread
            val decor = activity.window?.decorView ?: return@runOnUiThread
            decor.postDelayed({
                val now = try { Foreground.current(activity) } catch (_: Exception) { null }
                if (now == null || now == activity.packageName || Foreground.isLauncher(now)) launchPackage(target)
            }, 700)
        }
    }

    private fun launchPackage(pkg: String): Boolean = launchPackage(activity, pkg)

    @JavascriptInterface
    fun backToQuiz() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (activity is ParentActivity) activity.finish()
        }
    }

    @JavascriptInterface
    fun updateInfo(): String {
        val info = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity)) ?: return ""
        return Updater.toJson(info).put("current", Updater.currentVersion(activity)).toString()
    }

    @JavascriptInterface
    fun installUpdate(url: String, latest: String, notes: String) {
        val cached = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity))
        val payload = if (cached != null && cached.url == url) Updater.toJson(cached) else JSONObject()
            .put("latest", latest).put("notes", notes).put("url", url)
        payload.put("current", BuildConfig.VERSION_NAME)
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("payload", payload.toString()))
        }
    }

    @JavascriptInterface
    fun readAloud(text: String) = SpeechReader.read(activity, text, "", ::speechNotice)

    @JavascriptInterface
    fun readInLanguage(text: String, language: String) = SpeechReader.read(activity, text, language, ::speechNotice)

    @JavascriptInterface
    fun stopReading() = SpeechReader.stop()

    @JavascriptInterface
    fun playAudio(url: String) = SpeechReader.playAudio(activity, url, ::speechNotice)

    /** 最近一次朗读 / 音频播放的结果；失败时 reason/message/degraded 有值（#87 不再静默） */
    @JavascriptInterface
    fun speechStatus(): String = SpeechReader.status().toString()

    /** 失败信息推给网页：页面里装了 window.SOTV_SPEECH_NOTICE 就能显示提示（与 SOTV_INPUT/SOTV_UPDATE 同一约定） */
    private fun speechNotice(payload: JSONObject) {
        val view = web ?: return
        view.post { try { view.evaluateJavascript("window.SOTV_SPEECH_NOTICE&&SOTV_SPEECH_NOTICE($payload)", null) } catch (_: Throwable) { } }
    }

    @JavascriptInterface
    fun watchLesson() { activity.runOnUiThread { VideoLessons.open(activity) } }

    @JavascriptInterface
    fun answerVoice(draft: String, prompt: String, language: String) {
        activity.runOnUiThread { web?.let { InputSupport.openVoice(activity, it, draft, prompt, SpeechReader.recognitionLanguage(language)) } }
    }

    companion object {
        /**
         * 打开系统设置（#130）。很多电视 / 投影 / 平板把原生设置菜单精简掉了，
         * 家长找不到「应用管理 → 权限」这类入口，应用里需要一个能把完整设置调起来的按钮。
         * 逐级回退：系统设置 → 本应用详情页；都打不开时返回 false，由调用方提示。
         */
        fun openSystemSettings(context: Context): Boolean {
            try { GateService.openingSystemPage(parentAuthorized = true) } catch (_: Exception) { }
            val options = listOf(
                Intent(android.provider.Settings.ACTION_SETTINGS),
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            )
            for (intent in options) try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Diag.info("UI", "opened system settings ${intent.action}")
                return true
            } catch (_: Exception) { }
            Diag.warn("UI", "no system settings activity on this device")
            return false
        }
        /** 把记下的受控应用拉回前台；失败只记日志，不影响管控 */
        fun launchPackage(context: android.content.Context, pkg: String): Boolean = try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) false else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                Diag.info("UI", "resumed previous app $pkg")
                true
            }
        } catch (e: Exception) {
            Diag.warn("UI", "resume $pkg failed: ${e.message}")
            false
        }

        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
