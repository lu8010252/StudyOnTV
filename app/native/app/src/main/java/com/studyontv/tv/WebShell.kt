package com.studyontv.tv

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
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
        ensureDataDirectory(context)
        val web = createWebViewSafely(context)
        try { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) } catch (_: Throwable) { }
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
                if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) return true
                return !isAllowed(url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (LocalOnlyGuard.enabled) {
                    val reqUrl = request?.url?.toString()
                    if (!LocalOnlyGuard.isLocalUrl(reqUrl)) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
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
        @Volatile private var dataDirChecked = false

        private fun sanitizeSuffix(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifEmpty { "sub" }

        /**
         * ST-613：Android 9+（API 28+）下，在任何 WebView 初始化之前隔离非主进程数据目录，
         * 避免多进程或旧僵尸进程占锁导致 Using WebView from more than one process 崩溃。
         */
        private fun ensureDataDirectory(context: Context) {
            if (dataDirChecked || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            synchronized(this) {
                if (dataDirChecked) return
                dataDirChecked = true
                try {
                    val proc = Application.getProcessName().orEmpty()
                    if (proc.isNotEmpty() && proc != context.packageName) {
                        WebView.setDataDirectorySuffix(sanitizeSuffix(proc))
                    }
                } catch (_: Throwable) { }
            }
        }

        private fun createWebViewSafely(context: Context): WebView {
            ensureDataDirectory(context)
            return try {
                WebView(context)
            } catch (e: RuntimeException) {
                val msg = e.message.orEmpty()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    msg.contains("Using WebView from more than one process", ignoreCase = true)
                ) {
                    try {
                        WebView.setDataDirectorySuffix("p_${Process.myPid()}")
                        Diag.warn("WebView", "检测到旧进程占用默认 WebView 数据目录，已自动隔离至 p_${Process.myPid()}")
                        WebView(context)
                    } catch (_: Throwable) {
                        throw e
                    }
                } else {
                    throw e
                }
            }
        }

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
