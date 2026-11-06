package com.studyontv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

object AnswerInput {
    private var target = WeakReference<WebView>(null)
    private var requestId = ""
    private var completedAt = 0L
    var active = false
        private set

    fun recentlyCompleted(): Boolean = SystemClock.elapsedRealtime() - completedAt in 1..4000

    fun open(context: Context, web: WebView, voice: Boolean) {
        if (active) return
        active = true
        requestId = UUID.randomUUID().toString()
        target = WeakReference(web)
        if (context !is Activity) web.visibility = View.GONE
        try {
            context.startActivity(Intent(context, AnswerInputActivity::class.java)
                .putExtra("requestId", requestId).putExtra("voice", voice)
                .apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                })
        } catch (_: RuntimeException) {
            active = false
            requestId = ""
            target.clear()
            web.visibility = View.VISIBLE
            android.widget.Toast.makeText(context, "暂时无法打开输入页面，请使用文字作答", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun complete(id: String, result: JSONObject?) {
        if (id != requestId) return
        Handler(Looper.getMainLooper()).post {
            val view = target.get()
            view?.visibility = View.VISIBLE
            if (result != null) view?.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT(" + result.toString() + ")", null)
            completedAt = SystemClock.elapsedRealtime()
            active = false
            requestId = ""
            target.clear()
        }
    }
}
