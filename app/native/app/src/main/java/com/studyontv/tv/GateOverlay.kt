package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class GateOverlay(
    private val context: Context,
    private val windowType: Int,
    private val goHome: (() -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var web: WebView? = null
    private var attached = false
    private var currentReason = ""
    private val remote = RemoteKeys { Bridge().openParent() }
    val isShowing: Boolean get() = attached
    var lastError: String = ""
        private set
    /** 失败分类：permission / window / layout / ""（空=没失败）。诊断里直接可读。 */
    var lastErrorKind: String = ""
        private set
    /** addView 成功 != 真的能看到：随后校验窗口尺寸与可见性（部分电视会接受窗口却给 0 尺寸） */
    var verifiedVisible: Boolean = false
        private set
    /** 悬浮页的 document.readyState 是否 complete（黑屏 / 本地服务没起来的诊断依据） */
    var pageReady: Boolean = false
        private set
    private var attachAt = 0L
    private var verifyAttempts = 0

    /**
     * Gate 是否真的在屏幕上。
     *
     * addView 刚返回时还不知道会不会被系统剔除 / 是不是 0 尺寸，所以留一个
     * [GatePresentation.VISIBILITY_GRACE_MS] 的宽限期；超过宽限期还没校验通过就不算弹出来了。
     */
    fun presenting(now: Long = SystemClock.elapsedRealtime()): Boolean =
        GatePresentation.presenting(attached, verifiedVisible, now - attachAt)

    fun windowTypeName(): String = windowTypeName(windowType)

    /**
     * addView 之后的自检：0 尺寸 / isShown=false 都说明窗口没真正上屏。
     * 共试 3 次（1.0s / 1.9s / 2.8s）：大屏第一次创建 WebView 可能慢，
     * 不能因为一次布局没完成就把刚弹出的学习页当成失败。
     */
    private val verifyVisibility = object : Runnable {
        override fun run() {
            if (!attached) return
            val view = web ?: return
            val w = view.width
            val h = view.height
            val shown = try { view.isShown } catch (_: Exception) { false }
            if (w > 0 && h > 0 && shown) {
                verifiedVisible = true
                lastErrorKind = ""
                lastError = ""
                Diag.info("Gate", "overlay visible ${w}x$h type=${windowTypeName()} attempts=${verifyAttempts + 1}")
                return
            }
            verifyAttempts++
            if (verifyAttempts < 3) {
                handler.postDelayed(this, 900)
                return
            }
            verifiedVisible = false
            lastErrorKind = "layout"
            lastError = "window-not-visible ${w}x$h shown=$shown type=${windowTypeName()}"
            Diag.warn("Gate", "overlay attached but not visible: $lastError")
        }
    }

    /** 本地页面没加载完就只会有黑屏：记一条诊断，便于区分「弹窗没弹」和「弹了但是黑的」 */
    private fun probePage() {
        val view = web ?: return
        try {
            view.evaluateJavascript("document.readyState") { value ->
                pageReady = value != null && value.contains("complete")
                if (!pageReady) Diag.warn("Gate", "overlay page not ready state=$value type=${windowTypeName()}")
            }
        } catch (e: Exception) { Diag.warn("Gate", "page probe failed ${e.javaClass.simpleName}: ${e.message ?: ""}") }
    }

    private fun fail(kind: String, detail: String) {
        lastErrorKind = kind
        lastError = detail
    }

    fun show(reason: String): Boolean {
        if (attached) return true
        // 没有悬浮窗权限时直接给出可读的失败原因，不去创建 WebView（创建了也挂不上去）。
        if (windowType == applicationWindowType() && !canDraw(context)) {
            fail("permission", "permission-denied canDraw=false type=${windowTypeName()}")
            Diag.warn("Gate", "overlay refused: $lastError")
            return false
        }
        handler.removeCallbacks(release); releaseScheduled = false
        val reused = web != null
        val page = if (reason in listOf("timeup", "feedback")) "quiz" else "lock?reason=$reason"
        val url = Prefs.localBase(context) + "#/$page"
        val view = web ?: WebShell(context).create("about:blank").also { created ->
            created.addJavascriptInterface(Bridge(), "SOTVNative")
            created.setOnKeyListener { _, _, event -> remote.handle(created, event) }
            // 老电视（斐讯 T1 等）在 WebView 还没 attach 时就开始加载，挂到悬浮层后容易一直是黑屏。
            // 先创建空的 WebView，addView 成功后再 loadUrl；必要时用软件层兜底。
            if (Build.VERSION.SDK_INT < 26) created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web = created
        }
        val needLoad = !reused || reason != currentReason
        currentReason = reason
        val params = WindowManager.LayoutParams(-1, -1, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "StudyOnTV gate"
        return try {
            view.visibility = View.VISIBLE
            manager.addView(view, params)
            attached = true
            attachAt = SystemClock.elapsedRealtime()
            verifiedVisible = false
            pageReady = false
            verifyAttempts = 0
            lastError = ""
            lastErrorKind = ""
            if (needLoad) {
                view.loadUrl(url)
                view.postDelayed({ try { view.invalidate(); view.requestLayout() } catch (_: Exception) { } }, 350)
            } else view.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
            view.requestFocus()
            handler.removeCallbacks(verifyVisibility)
            handler.postDelayed(verifyVisibility, 1000)
            handler.postDelayed({ if (attached) probePage() }, 2500)
            MediaPause.pause(context)
            true
        } catch (e: Exception) {
            fail("window", "window=${windowTypeName()} ${e.javaClass.simpleName}: ${e.message ?: ""}")
            Diag.error("Gate", "addView failed type=${windowTypeName()} canDraw=${canDraw(context)}", e)
            destroy()
            false
        }
    }

    fun suspend() {
        handler.removeCallbacks(verifyVisibility)
        val view = web ?: return
        if (attached) try { manager.removeViewImmediate(view) } catch (_: Exception) { }
        if (attached && !InputSupport.active) MediaPause.release(context)
        if (attached) SpeechReader.stop()
        attached = false
        verifiedVisible = false
        pageReady = false
        attachAt = 0L
    }

    private val release = Runnable { destroy() }

    /**
     * 收起悬浮学习页。WebView 先保留 60 秒再销毁：孩子刚解锁又很快到时、或者家长调整规则时，
     * 再次弹出不必重新加载整页（以前每次都黑屏重载 1–2 秒）。
     */
    private var releaseScheduled = false

    fun hide() {
        if (web == null) return
        suspend()
        // hideAll() 每秒都会调用，只在第一次收起时安排销毁，否则计时会被不断推迟
        if (!releaseScheduled) { releaseScheduled = true; handler.postDelayed(release, 60000) }
    }

    fun destroy() {
        handler.removeCallbacks(release); releaseScheduled = false
        handler.removeCallbacks(verifyVisibility)
        val view = web ?: return
        suspend()
        MediaPause.release(context)
        web = null
        currentReason = ""
        verifiedVisible = false
        pageReady = false
        view.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun apiToken(): String = AdminAuth.deviceToken
        @JavascriptInterface fun deviceInfo(): String = org.json.JSONObject()
            .put("platform", if (DeviceBridge.isTv(context)) "android-tv" else "android").put("night", TvUi.systemNight(context)).toString()
        @JavascriptInterface fun answerInput(voice: Boolean) {
            handler.post {
                web?.let { InputSupport.open(context, it, voice) }
                if (InputSupport.active) suspend()
            }
        }
        @JavascriptInterface fun answerText(draft: String, prompt: String) {
            handler.post {
                web?.let { InputSupport.openText(context, it, draft, prompt) }
                if (InputSupport.active) suspend()
            }
        }
        @JavascriptInterface fun resumePrevious() {
            handler.post {
                val target = GateService.rememberedActive()
                hide()
                GateService.hideGates()
                if (target == null || target == context.packageName || Foreground.isLauncher(target)) return@post
                // 悬浮层收起后系统通常会把原应用带回前台；没回来就主动拉起
                handler.postDelayed({
                    val now = try { Foreground.current(context) } catch (_: Exception) { null }
                    if (now == null || now == context.packageName || Foreground.isLauncher(now))
                        DeviceBridge.launchPackage(context, target)
                }, 700)
            }
        }
        @JavascriptInterface fun backToQuiz() {
            handler.post {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                hide()
            }
        }
        @JavascriptInterface fun readAloud(text: String) = SpeechReader.read(context, text, "zh-CN", ::speechNotice)
        @JavascriptInterface fun readInLanguage(text: String, language: String) = SpeechReader.read(context, text, language, ::speechNotice)
        @JavascriptInterface fun stopReading() = SpeechReader.stop()
        @JavascriptInterface fun playAudio(url: String) = SpeechReader.playAudio(context, url, ::speechNotice)
        /** 最近一次朗读 / 音频播放的结果（失败时 reason/message/degraded 有值，#87 不再静默） */
        @JavascriptInterface fun speechStatus(): String = SpeechReader.status().toString()
        /** 失败信息推给网页：页面里装了 window.SOTV_SPEECH_NOTICE 就能显示提示 */
        private fun speechNotice(payload: org.json.JSONObject) {
            val view = web ?: return
            view.post { try { view.evaluateJavascript("window.SOTV_SPEECH_NOTICE&&SOTV_SPEECH_NOTICE($payload)", null) } catch (_: Throwable) { } }
        }
        @JavascriptInterface fun watchLesson() { handler.post { VideoLessons.open(context);hide() } }
        @JavascriptInterface fun answerVoice(draft: String, prompt: String, language: String) {
            handler.post {
                web?.let { InputSupport.openVoice(context, it, draft, prompt, SpeechReader.recognitionLanguage(language)) }
                if (InputSupport.active) suspend()
            }
        }
        @JavascriptInterface fun updateInfo(): String = ""
        @JavascriptInterface fun openParent() {
            handler.post {
                GateService.hideGates()
                hide()
                context.startActivity(Intent(context, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        @JavascriptInterface fun exitToLauncher() {
            val status = DeviceRuntime.learning.status()
            if (status.getString("reason").isNotEmpty() || (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))) return
            handler.post {
                // 悬浮窗盖在视频软件上面，视频软件一直没离开前台：收起悬浮窗就回到视频了，
                // 不要再发“回桌面”，否则视频软件会被压到桌面后面而被系统回收。
                hide()
            }
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun applicationWindowType(): Int = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        @Suppress("DEPRECATION")
        fun windowTypeName(type: Int): String = when (type) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "TYPE_APPLICATION_OVERLAY"
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            WindowManager.LayoutParams.TYPE_PHONE -> "TYPE_PHONE"
            else -> "TYPE_$type"
        }

        /**
         * 能否绘制悬浮窗。
         *
         * 旧实现只读 `Settings.canDrawOverlays()`，厂商把它映射到别的 AppOps 时永远读到 false，
         * `show()` 因此永不尝试 addView（issue #92 长虹）。现在交给 [OverlayAccess]：
         * 设置 API / AppOps / 一次真实 1×1 addView 探针（结果会话内缓存）。
         */
        fun canDraw(context: Context): Boolean = OverlayAccess.canDraw(context)
    }
}
