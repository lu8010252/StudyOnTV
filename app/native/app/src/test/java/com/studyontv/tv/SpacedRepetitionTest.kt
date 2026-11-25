package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionTest {
    @Test
    fun ankiIntervalsProgressAcrossAgainHardGoodAndEasy() {
        val fresh = SpacedRepetition.State()
        val labels0 = SpacedRepetition.previewLabels(fresh)
        assertEquals("10分钟", labels0.getString("again"))
        assertEquals("1天", labels0.getString("hard"))
        assertEquals("1天", labels0.getString("good"))
        assertEquals("4天", labels0.getString("easy"))

        val step1 = SpacedRepetition.next(fresh, 4, 1_000_000L)
        assertEquals(1, step1.repetitions)
        assertEquals(1L, step1.intervalDays)

        val step2 = SpacedRepetition.next(SpacedRepetition.State(step1.repetitions, step1.intervalDays, step1.ease), 4, 1_000_000L)
        assertEquals(2, step2.repetitions)
        assertEquals(6L, step2.intervalDays)

        val step3 = SpacedRepetition.next(SpacedRepetition.State(step2.repetitions, step2.intervalDays, step2.ease), 4, 1_000_000L)
        assertEquals(3, step3.repetitions)
        assertEquals(15L, step3.intervalDays)

        val easyStep = SpacedRepetition.next(SpacedRepetition.State(step2.repetitions, step2.intervalDays, step2.ease), 5, 1_000_000L)
        assertTrue(easyStep.intervalDays > step3.intervalDays)

        val lapsed = SpacedRepetition.next(SpacedRepetition.State(step3.repetitions, step3.intervalDays, step3.ease), 1, 2_000_000L)
        assertEquals(0, lapsed.repetitions)
        assertEquals(0L, lapsed.intervalDays)
        assertEquals(2_000_000L + 10 * 60 * 1000L, lapsed.dueAt)
    }

    @Test
    fun formatAnswerDisplayHandlesAllQuestionTypes() {
        val single = JSONObject().put("type", "single").put("a", JSONArray(listOf("3", "4", "5"))).put("k", 1)
        assertEquals("B. 4", LearningEngine.formatAnswerDisplay(single))

        val multi = JSONObject().put("type", "multiple").put("a", JSONArray(listOf("春", "夏", "秋"))).put("correctIndices", JSONArray(listOf(0, 2)))
        assertEquals("A. 春 / C. 秋", LearningEngine.formatAnswerDisplay(multi))

        val boolQ = JSONObject().put("type", "boolean").put("a", JSONArray(listOf("正确", "错误"))).put("k", 0)
        assertEquals("正确", LearningEngine.formatAnswerDisplay(boolQ))

        val textQ = JSONObject().put("type", "text").put("answers", JSONArray(listOf("北京", "首都北京")))
        assertEquals("北京 / 首都北京", LearningEngine.formatAnswerDisplay(textQ))

        val subjQ = JSONObject().put("type", "subjective").put("modelAnswer", "因为地球自转")
        assertEquals("因为地球自转", LearningEngine.formatAnswerDisplay(subjQ))
    }

    @Test
    fun adminLockoutAndLanSecurityRulesEnforceBoundaries() {
        assertEquals(0L, AdminAuth.lockoutDurationMs(4))
        assertEquals(60_000L, AdminAuth.lockoutDurationMs(5))
        assertEquals(0L, AdminAuth.lockoutDurationMs(6))
        assertEquals(120_000L, AdminAuth.lockoutDurationMs(10))
        assertEquals(300_000L, AdminAuth.lockoutDurationMs(15))
        assertEquals(300_000L, AdminAuth.lockoutDurationMs(20))

        assertEquals("*", LanServer.allowedCorsOrigin(null))
        assertNull(LanServer.allowedCorsOrigin("null"))
        assertEquals("http://127.0.0.1:5173", LanServer.allowedCorsOrigin("http://127.0.0.1:5173"))
        assertEquals("http://localhost:5173", LanServer.allowedCorsOrigin("http://localhost:5173"))
        assertEquals("http://192.168.1.25:8099", LanServer.allowedCorsOrigin("http://192.168.1.25:8099"))
        assertEquals("http://10.0.0.8:8099", LanServer.allowedCorsOrigin("http://10.0.0.8:8099"))
        assertEquals("http://172.16.5.2:8099", LanServer.allowedCorsOrigin("http://172.16.5.2:8099"))
        assertNull(LanServer.allowedCorsOrigin("https://evil.example"))
        assertNull(LanServer.allowedCorsOrigin("http://8.8.8.8:8099"))
        assertNull(LanServer.allowedCorsOrigin("http://172.32.0.1:8099"))

        assertTrue(LanServer.isDeviceRouteAllowed("GET", "/api/state"))
        assertTrue(LanServer.isDeviceRouteAllowed("POST", "/api/quiz/start"))
        assertTrue(LanServer.isDeviceRouteAllowed("POST", "/api/quiz/memorized"))
        assertTrue(LanServer.isDeviceRouteAllowed("POST", "/api/quiz/rate"))
        assertFalse(LanServer.isDeviceRouteAllowed("PUT", "/api/config"))
        assertFalse(LanServer.isDeviceRouteAllowed("POST", "/api/remote"))
        assertFalse(LanServer.isDeviceRouteAllowed("GET", "/api/backup"))
    }
}
