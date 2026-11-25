package com.studyontv.tv

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TtsProbeTest {
    private class FakeEngine(val langResult: Int = TextToSpeech.LANG_AVAILABLE) {
        val shutdownCount = AtomicInteger(0)
    }

    @Test
    fun synchronousOnInitDuringConstructorStillEvaluatesAndShutsDownOnce() {
        val fake = FakeEngine(TextToSpeech.LANG_COUNTRY_AVAILABLE)
        val coordinator = TtsProbe.ProbeCoordinator<FakeEngine>(
            engineName = "com.example.tts",
            languageTag = "zh-CN",
            checkLanguage = { it.langResult },
            shutdown = { it.shutdownCount.incrementAndGet() }
        )

        // Simulate ROM invoking OnInitListener synchronously before TextToSpeech constructor returns.
        coordinator.onInit(TextToSpeech.SUCCESS)
        assertFalse(coordinator.isSettled())
        assertEquals(0, fake.shutdownCount.get())

        coordinator.attachEngine(fake)
        assertTrue(coordinator.isSettled())
        assertEquals(1, fake.shutdownCount.get())

        val (result, timedOut) = coordinator.awaitResult(100L) { it() }
        assertFalse(timedOut)
        assertTrue(result.getBoolean("ok"))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, result.getInt("langStatus"))
        assertEquals("com.example.tts", result.getString("engine"))
        assertEquals("zh-CN", result.getString("language"))
        assertEquals(1, fake.shutdownCount.get())
    }

    @Test
    fun asyncOnInitCompletesAndShutsDownOnce() {
        val fake = FakeEngine(TextToSpeech.LANG_MISSING_DATA)
        val coordinator = TtsProbe.ProbeCoordinator<FakeEngine>(
            engineName = "com.example.tts",
            languageTag = "zh-CN",
            checkLanguage = { it.langResult },
            shutdown = { it.shutdownCount.incrementAndGet() }
        )

        coordinator.attachEngine(fake)
        assertFalse(coordinator.isSettled())

        coordinator.onInit(TextToSpeech.SUCCESS)
        val (result, timedOut) = coordinator.awaitResult(100L) { it() }
        assertFalse(timedOut)
        assertFalse(result.getBoolean("ok"))
        assertEquals(TextToSpeech.LANG_MISSING_DATA, result.getInt("langStatus"))
        assertTrue(result.getString("hint").contains("缺少 zh-CN 语言包"))
        assertEquals(1, fake.shutdownCount.get())
    }

    @Test
    fun timeoutCleansUpAttachedEngineAndLateOnInitDoesNotMutateResultOrDoubleShutdown() {
        val fake = FakeEngine(TextToSpeech.LANG_AVAILABLE)
        val coordinator = TtsProbe.ProbeCoordinator<FakeEngine>(
            engineName = "com.example.tts",
            languageTag = "zh-CN",
            checkLanguage = { it.langResult },
            shutdown = { it.shutdownCount.incrementAndGet() }
        )
        coordinator.attachEngine(fake)

        var scheduledCleanup: (() -> Unit)? = null
        val (result, timedOut) = coordinator.awaitResult(10L) { cleanup ->
            scheduledCleanup = cleanup
        }
        assertTrue(timedOut)
        assertFalse(result.getBoolean("ok"))
        assertEquals(TtsProbe.STATUS_INIT_FAILED, result.getInt("langStatus"))
        assertTrue(result.getString("hint").contains("检测超时"))

        // Run main-thread cleanup posted on timeout.
        scheduledCleanup!!.invoke()
        assertEquals(1, fake.shutdownCount.get())

        // Late onInit arriving after timeout must neither mutate returned JSON nor call shutdown again.
        coordinator.onInit(TextToSpeech.SUCCESS)
        assertFalse(result.getBoolean("ok"))
        assertEquals(TtsProbe.STATUS_INIT_FAILED, result.getInt("langStatus"))
        assertEquals(1, fake.shutdownCount.get())
    }

    @Test
    fun attachEngineAfterTimeoutImmediatelyShutsDown() {
        val fake = FakeEngine(TextToSpeech.LANG_AVAILABLE)
        val coordinator = TtsProbe.ProbeCoordinator<FakeEngine>(
            engineName = "com.example.tts",
            languageTag = "zh-CN",
            checkLanguage = { it.langResult },
            shutdown = { it.shutdownCount.incrementAndGet() }
        )

        val (result, timedOut) = coordinator.awaitResult(10L) { it() }
        assertTrue(timedOut)
        assertFalse(result.getBoolean("ok"))
        assertEquals(0, fake.shutdownCount.get())

        // Constructor finally returns after caller already timed out: engine must be shut down immediately.
        coordinator.attachEngine(fake)
        assertEquals(1, fake.shutdownCount.get())
    }

    @Test
    fun evaluatesInitFailureAndUnsupportedLanguage() {
        val initFail = TtsProbe.evaluateInitStatus(TextToSpeech.ERROR, "com.example.tts", "zh-CN") {
            TextToSpeech.LANG_AVAILABLE
        }
        assertFalse(initFail.ok)
        assertEquals(TtsProbe.STATUS_INIT_FAILED, initFail.langStatus)

        val unsupported = TtsProbe.evaluateInitStatus(TextToSpeech.SUCCESS, "com.example.tts", "zh-HK") {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        assertFalse(unsupported.ok)
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, unsupported.langStatus)
        assertTrue(unsupported.hint.contains("不支持 zh-HK"))
    }
}
