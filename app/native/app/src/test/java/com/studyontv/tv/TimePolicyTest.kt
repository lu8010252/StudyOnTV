package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimePolicyTest {

    @Test
    fun clearsCorruptedDeadlineJsonAndReturnsZero() {
        var cleared = false
        val left1 = TimePolicy.resolveLeft(
            raw = "{truncated",
            currentBoot = 1,
            elapsed = 10_000L,
            nowWall = 1_700_000_000_000L,
            onCorrupted = { cleared = true }
        )
        assertEquals(0L, left1)
        assertTrue(cleared)

        var clearedAgain = false
        val left2 = TimePolicy.resolveLeft(
            raw = "",
            currentBoot = 1,
            elapsed = 11_000L,
            nowWall = 1_700_000_001_000L,
            onCorrupted = { clearedAgain = true }
        )
        assertEquals(0L, left2)
        assertFalse(clearedAgain)
    }

    @Test
    fun preservesValidMonotonicAndWallClockDeadlines() {
        val raw = JSONObject()
            .put("boot", 3)
            .put("atElapsed", 5_000L)
            .put("untilElapsed", 65_000L)
            .put("until", 1_700_000_060_000L)
            .toString()

        // 同次开机走单调时钟
        assertEquals(
            50_000L,
            TimePolicy.resolveLeft(raw, currentBoot = 3, elapsed = 15_000L, nowWall = 1_700_000_999_000L)
        )
        // 重启后（boot 改变）走墙钟
        assertEquals(
            45_000L,
            TimePolicy.resolveLeft(raw, currentBoot = 4, elapsed = 2_000L, nowWall = 1_700_000_015_000L)
        )
    }
}
