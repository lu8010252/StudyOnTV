package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAccessibilityUninstallTest {

    @Test
    fun detectsChineseAndEnglishUninstallPromptsIncludingAlertDialog() {
        assertTrue(
            WatchAccessibilityService.isSelfUninstallPrompt(
                className = "android.app.AlertDialog",
                texts = listOf("题了个题", "要卸载此应用吗？")
            )
        )
        assertTrue(
            WatchAccessibilityService.isSelfUninstallPrompt(
                className = "android.app.AlertDialog",
                texts = listOf("Do you want to uninstall StudyOnTV?")
            )
        )
        assertTrue(
            WatchAccessibilityService.isSelfUninstallPrompt(
                className = "com.android.packageinstaller.UninstallAlertDialogFragment",
                texts = listOf("StudyOnTV EU"),
                selfLabels = setOf("StudyOnTV EU")
            )
        )
    }

    @Test
    fun ignoresThirdPartyAppUninstallOrNonUninstallDialogs() {
        assertFalse(
            WatchAccessibilityService.isSelfUninstallPrompt(
                className = "com.android.packageinstaller.UninstallAlertDialogFragment",
                texts = listOf("哔哩哔哩", "要卸载此应用吗？")
            )
        )
        assertFalse(
            WatchAccessibilityService.isSelfUninstallPrompt(
                className = "android.app.AlertDialog",
                texts = listOf("题了个题", "安装完成")
            )
        )
    }

    @Test
    fun ignoresMiuiSecurityCenterOverlayWindowsButKeepsRealActivities() {
        val miui = "com.miui.securitycenter"
        assertTrue(SystemSettingsGuard.isVendorSecurityCenter(miui))
        // 微信上方弹出的小米隐私/安全提示浮层（非 Activity/Dialog）必须忽略，不污染前台
        assertTrue(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "android.widget.FrameLayout", true))
        assertTrue(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "android.widget.LinearLayout", true))
        assertTrue(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "com.miui.privacy.PrivacyBannerView", true))
        assertTrue(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, null, true))
        // 真正进入小米手机管家应用管理 / 权限设置 Activity 或弹窗仍必须保留并触发拦截
        assertFalse(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "com.miui.appmanager.ApplicationsDetailsActivity", true))
        assertFalse(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "com.miui.securityscan.MainActivity", true))
        assertFalse(WatchAccessibilityService.shouldIgnoreOverlayWindow(miui, "miuix.appcompat.app.AlertDialog", true))
        // 正常第三方应用（如微信）不受影响；但 Toast/PopupWindow 忽略
        assertFalse(WatchAccessibilityService.shouldIgnoreOverlayWindow("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", false))
        assertTrue(WatchAccessibilityService.shouldIgnoreOverlayWindow("com.tencent.mm", "android.widget.Toast", false))
    }

    @Test
    fun activeWindowFilterBlocksVendorOverlaysAndSystemWindowsWithoutBreakingNormalApps() {
        val self = "com.studyontv.tv"
        val appType = WatchAccessibilityService.WINDOW_TYPE_APPLICATION
        val systemType = 3 // AccessibilityWindowInfo.TYPE_SYSTEM
        // 普通正常应用（TYPE_APPLICATION 或无法获取窗口类型时）正常识别
        assertTrue(WatchAccessibilityService.isAcceptableActiveWindow("com.tencent.mm", self, false, false, false, appType))
        assertTrue(WatchAccessibilityService.isAcceptableActiveWindow("com.tencent.mm", self, false, false, false, null))
        // 系统浮层窗口（非 TYPE_APPLICATION）一律过滤
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow("com.tencent.mm", self, false, false, false, systemType))
        // 国产安全中心：系统浮层或未知窗口类型必须过滤，真实 TYPE_APPLICATION 保留
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow("com.miui.securitycenter", self, false, false, true, systemType))
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow("com.miui.securitycenter", self, false, false, true, null))
        assertTrue(WatchAccessibilityService.isAcceptableActiveWindow("com.miui.securitycenter", self, false, false, true, appType))
        // 自身、临时窗口、输入法均过滤
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow(self, self, false, false, false, appType))
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow("com.android.systemui", self, true, false, false, appType))
        assertFalse(WatchAccessibilityService.isAcceptableActiveWindow("com.sohu.inputmethod.sogou", self, false, true, false, appType))
    }
}
