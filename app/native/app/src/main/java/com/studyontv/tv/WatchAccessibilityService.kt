package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
            // 无障碍服务由系统常驻，顺便充当看门狗：管理服务被系统清理后在下一次切换应用时拉起
            if (!GateService.running) GateService.ensureRunning(this, "accessibility")
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
        GateService.ensureRunning(this, "accessibility")
    }
    override fun onDestroy() {
        gate?.destroy(); gate = null
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
        fun enabled(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (_: Exception) { false }
        fun hasGate(): Boolean = instance?.gate?.isShowing == true
        fun showGate(reason: String): Boolean = instance?.gate?.show(reason) == true
        fun hideGate() { instance?.gate?.hide() }
        fun suspendGate() { instance?.gate?.suspend() }
    }
}
