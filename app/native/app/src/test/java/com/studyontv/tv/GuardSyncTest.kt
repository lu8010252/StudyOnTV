package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSyncTest {

    @Test
    fun allowsImmediateRebindOnColdStartBeforeCooldown() {
        // 开机第 5 秒，尚未重绑过（lastRebindAt = -1L），必须允许立即重绑
        assertTrue(GuardSync.canRebind(lastRebindAt = -1L, now = 5_000L))
    }

    @Test
    fun enforcesCooldownAfterRebind() {
        val t0 = 30_000L
        assertFalse(GuardSync.canRebind(lastRebindAt = t0, now = t0 + 1_000L))
        assertFalse(GuardSync.canRebind(lastRebindAt = t0, now = t0 + GuardSync.REBIND_COOLDOWN_MS))
        assertTrue(GuardSync.canRebind(lastRebindAt = t0, now = t0 + GuardSync.REBIND_COOLDOWN_MS + 1L))
    }
}
