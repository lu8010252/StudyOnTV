package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LearningEngineStateTest {

    private fun flashcardRound(
        id: String = "r-1",
        focus: String = "memorize",
        deckIds: List<String> = listOf("q1", "q2"),
        ratedIds: List<String> = emptyList(),
        ratedCorrectIds: List<String> = emptyList()
    ): JSONObject {
        val deck = JSONArray()
        deckIds.forEach { deck.put(JSONObject().put("id", it)) }
        return JSONObject()
            .put("id", id)
            .put("focus", focus)
            .put("studyDeck", deck)
            .put("ratedIds", JSONArray(ratedIds))
            .put("ratedCorrectIds", JSONArray(ratedCorrectIds))
    }

    @Test
    fun rateFlashcardRejectsMismatchedOrMissingRoundId() {
        val round = flashcardRound(id = "r-current")
        try {
            LearningEngine.planFlashcardRate(round, "r-stale", "q1", 4)
            fail("Expected IllegalArgumentException for mismatched roundId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("任务已更新"))
        }
        try {
            LearningEngine.planFlashcardRate(null, "r-stale", "q1", 4)
            fail("Expected IllegalArgumentException when no active round exists")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("任务已更新"))
        }
    }

    @Test
    fun rateFlashcardRejectsQuestionOutsideStudyDeck() {
        val round = flashcardRound(id = "r-1", deckIds = listOf("q1", "q2"))
        try {
            LearningEngine.planFlashcardRate(round, "r-1", "q999", 4)
            fail("Expected IllegalArgumentException for question outside deck")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不在当前闪卡轮次"))
        }
    }

    @Test
    fun rateFlashcardIsIdempotentForDuplicateQidAndCompletesMemorizeRound() {
        val round = flashcardRound(id = "r-1", focus = "memorize", deckIds = listOf("q1", "q2"))

        val first = LearningEngine.planFlashcardRate(round, "r-1", "q1", 4)
        assertFalse(first.alreadyRated)
        assertFalse(first.finished)
        assertTrue(first.correct)
        assertEquals(listOf("q1"), first.updatedRatedIds)
        assertEquals(listOf("q1"), first.updatedCorrectIds)

        // Persist first rating onto round and submit duplicate rating for q1
        round.put("ratedIds", JSONArray(first.updatedRatedIds))
        round.put("ratedCorrectIds", JSONArray(first.updatedCorrectIds))

        val duplicate = LearningEngine.planFlashcardRate(round, "r-1", "q1", 5)
        assertTrue(duplicate.alreadyRated)
        assertFalse(duplicate.finished)
        assertEquals(listOf("q1"), duplicate.updatedRatedIds)

        // Rate remaining card q2 with quality=1 (again -> incorrect) -> finishes memorize round
        val last = LearningEngine.planFlashcardRate(round, "r-1", "q2", 1)
        assertFalse(last.alreadyRated)
        assertTrue(last.finished)
        assertFalse(last.correct)
        assertEquals(listOf("q1", "q2"), last.updatedRatedIds)
        assertEquals(listOf("q1"), last.updatedCorrectIds)
        assertEquals(2, last.deckSize)
    }

    @Test
    fun extraStageRespectsStreakBaseDoneAndExtraPool() {
        val streakRound = JSONObject()
            .put("mode", "streak")
            .put("target", 5)
            .put("answered", 5)
            .put("correct", 4)
            .put("streak", 2)
            .put("voluntary", false)
            .put("rules", JSONObject().put("extraEnabled", true))

        // answered >= target, but streak < target => NOT baseDone => must NOT enter extraStage
        assertFalse(LearningEngine.isBaseDone(streakRound))
        assertFalse(LearningEngine.isExtraStage(streakRound, extraPool = 2L, supervisionEnabled = true))

        // Once streak reaches target and extraPool > 0 => enters extraStage
        streakRound.put("streak", 5)
        assertTrue(LearningEngine.isBaseDone(streakRound))
        assertTrue(LearningEngine.isExtraStage(streakRound, extraPool = 2L, supervisionEnabled = true))

        // When extraPool is 0 => must NOT enter extraStage even if baseDone
        assertFalse(LearningEngine.isExtraStage(streakRound, extraPool = 0L, supervisionEnabled = true))
    }

    @Test
    fun extraStageNeverActivatesForVoluntaryOrPureStudyOrCountMode() {
        val voluntaryPractice = JSONObject()
            .put("mode", "practice")
            .put("target", 5)
            .put("answered", 0)
            .put("voluntary", true)
            .put("rules", JSONObject().put("extraEnabled", true))
        assertFalse(LearningEngine.isExtraEnabled(voluntaryPractice, supervisionEnabled = true))
        assertFalse(LearningEngine.isExtraStage(voluntaryPractice, extraPool = 3L, supervisionEnabled = true))

        // Non-voluntary practice with supervision ON and extraPool > 0 enters extraStage
        val supervisedPractice = JSONObject(voluntaryPractice.toString()).put("voluntary", false)
        assertTrue(LearningEngine.isExtraEnabled(supervisedPractice, supervisionEnabled = true))
        assertTrue(LearningEngine.isExtraStage(supervisedPractice, extraPool = 3L, supervisionEnabled = true))
        // But with supervision OFF (pure study mode), voluntary becomes true => no extraStage
        assertFalse(LearningEngine.isExtraEnabled(supervisedPractice, supervisionEnabled = false))
        assertFalse(LearningEngine.isExtraStage(supervisedPractice, extraPool = 3L, supervisionEnabled = false))

        // Count mode never enters extraStage
        val countRound = JSONObject()
            .put("mode", "count")
            .put("target", 5)
            .put("answered", 5)
            .put("correct", 5)
            .put("voluntary", false)
            .put("rules", JSONObject().put("extraEnabled", true))
        assertTrue(LearningEngine.isBaseDone(countRound))
        assertFalse(LearningEngine.isExtraEnabled(countRound, supervisionEnabled = true))
        assertFalse(LearningEngine.isExtraStage(countRound, extraPool = 3L, supervisionEnabled = true))
    }

    @Test
    fun computeCanExitAllowsFreeQuotaAndBlocksActiveReasons() {
        // Free quota alone (remaining=0, free=false, supervision=true, reason="") => canExit = true
        assertTrue(LearningEngine.hasUsableWatchQuota(remainingMs = 0L, freeQuotaMs = 60_000L))
        assertTrue(LearningEngine.computeCanExit(supervision = true, reason = "", remainingMs = 0L, freeQuotaMs = 60_000L, free = false))
        // Sub-second freeQuota (< 1000ms) does not grant exit
        assertFalse(LearningEngine.computeCanExit(supervision = true, reason = "", remainingMs = 0L, freeQuotaMs = 500L, free = false))
        // Blocking reason overrides even if freeQuota > 0
        for (blocked in listOf("locked", "rest", "feedback", "quota", "schedule")) {
            assertFalse(LearningEngine.computeCanExit(supervision = true, reason = blocked, remainingMs = 0L, freeQuotaMs = 60_000L, free = false))
        }
        // Pure study mode (supervision=false) always allows exit
        assertTrue(LearningEngine.computeCanExit(supervision = false, reason = "feedback", remainingMs = 0L, freeQuotaMs = 0L, free = false))
    }

    @Test
    fun findNextAvailableSlotReturnsZeroWhenAllDaysDisabledAndFindsNextSlotOtherwise() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val nowCal = java.util.Calendar.getInstance(utc).apply {
            set(2026, java.util.Calendar.JUNE, 15, 10, 15, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val allZero = List(7) { "0".repeat(48) }
        val dayOfWeekIndex: (java.util.Calendar) -> Int = { cal -> (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 }

        // All disabled => must return 0L for both tomorrow=false and tomorrow=true
        assertEquals(0L, LearningEngine.findNextAvailableSlotMillis(allZero, nowCal.timeInMillis, tomorrow = false, timeZone = utc, dayIndexOf = dayOfWeekIndex))
        assertEquals(0L, LearningEngine.findNextAvailableSlotMillis(allZero, nowCal.timeInMillis, tomorrow = true, timeZone = utc, dayIndexOf = dayOfWeekIndex))

        // Monday (2026-06-15) has slot 22 (11:00) enabled => returns 11:00 UTC today when tomorrow=false
        val mondayEleven = "0".repeat(22) + "1" + "0".repeat(25)
        val tuesdayNine = "0".repeat(18) + "1" + "0".repeat(29)
        val slots = listOf(mondayEleven, tuesdayNine, "0".repeat(48), "0".repeat(48), "0".repeat(48), "0".repeat(48), "0".repeat(48))

        val expectedToday = (nowCal.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 11); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        assertEquals(expectedToday, LearningEngine.findNextAvailableSlotMillis(slots, nowCal.timeInMillis, tomorrow = false, timeZone = utc, dayIndexOf = dayOfWeekIndex))

        val expectedTomorrow = (nowCal.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        assertEquals(expectedTomorrow, LearningEngine.findNextAvailableSlotMillis(slots, nowCal.timeInMillis, tomorrow = true, timeZone = utc, dayIndexOf = dayOfWeekIndex))
    }
}
