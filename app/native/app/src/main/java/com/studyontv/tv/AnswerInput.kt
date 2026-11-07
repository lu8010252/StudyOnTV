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

object AnswerInput {
    private var target = WeakReference<WebView>(null)
    private var requestId = ""
    var active = false
        private set

    /** 打开一个原生输入页（手机：手写 / 语音；电视：遥控器 + 系统输入法），结果经 SOTV_INPUT 回传给网页 */
    fun open(context: Context, web: WebView, screen: Class<out Activity>, extras: Intent.() -> Unit = {}) {
        if (active) return
        active = true
        requestId = UUID.randomUUID().toString()
        target = WeakReference(web)
        if (context !is Activity) web.visibility = View.GONE
        try {
            context.startActivity(Intent(context, screen)
                .putExtra("requestId", requestId)
                .apply(extras)
                .apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                })
        } catch (_: RuntimeException) {
            active = false
            requestId = ""
            target.clear()
            web.visibility = View.VISIBLE
            android.widget.Toast.makeText(context, "暂时无法打开输入页面，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun complete(id: String, result: JSONObject?) {
        if (id != requestId) return
        Handler(Looper.getMainLooper()).post {
            val view = target.get()
            view?.visibility = View.VISIBLE
            active = false
            requestId = ""
            target.clear()
            if (result != null) {
                // 延迟 120ms 让 WebView visibility 恢复后 JS 上下文稳定，
                // 避免部分厂商 WebView 在切换过程中 state.round 短暂为 null 导致回调被丢弃
                Handler(Looper.getMainLooper()).postDelayed({
                    view?.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT(" + result.toString() + ")", null)
                }, 120)
            }
        }
    }
}
