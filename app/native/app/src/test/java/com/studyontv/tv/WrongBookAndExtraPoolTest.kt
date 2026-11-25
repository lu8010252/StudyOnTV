package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrongBookAndExtraPoolTest {

    @Test
    fun parseAndMergeExtraQueueDeduplicatesAndPreservesOrder() {
        assertEquals(emptyList<String>(), LocalStore.parseExtraQueue(""))
        assertEquals(emptyList<String>(), LocalStore.parseExtraQueue("not-json"))
        assertEquals(listOf("q1", "q2", "q3"), LocalStore.parseExtraQueue("""["q1", " q2 ", "", "q1", "q3"]"""))

        val (merged1, added1) = LocalStore.mergeExtraQueue(listOf("q1", "q2"), listOf("q2", "q3", "q4", "q3", ""))
        assertEquals(listOf("q1", "q2", "q3", "q4"), merged1)
        assertEquals(2, added1)

        val (merged2, added2) = LocalStore.mergeExtraQueue(merged1, listOf("q1", "q4"))
        assertEquals(listOf("q1", "q2", "q3", "q4"), merged2)
        assertEquals(0, added2)
    }

    @Test
    fun selectNextExtraQueueIdPrefersNonLastValidAndPrunesStale() {
        val valid = setOf("q2", "q3")
        val (picked, stale) = LearningEngine.selectNextExtraQueueId(
            queueIds = listOf("q-deleted", "q2", "q3"),
            lastId = "q2",
            isValid = { it in valid }
        )
        assertEquals("q3", picked)
        assertEquals(listOf("q-deleted"), stale)

        // When only lastId is valid in queue, falls back to lastId
        val (fallback, stale2) = LearningEngine.selectNextExtraQueueId(
            queueIds = listOf("q-deleted", "q2"),
            lastId = "q2",
            isValid = { it == "q2" }
        )
        assertEquals("q2", fallback)
        assertEquals(listOf("q-deleted"), stale2)

        // When all queued items are invalid, returns null and reports all as stale
        val (none, stale3) = LearningEngine.selectNextExtraQueueId(
            queueIds = listOf("q-a", "q-b"),
            lastId = null,
            isValid = { false }
        )
        assertNull(none)
        assertEquals(listOf("q-a", "q-b"), stale3)
    }

    @Test
    fun planClearExtraActiveRoundConvergesExtraStageRoundsWithoutStalling() {
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.NONE,
            LearningEngine.planClearExtraActiveRound(null, supervisionEnabled = true)
        )

        // Pending subjective review must not be forcibly settled
        val pendingRound = JSONObject()
            .put("mode", "round").put("target", 5).put("answered", 5).put("correct", 5)
            .put("pendingReview", "rev-1")
            .put("rules", JSONObject().put("passRatio", 0.6))
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.NONE,
            LearningEngine.planClearExtraActiveRound(pendingRound, supervisionEnabled = true)
        )

        // Base done & passed in extra stage => SETTLE_PASS
        val passedExtraRound = JSONObject()
            .put("mode", "round").put("target", 5).put("answered", 5).put("correct", 4)
            .put("extra", true)
            .put("rules", JSONObject().put("passRatio", 0.6))
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.SETTLE_PASS,
            LearningEngine.planClearExtraActiveRound(passedExtraRound, supervisionEnabled = true)
        )

        // Base done & failed passRatio => SETTLE_FAIL
        val failedExtraRound = JSONObject()
            .put("mode", "round").put("target", 5).put("answered", 5).put("correct", 1)
            .put("extra", true)
            .put("rules", JSONObject().put("passRatio", 0.6))
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.SETTLE_FAIL,
            LearningEngine.planClearExtraActiveRound(failedExtraRound, supervisionEnabled = true)
        )

        // Practice mode in extra stage => CLEAR_EXTRA_FLAG
        val practiceExtra = JSONObject().put("mode", "practice").put("extra", true)
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.CLEAR_EXTRA_FLAG,
            LearningEngine.planClearExtraActiveRound(practiceExtra, supervisionEnabled = true)
        )
        val practiceNormal = JSONObject().put("mode", "practice").put("extra", false)
        assertEquals(
            LearningEngine.Companion.ClearExtraRoundAction.NONE,
            LearningEngine.planClearExtraActiveRound(practiceNormal, supervisionEnabled = true)
        )
    }

    @Test
    fun parentOnlyRoutesRemainBlockedForDeviceToken() {
        assertFalse(LanServer.isDeviceRouteAllowed("GET", "/api/wrong-questions"))
        assertFalse(LanServer.isDeviceRouteAllowed("POST", "/api/wrong-questions/master"))
        assertFalse(LanServer.isDeviceRouteAllowed("POST", "/api/wrong-questions/extra-pool"))
        assertFalse(LanServer.isDeviceRouteAllowed("POST", "/api/extra-pool/clear"))
        assertFalse(LanServer.isDeviceRouteAllowed("POST", "/api/update/install"))
        assertTrue(LanServer.isDeviceRouteAllowed("GET", "/api/state"))
    }
}
