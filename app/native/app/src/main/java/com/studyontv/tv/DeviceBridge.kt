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
            // 答题结束后回到之前的应用（如腾讯视频）。
            // GateActivity 是单独的任务：只 finish，系统会自动回到它下面的视频软件。
            // 不再发“回桌面”的 HOME 意图——那样视频软件会被压到桌面后面，内存紧张时就被系统回收了。
            if (activity is GateActivity) {
                activity.finish()
                activity.overridePendingTransition(0, 0)
            } else {
                // MainActivity / 普通场景：退到后台，之前的视频 App 自动回到前台
                activity.moveTaskToBack(true)
            }
        }
    }

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

    private var tts: TextToSpeech? = null

    @JavascriptInterface
    fun readAloud(text: String) {
        if (text.isBlank()) return
        activity.runOnUiThread {
            if (tts == null) {
                tts = TextToSpeech(activity) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.CHINESE
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
                    }
                }
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
            }
        }
    }

    @JavascriptInterface
    fun stopReading() {
        tts?.stop()
    }

    companion object {
        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
