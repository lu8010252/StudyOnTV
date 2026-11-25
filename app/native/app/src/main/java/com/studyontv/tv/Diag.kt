package com.studyontv.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本机诊断日志：只保留最近的运行信息，不上传。
 * 面板可以查看和导出，方便排查电视上的权限、计时、音频和更新问题。
 */
object Diag {
    private const val FILE = "sotv.log"
    private const val MAX_BYTES = 1024 * 1024L
    private const val KEEP = 3
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun dir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    @Synchronized
    fun write(level: String, tag: String, message: String) {
        try {
            val context = DeviceRuntime.appContext
            rotate(context)
            val line = "${format.format(Date())} $level/$tag ${Thread.currentThread().name}: $message\n"
            File(dir(context), FILE).appendText(line)
            android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "StudyOnTV-$tag", message)
        } catch (_: Throwable) { }
    }

    fun info(tag: String, message: String) = write("I", tag, message)
    fun warn(tag: String, message: String) = write("W", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        write("E", tag, message + (throwable?.let { " | " + it.javaClass.simpleName + ": " + (it.message ?: "") } ?: ""))
        if (throwable != null) write("E", tag, throwable.stackTraceToString().take(8000))
    }

    internal fun rotateInDir(logDir: File, maxBytes: Long = MAX_BYTES, keep: Int = KEEP) {
        val current = File(logDir, FILE)
        if (!current.exists() || current.length() < maxBytes) return
        if (keep <= 0) {
            current.delete()
            return
        }
        val oldest = File(logDir, "$FILE.$keep")
        if (oldest.exists()) oldest.delete()
        for (i in keep - 1 downTo 1) {
            val from = File(logDir, "$FILE.$i")
            val to = File(logDir, "$FILE.${i + 1}")
            if (from.exists()) {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
        val firstArchive = File(logDir, "$FILE.1")
        if (firstArchive.exists()) firstArchive.delete()
        current.renameTo(firstArchive)
    }

    private fun rotate(context: Context) = rotateInDir(dir(context))

    fun read(context: Context, limit: Int = 500, query: String = ""): JSONArray {
        val lines = ArrayList<String>()
        val files = (KEEP downTo 1).map { File(dir(context), "$FILE.$it") } + File(dir(context), FILE)
        files.forEach { file -> if (file.exists()) try { lines += file.readLines().takeLast(limit) } catch (_: Exception) { } }
        val key = query.trim().lowercase()
        val filtered = if (key.isEmpty()) lines else lines.filter { it.lowercase().contains(key) }
        return JSONArray(filtered.takeLast(limit))
    }

    @Synchronized
    fun clear(context: Context) {
        try { dir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }

    private fun buildDeviceJson(context: Context): JSONObject =
        JSONObject()
            .put("app", "StudyOnTV")
            .put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("flavor", BuildConfig.FLAVOR)
            .put("package", context.packageName)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("uptimeMinutes", android.os.SystemClock.elapsedRealtime() / 60000)
            .put("monitor", GateService.diagnostics(context))
            .put("autostart", Watchdog.report(context))
            .put("update", Updater.status(context))

    fun export(context: Context): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            dir(context).listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}")); zip.write(file.readBytes()); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("device.json")); zip.write(buildDeviceJson(context).toString(2).toByteArray()); zip.closeEntry()
            try {
                val config = DeviceRuntime.store.config()
                zip.putNextEntry(ZipEntry("config.json")); zip.write(config.toString(2).toByteArray()); zip.closeEntry()
            } catch (_: Exception) { }
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // 软件体验提升计划：运行诊断日志与崩溃日志自动上传（兼容阿里云 OSS 直传与 HTTP POST 端点）
    // -------------------------------------------------------------------------

    private const val RUNTIME_UPLOAD_INTERVAL_MS = 6L * 60 * 60 * 1000L
    private val runtimeUploading = AtomicBoolean(false)
    private val crashUploading = AtomicBoolean(false)

    internal fun isAliyunOssEndpoint(endpoint: String): Boolean {
        val host = try { URL(endpoint).host.lowercase(Locale.US) } catch (_: Exception) { return false }
        return host.endsWith(".aliyuncs.com") && host.contains(".oss-")
    }

    private fun sanitizeTokenPart(raw: String, fallback: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(24).ifEmpty { fallback }

    internal fun ossObjectKey(kind: String, nowMillis: Long = System.currentTimeMillis()): String {
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
        val day = dayFmt.format(Date(nowMillis))
        val brand = sanitizeTokenPart(Build.MANUFACTURER ?: "", "unknown")
        val model = sanitizeTokenPart(Build.MODEL ?: "", "device")
        val ver = sanitizeTokenPart(BuildConfig.VERSION_NAME, "0")
        val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(6)
        return "logs/$day/${nowMillis / 1000L}_${brand}_${model}_v${ver}_${kind}_$rand.zip"
    }

    private fun ossBucketFromHost(host: String): String = host.substringBefore(".oss-")

    private fun ossSignV1(ak: String, sk: String, method: String, contentType: String, dateHeader: String, canonicalResource: String): String {
        val stringToSign = "$method\n\n$contentType\n$dateHeader\n$canonicalResource"
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(sk.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = MediaStore.encodeBase64(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
        return "OSS $ak:$sig"
    }

    private fun uploadZip(endpoint: String, token: String, zipBytes: ByteArray, kind: String): Int {
        val trimmed = endpoint.trim().trimEnd('/')
        if (isAliyunOssEndpoint(trimmed)) {
            val key = ossObjectKey(kind)
            val targetUrl = URL("$trimmed/$key")
            val conn = targetUrl.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                val contentType = "application/zip"
                val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }
                val dateStr = dateFmt.format(Date())
                conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("Date", dateStr)
                conn.setRequestProperty("User-Agent", "StudyOnTV-Telemetry/1")
                // 可选：token 留空走匿名只写 Bucket Policy；填写 "AccessKeyId:AccessKeySecret" 时自动走 OSS V1 签名
                val sep = token.indexOf(':')
                if (sep > 0 && sep < token.length - 1) {
                    val ak = token.substring(0, sep).trim()
                    val sk = token.substring(sep + 1).trim()
                    val bucket = ossBucketFromHost(targetUrl.host)
                    conn.setRequestProperty("Authorization", ossSignV1(ak, sk, "PUT", contentType, dateStr, "/$bucket/$key"))
                } else {
                    conn.setRequestProperty("x-oss-forbid-overwrite", "true")
                }
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.outputStream.use { it.write(zipBytes) }
                return conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
        val conn = URL(trimmed).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setRequestProperty("X-Report-Kind", kind)
            conn.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            conn.setRequestProperty("X-App-VersionCode", BuildConfig.VERSION_CODE.toString())
            conn.setRequestProperty("X-Device-Model", Build.MODEL)
            conn.setRequestProperty("X-Device-Manufacturer", Build.MANUFACTURER)
            conn.setRequestProperty("X-Android-SDK", Build.VERSION.SDK_INT.toString())
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.outputStream.use { it.write(zipBytes) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    internal fun runPendingCrashUpload(
        readAndClear: () -> String,
        writePending: (String) -> Unit,
        buildZip: (String) -> ByteArray,
        upload: (ByteArray) -> Int,
        onSuccess: (Int, Int) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ): Boolean {
        var summary: String? = null
        return try {
            summary = readAndClear()
            val zipBytes = buildZip(summary)
            val code = upload(zipBytes)
            if (code in 200..299) {
                onSuccess(zipBytes.size, code)
                true
            } else {
                writePending(summary)
                onFailure("upload failed: HTTP $code — writing back pending for retry")
                false
            }
        } catch (e: Exception) {
            if (summary != null) {
                try { writePending(summary) } catch (_: Exception) { }
            }
            onFailure("upload error: ${e.javaClass.simpleName}: ${e.message} — writing back pending for retry")
            false
        }
    }

    /**
     * 在 App 启动时调用（DeviceRuntime.ensure() 里）。
     * 若存在待上传的崩溃标记且用户已参与体验提升计划，则在后台线程上传崩溃 ZIP。
     */
    fun uploadPendingCrash(context: Context, endpoint: String, token: String) {
        if (LocalOnlyGuard.enabled) return
        if (!Prefs.crashUploadConsent(context)) return
        if (endpoint.isBlank()) return
        if (!CrashDetector.hasPending(context)) return
        if (!crashUploading.compareAndSet(false, true)) return

        val app = context.applicationContext
        val thread = Thread {
            try {
                runPendingCrashUpload(
                    readAndClear = { CrashDetector.readAndClear(app) },
                    writePending = { CrashDetector.writePending(app, it) },
                    buildZip = { buildCrashZip(app, it) },
                    upload = { uploadZip(endpoint, token, it, "crash") },
                    onSuccess = { size, code -> info("Crash", "upload ok ($size bytes, HTTP $code)") },
                    onFailure = { msg -> warn("Crash", msg) }
                )
            } finally {
                crashUploading.set(false)
            }
        }
        thread.name = "crash-upload"
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 定期上报运行诊断日志与脱敏使用概览（每 6 小时最多一次；不含密码、API Key、题库内容与孩子姓名）。
     */
    fun uploadRuntimeLogsIfDue(context: Context, force: Boolean = false) {
        if (LocalOnlyGuard.enabled) return
        if (!Prefs.crashUploadConsent(context)) return
        val endpoint = Prefs.crashEndpoint(context)
        if (endpoint.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.lastTelemetryUploadAt(context) < RUNTIME_UPLOAD_INTERVAL_MS) return
        if (!runtimeUploading.compareAndSet(false, true)) return
        val app = context.applicationContext
        val token = Prefs.crashToken(app)
        val thread = Thread {
            try {
                val zipBytes = buildRuntimeZip(app)
                val code = uploadZip(endpoint, token, zipBytes, "runtime")
                if (code in 200..299) {
                    Prefs.setLastTelemetryUploadAt(app, System.currentTimeMillis())
                    info("Telemetry", "runtime log upload ok (${zipBytes.size} bytes, HTTP $code)")
                } else {
                    warn("Telemetry", "runtime log upload failed: HTTP $code")
                }
            } catch (e: Exception) {
                warn("Telemetry", "runtime log upload error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runtimeUploading.set(false)
            }
        }
        thread.name = "runtime-log-upload"
        thread.isDaemon = true
        thread.start()
    }

    private fun buildUsageSummaryJson(): JSONObject {
        val out = JSONObject()
        try {
            val store = DeviceRuntime.store
            val cfg = store.config()
            out.put("supervisionEnabled", cfg.optBoolean("supervisionEnabled", true))
                .put("playMode", cfg.optString("playMode", "round"))
                .put("memorizeFirst", cfg.optBoolean("memorizeFirst", false))
                .put("spacedRepetitionEnabled", cfg.optBoolean("spacedRepetitionEnabled", false))
                .put("spacedScope", cfg.optString("spacedScope", "all"))
                .put("shuffleOptions", cfg.optBoolean("shuffleOptions", false))
                .put("scheduleMode", cfg.optString("scheduleMode", "allow"))
                .put("allDay", cfg.optBoolean("allDay", true))
                .put("forceFullscreenGate", cfg.optBoolean("forceFullscreenGate", false))
                .put("whitelistCount", cfg.optJSONArray("watchWhitelist")?.length() ?: 0)
                .put("questionsCount", store.countQuestions())
                .put("childrenCount", store.children().length())
                .put("study", store.studySummary())
                .put("speechMode", SpeechReader.settings(store).optString("mode", "system"))
                .put("gradingMode", GradingService.settings(store).optString("mode", "manual"))
        } catch (_: Throwable) { }
        return out
    }

    private fun buildRuntimeZip(context: Context): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("device.json"))
            zip.write(buildDeviceJson(context).toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("usage_summary.json"))
            zip.write(buildUsageSummaryJson().toString(2).toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * 构建崩溃上传专用 ZIP（比完整 export 小，只含日志和设备信息）。
     */
    private fun buildCrashZip(context: Context, summary: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 崩溃摘要（纯文本，方便在 R2/Telegram 预览）
            zip.putNextEntry(ZipEntry("crash_summary.txt"))
            zip.write(summary.toByteArray())
            zip.closeEntry()

            // 最近的日志文件
            dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }

            // 设备信息
            zip.putNextEntry(ZipEntry("device.json"))
            zip.write(buildDeviceJson(context).toString(2).toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write("E", "Crash", "thread=${thread.name} " + throwable.javaClass.name + ": " + throwable.message)
                write("E", "Crash", throwable.stackTraceToString().take(12000))
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
