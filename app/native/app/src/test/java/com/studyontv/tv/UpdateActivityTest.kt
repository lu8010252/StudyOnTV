package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateActivityTest {
    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `cached apk matches expected sha256 case-insensitively and preserves file`() {
        val file = File.createTempFile("sotv-update-match", ".apk")
        try {
            val payload = "fake-apk-bytes-v1".toByteArray()
            file.writeBytes(payload)
            val digest = sha256Of(payload)

            assertTrue(UpdateActivity.isCachedApkMatching(file, digest))
            assertTrue(UpdateActivity.isCachedApkMatching(file, digest.uppercase()))
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `cached apk mismatch deletes stale file and returns false`() {
        val file = File.createTempFile("sotv-update-mismatch", ".apk")
        try {
            file.writeBytes("stale-apk-v1".toByteArray())
            val expected = sha256Of("new-apk-v2".toByteArray())

            assertFalse(UpdateActivity.isCachedApkMatching(file, expected))
            assertFalse("Stale cached APK must be deleted on SHA-256 mismatch", file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `blank expected sha256 accepts existing file and rejects missing file`() {
        val file = File.createTempFile("sotv-update-blank", ".apk")
        try {
            file.writeBytes("apk-without-manifest-hash".toByteArray())
            assertTrue(UpdateActivity.isCachedApkMatching(file, ""))
            assertTrue(UpdateActivity.isCachedApkMatching(file, "   "))
            file.delete()
            assertFalse(UpdateActivity.isCachedApkMatching(file, ""))
        } finally {
            file.delete()
        }
    }
}
