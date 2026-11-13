package com.studyontv.tv

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL








class WebShell(private val context: Context) {

    var onBackPressed: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun create(url: String): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val web = WebView(context)
        web.setBackgroundColor(TvUi.background(context))
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        
        
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        // #141：电视把系统字体调大后，WebView 会把学习页的字再放大一遍，题干被顶出屏幕；
        // 页面自己按 0.5208vw 和「学习页字号」缩放，这里固定 100%，只认家长设置的值
        s.textZoom = 100
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.userAgentString = s.userAgentString + " StudyOnTV/0.1 (AndroidTV)"

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return !isAllowed(url)
            }
        }
        web.webChromeClient = WebChromeClient()
        web.loadUrl(url)
        return web
    }

    
    fun warmUp(url: String) {
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.connect()
                c.inputStream?.close()
                c.disconnect()
            } catch (t: Throwable) {
                
            }
        }.apply { isDaemon = true }.start()
    }

    fun dispatchKey(web: WebView, keyCode: Int, event: KeyEvent?): Boolean {
        val longPress = event?.repeatCount ?: 0
        val js = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "window.TV&&TV.move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "window.TV&&TV.move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "window.TV&&TV.move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "window.TV&&TV.move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                "window.TV&&TV.select()"
            KeyEvent.KEYCODE_BACK -> {
                if (longPress > 2) return false
                web.evaluateJavascript("window.TV&&TV.back()", null)
                return true
            }
            else -> return false
        }
        web.evaluateJavascript(js, null)
        return true
    }

    companion object {
        
        fun isAllowed(url: String?): Boolean {
            if (url == null || url == "about:blank") return true
            val base = PrefsHolder.baseUrl ?: return false
            return try {
                val target = URL(url)
                val source = URL(base)
                target.protocol == source.protocol && target.host == source.host && target.port == source.port
            } catch (t: Throwable) {
                false
            }
        }
    }

    
    object PrefsHolder {
        @Volatile
        var baseUrl: String? = null
    }
}
