package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView

class ParentActivity : Activity() {
    private var verified = false
    private var initialSetup = false
    /** Read by the key filter: no password/preferences lookup may happen in that callback. */
    val requiresParentVerification: Boolean get() = !initialSetup && !verified
    private var web: WebView? = null
    private var showingWeb = false
    private val remote = RemoteKeys { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceRuntime.ensure(this)
        initialSetup = !AdminAuth.configured(this)
        if (!initialSetup && AdminAuth.recentlyVerified()) { verified = true; connection() }
        else form(initialSetup)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun changePassword() = form(true, true)

    private fun releaseWeb() {
        showingWeb = false
        val old = web ?: return
        web = null
        (old.parent as? android.view.ViewGroup)?.removeView(old)
        old.destroy()
    }

    private fun form(setup: Boolean, changing: Boolean = false) {
        releaseWeb()
        val tv = DeviceBridge.isTv(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (tv) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
            setPadding(dp(24), dp(if (tv) 28 else 40), dp(24), dp(32))
            setBackgroundColor(TvUi.background(this@ParentActivity))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(column, LinearLayout.LayoutParams(if (tv) dp(520) else -1, -2))
        fun text(value: String, size: Float, color: Int = TvUi.foreground(this)) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(text(if (changing) "修改管理密码" else if (setup) "设置管理密码" else "家长验证", if (tv) 32f else 28f).apply { paint.isFakeBoldText = true })
        column.addView(text(if (setup) "请设置 6–64 位密码。再次连接、打开管理面板和 API 都需要验证。" else "请输入管理密码", 15f, TvUi.muted(this)))
        val secret = EditText(this).apply {
            hint = if (changing) "新密码" else "管理密码"
            // 注意顺序：isSingleLine 内部会重设 inputType，必须放在最前面，
            // 否则密码类型和遮罩都会被清掉，密码就明文显示了（issue #65）
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            imeOptions = passwordImeOptions(setup)
            TvUi.field(this)
        }
        column.addView(secret, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val confirm = EditText(this).apply {
            hint = "再次输入密码"
            isSingleLine = true
            inputType = secret.inputType
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            TvUi.field(this)
        }
        if (setup) column.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val error = text("", 14f, TvUi.palette(this).warn)
        column.addView(error)
        lateinit var submit: Button
        fun go() {
            if (!submit.isEnabled) return
            val value = secret.text.toString()
            if (setup) {
                if (value.length !in 6..64 || value != confirm.text.toString()) {
                    error.text = "密码需要 6–64 位，两次输入须一致"; return
                }
                submit.isEnabled = false
                AdminAuth.setPassword(this, value)
                initialSetup = false
                AdminAuth.markVerified()
                if (!Prefs.onboarded(this)) {
                    startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
                }
                verified = true
                connection()
            } else {
                submit.isEnabled = false
                submit.text = "正在验证…"
                error.text = ""
                Thread {
                    val ok = AdminAuth.verify(this, value)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        submit.isEnabled = true
                        submit.text = "验证并打开连接页"
                        if (ok) { AdminAuth.markVerified(); verified = true; connection() }
                        else { error.text = "密码错误，连续错误 5 次后需等待一分钟"; secret.setText(""); secret.requestFocus() }
                    }
                }.start()
            }
        }
        submit = TvUi.button(this, if (setup) "保存密码" else "验证并打开连接页", true) { go() }
        column.addView(submit, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4) })
        (if (setup) confirm else secret).setOnEditorActionListener { _, actionId, event ->
            if (shouldSubmitOnEditorAction(actionId, event?.keyCode, event?.action)) {
                go()
                true
            } else {
                false
            }
        }
        if (!setup || changing) column.addView(TvUi.button(this, if (changing) "取消" else "返回答题") {
            if (changing) connection() else { startActivity(Intent(this, MainActivity::class.java)); finish() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        if (setup && !changing) column.addView(text(if (tv) "之后在学习页最上方连续短按遥控器 ↑ 5 次，可重新打开此入口。" else "之后在学习页连续点击左上角标题 5 次，可重新打开此入口。", 14f, TvUi.muted(this)))
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(TvUi.background(this@ParentActivity)); addView(content) })
        secret.requestFocus()
    }

    private fun connection() {
        // 权限设置 / 修改密码 / 返回答题都放在网页里，方便遥控器在同一个页面里移动焦点
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        releaseWeb()
        web = WebShell(this).create(Prefs.localBase(this) + "#/pair").also {
            it.addJavascriptInterface(DeviceBridge(this, true, it), "SOTVNative")
        }
        showingWeb = true
        setContentView(web)
        web?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (MainActivity.isPowerKey(event.keyCode)) return super.dispatchKeyEvent(event)
        val view = web
        if (showingWeb && view != null) {
            val url = view.url.orEmpty()
            if (url.contains("/admin")) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && view.canGoBack()) {
                    view.goBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            if (DeviceBridge.isTv(this)) {
                return remote.handle(view, event) || super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // 去系统权限页转一圈回来不用重新输密码；离开超过 1 分钟才重新验证
        if (verified && stoppedAt > 0 && android.os.SystemClock.elapsedRealtime() - stoppedAt > 60000) {
            verified = false
            releaseWeb()
            form(false)
        }
    }

    override fun onDestroy() {
        releaseWeb()
        super.onDestroy()
    }

    companion object {
        internal fun passwordImeOptions(setup: Boolean): Int =
            (if (setup) android.view.inputmethod.EditorInfo.IME_ACTION_NEXT else android.view.inputmethod.EditorInfo.IME_ACTION_DONE) or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI

        internal fun shouldSubmitOnEditorAction(actionId: Int, keyCode: Int?, keyAction: Int?): Boolean {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                return keyAction == null || keyAction == KeyEvent.ACTION_DOWN
            }
            return (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                keyAction == KeyEvent.ACTION_DOWN
        }
    }
}
