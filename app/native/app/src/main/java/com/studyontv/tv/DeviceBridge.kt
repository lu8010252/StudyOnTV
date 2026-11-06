package com.studyontv.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONObject

open class DeviceBridge(protected val activity: Activity, private val connectionAllowed: Boolean = false, private val web: android.webkit.WebView? = null) {
    @JavascriptInterface
    fun answerInput(voice: Boolean) {
        activity.runOnUiThread { web?.let { InputSupport.open(activity, it, voice) } }
    }

    @JavascriptInterface
    fun apiToken(): String = AdminAuth.deviceToken

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject().put("platform", if (isTv(activity)) "android-tv" else "android")
        .put("model", Build.MODEL).put("version", BuildConfig.VERSION_NAME).toString()

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
        // 家长暂停管控期间随时可以回桌面
        if (!status.optBoolean("paused") && (status.getString("reason").isNotEmpty() || status.optLong("remainingSeconds") <= 0)) return
        activity.runOnUiThread {
            GateService.hideGates()
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (activity is GateActivity) activity.finish() else activity.moveTaskToBack(true)
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
        return JSONObject().put("latest", info.latest).put("current", info.current)
            .put("notes", info.notes).put("url", info.url).put("force", info.force).toString()
    }

    @JavascriptInterface
    fun installUpdate(url: String, latest: String, notes: String) {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("payload", JSONObject()
                .put("latest", latest).put("current", BuildConfig.VERSION_NAME).put("notes", notes).put("url", url).toString()))
        }
    }

    companion object {
        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
