package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LocalStoreAnalyticsTest {

    @Test
    fun inSpacedScopeMatchesWrongOnlyAndAllSemantics() {
        // spacedScope = "all" (wrongOnly = false): every studied question is in scope
        assertTrue(LocalStore.inSpacedScope(wrongOnly = false, lastCorrect = 1, answered = 5, correct = 5))
        assertTrue(LocalStore.inSpacedScope(wrongOnly = false, lastCorrect = -1, answered = 0, correct = 0))

        // spacedScope = "wrong_only" (wrongOnly = true):
        // Never missed (lastCorrect = 1, answered == correct) => excluded
        assertFalse(LocalStore.inSpacedScope(wrongOnly = true, lastCorrect = 1, answered = 5, correct = 5))
        // Unanswered flashcard-only without wrong => excluded
        assertFalse(LocalStore.inSpacedScope(wrongOnly = true, lastCorrect = -1, answered = 0, correct = 0))
        // Last answer was wrong => included
        assertTrue(LocalStore.inSpacedScope(wrongOnly = true, lastCorrect = 0, answered = 1, correct = 0))
        // Currently right, but has historical wrong (answered > correct) => included
        assertTrue(LocalStore.inSpacedScope(wrongOnly = true, lastCorrect = 1, answered = 3, correct = 2))
    }

    @Test
    fun resolveTimeZoneAndStartOfDayRespectPerChildZone() {
        val shanghai = LocalStore.resolveTimeZone("Asia/Shanghai")
        val newYork = LocalStore.resolveTimeZone("America/New_York")
        assertEquals("Asia/Shanghai", shanghai.id)
        assertEquals("America/New_York", newYork.id)

        // Invalid timezone falls back to device default rather than silently shifting to GMT
        assertEquals(TimeZone.getDefault().id, LocalStore.resolveTimeZone("Invalid/Zone_XYZ").id)
        assertEquals("UTC", LocalStore.resolveTimeZone("UTC").id)

        // 2026-06-15T18:00:00Z: Shanghai is 2026-06-16 02:00, New York is 2026-06-15 14:00
        val instantMs = 1781546400000L
        val shanghaiStart = LocalStore.startOfDayMillis(instantMs, shanghai)
        val nyStart = LocalStore.startOfDayMillis(instantMs, newYork)
        assertNotEquals(shanghaiStart, nyStart)
        // Shanghai midnight (2026-06-16 00:00+08 = 2026-06-15 16:00Z) is 12h after NY midnight (2026-06-15 00:00-04 = 2026-06-15 04:00Z)
        assertEquals(12L * 3600_000L, shanghaiStart - nyStart)
    }
}
