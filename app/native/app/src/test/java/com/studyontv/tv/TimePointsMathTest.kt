package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimePointsMathTest {
    @Test
    fun parsesValidClockStringsAndFormatsLabels() {
        assertEquals(0, TimePointsMath.minutes("00:00"))
        assertEquals(19 * 60 + 15, TimePointsMath.minutes("19:15"))
        assertEquals(23 * 60 + 59, TimePointsMath.minutes("23:59"))
        assertNull(TimePointsMath.minutes(null))
        assertNull(TimePointsMath.minutes("24:00"))
        assertNull(TimePointsMath.minutes("9:00"))
        assertNull(TimePointsMath.minutes("12:60"))
        assertEquals("09:05", TimePointsMath.label(9 * 60 + 5))
    }

    @Test
    fun sameDayWindowIsLeftClosedRightOpen() {
        val start = TimePointsMath.minutes("19:00")!!
        val end = TimePointsMath.minutes("20:15")!!
        val days = setOf(2) // 周三 (0=周一 .. 6=周日)

        assertFalse(TimePointsMath.hits(days, start, end, 2, TimePointsMath.minutes("18:59")!!))
        assertTrue(TimePointsMath.hits(days, start, end, 2, TimePointsMath.minutes("19:00")!!))
        assertTrue(TimePointsMath.hits(days, start, end, 2, TimePointsMath.minutes("20:14")!!))
        assertFalse(TimePointsMath.hits(days, start, end, 2, TimePointsMath.minutes("20:15")!!))
        assertFalse(TimePointsMath.hits(days, start, end, 3, TimePointsMath.minutes("19:30")!!))
        // 空窗口（start == end）永不命中
        assertFalse(TimePointsMath.hits(days, start, start, 2, start))
    }

    @Test
    fun crossMidnightWindowExtendsIntoFollowingMorningOnly() {
        val start = TimePointsMath.minutes("23:30")!!
        val end = TimePointsMath.minutes("00:30")!!
        val fridayAndSunday = setOf(4, 6) // 周五、周日

        // 周五当晚 23:30 命中，周五凌晨 00:15 不命中（周四不在集合内）
        assertTrue(TimePointsMath.hits(fridayAndSunday, start, end, 4, TimePointsMath.minutes("23:30")!!))
        assertFalse(TimePointsMath.hits(fridayAndSunday, start, end, 4, TimePointsMath.minutes("00:15")!!))

        // 周五安排延续到周六凌晨 00:00–00:29，00:30 结束；周六当晚 23:45 不命中
        assertTrue(TimePointsMath.hits(fridayAndSunday, start, end, 5, TimePointsMath.minutes("00:00")!!))
        assertTrue(TimePointsMath.hits(fridayAndSunday, start, end, 5, TimePointsMath.minutes("00:29")!!))
        assertFalse(TimePointsMath.hits(fridayAndSunday, start, end, 5, TimePointsMath.minutes("00:30")!!))
        assertFalse(TimePointsMath.hits(fridayAndSunday, start, end, 5, TimePointsMath.minutes("23:45")!!))

        // 周日（day=6）跨午夜延续到周一（day=0）凌晨
        assertTrue(TimePointsMath.hits(fridayAndSunday, start, end, 0, TimePointsMath.minutes("00:10")!!))
        assertFalse(TimePointsMath.hits(fridayAndSunday, start, end, 0, TimePointsMath.minutes("23:50")!!))
    }
}
