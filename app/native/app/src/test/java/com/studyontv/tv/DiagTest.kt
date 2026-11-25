package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DiagTest {

    @Test
    fun rotateInDirPreservesStrictNewestToOldestArchiveOrder() {
        val dir = Files.createTempDirectory("sotv-diag-test").toFile()
        try {
            val current = File(dir, "sotv.log")

            // Rotation 1: A -> .1
            current.writeText("LOG-A-123456")
            Diag.rotateInDir(dir, maxBytes = 10L, keep = 3)
            assertFalse(current.exists())
            assertEquals("LOG-A-123456", File(dir, "sotv.log.1").readText())
            assertFalse(File(dir, "sotv.log.2").exists())
            assertFalse(File(dir, "sotv.log.3").exists())

            // Rotation 2: B -> .1, A -> .2
            current.writeText("LOG-B-123456")
            Diag.rotateInDir(dir, maxBytes = 10L, keep = 3)
            assertEquals("LOG-B-123456", File(dir, "sotv.log.1").readText())
            assertEquals("LOG-A-123456", File(dir, "sotv.log.2").readText())
            assertFalse(File(dir, "sotv.log.3").exists())

            // Rotation 3: C -> .1, B -> .2, A -> .3
            current.writeText("LOG-C-123456")
            Diag.rotateInDir(dir, maxBytes = 10L, keep = 3)
            assertEquals("LOG-C-123456", File(dir, "sotv.log.1").readText())
            assertEquals("LOG-B-123456", File(dir, "sotv.log.2").readText())
            assertEquals("LOG-A-123456", File(dir, "sotv.log.3").readText())

            // Rotation 4: D -> .1, C -> .2, B -> .3 (A evicted)
            current.writeText("LOG-D-123456")
            Diag.rotateInDir(dir, maxBytes = 10L, keep = 3)
            assertEquals("LOG-D-123456", File(dir, "sotv.log.1").readText())
            assertEquals("LOG-C-123456", File(dir, "sotv.log.2").readText())
            assertEquals("LOG-B-123456", File(dir, "sotv.log.3").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingCrashUploadWritesBackOnNetworkExceptionAndHttpError() {
        var rewritten: String? = null
        val ok = Diag.runPendingCrashUpload(
            readAndClear = { "crash-summary-1" },
            writePending = { rewritten = it },
            buildZip = { it.toByteArray() },
            upload = { 200 }
        )
        assertTrue(ok)
        assertNull(rewritten)

        val httpFail = Diag.runPendingCrashUpload(
            readAndClear = { "crash-summary-2" },
            writePending = { rewritten = it },
            buildZip = { it.toByteArray() },
            upload = { 503 }
        )
        assertFalse(httpFail)
        assertEquals("crash-summary-2", rewritten)

        rewritten = null
        val ioFail = Diag.runPendingCrashUpload(
            readAndClear = { "crash-summary-3" },
            writePending = { rewritten = it },
            buildZip = { it.toByteArray() },
            upload = { throw IOException("network unreachable") }
        )
        assertFalse(ioFail)
        assertEquals("crash-summary-3", rewritten)
    }
}
