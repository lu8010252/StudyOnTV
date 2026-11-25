package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePresentationTest {

    @Test
    fun fullscreenDecisionWaitsDuringCooldownWithoutOverlayBreakthrough() {
        val t0 = 10_000L
        // 首次触发（lastFullscreenAt == 0）直接拉起全屏学习页
        assertEquals(
            GatePresentation.FullscreenDecision.LAUNCH_FULLSCREEN,
            GatePresentation.fullscreenDecision(
                preferFullscreen = true,
                shouldShow = true,
                now = t0,
                lastGateAt = 0L,
                lastFullscreenAt = 0L
            )
        )
        // T0 + 2s：已超过 VISIBLE_RETRY_MS(1.8s) 但仍在 FULLSCREEN_COOLDOWN_MS(5s) 冷却期内，严禁穿透到悬浮窗
        assertEquals(
            GatePresentation.FullscreenDecision.WAIT_COOLDOWN,
            GatePresentation.fullscreenDecision(
                preferFullscreen = true,
                shouldShow = true,
                now = t0 + 2_000L,
                lastGateAt = t0,
                lastFullscreenAt = t0
            )
        )
        // 恰好 5s 边界仍在冷却期内
        assertEquals(
            GatePresentation.FullscreenDecision.WAIT_COOLDOWN,
            GatePresentation.fullscreenDecision(
                preferFullscreen = true,
                shouldShow = true,
                now = t0 + GatePresentation.FULLSCREEN_COOLDOWN_MS,
                lastGateAt = t0,
                lastFullscreenAt = t0
            )
        )
        // 超过 5s 后允许再次拉起全屏学习页
        assertEquals(
            GatePresentation.FullscreenDecision.LAUNCH_FULLSCREEN,
            GatePresentation.fullscreenDecision(
                preferFullscreen = true,
                shouldShow = true,
                now = t0 + GatePresentation.FULLSCREEN_COOLDOWN_MS + 1L,
                lastGateAt = t0,
                lastFullscreenAt = t0
            )
        )
    }

    @Test
    fun fullscreenDecisionUsesOverlayWhenNotPreferredOrNotShowing() {
        assertEquals(
            GatePresentation.FullscreenDecision.USE_OVERLAY,
            GatePresentation.fullscreenDecision(
                preferFullscreen = false,
                shouldShow = true,
                now = 20_000L,
                lastGateAt = 10_000L,
                lastFullscreenAt = 10_000L
            )
        )
        assertEquals(
            GatePresentation.FullscreenDecision.USE_OVERLAY,
            GatePresentation.fullscreenDecision(
                preferFullscreen = true,
                shouldShow = false,
                now = 20_000L,
                lastGateAt = 10_000L,
                lastFullscreenAt = 10_000L
            )
        )
    }

    @Test
    fun destroyMuteReleaseClearsAllOnlyWhenNoLearningActivity() {
        assertTrue(GatePresentation.shouldReleaseAllMuteOnDestroy(hasLearningActivity = false))
        assertFalse(GatePresentation.shouldReleaseAllMuteOnDestroy(hasLearningActivity = true))
    }

    @Test
    fun fullscreenLaunchBlockedDuringPowerExitGraceOrShutdown() {
        val t0 = 50_000L
        val exitUntil = t0 + GatePresentation.POWER_EXIT_GRACE_MS
        // 正常状态允许拉起
        assertTrue(GatePresentation.fullscreenLaunchAllowed(interactive = true, screenOn = true, shuttingDown = false, now = t0, powerExitUntil = 0L))
        // 电源键退出宽限期内禁止重拉
        assertFalse(GatePresentation.fullscreenLaunchAllowed(interactive = true, screenOn = true, shuttingDown = false, now = t0 + 1_000L, powerExitUntil = exitUntil))
        // 宽限期结束后恢复允许
        assertTrue(GatePresentation.fullscreenLaunchAllowed(interactive = true, screenOn = true, shuttingDown = false, now = exitUntil, powerExitUntil = exitUntil))
        // 收到关机广播或非交互/熄屏时一律禁止
        assertFalse(GatePresentation.fullscreenLaunchAllowed(interactive = true, screenOn = true, shuttingDown = true, now = exitUntil + 1_000L, powerExitUntil = 0L))
        assertFalse(GatePresentation.fullscreenLaunchAllowed(interactive = false, screenOn = true, shuttingDown = false, now = t0, powerExitUntil = 0L))
        assertFalse(GatePresentation.fullscreenLaunchAllowed(interactive = true, screenOn = false, shuttingDown = false, now = t0, powerExitUntil = 0L))
    }

    @Test
    fun overlayPermissionAlertActionTracksRevokeAndRecovery() {
        // 首次未授权时不误报
        assertEquals(
            GatePresentation.OverlayAlertAction.NONE,
            GatePresentation.overlayPermissionAlertAction(supervisionEnabled = true, wasGranted = false, nowGranted = false)
        )
        // 曾授权后运行期被撤回 -> 触发告警通知 1003
        assertEquals(
            GatePresentation.OverlayAlertAction.NOTIFY_REVOKED,
            GatePresentation.overlayPermissionAlertAction(supervisionEnabled = true, wasGranted = true, nowGranted = false)
        )
        // 重新授权 -> 取消旧告警通知 1003
        assertEquals(
            GatePresentation.OverlayAlertAction.CANCEL_ALERT,
            GatePresentation.overlayPermissionAlertAction(supervisionEnabled = true, wasGranted = false, nowGranted = true)
        )
        // 总管控关闭 -> 取消告警，不误报
        assertEquals(
            GatePresentation.OverlayAlertAction.CANCEL_ALERT,
            GatePresentation.overlayPermissionAlertAction(supervisionEnabled = false, wasGranted = true, nowGranted = false)
        )
    }
}
