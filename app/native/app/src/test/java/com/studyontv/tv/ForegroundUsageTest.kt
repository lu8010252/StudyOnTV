package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundUsageTest {

    private val selfPkg = "com.studyontv.tv"

    @Test
    fun skipsInputMethodAndTransientPackagesWhenPickingRecentUsage() {
        val now = 10_000_000L
        val enabledImes = setOf("com.custom.ime")
        val candidates = listOf(
            Foreground.UsageCandidate("com.sohu.inputmethod.sogou", now - 1_000L),
            Foreground.UsageCandidate("com.huawei.secime", now - 2_000L),
            Foreground.UsageCandidate("com.custom.ime", now - 3_000L),
            Foreground.UsageCandidate("com.android.systemui", now - 4_000L),
            Foreground.UsageCandidate(selfPkg, now - 5_000L),
            Foreground.UsageCandidate("com.netease.open.course", now - 10_000L),
            Foreground.UsageCandidate("com.old.video.app", now - Foreground.STALE_MS - 1_000L)
        )

        val picked = Foreground.pickRecentUsage(candidates, now, selfPkg) { pkg ->
            Foreground.isInputMethodName(pkg) || pkg in enabledImes
        }

        assertEquals("com.netease.open.course", picked?.packageName)
    }

    @Test
    fun returnsNullWhenOnlyImeSelfTransientOrStaleEntriesExist() {
        val now = 10_000_000L
        val candidates = listOf(
            Foreground.UsageCandidate("com.baidu.input_huawei", now - 500L),
            Foreground.UsageCandidate("com.android.packageinstaller", now - 1_000L),
            Foreground.UsageCandidate(selfPkg, now - 2_000L),
            Foreground.UsageCandidate("com.example.stale", now - Foreground.STALE_MS - 1L),
            Foreground.UsageCandidate("com.example.zero", 0L)
        )

        val picked = Foreground.pickRecentUsage(candidates, now, selfPkg) { pkg ->
            Foreground.isInputMethodName(pkg)
        }

        assertNull(picked)
    }
}
