package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdaterTest {
    @Before
    fun resetState() {
        Updater.resetInstallState()
    }

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

    @Test
    fun remoteInstallPreconditionBlocksLocalOnlyAndMissingUpdate() {
        val validInfo = Updater.Info(
            latest = "0.5.5",
            current = "0.5.4",
            force = false,
            notes = "notes",
            url = "https://www.pgyer.com/app/installUpdate/test"
        )
        val localOnlyRes = Updater.validateRemoteInstallPrecondition(localOnly = true, newerInfo = validInfo)
        assertEquals(403, localOnlyRes?.first)
        assertTrue(localOnlyRes!!.second.contains("纯本地版本"))

        val noUpdateRes = Updater.validateRemoteInstallPrecondition(localOnly = false, newerInfo = null)
        assertEquals(400, noUpdateRes?.first)
        assertTrue(noUpdateRes!!.second.contains("没有可安装的新版本"))

        val blankUrlRes = Updater.validateRemoteInstallPrecondition(localOnly = false, newerInfo = validInfo.copy(url = ""))
        assertEquals(400, blankUrlRes?.first)

        assertNull(Updater.validateRemoteInstallPrecondition(localOnly = false, newerInfo = validInfo))
    }

    @Test
    fun remoteInstallStateMachinePreventsReentryAndSerializesStatusFields() {
        val t0 = 1_000_000L
        assertEquals("idle", Updater.currentInstallSnapshot(t0).state)
        assertTrue(Updater.tryBeginInstall("0.5.5", nowMs = t0))
        assertEquals("downloading", Updater.currentInstallSnapshot(t0).state)
        assertEquals("0.5.5", Updater.currentInstallSnapshot(t0).version)

        // Concurrent re-entry while downloading must be rejected
        assertFalse(Updater.tryBeginInstall("0.5.5", nowMs = t0 + 1_000L))

        Updater.updateInstallState("installing", 150, "请在设备上确认安装", "0.5.5", nowMs = t0 + 5_000L)
        val json = Updater.appendInstallStatus(JSONObject(), Updater.currentInstallSnapshot(t0 + 6_000L))
        assertEquals("installing", json.getString("installState"))
        assertEquals(100, json.getInt("installProgress"))
        assertEquals("请在设备上确认安装", json.getString("installMessage"))
        assertEquals("0.5.5", json.getString("installVersion"))

        // Concurrent re-entry while installing within cooldown (15s) must be rejected
        assertFalse(Updater.tryBeginInstall("0.5.5", nowMs = t0 + 5_000L + Updater.INSTALLING_RETRY_COOLDOWN_MS - 1L))

        // After cooldown elapses (e.g. user canceled installer or Android 10+ silently blocked background start), retry is allowed
        assertTrue(Updater.tryBeginInstall("0.5.5", nowMs = t0 + 5_000L + Updater.INSTALLING_RETRY_COOLDOWN_MS))
        assertEquals("downloading", Updater.currentInstallSnapshot(t0 + 5_000L + Updater.INSTALLING_RETRY_COOLDOWN_MS).state)

        // After error, retry is allowed immediately
        Updater.updateInstallState("error", 0, "网络中断", "0.5.5", nowMs = t0 + 30_000L)
        assertTrue(Updater.tryBeginInstall("0.5.5", nowMs = t0 + 30_001L))
        assertEquals("downloading", Updater.currentInstallSnapshot(t0 + 30_001L).state)
    }

    @Test
    fun installingStateAutoConvergesToIdleAfterTtl() {
        val t0 = 2_000_000L
        Updater.updateInstallState("installing", 100, "请在设备屏幕上确认安装", "0.5.5", nowMs = t0)
        // Before TTL (60s): remains installing
        assertEquals("installing", Updater.currentInstallSnapshot(t0 + Updater.INSTALLING_TTL_MS - 1L).state)
        // At/after TTL: automatically converges back to idle so Web UI stops polling and unlocks button
        val expired = Updater.currentInstallSnapshot(t0 + Updater.INSTALLING_TTL_MS)
        assertEquals("idle", expired.state)
        assertEquals(0, expired.progress)
        assertEquals("", expired.message)
        assertTrue(Updater.tryBeginInstall("0.5.5", nowMs = t0 + Updater.INSTALLING_TTL_MS + 1L))
    }

    @Test
    fun resolveSilentCapabilityCoversAllThreeModes() {
        val pkg = "com.studyontv.tv"
        // Device Owner always wins (Android 9+ / API 28+)
        assertEquals(Updater.SILENT_DEVICE_OWNER, Updater.resolveSilentCapability(true, 28, null, pkg))
        assertEquals(Updater.SILENT_DEVICE_OWNER, Updater.resolveSilentCapability(true, 34, pkg, pkg))

        // Android 12+ (API 31+) installed by self -> self_update
        assertEquals(Updater.SILENT_SELF_UPDATE, Updater.resolveSilentCapability(false, 31, pkg, pkg))
        assertEquals(Updater.SILENT_SELF_UPDATE, Updater.resolveSilentCapability(false, 34, pkg, pkg))

        // Below Android 12 -> confirm even if installer matches
        assertEquals(Updater.SILENT_CONFIRM, Updater.resolveSilentCapability(false, 30, pkg, pkg))

        // Android 12+ installed by store / adb / unknown -> confirm
        assertEquals(Updater.SILENT_CONFIRM, Updater.resolveSilentCapability(false, 34, "com.android.vending", pkg))
        assertEquals(Updater.SILENT_CONFIRM, Updater.resolveSilentCapability(false, 34, null, pkg))
        assertEquals(Updater.SILENT_CONFIRM, Updater.resolveSilentCapability(false, 34, "", ""))
    }

    @Test
    fun checkApkReadyValidatesFileVersionAndSha256() {
        val sha = "a1b2c3d4e5f6"
        // Exact match (case-insensitive SHA-256)
        assertTrue(Updater.checkApkReady(true, 1024L, "0.5.6", sha, "0.5.6", "A1B2C3D4E5F6"))
        // Empty expected SHA-256 passes when file + version match
        assertTrue(Updater.checkApkReady(true, 1024L, "0.5.6", "", "0.5.6", ""))
        // Missing or empty file fails
        assertFalse(Updater.checkApkReady(false, 1024L, "0.5.6", sha, "0.5.6", sha))
        assertFalse(Updater.checkApkReady(true, 0L, "0.5.6", sha, "0.5.6", sha))
        // Version mismatch or blank version fails
        assertFalse(Updater.checkApkReady(true, 1024L, "0.5.6", sha, "0.5.5", sha))
        assertFalse(Updater.checkApkReady(true, 1024L, "", sha, "", sha))
        // SHA-256 mismatch fails
        assertFalse(Updater.checkApkReady(true, 1024L, "0.5.6", sha, "0.5.6", "deadbeef"))
        assertFalse(Updater.checkApkReady(true, 1024L, "0.5.6", sha, "0.5.6", ""))
    }

    @Test
    fun autoDownloadAndPreDownloadEnforceLocalOnlyIsolation() {
        // EU Local-Only always forces autoDownload = false
        assertFalse(Updater.resolveAutoDownloadSetting(requested = true, localOnly = true))
        assertFalse(Updater.resolveAutoDownloadSetting(requested = false, localOnly = true))
        assertTrue(Updater.resolveAutoDownloadSetting(requested = true, localOnly = false))
        assertFalse(Updater.resolveAutoDownloadSetting(requested = false, localOnly = false))

        val info = Updater.Info(
            latest = "0.5.6",
            current = "0.5.5",
            force = false,
            notes = "notes",
            url = "https://www.pgyer.com/app/installUpdate/test",
            sha256 = "abc123"
        )
        assertTrue(Updater.shouldPreDownload(localOnly = false, autoDownload = true, newerInfo = info, apkReady = false))
        assertFalse(Updater.shouldPreDownload(localOnly = true, autoDownload = true, newerInfo = info, apkReady = false))
        assertFalse(Updater.shouldPreDownload(localOnly = false, autoDownload = false, newerInfo = info, apkReady = false))
        assertFalse(Updater.shouldPreDownload(localOnly = false, autoDownload = true, newerInfo = null, apkReady = false))
        assertFalse(Updater.shouldPreDownload(localOnly = false, autoDownload = true, newerInfo = info.copy(url = ""), apkReady = false))
        assertFalse(Updater.shouldPreDownload(localOnly = false, autoDownload = true, newerInfo = info, apkReady = true))
    }

    @Test
    fun installReceiverCallbacksSyncUpdaterState() {
        val t0 = 3_000_000L
        Updater.updateInstallState("installing", 100, "正在后台静默安装 v0.5.6…", "0.5.6", nowMs = t0)

        Updater.onInstallPendingUserAction(nowMs = t0 + 1_000L)
        val pending = Updater.currentInstallSnapshot(t0 + 1_000L)
        assertEquals("installing", pending.state)
        assertTrue(pending.message.contains("确认安装"))
        assertEquals("0.5.6", pending.version)

        Updater.onInstallResult(success = false, message = "安装被取消", nowMs = t0 + 2_000L)
        val failed = Updater.currentInstallSnapshot(t0 + 2_000L)
        assertEquals("error", failed.state)
        assertEquals("安装被取消", failed.message)
        assertEquals("0.5.6", failed.version)

        Updater.updateInstallState("installing", 100, "正在后台静默安装 v0.5.6…", "0.5.6", nowMs = t0 + 3_000L)
        Updater.onInstallResult(success = true, message = "安装成功", nowMs = t0 + 4_000L)
        val done = Updater.currentInstallSnapshot(t0 + 4_000L)
        assertEquals("idle", done.state)
        assertEquals(0, done.progress)
    }

    @Test
    fun compareAndUpdateInstallStateDoesNotOverwriteEarlierReceiverCallback() {
        val t0 = 4_000_000L
        // Normal path: no receiver callback intervened -> CAS updates message
        val before1 = Updater.updateInstallState("installing", 100, "正在后台静默安装 v0.5.6…", "0.5.6", nowMs = t0)
        assertTrue(Updater.compareAndUpdateInstallState(before1, "installing", 100, "已提交后台静默更新", "0.5.6", nowMs = t0 + 100L))
        assertEquals("已提交后台静默更新", Updater.currentInstallSnapshot(t0 + 100L).message)

        // Race path 1: receiver reports failure BEFORE install() returns -> CAS must NOT overwrite error back to installing
        val before2 = Updater.updateInstallState("installing", 100, "正在后台静默安装 v0.5.6…", "0.5.6", nowMs = t0 + 200L)
        Updater.onInstallResult(success = false, message = "安装被取消", nowMs = t0 + 250L)
        assertFalse(Updater.compareAndUpdateInstallState(before2, "installing", 100, "已提交后台静默更新", "0.5.6", nowMs = t0 + 300L))
        val afterRace1 = Updater.currentInstallSnapshot(t0 + 300L)
        assertEquals("error", afterRace1.state)
        assertEquals("安装被取消", afterRace1.message)

        // Race path 2: receiver reports STATUS_PENDING_USER_ACTION BEFORE install() returns -> CAS must NOT overwrite confirm prompt
        val before3 = Updater.updateInstallState("installing", 100, "正在后台静默安装 v0.5.6…", "0.5.6", nowMs = t0 + 400L)
        Updater.onInstallPendingUserAction(nowMs = t0 + 450L)
        assertFalse(Updater.compareAndUpdateInstallState(before3, "installing", 100, "已提交后台静默更新", "0.5.6", nowMs = t0 + 500L))
        val afterRace2 = Updater.currentInstallSnapshot(t0 + 500L)
        assertEquals("installing", afterRace2.state)
        assertTrue(afterRace2.message.contains("确认安装"))
    }
}
