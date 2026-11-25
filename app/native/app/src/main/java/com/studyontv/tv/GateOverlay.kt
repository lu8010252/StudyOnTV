package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class GateOverlay(
    private val context: Context,
    private val windowType: Int,
    private val goHome: (() -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: FrameLayout? = null
    private var web: WebView? = null
    private var inlineInputPanel: View? = null
    @Volatile private var attached = false
    private var currentReason = ""
    private val remote = RemoteKeys { Bridge().openParent() }
    val isShowing: Boolean get() = attached
    @Volatile var lastError: String = ""
        private set
    /** 失败分类：permission / window / layout / ""（空=没失败）。诊断里直接可读。 */
    @Volatile var lastErrorKind: String = ""
        private set
    /** addView 成功 != 真的能看到：随后校验窗口尺寸与可见性（部分电视会接受窗口却给 0 尺寸） */
    @Volatile var verifiedVisible: Boolean = false
        private set
    /** 悬浮页的 document.readyState 是否 complete（黑屏 / 本地服务没起来的诊断依据） */
    @Volatile var pageReady: Boolean = false
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
    private val probePageTask = Runnable { if (attached) probePage() }

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
        if (attached) {
            // 已经挂载时管控原因变化（例如答题页中途跳进系统设置变成 settings，或恢复为 timeup）也要切换目标路由
            if (reason != currentReason) {
                currentReason = reason
                dismissInlineInput(notifyCancel = true)
                val targetUrl = Prefs.localBase(context) + "#/" + gateLearningPage(reason)
                web?.let { view ->
                    view.loadUrl(targetUrl)
                    view.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
                }
            }
            return true
        }
        // 没有悬浮窗权限时直接给出可读的失败原因，不去创建 WebView（创建了也挂不上去）。
        if (windowType == applicationWindowType() && !canDraw(context)) {
            fail("permission", "permission-denied canDraw=false type=${windowTypeName()}")
            Diag.warn("Gate", "overlay refused: $lastError")
            return false
        }
        handler.removeCallbacks(release); releaseScheduled = false
        val reused = web != null
        val page = gateLearningPage(reason)
        val url = Prefs.localBase(context) + "#/$page"
        val view = web ?: WebShell(context).create("about:blank").also { created ->
            created.addJavascriptInterface(Bridge(), "SOTVNative")
            created.setOnKeyListener { _, _, event -> remote.handle(created, event) }
            // 老电视（斐讯 T1 等）在 WebView 还没 attach 时就开始加载，挂到悬浮层后容易一直是黑屏。
            // 先创建空的 WebView，addView 成功后再 loadUrl；必要时用软件层兜底。
            if (Build.VERSION.SDK_INT < 26) created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web = created
        }
        val host = container ?: FrameLayout(context).also { created ->
            created.addView(view, FrameLayout.LayoutParams(-1, -1))
            container = created
        }
        if (view.parent == null) host.addView(view, FrameLayout.LayoutParams(-1, -1))
        val needLoad = !reused || reason != currentReason
        currentReason = reason
        val params = WindowManager.LayoutParams(-1, -1, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        params.gravity = Gravity.TOP or Gravity.START
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        params.title = "StudyOnTV gate"
        return try {
            view.visibility = View.VISIBLE
            host.visibility = View.VISIBLE
            manager.addView(host, params)
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
            handler.removeCallbacks(probePageTask)
            handler.postDelayed(verifyVisibility, 1000)
            handler.postDelayed(probePageTask, 2500)
            MediaPause.pause(context)
            true
        } catch (e: Exception) {
            fail("window", "window=${windowTypeName()} ${e.javaClass.simpleName}: ${e.message ?: ""}")
            Diag.error("Gate", "addView failed type=${windowTypeName()} canDraw=${canDraw(context)}", e)
            destroy()
            false
        }
    }

    fun suspend() = suspendInternal(notifyCancel = true)

    private fun suspendInternal(notifyCancel: Boolean) {
        handler.removeCallbacks(verifyVisibility)
        handler.removeCallbacks(probePageTask)
        dismissInlineInput(notifyCancel = notifyCancel)
        val host = container ?: return
        if (attached) try { manager.removeViewImmediate(host) } catch (_: Exception) { }
        if (attached && !InputSupport.active) MediaPause.release(context)
        if (attached) SpeechReader.stop()
        attached = false
        verifiedVisible = false
        pageReady = false
        attachAt = 0L
    }

    /**
     * #172：在悬浮层内部直接弹出原生输入框（不拉起 TvTextInputActivity、不卸载悬浮层），
     * 避免小米电视 S（Android 14）等系统在切 Activity 时触发后台/合规清理导致进程被杀、填空答案丢失。
     */
    private fun showInlineInput(draft: String, prompt: String, passwordMode: Boolean) {
        val host = container ?: return
        val targetWeb = web ?: return
        if (!attached) return
        dismissInlineInput(notifyCancel = false)
        val dp = context.resources.displayMetrics.density
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(TvUi.background(context))
            isClickable = true
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((64 * dp).toInt(), (24 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt())
        }
        panel.addView(TextView(context).apply {
            text = if (passwordMode) "家长验证" else "填写答案"
            textSize = 26f
            setTextColor(TvUi.foreground(context))
        })
        if (prompt.isNotBlank()) {
            panel.addView(TextView(context).apply {
                text = prompt.take(200)
                textSize = 16f
                setTextColor(TvUi.muted(context))
                maxLines = 3
                setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
        }
        lateinit var edit: EditText
        fun commit(accepted: Boolean) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            val text = edit.text?.toString().orEmpty()
            dismissInlineInput(notifyCancel = false)
            if (accepted) {
                val payload = JSONObject().put("text", text).toString()
                targetWeb.post {
                    try { targetWeb.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT($payload)", null) } catch (_: Throwable) { }
                    targetWeb.requestFocus()
                }
            } else {
                targetWeb.post {
                    try { targetWeb.evaluateJavascript("window.SOTV_INPUT_CANCEL&&SOTV_INPUT_CANCEL()", null) } catch (_: Throwable) { }
                    targetWeb.requestFocus()
                }
            }
        }
        edit = EditText(context).apply {
            hint = if (passwordMode) "输入家长管理密码" else "用遥控器或输入法填写答案，完成后选“确定”"
            inputType = if (passwordMode) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = if (passwordMode) 1 else 2
            maxLines = if (passwordMode) 1 else 5
            gravity = Gravity.TOP or Gravity.START
            TvUi.field(this)
            setText(draft)
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { commit(true); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    commit(false); true
                } else false
            }
        }
        panel.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addBtn(label: String, primary: Boolean = false, action: () -> Unit) {
            val btn = TvUi.button(context, label, primary, action)
            btn.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    commit(false); true
                } else false
            }
            row.addView(btn, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = (8 * dp).toInt(); rightMargin = (8 * dp).toInt()
            })
        }
        addBtn(if (passwordMode) "验证解锁" else "确定", true) { commit(true) }
        addBtn("清空") { edit.setText("") }
        addBtn("取消") { commit(false) }
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })
        scroll.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inlineInputPanel = scroll
        edit.requestFocus()
        edit.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dismissInlineInput(notifyCancel: Boolean) {
        val panel = inlineInputPanel ?: return
        inlineInputPanel = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = panel.findFocus()?.windowToken ?: panel.windowToken
        if (token != null) imm?.hideSoftInputFromWindow(token, 0)
        (panel.parent as? ViewGroup)?.removeView(panel)
        if (notifyCancel) {
            web?.post {
                try { web?.evaluateJavascript("window.SOTV_INPUT_CANCEL&&SOTV_INPUT_CANCEL()", null) } catch (_: Throwable) { }
            }
        }
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
        handler.removeCallbacks(probePageTask)
        val view = web ?: return
        suspendInternal(notifyCancel = false)
        MediaPause.release(context)
        (view.parent as? ViewGroup)?.removeView(view)
        container = null
        web = null
        currentReason = ""
        verifiedVisible = false
        pageReady = false
        view.destroy()
    }

    fun showNavigationBlocked(): Boolean = presenting() && LearningNavigation.showBlockedNotice(web)

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
                if (DeviceBridge.isTv(context) && attached) {
                    showInlineInput(draft, prompt, passwordMode = false)
                } else {
                    web?.let { InputSupport.openText(context, it, draft, prompt) }
                    if (InputSupport.active) suspend()
                }
            }
        }
        private fun canExitNow(): Boolean = try {
            DeviceRuntime.learning.status().optBoolean("canExit")
        } catch (_: Throwable) {
            false
        }
        @JavascriptInterface fun resumePrevious() {
            if (!canExitNow()) return
            handler.post {
                if (!canExitNow()) return@post
                val target = GateService.rememberedActive()
                hide()
                GateService.hideGates()
                if (target == null || target == context.packageName || Foreground.isLauncher(target)) return@post
                // 悬浮层收起后系统通常会把原应用带回前台；没回来就主动拉起
                handler.postDelayed({
                    if (!canExitNow()) return@postDelayed
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
        @JavascriptInterface fun readAloud(text: String) = SpeechReader.read(context, text, "", ::speechNotice)
        @JavascriptInterface fun readInLanguage(text: String, language: String) = SpeechReader.read(context, text, language, ::speechNotice)
        /** 听力题：按题指定语速朗读（listenRate），rate 传 -1 表示跟随全局语速 */
        @JavascriptInterface fun readWithRate(text: String, language: String, rate: Double) = SpeechReader.read(context, text, language, ::speechNotice, rate)
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
        @JavascriptInterface fun askParentPassword() {
            handler.post {
                val allowed = try { DeviceRuntime.store.config().optBoolean("allowQuizUnlock", false) } catch (_: Throwable) { false }
                if (!allowed) return@post
                val prompt = "输入家长密码，验证后可临时解锁 ${DeviceBridge.QUIZ_UNLOCK_MINUTES} 分钟"
                if (DeviceBridge.isTv(context) && attached) {
                    showInlineInput("", prompt, passwordMode = true)
                } else {
                    web?.let { InputSupport.openPassword(context, it, prompt) }
                    if (InputSupport.active) suspend()
                }
            }
        }
        @JavascriptInterface fun quizUnlock(value: String): String {
            val allowed = try { DeviceRuntime.store.config().optBoolean("allowQuizUnlock", false) } catch (_: Throwable) { false }
            if (!allowed) return org.json.JSONObject().put("ok", false).put("error", "disabled").toString()
            if (!AdminAuth.verify(context, value)) return org.json.JSONObject().put("ok", false).put("error", "wrong").toString()
            return try {
                AdminAuth.markVerified()
                val status = DeviceRuntime.learning.remote("unlock", DeviceBridge.QUIZ_UNLOCK_MINUTES, "quiz")
                Diag.info("Auth", "家长在悬浮管控层临时解锁 ${DeviceBridge.QUIZ_UNLOCK_MINUTES} 分钟")
                GateService.hideGates()
                GateService.refreshNow()
                org.json.JSONObject().put("ok", true).put("status", status).toString()
            } catch (e: Exception) {
                Diag.warn("Auth", "overlay quiz unlock failed: ${e.message}")
                org.json.JSONObject().put("ok", false).put("error", "failed").toString()
            }
        }
        @JavascriptInterface fun askSettingsPassword() {
            handler.post {
                val prompt = "输入管理密码，验证后可临时进入系统设置 2 分钟"
                if (DeviceBridge.isTv(context) && attached) {
                    showInlineInput("", prompt, passwordMode = true)
                } else {
                    web?.let { InputSupport.openPassword(context, it, prompt) }
                    if (InputSupport.active) suspend()
                }
            }
        }
        @JavascriptInterface fun settingsUnlock(value: String): String {
            if (!AdminAuth.verify(context, value)) return org.json.JSONObject().put("ok", false).put("error", "wrong").toString()
            SystemSettingsGuard.grant()
            Diag.info("Auth", "家长在悬浮拦截层验证密码，临时放行系统设置 2 分钟")
            handler.post { hide() }
            GateService.hideGates()
            GateService.refreshNow()
            return org.json.JSONObject().put("ok", true).toString()
        }
        @JavascriptInterface fun navigationBlocked(): Boolean {
            LearningNavigation.notifyBlocked(context)
            return true
        }
        @JavascriptInterface fun exitToLauncher() {
            if (!canExitNow()) return
            handler.post {
                // 悬浮窗盖在视频软件上面，视频软件一直没离开前台：收起悬浮窗就回到视频了，
                // 不要再发“回桌面”，否则视频软件会被压到桌面后面而被系统回收。
                if (!canExitNow()) return@post
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
