package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL





object ApkInstaller {

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFile(ctx: Context): File = File(dir(ctx), "update.apk")

    
    @Volatile var lastError = ""
        private set

    @Suppress("DEPRECATION")
    private fun signatures(ctx: Context, info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else info.signatures
        return sigs?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    /** 包名一致、版本更高、签名与当前安装的版本一致（镜像下载也不会被替换成别人的包） */
    @Suppress("DEPRECATION")
    private fun validUpdate(ctx: Context, file: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: run { lastError = "安装包损坏或不完整"; return false }
        archive.applicationInfo?.let { it.sourceDir = file.absolutePath; it.publicSourceDir = file.absolutePath }
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        if (archive.packageName != ctx.packageName) { lastError = "安装包与本应用不符"; return false }
        if (archive.versionCode <= current.versionCode) { lastError = "安装包版本不比当前新"; return false }
        val mine = signatures(ctx, current)
        val theirs = signatures(ctx, archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine.intersect(theirs).isEmpty()) { lastError = "安装包签名与当前版本不一致，已拒绝"; return false }
        return true
    }

    fun alreadyDownloaded(ctx: Context, sizeHint: Long): Boolean {
        val f = apkFile(ctx)
        return f.exists() && (sizeHint <= 0 || f.length() == sizeHint) && validUpdate(ctx, f)
    }

    /** 依次尝试直连和各个加速镜像，任一线路成功即返回 */
    fun download(ctx: Context, url: String, sha256: String, onSource: (String) -> Unit, onProgress: (Int) -> Unit): File? {
        lastError = ""
        for ((name, candidate) in Updater.downloadCandidates(ctx, url)) {
            onSource(name)
            val file = downloadFrom(ctx, candidate, sha256, onProgress)
            if (file != null) { Prefs.setUpdateLastSource(ctx, name); return file }
            if (lastError.contains("签名") || lastError.contains("校验")) android.util.Log.w("StudyOnTV", "Rejected update from $name: $lastError")
        }
        return null
    }

    private fun downloadFrom(ctx: Context, url: String, sha256: String, onProgress: (Int) -> Unit): File? {
        val target = apkFile(ctx)
        val partial = File(dir(ctx), "update.pending.apk")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 60000   // 蒲公英 APK 文件大，慢网需要更长时间
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Pgyer.USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            // 手动跟随重定向（部分 HTTP→HTTPS 或 302 跳转 HttpURLConnection 不处理）
            var finalConn: HttpURLConnection = conn!!
            var redirectCode = finalConn.responseCode
            var redirectCount = 0
            while (redirectCode in 301..303 && redirectCount++ < 5) {
                val loc = finalConn.getHeaderField("Location") ?: break
                finalConn.disconnect()
                finalConn = URL(loc).openConnection() as HttpURLConnection
                finalConn.connectTimeout = 12000; finalConn.readTimeout = 60000
                finalConn.instanceFollowRedirects = true
                finalConn.setRequestProperty("User-Agent", Pgyer.USER_AGENT)
                redirectCode = finalConn.responseCode
            }
            conn = finalConn
            if (redirectCode !in 200..299) { lastError = "下载失败（HTTP $redirectCode）"; return null }
            val total = conn!!.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            conn!!.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var shown = -1
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (percent != shown) { shown = percent; onProgress(percent) }
                    }
                    output.flush()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && !actual.equals(sha256, true)) { lastError = "安装包校验失败（SHA-256 不一致）"; return null }
            if (!validUpdate(ctx, partial)) return null
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) { lastError = "无法保存安装包"; return null }
            onProgress(100)
            return target
        } catch (e: Exception) {
            lastError = "下载中断：" + (e.message ?: e.javaClass.simpleName).take(80)
            return null
        } finally {
            partial.delete()
            conn?.disconnect()
        }
    }

    fun install(ctx: Context, file: File): Boolean {
        lastError = ""
        Prefs.setUpdateInstallError(ctx, "")
        if (!canInstall(ctx)) {
            // Android 8 起「安装未知应用」是按应用授权的，没开的话调起安装器只会失败，先把话说清楚
            lastError = "系统还没允许「题了个题」安装应用"
            Diag.warn("Install", "canRequestPackageInstalls=false, install not started")
            return false
        }
        GateService.allowInstallPage()
        if (installSession(ctx, file)) return true
        return installView(ctx, file)
    }

    /** Android 8+ 需要「允许安装未知应用」；更老的版本是全局的「未知来源」，这里不做判断 */
    fun canInstall(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) { true }

    /** 「允许安装未知应用」的系统设置页；各家电视入口不同，失败就退到本应用详情页 */
    fun permissionIntent(ctx: Context): Intent {
        val direct = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + ctx.packageName))
        if (ctx.packageManager.resolveActivity(direct, 0) != null) return direct
        return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + ctx.packageName))
    }

    /**
     * 优先走 PackageInstaller 会话：安装结果会回调到 [UpdateInstallReceiver]，失败原因能落到日志和面板里。
     * 少数定制电视的 PackageInstaller 有问题（创建会话抛异常），这时退回 ACTION_VIEW 交给系统安装器。
     */
    private fun installSession(ctx: Context, file: File): Boolean {
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                val flags = if (Build.VERSION.SDK_INT >= 31)
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val pending = android.app.PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            Diag.info("Install", "install session committed size=${file.length()} api=${Build.VERSION.SDK_INT}")
            return true
        } catch (e: Exception) {
            Diag.warn("Install", "session install failed, falling back to ACTION_VIEW: ${e.javaClass.simpleName} ${e.message}")
            lastError = "安装会话失败：" + (e.message ?: e.javaClass.simpleName).take(80)
            return false
        }
    }

    /** 会话走不通时（例如电视限制了后台拉起确认页）退回系统安装器 */
    fun fallbackToView(ctx: Context): Boolean {
        val file = apkFile(ctx)
        if (!file.exists()) { lastError = "安装包已丢失，请重新下载"; return false }
        return installView(ctx, file)
    }

    /** 老路径：把安装包交给系统安装器。没有安装结果的回调，只能保证「已调起」 */
    private fun installView(ctx: Context, file: File): Boolean {
        return try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ApkProvider.uriFor(ctx, file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            lastError = ""
            Diag.info("Install", "ACTION_VIEW installer started")
            true
        } catch (e: Exception) {
            lastError = "这台电视没有可用的安装器，请用「手动下载」到电视上安装"
            Diag.error("Install", "no installer activity", e)
            false
        }
    }

    const val ACTION_INSTALL_RESULT = "com.studyontv.tv.INSTALL_RESULT"
}
