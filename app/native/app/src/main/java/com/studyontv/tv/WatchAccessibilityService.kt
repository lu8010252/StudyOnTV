package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class WatchAccessibilityService : AccessibilityService() {
    private var gate: GateOverlay? = null
    private val navigationKeys = NavigationKeyFilter { LearningNavigation.shouldBlockHome() }
    /** 当前的按键过滤状态；只在需要拦 Home / 任务键时打开（#162） */
    private var keyFilterOn = false
    private var keyFilterApplied = false

    /**
     * 打开 / 关闭系统级按键过滤。
     *
     * 常驻 `FLAG_REQUEST_FILTER_KEY_EVENTS` 会让所有按键（包括音量键）改走无障碍通路：
     * 海信 VIDAA 上厂商音量面板因此失效，SystemUI 退回 AOSP 白卡片（#162）。
     * 这里仅在「学习页正在拦 Home」时申请，其它时候保持关闭。
     */
    fun setKeyFilter(enabled: Boolean) {
        if (keyFilterApplied && enabled == keyFilterOn) return
        val info = try { serviceInfo } catch (_: Exception) { null } ?: return
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = if (enabled) info.flags or flag else info.flags and flag.inv()
        try {
            setServiceInfo(info)
            keyFilterOn = enabled
            keyFilterApplied = true
            Diag.info("A11y", if (enabled) "按键过滤已打开（学习页需要拦 Home / 任务键）" else "按键过滤已关闭")
        } catch (e: Exception) {
            Diag.warn("A11y", "切换按键过滤失败 ${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        val consumed = navigationKeys.handle(event)
        if (consumed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME -> blockedHomePresses++
                KeyEvent.KEYCODE_APP_SWITCH -> blockedAppSwitchPresses++
            }
            LearningNavigation.notifyBlocked(DeviceRuntime.appContext)
        }
        return consumed
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && (pkg.contains("inputmethod") || pkg.contains("keyboard"))) return
            // 系统栏、音量条、投影自带的悬浮菜单都会弹出临时窗口，真正的前台应用并没有变，不能当成切换
            if (pkg == "com.android.systemui") return
            // countdown / gate 悬浮窗不代表应用 Activity 在前台（#96）。
            val ownOverlay = pkg == packageName && !Foreground.hasResumedActivity()
            if (!ownOverlay && (!GateService.hasGate() || pkg != packageName)) {
                val cls = event.className?.toString()?.takeIf { it.contains("Activity") }
                Foreground.update(pkg, cls)
            }
            // 无障碍服务由系统常驻，顺便充当看门狗：管理服务被系统清理后在下一次切换应用时拉起
            if (!GateService.running) GateService.ensureRunning(this, "accessibility")
            if (!ownOverlay) GateService.onForegroundChanged(this, pkg)
        }
    }
    override fun onInterrupt() = Unit

    /**
     * 被解绑（用户/系统关掉无障碍、或服务自身出问题被系统摘掉）时先走这里，
     * 留下一条记录，#120 这类「无障碍自己关闭」才能分清是解绑还是进程被杀（进程被杀不会走到任何回调）。
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        navigationKeys.reset()
        Diag.warn("A11y", "service unbound action=${intent?.action}")
        return super.onUnbind(intent)
    }
    override fun onServiceConnected() {
        instance = this
        connected = true
        GateService.note("无障碍服务已连接")
        Diag.info("A11y", "service connected")
        val overlayType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        gate = GateOverlay(this, overlayType) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        GateService.ensureRunning(this, "accessibility")
        // 连接时按当前状态定一次：升级前 XML 里写死的按键过滤要在这里清掉（#162）
        keyFilterApplied = false
        setKeyFilter(LearningNavigation.shouldBlockHome())
    }
    override fun onDestroy() {
        navigationKeys.reset()
        Diag.info("A11y", "service destroyed connected=$connected")
        gate?.destroy(); gate = null
        instance = null
        connected = false
        GateService.note("无障碍服务已断开")
        Foreground.clearExternal()
        super.onDestroy()
    }

    companion object {
        @Volatile var blockedHomePresses = 0L
            private set
        @Volatile var blockedAppSwitchPresses = 0L
            private set
        @Volatile private var instance: WatchAccessibilityService? = null
        @Volatile var connected = false
            private set
        fun enabled(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (_: Exception) { false }
        fun hasGate(): Boolean = instance?.gate?.isShowing == true
        fun presentedGate(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean = instance?.gate?.presenting(now) == true
        fun lastGateError(): String = instance?.gate?.let { g ->
            g.lastError.ifEmpty { "accessibility gate attached but not visible kind=${g.lastErrorKind}" }
        } ?: ""
        fun showNavigationBlocked(): Boolean = instance?.gate?.showNavigationBlocked() == true
        /** 只在需要拦 Home / 任务键时申请按键过滤（#162），由 GateService 每个 tick 同步 */
        fun setHomeFilter(enabled: Boolean) { instance?.setKeyFilter(enabled) }
        fun keyFilterRequested(): Boolean = connected && ((instance?.serviceInfo?.flags ?: 0) and
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        fun showGate(reason: String): Boolean = instance?.gate?.show(reason) == true
        fun hideGate() { instance?.gate?.hide() }
        fun suspendGate() { instance?.gate?.suspend() }
    }
}
