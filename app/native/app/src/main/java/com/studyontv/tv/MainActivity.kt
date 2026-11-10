package com.studyontv.tv

import android.app.Activity
import android.app.AlertDialog
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
    private val remote = RemoteKeys { startActivity(Intent(this, ParentActivity::class.java)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程被杀 / 升级后先恢复上次遗留的媒体流静音，避免电视一直无声（issue #116）。
        // 放在配置检查之前：即使还没完成设置，也不该让整机保持静音。
        MediaPause.recover(this)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        DeviceRuntime.ensure(this)
        checkCrashReport()   // 检测上次崩溃标记，有则弹窗
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 透明主题的拦截页在安卓 8.0 上不允许设置方向（会直接崩溃），电视本来就是横屏，跳过
        if (DeviceBridge.isTv(this) && this !is GateActivity) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val reason = intent.getStringExtra("reason")
        page = if (this is GateActivity && reason != null && reason !in listOf("timeup", "feedback")) "lock?reason=$reason" else "quiz"
        createWeb()
        GateService.start(this)
    }

    /** 每次回到学习页都检查一次（6 小时内用缓存，不会频繁联网）；电视上 Activity 很少重建，只在 onCreate 检查会漏掉 */
    private fun checkCrashReport() {
        if (!BuglyInit.configured) { CrashDetector.clear(this); return }   // 没配置自己的 Bugly，不弹窗、不上传
        if (!CrashDetector.hasPending(this)) return
        // 已经同意过的直接初始化并清标记，不再重复弹窗
        if (Prefs.buglyConsent(this)) {
            CrashDetector.clear(this)
            BuglyInit.init(this)
            return
        }
        val summary = CrashDetector.readAndClear(this)
        AlertDialog.Builder(this)
            .setTitle("🚨 上次运行时发生了崩溃")
            .setMessage(
                "是否允许上传崩溃日志帮助修复问题？\n\n" +
                "包含：崩溃调用堆栈、设备型号、Android 版本、应用版本号\n" +
                "不含：题库内容、管理密码、答题记录、局域网 IP\n\n" +
                "日志将上传至腾讯 Bugly 服务器，可随时在「设置 → 崩溃报告」关闭。"
            )
            .setPositiveButton("提交日志") { _, _ ->
                BuglyInit.initWithConsent(this)
                android.widget.Toast.makeText(this, "已提交，感谢反馈！", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("不了") { _, _ -> /* 标记已在 readAndClear 里删除 */ }
            .setCancelable(false)
            .show()
    }

    private fun checkUpdate() {
        if (this is GateActivity) return
        Updater.check(this) { info ->
            val newer = Updater.newerThanCurrent(this, info) ?: return@check
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
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
        MediaPause.release(this)
        SpeechReader.stop()
        // 正在等原生输入页（填空题）回传答案时不能销毁 WebView：
        // TvTextInputActivity / InputSupport 会把结果写回当前 WebView，destroy() 后 JS 上下文消失，
        // 重投全部落在死实例上，表现为「点确定回到题目还是空的」（#64 / #77 / #80 / #84）。
        val awaitingInput = InputSupport.active || AnswerInput.active
        if (DeviceBridge.isTv(this) && ::web.isInitialized && !webReleased && !awaitingInput) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
            webReleased = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val reason = intent?.getStringExtra("reason")
        page = if (this is GateActivity && reason != null && reason !in listOf("timeup", "feedback")) "lock?reason=$reason" else "quiz"
        if (::web.isInitialized && !webReleased) {
            web.loadUrl(Prefs.localBase(this) + "#/$page")
            web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台重试一次遗留静音恢复（非前台时系统可能拦截音量写入）。
        MediaPause.recover(this)
        if (Prefs.onboarded(this)) GateService.ensureRunning(this, "app")
        if (this is GateActivity && !DeviceRuntime.learning.status().optBoolean("free")) MediaPause.pause(this)
        if (webReleased) createWeb()
        checkUpdate()
        if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 手机 / 平板的回车键交给网页输入框
        if (!DeviceBridge.isTv(this) && event.keyCode == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event)
        val view = if (::web.isInitialized && !webReleased) web else null
        return remote.handle(view, event) || super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::web.isInitialized && !webReleased) web.destroy()
        super.onDestroy()
    }
}
