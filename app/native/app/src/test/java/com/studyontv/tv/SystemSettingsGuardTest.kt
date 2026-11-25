package com.studyontv.tv

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemSettingsGuardTest {

    private val selfPkg = "com.studyontv.tv"

    @Before
    fun setUp() {
        SystemSettingsGuard.revoke()
    }

    @After
    fun tearDown() {
        SystemSettingsGuard.revoke()
    }

    @Test
    fun clearsTamperAfterGuardPeriodOnNormalExternalApp() {
        val t0 = 10_000L
        SystemSettingsGuard.flagTamperAttempt(t0)
        assertTrue(SystemSettingsGuard.tamperActive(t0))

        // 500ms 内退回桌面：仍在 1.5s 保护窗口内，不立即清除
        assertTrue(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = "com.android.launcher3",
                selfPackage = selfPkg,
                isSettingsPkg = false,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + 500L
            )
        )
        assertTrue(SystemSettingsGuard.tamperActive(t0 + 500L))

        // 停留在桌面超过 1.5s 后经周期同步自动清除，不再永久锁死
        assertFalse(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = "com.android.launcher3",
                selfPackage = selfPkg,
                isSettingsPkg = false,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + SystemSettingsGuard.TAMPER_CLEAR_GUARD_MS
            )
        )
        assertFalse(SystemSettingsGuard.tamperActive(t0 + SystemSettingsGuard.TAMPER_CLEAR_GUARD_MS))
    }

    @Test
    fun keepsBlockingWhileRemainingInSettingsOrPackageInstaller() {
        val t0 = 20_000L
        SystemSettingsGuard.flagTamperAttempt(t0)

        // 停留在系统设置超过 1.5s 甚至超过 60s 仍持续拦截
        assertTrue(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = "com.android.settings",
                selfPackage = selfPkg,
                isSettingsPkg = true,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + SystemSettingsGuard.TAMPER_MAX_DURATION_MS + 5_000L
            )
        )
        assertTrue(SystemSettingsGuard.tamperActive(t0 + SystemSettingsGuard.TAMPER_MAX_DURATION_MS + 5_000L, hasForegroundPackage = true))

        // 停留在卸载确认页（packageinstaller）超过 1.5s 仍持续拦截
        assertTrue(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = "com.google.android.packageinstaller",
                selfPackage = selfPkg,
                isSettingsPkg = false,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + SystemSettingsGuard.TAMPER_CLEAR_GUARD_MS + 10_000L
            )
        )
        assertTrue(SystemSettingsGuard.tamperActive(t0 + SystemSettingsGuard.TAMPER_CLEAR_GUARD_MS + 10_000L, hasForegroundPackage = true))
    }

    @Test
    fun expiresTamperInOverlayOnlyModeWhenForegroundPackageUnknown() {
        val t0 = 30_000L
        SystemSettingsGuard.flagTamperAttempt(t0)

        // 仅悬浮窗模式（pkg == null）：最大保护期内仍拦截
        assertTrue(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = null,
                selfPackage = selfPkg,
                isSettingsPkg = false,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + SystemSettingsGuard.TAMPER_MAX_DURATION_MS - 1L
            )
        )

        // 超过最大保护期后自动过期，防止无包名能力设备永久锁死
        assertFalse(
            SystemSettingsGuard.evaluateBlocked(
                awake = true,
                monitoring = true,
                unlimited = false,
                pkg = null,
                selfPackage = selfPkg,
                isSettingsPkg = false,
                blockSettings = true,
                recentlyVerified = false,
                now = t0 + SystemSettingsGuard.TAMPER_MAX_DURATION_MS
            )
        )
        assertFalse(SystemSettingsGuard.tamperActive(t0 + SystemSettingsGuard.TAMPER_MAX_DURATION_MS, hasForegroundPackage = false))
    }
}
