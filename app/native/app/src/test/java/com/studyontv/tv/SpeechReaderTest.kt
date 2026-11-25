package com.studyontv.tv

import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechReaderTest {
    @Test
    fun cloudSuccessUpdatesStatusAndStaleFailureDoesNotPolluteIt() {
        val req1 = SpeechReader.bumpGeneration()
        val req2 = SpeechReader.bumpGeneration()
        val started = System.currentTimeMillis()

        // Newer request starts cloud playback successfully.
        SpeechReader.notify(true, "deepseek", "ok", "zh-CN", "zh-CN", false, "", req2)
        val afterSuccess = SpeechReader.status()
        assertTrue(afterSuccess.getBoolean("ok"))
        assertEquals("deepseek", afterSuccess.getString("mode"))
        assertEquals("ok", afterSuccess.getString("reason"))
        assertTrue(afterSuccess.getLong("at") >= started)

        // Cancelled earlier request fails asynchronously: must not overwrite newer status.
        SpeechReader.notify(false, "edge", "cloud-unavailable", "zh-CN", "", true, "云端朗读失败：timeout", req1)
        val afterStaleFail = SpeechReader.status()
        assertTrue(afterStaleFail.getBoolean("ok"))
        assertEquals("deepseek", afterStaleFail.getString("mode"))
        assertEquals("ok", afterStaleFail.getString("reason"))
    }

    @Test
    fun validatePcmBufferSizeRejectsErrorCodesAndFloorsValidSize() {
        assertEquals(16 * 1024, SpeechReader.validatePcmBufferSize(4096))
        assertEquals(32 * 1024, SpeechReader.validatePcmBufferSize(32 * 1024))
    }

    @Test(expected = IllegalArgumentException::class)
    fun validatePcmBufferSizeRejectsNegativeErrorCode() {
        SpeechReader.validatePcmBufferSize(AudioTrack.ERROR_BAD_VALUE)
    }

    @Test
    fun validateAudioTrackStateAcceptsInitialized() {
        SpeechReader.validateAudioTrackState(AudioTrack.STATE_INITIALIZED)
    }

    @Test(expected = IllegalStateException::class)
    fun validateAudioTrackStateRejectsUninitialized() {
        SpeechReader.validateAudioTrackState(AudioTrack.STATE_UNINITIALIZED)
    }

    @Test
    fun validatePcmWriteDistinguishesInterruptFromHardwareError() {
        assertEquals(2048, SpeechReader.validatePcmWrite(2048, isCurrent = true))
        // Cancelled / interrupted write returns 0 cleanly without throwing.
        assertEquals(0, SpeechReader.validatePcmWrite(AudioTrack.ERROR_DEAD_OBJECT, isCurrent = false))
        assertEquals(0, SpeechReader.validatePcmWrite(0, isCurrent = false))
    }

    @Test(expected = IllegalStateException::class)
    fun validatePcmWriteThrowsOnActiveNegativeError() {
        SpeechReader.validatePcmWrite(AudioTrack.ERROR_DEAD_OBJECT, isCurrent = true)
    }

    @Test
    fun resolveRecognizeKeyBlocksInLocalOnlyAndReusesGradingKeyOtherwise() {
        val speechAuto = """{"recognizeMode":"auto"}"""
        val speechOwn = """{"recognizeMode":"deepseek","deepseekKey":"sk-speech-own"}"""
        val speechSystem = """{"recognizeMode":"system","deepseekKey":"sk-speech-own"}"""
        val gradingDeepseek = """{"provider":"deepseek","endpoint":"https://api.deepseek.com/v1","apiKey":"sk-grading-reused"}"""

        // EU Local-Only must always return empty string.
        assertEquals("", SpeechReader.resolveRecognizeKey(true, speechOwn) { gradingDeepseek })
        assertEquals("", SpeechReader.resolveRecognizeKey(true, speechAuto) { gradingDeepseek })

        // System mode returns empty string.
        assertEquals("", SpeechReader.resolveRecognizeKey(false, speechSystem) { gradingDeepseek })

        // Own key takes precedence.
        assertEquals("sk-speech-own", SpeechReader.resolveRecognizeKey(false, speechOwn) { gradingDeepseek })

        // Reuses grading key from supplied provider when own key is empty.
        assertEquals("sk-grading-reused", SpeechReader.resolveRecognizeKey(false, speechAuto) { gradingDeepseek })

        // Does not reuse non-DeepSeek provider key.
        val gradingOther = """{"provider":"openai","endpoint":"https://api.openai.com/v1","apiKey":"sk-openai"}"""
        assertEquals("", SpeechReader.resolveRecognizeKey(false, speechAuto) { gradingOther })
    }

    @Test
    fun cloudChunksAndSafeTruncateNeverSplitSurrogatePairs() {
        // Place emoji (2 UTF-16 code units) straddling index 149..150 (150-char boundary)
        val text = "a".repeat(149) + "📐" + "b".repeat(20)
        val chunks = SpeechReader.cloudChunks(text, "edge")
        assertEquals(2, chunks.size)
        assertEquals(text, chunks.joinToString(""))
        assertFalse(Character.isHighSurrogate(chunks[0].last()))
        assertFalse(Character.isLowSurrogate(chunks[1].first()))

        val truncated = SpeechReader.safeTruncate("a".repeat(9) + "📐", 10)
        assertEquals("a".repeat(9), truncated)
    }

    @Test
    fun invalidCloudResponseIsNeverCachedAndCorruptedDiskCacheSelfHeals() {
        val dir = java.nio.file.Files.createTempDirectory("sotv-tts-cache-test").toFile()
        try {
            val cacheKey = "test-deepseek-key-${System.nanoTime()}"
            val badJson = """{"error":{"message":"rate limit exceeded on gateway 200 wrapper"}}""".toByteArray(Charsets.UTF_8)
            val validWav = VoiceCapture.wav(ByteArray(320) { 1 })

            // 1. Bad HTTP-200 JSON must fail validation and NOT be cached.
            var failed = false
            try {
                SpeechReader.synthWithCacheCore(dir, "deepseek", cacheKey) { badJson }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(0, dir.listFiles()?.size ?: 0)

            // 2. Subsequent valid WAV succeeds and writes atomically (no leftover .tmp files).
            val ok = SpeechReader.synthWithCacheCore(dir, "deepseek", cacheKey) { validWav }
            assertEquals(validWav.size, ok.size)
            val files = dir.listFiles().orEmpty()
            assertEquals(1, files.size)
            assertTrue(files[0].name.endsWith(".bin"))

            // 3. Corrupt disk file (0 bytes) -> readDiskCacheFromDir deletes it and returns null.
            files[0].writeBytes(ByteArray(0))
            assertEquals(null, SpeechReader.readDiskCacheFromDir(dir, "deepseek", cacheKey))
            assertFalse(files[0].exists())

            // 4. MP3 validation rejects JSON/HTML and accepts valid MPEG frame sync.
            assertFalse(SpeechReader.isValidCachedAudio("edge", badJson + ByteArray(200) { ' '.code.toByte() }))
            val fakeMp3 = ByteArray(256).apply { this[0] = 0xFF.toByte(); this[1] = 0xF3.toByte() }
            assertTrue(SpeechReader.isValidCachedAudio("edge", fakeMp3))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun requireCloudHttpAllowedBlocksInLocalOnlyMode() {
        SpeechReader.requireCloudHttpAllowed("语音合成", localOnly = true)
    }

    @Test
    fun openCloudConnectionDisablesRedirectsAndEnforcesLocalOnly() {
        val conn = SpeechReader.openCloudConnection("https://example.invalid/tts", "语音合成", localOnly = false)
        try {
            assertFalse(conn.instanceFollowRedirects)
        } finally {
            conn.disconnect()
        }

        var blocked = false
        try {
            SpeechReader.openCloudConnection("https://example.invalid/tts", "语音合成", localOnly = true)
        } catch (_: IllegalStateException) {
            blocked = true
        }
        assertTrue(blocked)
    }

    @Test
    fun applySpeechKeyMutationsClearsBeforeApplyingNewKeys() {
        val next = org.json.JSONObject()
            .put("azureKey", "old-azure")
            .put("aliyunSecret", "old-aliyun")
            .put("deepseekKey", "old-ds")

        // clearKeys=true + new deepseekKey: clears old keys first, keeps newly submitted deepseekKey.
        SpeechReader.applySpeechKeyMutations(
            next,
            org.json.JSONObject().put("clearKeys", true).put("deepseekKey", "  sk-new-ds  ")
        )
        assertFalse(next.has("azureKey"))
        assertFalse(next.has("aliyunSecret"))
        assertEquals("sk-new-ds", next.optString("deepseekKey"))

        // clearKeys=true alone: removes all keys.
        SpeechReader.applySpeechKeyMutations(next, org.json.JSONObject().put("clearKeys", true))
        assertFalse(next.has("deepseekKey"))
    }
}
