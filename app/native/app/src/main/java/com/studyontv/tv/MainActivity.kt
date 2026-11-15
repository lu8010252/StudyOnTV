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

    /**
     * 检测上次崩溃标记，弹窗征求用户同意后自动上传。
     * 已同意且配置了 endpoint 的用户不再弹窗——DeviceRuntime.ensure() 里已自动触发上传。
     */
    private fun checkCrashReport() {
        if (!BuglyInit.configured) { CrashDetector.clear(this); return }   // 没配置自己的 Bugly，不弹窗、不上传
        if (!CrashDetector.hasPending(this)) return

        // 已同意：DeviceRuntime.ensure() 已自动触发上传，无需再操作
        if (Prefs.crashUploadConsent(this)) return

        // 未同意：弹窗征求同意
        val summary = CrashDetector.readAndClear(this)
        AlertDialog.Builder(this)
            .setTitle("🚨 上次运行时发生了崩溃")
            .setMessage(
                "是否允许上传崩溃日志帮助修复问题？\n\n" +
                "包含：崩溃调用堆栈、设备型号、Android 版本、应用版本号\n" +
                "不含：题库内容、管理密码、答题记录、局域网 IP\n\n" +
                "日志将上传至开发者自建服务器，可随时在「设置 → 崩溃报告」关闭。"
            )
            .setPositiveButton("同意并上传") { _, _ ->
                Prefs.setCrashUploadConsent(this, true)
                Prefs.setCrashUploadConsentAsked(this, true)
                val endpoint = Prefs.crashEndpoint(this)
                val token = Prefs.crashToken(this)
                // 写回标记：endpoint 已配置时由上传线程读取，未配置时留到下次启动补传
                CrashDetector.writePending(this, summary)
                if (endpoint.isNotBlank()) {
                    Diag.uploadPendingCrash(this, endpoint, token)
                    android.widget.Toast.makeText(this, "正在上传崩溃日志，感谢反馈！", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "已记录同意，请在设置页填写上传地址后生效。", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("不了") { _, _ ->
                Prefs.setCrashUploadConsentAsked(this, true)
            }
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
        // (二改) 电视上手动打开程序再去看视频时，已停止的界面窗口还占着约 64MB 图形内存（WebView 销毁了也不还），
        // 极米只有 1.7G，打开 bilibili 时先被内存清理杀掉的就是它。直接把主界面关掉，后台只剩 15MB 左右的服务。
        // 拦截页（GateActivity）不关：它要保持在视频软件上面；等原生输入页回传答案时也不关。
        if (DeviceBridge.isTv(this) && this !is GateActivity && !awaitingInput && !isChangingConfigurations) finish()
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

    fun showNavigationBlocked(): Boolean =
        if (::web.isInitialized && !webReleased) LearningNavigation.showBlockedNotice(web) else false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // #143：创维 A5D Pro / 酷开开启「强制使用全屏学习页」后，遥控器关机键被全屏页吞掉。
        // 电源 / 休眠键任何页面都不处理，原样交还系统；全屏学习页自己先退出，给固件让出正常关机路径。
        if (isPowerKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && this is GateActivity) {
                Diag.info("Gate", "遥控器电源键：退出全屏学习页，交给系统处理")
                finish()
            }
            return super.dispatchKeyEvent(event)
        }
        // 手机 / 平板的回车键交给网页输入框
        if (!DeviceBridge.isTv(this) && event.keyCode == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event)
        val view = if (::web.isInitialized && !webReleased) web else null
        return remote.handle(view, event) || super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::web.isInitialized && !webReleased) web.destroy()
        super.onDestroy()
    }

    companion object {
        /** 电源 / 休眠 / 待机键：任何页面都不能拦截，否则电视关不了机（#143） */
        fun isPowerKey(code: Int): Boolean = code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP ||
            code == KeyEvent.KEYCODE_SOFT_SLEEP
    }
}
