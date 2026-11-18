package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun releaseVersionIsNewerThanSameCoreBeta() {
        assertTrue(Updater.isNewer("0.4.4", "0.4.4-beta"))
        assertTrue(Updater.isNewer("v0.4.4", "0.4.4-beta"))
        assertFalse(Updater.isNewer("0.4.4-beta", "0.4.4"))
        assertFalse(Updater.isNewer("0.4.4-beta", "0.4.4-beta"))
    }

    @Test
    fun comparesCoreMajorMinorPatchAndPreReleaseSegments() {
        assertTrue(Updater.isNewer("0.4.5", "0.4.4"))
        assertTrue(Updater.isNewer("0.4.5-beta", "0.4.4"))
        assertTrue(Updater.isNewer("0.5.0", "0.4.99"))
        assertTrue(Updater.isNewer("1.0.0", "0.9.9-beta"))
        assertFalse(Updater.isNewer("0.4.4", "0.4.4"))
        assertFalse(Updater.isNewer("0.4.3", "0.4.4-beta"))
        assertTrue(Updater.isNewer("0.4.4-beta.2", "0.4.4-beta.1"))
        assertFalse(Updater.isNewer("0.4.4-beta.1", "0.4.4-beta.2"))
        assertFalse(Updater.isNewer("", "0.4.4"))
    }
}
