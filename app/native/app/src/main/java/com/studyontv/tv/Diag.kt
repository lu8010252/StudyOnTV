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

    private fun rotate(context: Context) {
        val current = File(dir(context), FILE)
        if (!current.exists() || current.length() < MAX_BYTES) return
        for (i in KEEP - 1 downTo 1) {
            val from = File(dir(context), if (i == 1) FILE else "$FILE.$i")
            val to = File(dir(context), "$FILE.${i + 1}")
            if (from.exists()) { to.delete(); from.renameTo(to) }
        }
        current.renameTo(File(dir(context), "$FILE.1"))
    }

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
    // 崩溃自动上传（Cloudflare Worker，零第三方依赖）
    // -------------------------------------------------------------------------

    /**
     * 在 App 启动时调用（DeviceRuntime.ensure() 里）。
     *
     * 若存在待上传的崩溃标记（crash_pending 文件），且用户已同意上传、配置了 endpoint，
     * 则在后台守护线程中把崩溃 ZIP POST 到 Cloudflare Worker。
     * 上传成功后标记已在读取时删除；HTTP 失败会写回标记，下次启动重试。
     *
     * @param endpoint  Worker URL，例如 "https://sotv-crash.xxx.workers.dev/crash"
     * @param token     Worker 里配置的 Bearer Token
     */
    fun uploadPendingCrash(context: Context, endpoint: String, token: String) {
        if (!Prefs.crashUploadConsent(context)) return
        if (endpoint.isBlank()) return
        if (!CrashDetector.hasPending(context)) return

        val thread = Thread {
            try {
                val summary = CrashDetector.readAndClear(context)
                val zipBytes = buildCrashZip(context, summary)

                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/zip")
                conn.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
                conn.setRequestProperty("X-App-VersionCode", BuildConfig.VERSION_CODE.toString())
                conn.setRequestProperty("X-Device-Model", Build.MODEL)
                conn.setRequestProperty("X-Device-Manufacturer", Build.MANUFACTURER)
                conn.setRequestProperty("X-Android-SDK", Build.VERSION.SDK_INT.toString())
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.doOutput = true

                conn.outputStream.use { it.write(zipBytes) }

                val code = conn.responseCode
                if (code in 200..299) {
                    info("Crash", "upload ok → $endpoint (${zipBytes.size} bytes, HTTP $code)")
                } else {
                    warn("Crash", "upload failed: HTTP $code — writing back pending for retry")
                    CrashDetector.writePending(context, summary)
                }
                conn.disconnect()
            } catch (e: Exception) {
                warn("Crash", "upload error: ${e.javaClass.simpleName}: ${e.message}")
                // 网络失败不写回 pending——本次崩溃只保留在本地日志里
            }
        }
        thread.name = "crash-upload"
        thread.isDaemon = true
        thread.start()
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
