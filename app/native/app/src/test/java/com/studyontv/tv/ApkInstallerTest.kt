package com.studyontv.tv

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerTest {

    @Test
    fun validateDownloadedSizeDetectsEmptyAndTruncatedDownloads() {
        assertNotNull(ApkInstaller.validateDownloadedSize(done = 0L, contentLength = -1L))
        assertNotNull(ApkInstaller.validateDownloadedSize(done = 0L, contentLength = 1000L))
        val truncated = ApkInstaller.validateDownloadedSize(done = 500L, contentLength = 1000L)
        assertNotNull(truncated)
        assertTrue(truncated!!.contains("下载不完整"))

        assertNull(ApkInstaller.validateDownloadedSize(done = 1000L, contentLength = 1000L))
        assertNull(ApkInstaller.validateDownloadedSize(done = 2048L, contentLength = -1L))
    }

    @Test
    fun checkLocalOnlyBlockedGuardsInstallAndDownload() {
        val blocked = ApkInstaller.checkLocalOnlyBlocked(localOnly = true)
        assertNotNull(blocked)
        assertTrue(blocked!!.contains("EU Local-Only"))
        assertNull(ApkInstaller.checkLocalOnlyBlocked(localOnly = false))
    }
}
