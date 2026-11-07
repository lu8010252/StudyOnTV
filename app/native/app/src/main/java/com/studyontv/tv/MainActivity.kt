package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView

open class MainActivity : Activity() {
    protected lateinit var web: WebView
    private var webReleased = false
    private var page = "quiz"
    private var upCount = 0
    private var lastUp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        DeviceRuntime.ensure(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 透明主题的拦截页在安卓 8.0 上不允许设置方向（会直接崩溃），电视本来就是横屏，跳过
        if (DeviceBridge.isTv(this) && this !is GateActivity) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val reason = intent.getStringExtra("reason")
        page = if (this is GateActivity && reason != null && reason != "timeup") "lock?reason=$reason" else "quiz"
        createWeb()
        GateService.start(this)
        if (this !is GateActivity) Updater.check(this) { info ->
            val newer = Updater.newerThanCurrent(this, info) ?: return@check
            runOnUiThread {
                val raw = DeviceBridge(this).updateInfo()
                if (newer.force) startActivity(Intent(this, UpdateActivity::class.java).putExtra("payload", raw))
                else if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_UPDATE&&SOTV_UPDATE($raw)", null)
            }
        }
    }

    private fun createWeb() {
        web = WebShell(this).create(Prefs.localBase(this) + "#/$page")
        web.addJavascriptInterface(DeviceBridge(this, false, web), "SOTVNative")
        setContentView(web)
        web.requestFocus()
        webReleased = false
    }

    override fun onStop() {
        super.onStop()
        // 遥控器填写答案时，输入页盖在我们上面，别把 WebView 销毁，否则填好的字回不来
        if (DeviceBridge.isTv(this) && !InputSupport.active && !InputSupport.recentlyCompleted() && ::web.isInitialized && !webReleased) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
            webReleased = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val reason = intent?.getStringExtra("reason")
        page = if (this is GateActivity && reason != null && reason != "timeup") "lock?reason=$reason" else "quiz"
        if (::web.isInitialized && !webReleased) {
            web.loadUrl(Prefs.localBase(this) + "#/$page")
            web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (webReleased) createWeb()
        if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                val now = android.os.SystemClock.elapsedRealtime()
                upCount = if (now - lastUp < 900) upCount + 1 else 1
                lastUp = now
                if (upCount >= 5) {
                    upCount = 0
                    startActivity(Intent(this, ParentActivity::class.java)); return true
                }
            } else upCount = 0
        }
        if (!DeviceBridge.isTv(this) && event.keyCode == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event)
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select()"
            KeyEvent.KEYCODE_BACK -> "back()"
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::web.isInitialized && !webReleased) web.evaluateJavascript("window.TV&&TV.$action", null)
        return true
    }

    override fun onDestroy() {
        if (::web.isInitialized && !webReleased) web.destroy()
        super.onDestroy()
    }
}
