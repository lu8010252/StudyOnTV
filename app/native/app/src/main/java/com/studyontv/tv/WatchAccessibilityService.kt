package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class WatchAccessibilityService : AccessibilityService() {
    private var gate: GateOverlay? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && (pkg.contains("inputmethod") || pkg.contains("keyboard"))) return
            // 系统栏、音量条、投影自带的悬浮菜单都会弹出临时窗口，真正的前台应用并没有变，不能当成切换
            if (pkg == "com.android.systemui") return
            if (!GateService.hasGate() || pkg != packageName) Foreground.update(pkg)
            GateService.onForegroundChanged(this, pkg)
        }
    }
    override fun onInterrupt() = Unit
    override fun onServiceConnected() {
        instance = this
        connected = true
        GateService.note("无障碍服务已连接")
        gate = GateOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (Prefs.onboarded(this)) GateService.start(this)
    }
    override fun onDestroy() {
        gate?.hide(); gate = null
        instance = null
        connected = false
        GateService.note("无障碍服务已断开")
        Foreground.clearExternal()
        super.onDestroy()
    }

    companion object {
        private var instance: WatchAccessibilityService? = null
        var connected = false
            private set
        fun hasGate(): Boolean = instance?.gate?.isShowing == true
        fun showGate(reason: String): Boolean = instance?.gate?.show(reason) == true
        fun hideGate() { instance?.gate?.hide() }
        fun suspendGate() { instance?.gate?.suspend() }
    }
}
