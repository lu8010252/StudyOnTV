package com.studyontv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * TV 版答题输入：遥控器没法直接在网页的输入框里打字，
 * 这里弹出一个原生输入页（用投影自带的电视输入法），确定后把文字回填到网页。
 */
object AnswerInput {
    private var target = WeakReference<WebView>(null)
    private var requestId = ""
    var active = false
        private set

    fun open(context: Context, web: WebView, voice: Boolean) {
        if (active) return
        active = true
        requestId = UUID.randomUUID().toString()
        target = WeakReference(web)
        if (context !is Activity) web.visibility = View.GONE
        try {
            context.startActivity(Intent(context, AnswerInputActivity::class.java)
                .putExtra("requestId", requestId)
                .apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                })
        } catch (_: RuntimeException) {
            active = false
            requestId = ""
            target.clear()
            web.visibility = View.VISIBLE
        }
    }

    fun complete(id: String, result: JSONObject?) {
        if (id != requestId) return
        Handler(Looper.getMainLooper()).post {
            val view = target.get()
            view?.visibility = View.VISIBLE
            if (result != null) view?.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT(" + result.toString() + ")", null)
            active = false
            requestId = ""
            target.clear()
        }
    }
}
