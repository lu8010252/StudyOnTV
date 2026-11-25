package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureTest {
    @Test
    fun wavHeaderWrapsPcmAndRoundTripsThroughWavAudio() {
        val pcm = ByteArray(1600) { (it and 0xff).toByte() }
        val wav = VoiceCapture.wav(pcm, rate = 16000)
        assertEquals(pcm.size + 44, wav.size)
        assertTrue(WavAudio.isWave(wav))
        val parsed = WavAudio.parse(wav)
        assertNotNull(parsed)
        assertEquals(16000, parsed!!.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(pcm.size, parsed.data.size)
    }

    @Test
    fun transcribeAndDeliverReportsCheckingAndResultWhenNotCancelled() {
        val states = mutableListOf<String>()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val session = VoiceCapture.Session(
            key = "sk-test",
            onState = { states.add(it) },
            onResult = { results.add(it) },
            onError = { errors.add(it) }
        )

        val pcm = ByteArray(16000)
        session.transcribeAndDeliver(
            bytes = pcm,
            transcriber = { _, _ -> "答案是 42" },
            postMain = { it() }
        )

        assertEquals(listOf("checking"), states)
        assertEquals(listOf("答案是 42"), results)
        assertTrue(errors.isEmpty())
        assertFalse(session.active)
    }

    @Test
    fun cancelDuringTranscribeSuppressesResultAndErrorCallbacks() {
        val states = mutableListOf<String>()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val session = VoiceCapture.Session(
            key = "sk-test",
            onState = { states.add(it) },
            onResult = { results.add(it) },
            onError = { errors.add(it) }
        )

        val pcm = ByteArray(16000)
        session.transcribeAndDeliver(
            bytes = pcm,
            transcriber = { _, _ ->
                session.cancel()
                "旧语音结果"
            },
            postMain = { it() }
        )

        assertEquals(listOf("checking"), states)
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        assertFalse(session.active)
    }

    @Test
    fun cancelDuringFailingTranscribeSuppressesErrorCallback() {
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val session = VoiceCapture.Session(
            key = "sk-test",
            onState = { },
            onResult = { results.add(it) },
            onError = { errors.add(it) }
        )

        val pcm = ByteArray(16000)
        session.transcribeAndDeliver(
            bytes = pcm,
            transcriber = { _, _ ->
                session.cancel()
                throw IllegalStateException("network timeout")
            },
            postMain = { it() }
        )

        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        assertFalse(session.active)
    }

    @Test
    fun cancelBeforeQueuedMainCallbackRunsSuppressesResultDelivery() {
        val queued = mutableListOf<() -> Unit>()
        val results = mutableListOf<String>()
        val session = VoiceCapture.Session(
            key = "sk-test",
            onState = { },
            onResult = { results.add(it) },
            onError = { }
        )

        val pcm = ByteArray(16000)
        session.transcribeAndDeliver(
            bytes = pcm,
            transcriber = { _, _ -> "延迟结果" },
            postMain = { queued.add(it) }
        )

        // Cancel after transcribe finished and posted to main thread, before main looper drains queue.
        session.cancel()
        queued.forEach { it() }
        assertTrue(results.isEmpty())
    }
}
