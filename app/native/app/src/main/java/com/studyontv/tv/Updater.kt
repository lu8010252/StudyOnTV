package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * 版本检查：读二改版自己的 GitHub Release（OWNER_REPO）。
 * 上游改用了作者自己的蒲公英账号，那会把上游的包推给二改版，所以这里不用；Pgyer.kt 保留但不再调用，方便以后合并上游。
 */
object Updater {
    const val OWNER_REPO = "lu8010252/StudyOnTV"
    private const val GITHUB_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val MANIFEST_PATH = "$OWNER_REPO/main/update.json"
    private val MANIFEST_URLS = listOf(
        "https://raw.githubusercontent.com/$MANIFEST_PATH",
        "https://cdn.jsdelivr.net/gh/$OWNER_REPO@main/update.json",
        "https://fastly.jsdelivr.net/gh/$OWNER_REPO@main/update.json",
        "https://gcore.jsdelivr.net/gh/$OWNER_REPO@main/update.json"
    )
    /** 公益 GitHub 加速镜像，随时可能失效；家长可在管理面板中修改 */
    val DEFAULT_MIRRORS = emptyList<String>()
    private const val CACHE_MS = 6L * 60 * 60 * 1000

    data class Info(
        val latest: String,
        val current: String,
        val force: Boolean,
        val notes: String,
        val url: String,
        val sha256: String = "",
        val source: String = ""
    )

    fun api(ctx: Context): String = Prefs.updateApi(ctx)

    fun mirrors(ctx: Context): List<String> {
        val saved = Prefs.updateMirrors(ctx) ?: return DEFAULT_MIRRORS
        return saved.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("https://") }
            .map { if (it.endsWith("/")) it else "$it/" }.distinct()
    }

    /** 可用的 APK 下载地址：上次成功的线路优先，其次直连，再其次各个镜像 */
    fun downloadCandidates(ctx: Context, url: String): List<Pair<String, String>> =
        listOf("GitHub" to url)

    fun check(ctx: Context, forceRefresh: Boolean = false, onResult: (Info?) -> Unit) {
        if (LocalOnlyGuard.enabled) {
            onResult(null)
            return
        }
        val cachedAt = Prefs.updateCheckedAt(ctx)
        val cached = cachedInfo(ctx)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            onResult(cached)
            return
        }
        Thread { onResult(checkNow(ctx).first) }.apply { isDaemon = true }.start()
    }

    private val checkingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val checking: Boolean get() = checkingFlag.get()

    /** Web 端一键在线更新的线程安全状态机快照（ST-603 / #184）。 */
    data class InstallSnapshot(
        val state: String = "idle",      // "idle" | "downloading" | "installing" | "error"
        val progress: Int = 0,           // 0..100
        val message: String = "",
        val version: String = "",
        val updatedAtMs: Long = 0L
    )

    internal const val INSTALLING_TTL_MS = 60_000L
    internal const val INSTALLING_RETRY_COOLDOWN_MS = 15_000L

    const val SILENT_DEVICE_OWNER = "device_owner"
    const val SILENT_SELF_UPDATE = "self_update"
    const val SILENT_CONFIRM = "confirm"

    /**
     * 判断当前设备的官方合规静默更新能力（ST-607）：
     * - Device Owner（Android 9+）：系统原生静默覆盖安装 -> "device_owner"
     * - Android 12+（API 31+）且安装来源为自身包名：官方同源静默自更新 -> "self_update"
     * - 其他情况：标准系统确认框 -> "confirm"
     */
    internal fun resolveSilentCapability(
        isDeviceOwner: Boolean,
        sdkInt: Int,
        installerPackage: String?,
        ownPackage: String
    ): String = when {
        isDeviceOwner -> SILENT_DEVICE_OWNER
        sdkInt >= 31 && ownPackage.isNotBlank() && installerPackage == ownPackage -> SILENT_SELF_UPDATE
        else -> SILENT_CONFIRM
    }

    fun silentCapability(ctx: Context): String {
        val isOwner = try { UninstallGuard.owner(ctx) } catch (_: Exception) { false }
        val ownPkg = try { ctx.packageName.orEmpty() } catch (_: Exception) { "" }
        val installerPkg = if (Build.VERSION.SDK_INT >= 30 && ownPkg.isNotEmpty()) {
            try {
                ctx.packageManager.getInstallSourceInfo(ownPkg).installingPackageName
            } catch (_: Exception) {
                null
            }
        } else null
        return resolveSilentCapability(isOwner, Build.VERSION.SDK_INT, installerPkg, ownPkg)
    }

    internal fun checkApkReady(
        fileExists: Boolean,
        fileLength: Long,
        expectedVersion: String,
        expectedSha256: String,
        savedVersion: String,
        savedSha256: String
    ): Boolean {
        val targetVer = expectedVersion.trim()
        if (!fileExists || fileLength <= 0L || targetVer.isEmpty()) return false
        if (savedVersion.trim() != targetVer) return false
        val wantSha = expectedSha256.trim()
        if (wantSha.isNotEmpty() && !savedSha256.trim().equals(wantSha, ignoreCase = true)) return false
        return true
    }

    fun isApkReady(ctx: Context, info: Info?): Boolean {
        if (LocalOnlyGuard.enabled || info == null) return false
        val f = ApkInstaller.apkFile(ctx)
        val exists = f.exists() && f.isFile
        return checkApkReady(
            fileExists = exists,
            fileLength = if (exists) f.length() else 0L,
            expectedVersion = info.latest,
            expectedSha256 = info.sha256,
            savedVersion = Prefs.readyApkVersion(ctx),
            savedSha256 = Prefs.readyApkSha256(ctx)
        )
    }

    internal fun markApkReady(ctx: Context, version: String, sha256: String) {
        Prefs.setReadyApkVersion(ctx, version.trim())
        Prefs.setReadyApkSha256(ctx, sha256.trim())
    }

    internal fun clearReadyApk(ctx: Context) {
        Prefs.setReadyApkVersion(ctx, "")
        Prefs.setReadyApkSha256(ctx, "")
    }

    internal fun resolveAutoDownloadSetting(requested: Boolean, localOnly: Boolean): Boolean =
        if (localOnly) false else requested

    internal fun shouldPreDownload(
        localOnly: Boolean,
        autoDownload: Boolean,
        newerInfo: Info?,
        apkReady: Boolean
    ): Boolean = !localOnly && autoDownload && newerInfo != null && newerInfo.url.isNotBlank() && !apkReady

    private val installSnapshotRef = AtomicReference(InstallSnapshot())

    internal fun currentInstallSnapshot(nowMs: Long = System.currentTimeMillis()): InstallSnapshot {
        val current = installSnapshotRef.get()
        if (current.state == "installing" && current.updatedAtMs > 0L && nowMs - current.updatedAtMs >= INSTALLING_TTL_MS) {
            val idle = InstallSnapshot()
            if (installSnapshotRef.compareAndSet(current, idle)) return idle
            return installSnapshotRef.get()
        }
        return current
    }

    internal fun resetInstallState() {
        installSnapshotRef.set(InstallSnapshot())
    }

    internal fun updateInstallState(
        state: String,
        progress: Int,
        message: String,
        version: String,
        nowMs: Long = System.currentTimeMillis()
    ): InstallSnapshot {
        val next = InstallSnapshot(state, progress.coerceIn(0, 100), message, version, nowMs)
        installSnapshotRef.set(next)
        return next
    }

    internal fun compareAndUpdateInstallState(
        expected: InstallSnapshot,
        state: String,
        progress: Int,
        message: String,
        version: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = installSnapshotRef.compareAndSet(
        expected,
        InstallSnapshot(state, progress.coerceIn(0, 100), message, version, nowMs)
    )

    internal fun onInstallPendingUserAction(nowMs: Long = System.currentTimeMillis()) {
        val cur = currentInstallSnapshot(nowMs)
        if (cur.state == "installing") {
            updateInstallState("installing", 100, "安装包已就绪，请在设备屏幕上确认安装", cur.version, nowMs)
        }
    }

    internal fun onInstallResult(success: Boolean, message: String = "", nowMs: Long = System.currentTimeMillis()) {
        val cur = currentInstallSnapshot(nowMs)
        if (success) {
            resetInstallState()
        } else {
            updateInstallState("error", 100, message.ifBlank { "安装失败" }, cur.version, nowMs)
        }
    }

    /** 校验是否允许发起远程在线更新；返回 null 表示通过，否则返回 (HTTP 状态码, 错误文案)。 */
    internal fun validateRemoteInstallPrecondition(localOnly: Boolean, newerInfo: Info?): Pair<Int, String>? = when {
        localOnly -> 403 to "纯本地版本不支持在线更新"
        newerInfo == null -> 400 to "当前没有可安装的新版本，请先检查更新"
        newerInfo.url.isBlank() -> 400 to "当前版本缺少下载地址，请先检查更新"
        else -> null
    }

    /**
     * 原子尝试进入 downloading 状态：
     * - downloading 期间严格拒绝并发重入；
     * - installing 期间在冷却期内拒绝重入，超过冷却期（用户取消安装或系统静默拦截后台拉起）允许重新发起。
     */
    internal fun tryBeginInstall(
        version: String,
        nowMs: Long = System.currentTimeMillis(),
        message: String = "正在准备下载 v$version…"
    ): Boolean {
        while (true) {
            val current = installSnapshotRef.get()
            if (current.state == "downloading") return false
            if (current.state == "installing" &&
                (current.updatedAtMs <= 0L || nowMs - current.updatedAtMs < INSTALLING_RETRY_COOLDOWN_MS)
            ) {
                return false
            }
            val next = InstallSnapshot(
                state = "downloading",
                progress = 0,
                message = message,
                version = version,
                updatedAtMs = nowMs
            )
            if (installSnapshotRef.compareAndSet(current, next)) return true
        }
    }

    /**
     * 发现新版本且家长开启了「后台自动预下载」时，静默在后台下载并校验 APK 到本地缓存（ST-607）。
     * 与 [startRemoteInstall] 共享 CAS 防并发重入；预下载完成后回到 idle，不弹窗打扰。
     */
    fun preDownloadIfNeeded(ctx: Context, info: Info?) {
        val newer = newerThanCurrent(ctx, info)
        val autoEnabled = resolveAutoDownloadSetting(Prefs.autoDownloadUpdate(ctx), LocalOnlyGuard.enabled)
        if (!shouldPreDownload(LocalOnlyGuard.enabled, autoEnabled, newer, isApkReady(ctx, newer))) {
            return
        }
        val targetInfo = newer!!
        if (!tryBeginInstall(targetInfo.latest, message = "正在后台预下载 v${targetInfo.latest}…")) {
            return
        }
        val appCtx = ctx.applicationContext ?: ctx
        Thread {
            try {
                Diag.info("Update", "starting background pre-download for v${targetInfo.latest}")
                val file = ApkInstaller.download(
                    appCtx,
                    targetInfo.url,
                    targetInfo.sha256,
                    onSource = { src ->
                        val cur = currentInstallSnapshot()
                        if (cur.state == "downloading") {
                            updateInstallState("downloading", cur.progress, "正在后台从 $src 预下载 v${targetInfo.latest}…", targetInfo.latest)
                        }
                    },
                    onProgress = { percent ->
                        val pct = if (percent < 0) currentInstallSnapshot().progress else percent.coerceIn(0, 100)
                        val msg = if (percent < 0) "正在后台预下载 v${targetInfo.latest}…" else "正在后台预下载 v${targetInfo.latest} ($pct%)"
                        updateInstallState("downloading", pct, msg, targetInfo.latest)
                    }
                )
                if (file != null) {
                    markApkReady(appCtx, targetInfo.latest, targetInfo.sha256.ifBlank { ApkInstaller.lastDownloadSha256 })
                    Diag.info("Update", "background pre-download ready for v${targetInfo.latest}")
                } else {
                    Diag.warn("Update", "background pre-download failed: ${ApkInstaller.lastError}")
                }
                resetInstallState()
            } catch (e: Exception) {
                Diag.warn("Update", "background pre-download error: ${e.javaClass.simpleName} ${e.message}")
                resetInstallState()
            }
        }.apply { isDaemon = true }.start()
    }

    internal fun appendInstallStatus(target: JSONObject, snap: InstallSnapshot = currentInstallSnapshot()): JSONObject =
        target.put("installState", snap.state)
            .put("installProgress", snap.progress)
            .put("installMessage", snap.message)
            .put("installVersion", snap.version)

    /**
     * Web 家长端一键触发设备在线更新（ST-603 / #184）：
     * 1. EU Local-Only 直接返回 403；
     * 2. 无新版本返回 400；
     * 3. 防并发重入，后台复用 ApkInstaller.download + 校验，完成后拉起 UpdateActivity（带本地 apkPath）或回退系统安装会话。
     */
    fun startRemoteInstall(ctx: Context): Pair<Int, JSONObject> {
        val info = newerThanCurrent(ctx, cachedInfo(ctx))
        validateRemoteInstallPrecondition(LocalOnlyGuard.enabled, info)?.let { (code, message) ->
            return code to JSONObject().put("error", message)
        }
        val targetInfo = info!!
        if (!tryBeginInstall(targetInfo.latest)) {
            return 200 to status(ctx)
        }
        val appCtx = ctx.applicationContext ?: ctx
        Thread {
            try {
                val cachedApk = ApkInstaller.apkFile(appCtx)
                val expectedSha = targetInfo.sha256.ifBlank { Prefs.readyApkSha256(appCtx) }
                val readyFile = if (
                    isApkReady(appCtx, targetInfo) &&
                    UpdateActivity.isCachedApkMatching(cachedApk, expectedSha, deleteOnMismatch = true)
                ) {
                    updateInstallState("installing", 100, "已命中本地预下载安装包 v${targetInfo.latest}，跳过下载直接安装", targetInfo.latest)
                    cachedApk
                } else {
                    clearReadyApk(appCtx)
                    val downloaded = ApkInstaller.download(
                        appCtx,
                        targetInfo.url,
                        targetInfo.sha256,
                        onSource = { src ->
                            val cur = currentInstallSnapshot()
                            if (cur.state == "downloading") {
                                updateInstallState("downloading", cur.progress, "正在从 $src 下载 v${targetInfo.latest}…", targetInfo.latest)
                            }
                        },
                        onProgress = { percent ->
                            val pct = if (percent < 0) currentInstallSnapshot().progress else percent.coerceIn(0, 100)
                            val msg = if (percent < 0) "正在下载 v${targetInfo.latest}…" else "正在下载 v${targetInfo.latest} ($pct%)"
                            updateInstallState("downloading", pct, msg, targetInfo.latest)
                        }
                    )
                    if (downloaded != null) {
                        markApkReady(appCtx, targetInfo.latest, targetInfo.sha256.ifBlank { ApkInstaller.lastDownloadSha256 })
                    }
                    downloaded
                }

                if (readyFile == null) {
                    val err = ApkInstaller.lastError.ifBlank { "下载失败，请检查设备网络后重试" }
                    updateInstallState("error", 0, err, targetInfo.latest)
                    return@Thread
                }

                val capability = silentCapability(appCtx)
                if (capability == SILENT_DEVICE_OWNER || capability == SILENT_SELF_UPDATE) {
                    val beforeSubmit = updateInstallState("installing", 100, "正在后台静默安装 v${targetInfo.latest}…", targetInfo.latest)
                    Prefs.setUpdateInProgress(appCtx, true)
                    val submitted = try { ApkInstaller.install(appCtx, readyFile) } catch (_: Exception) { false }
                    if (submitted) {
                        compareAndUpdateInstallState(beforeSubmit, "installing", 100, "已提交后台静默更新 v${targetInfo.latest}，安装完成后服务将自动恢复", targetInfo.latest)
                    } else {
                        Prefs.setUpdateInProgress(appCtx, false)
                        val reason = ApkInstaller.lastError.ifBlank { "后台提交安装会话失败，请在设备上手动完成安装" }
                        compareAndUpdateInstallState(beforeSubmit, "error", 100, reason, targetInfo.latest)
                    }
                    return@Thread
                }

                val beforeLaunch = updateInstallState("installing", 100, "安装包已下载完成，正在拉起设备安装界面…", targetInfo.latest)
                GateService.allowInstallPage()
                val launched = try {
                    val intent = Intent(appCtx, UpdateActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("payload", toJson(targetInfo).toString())
                        putExtra("apkPath", readyFile.absolutePath)
                        putExtra("auto", true)
                    }
                    appCtx.startActivity(intent)
                    true
                } catch (e: Exception) {
                    Diag.warn("Update", "remote startActivity(UpdateActivity) blocked: ${e.javaClass.simpleName} ${e.message}")
                    false
                }

                if (launched) {
                    compareAndUpdateInstallState(beforeLaunch, "installing", 100, "安装包已下载完成，请在设备屏幕上确认安装", targetInfo.latest)
                } else {
                    val fallbackOk = try { ApkInstaller.install(appCtx, readyFile) } catch (_: Exception) { false }
                    if (fallbackOk) {
                        compareAndUpdateInstallState(beforeLaunch, "installing", 100, "安装包已提交系统安装器，请在设备屏幕上确认安装", targetInfo.latest)
                    } else {
                        val reason = ApkInstaller.lastError.ifBlank { "系统限制了后台拉起安装界面，请在电视/平板上打开应用完成安装" }
                        compareAndUpdateInstallState(beforeLaunch, "error", 100, reason, targetInfo.latest)
                    }
                }
            } catch (e: Exception) {
                Diag.error("Update", "remote install task failed", e)
                updateInstallState("error", 0, "在线更新失败：${e.message ?: e.javaClass.simpleName}", targetInfo.latest)
            }
        }.apply { isDaemon = true }.start()
        return 200 to status(ctx)
    }

    /** 管理面板“立即检查”：后台执行，面板轮询 status()（不能加锁，否则会等正在进行的检查） */
    fun startCheck(ctx: Context) {
        if (LocalOnlyGuard.enabled) return
        if (!checkingFlag.compareAndSet(false, true)) return
        Thread { try { checkNow(ctx) } finally { checkingFlag.set(false) } }.apply { isDaemon = true }.start()
    }

    /** 同步检查；返回结果和每个来源的尝试记录（给管理面板显示） */
    @Synchronized
    fun checkNow(ctx: Context): Pair<Info?, JSONArray> {
        val log = JSONArray()
        if (LocalOnlyGuard.enabled) {
            log.put(JSONObject().put("source", "LocalOnly").put("ok", false).put("ms", 0)
                .put("message", "仅本地隐私版（EU Local-Only）已禁用在线更新"))
            Prefs.setUpdateInfo(ctx, "")
            Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
            Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
            return null to log
        }
        val current = currentVersion(ctx)
        val started = System.currentTimeMillis()
        var info: Info? = null
        try {
            val data = fetch(GITHUB_API)
            info = data?.let { parse(it, current, "GitHub") }
            log.put(JSONObject().put("source", "GitHub").put("ok", true).put("ms", System.currentTimeMillis() - started)
                .put("message", if (info == null) "当前已是最新版本" else "v" + info.latest))
        } catch (e: Exception) {
            Diag.error("Update", "GitHub release check failed", e)
            log.put(JSONObject().put("source", "GitHub").put("ok", false).put("ms", System.currentTimeMillis() - started)
                .put("message", e.message?.take(160) ?: e.javaClass.simpleName))
        }
        Prefs.setUpdateInfo(ctx, info?.let { toJson(it).toString() } ?: "")
        Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
        Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
        preDownloadIfNeeded(ctx, info)
        return info to log
    }

    fun toJson(info: Info): JSONObject = JSONObject()
        .put("latest", info.latest).put("current", info.current).put("force", info.force)
        .put("notes", info.notes).put("url", info.url).put("sha256", info.sha256).put("source", info.source)

    internal fun effectiveCachedInfo(cached: Info?, localOnly: Boolean): Info? = if (localOnly) null else cached

    fun cachedInfo(ctx: Context): Info? {
        if (LocalOnlyGuard.enabled) return null
        val raw = Prefs.updateInfo(ctx)
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("latest"), o.optString("current"), o.optBoolean("force"),
                o.optString("notes"), o.optString("url"), o.optString("sha256"), o.optString("source"))
        } catch (e: Exception) {
            null
        }
    }

    private fun fetch(endpoint: String): JSONObject? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "StudyOnTV-Updater")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException(if (code == 403 || code == 429) "HTTP $code（请求太频繁或被限流）" else "HTTP $code")
            val text = conn.inputStream.use { stream -> stream.readBytes().also { require(it.size < 2_000_000) { "响应过大" } } }
            JSONObject(String(text, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    private val flavorMarker get() = if (BuildConfig.FLAVOR == "tv") "-TV-" else "-Mobile-"

    /** 同时支持 GitHub Release JSON 和 update.json 清单 */
    private fun parse(data: JSONObject, current: String, source: String): Info? =
        if (data.has("tag_name")) parseRelease(data, current, source) else parseManifest(data, current, source)

    private fun parseRelease(release: JSONObject, current: String, source: String): Info? {
        val tag = release.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val body = release.optString("body")
        val assets = release.optJSONArray("assets")
        val candidates = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        val selected = candidates.firstOrNull { it.optString("name").contains(flavorMarker, true) && it.optString("name").endsWith(".apk", true) }
            ?: if (BuildConfig.FLAVOR == "tv") candidates.firstOrNull { it.optString("name").matches(Regex("StudyOnTV-v[0-9.]+\\.apk")) } else null
        val url = selected?.optString("browser_download_url")?.takeIf { it.isNotEmpty() } ?: return null
        // GitHub 会给 Release 附件计算 digest（sha256:...），用于校验镜像下载的文件
        val digest = selected.optString("digest").takeIf { it.startsWith("sha256:", true) }?.substring(7).orEmpty()
        val force = body.contains("[force]", true) || body.contains("force update", true)
        return Info(tag, current, force && isNewer(tag, current), body.trim(), url, digest.lowercase(), source)
    }

    /**
     * update.json 格式（由 scripts/make-update-manifest.mjs 生成）：
     * {"version":"0.2.2","notes":"…","force":false,
     *  "assets":{"tv":{"url":"https://github.com/…/StudyOnTV-TV-v0.2.2.apk","sha256":"…"},"mobile":{…}}}
     */
    private fun parseManifest(manifest: JSONObject, current: String, source: String): Info? {
        val version = manifest.optString("version").removePrefix("v").trim()
        if (version.isEmpty()) return null
        val asset = manifest.optJSONObject("assets")?.optJSONObject(if (BuildConfig.FLAVOR == "tv") "tv" else "mobile") ?: return null
        val url = asset.optString("url").takeIf { it.startsWith("https://") } ?: return null
        val notes = manifest.optString("notes")
        val force = manifest.optBoolean("force") || notes.contains("[force]", true)
        return Info(version, current, force && isNewer(version, current), notes.trim(), url, asset.optString("sha256").lowercase(), source)
    }

    fun isNewer(latest: String, current: String): Boolean {
        val remoteClean = latest.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val localClean = current.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (remoteClean.isEmpty()) return false
        val remoteCore = remoteClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val localCore = localClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteCore.size, localCore.size)) {
            val x = remoteCore.getOrElse(i) { 0 }
            val y = localCore.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val remotePre = remoteClean.substringAfter('-', "")
        val localPre = localClean.substringAfter('-', "")
        if (remotePre.isEmpty() && localPre.isEmpty()) return false
        if (remotePre.isEmpty() && localPre.isNotEmpty()) return true
        if (remotePre.isNotEmpty() && localPre.isEmpty()) return false
        val rParts = remotePre.split('.', '-')
        val lParts = localPre.split('.', '-')
        for (i in 0 until minOf(rParts.size, lParts.size)) {
            val rp = rParts[i]
            val lp = lParts[i]
            if (rp == lp) continue
            val rn = rp.toIntOrNull()
            val ln = lp.toIntOrNull()
            return when {
                rn != null && ln != null -> rn > ln
                rn != null -> false
                ln != null -> true
                else -> rp > lp
            }
        }
        return rParts.size > lParts.size
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" } catch (e: Exception) { "0.0.0" }

    fun currentVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    fun newerThanCurrent(ctx: Context, info: Info?): Info? =
        if (info != null && isNewer(info.latest, currentVersion(ctx))) info else null

    /** 管理面板用：当前状态 */
    fun status(ctx: Context): JSONObject {
        val info = effectiveCachedInfo(cachedInfo(ctx), LocalOnlyGuard.enabled)
        val hasUpdate = newerThanCurrent(ctx, info) != null
        val snap = currentInstallSnapshot().let { cur ->
            if (!hasUpdate && cur.state == "installing") {
                resetInstallState()
                InstallSnapshot()
            } else cur
        }
        val autoDownload = resolveAutoDownloadSetting(Prefs.autoDownloadUpdate(ctx), LocalOnlyGuard.enabled)
        val apkReady = hasUpdate && isApkReady(ctx, info)
        val downloadedVersion = if (apkReady) info?.latest.orEmpty() else ""
        val log = try { JSONObject(Prefs.updateLog(ctx)) } catch (_: Exception) { JSONObject() }
        return appendInstallStatus(
            JSONObject().put("current", currentVersion(ctx))
                .put("latest", info?.latest ?: "").put("hasUpdate", hasUpdate)
                .put("notes", info?.notes ?: "").put("source", info?.source ?: "")
                // 面板要给出「手动下载 / 用电脑 adb 安装」的地址，否则家长在山寨电视上装不上时无从下手
                .put("url", info?.url ?: "")
                .put("checkedAt", Prefs.updateCheckedAt(ctx)).put("checking", checking)
                .put("logAt", log.optLong("at"))
                .put("api", api(ctx)).put("mirrors", JSONArray(mirrors(ctx))).put("defaultMirrors", JSONArray(DEFAULT_MIRRORS))
                .put("lastDownloadSource", Prefs.updateLastSource(ctx))
                // 「能下不能装」是电视上最常见的一类反馈：把系统原话和授权状态直接给面板
                .put("canInstall", ApkInstaller.canInstall(ctx))
                .put("installError", Prefs.updateInstallError(ctx))
                .put("autoDownload", autoDownload)
                .put("apkReady", apkReady)
                .put("downloadedVersion", downloadedVersion)
                .put("silentCapability", silentCapability(ctx))
                .put("log", log.optJSONArray("items") ?: JSONArray()),
            snap
        )
    }

    fun saveSettings(ctx: Context, input: JSONObject): JSONObject {
        if (input.has("api")) {
            val api = input.optString("api").trim()
            require(api.isEmpty() || api.startsWith("https://")) { "自定义更新地址需要以 https:// 开头" }
            Prefs.setUpdateApi(ctx, api)
        }
        if (input.has("mirrors")) {
            val raw = input.get("mirrors")
            val list = if (raw is JSONArray) (0 until raw.length()).map { raw.optString(it) } else raw.toString().split('\n', ',')
            val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
            clean.forEach { require(it.startsWith("https://")) { "镜像地址需要以 https:// 开头：$it" } }
            require(clean.size <= 10) { "最多填写 10 个镜像" }
            Prefs.setUpdateMirrors(ctx, if (input.optBoolean("resetMirrors")) null else clean.joinToString("\n"))
        }
        if (input.has("autoDownload")) {
            val effective = resolveAutoDownloadSetting(input.optBoolean("autoDownload", false), LocalOnlyGuard.enabled)
            Prefs.setAutoDownloadUpdate(ctx, effective)
            if (effective) {
                preDownloadIfNeeded(ctx, cachedInfo(ctx))
            }
        }
        if (input.has("api") || input.has("mirrors")) {
            Prefs.setUpdateCheckedAt(ctx, 0)
        }
        return status(ctx)
    }
}
