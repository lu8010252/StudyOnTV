package com.studyontv.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    fun export(context: Context): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            dir(context).listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}")); zip.write(file.readBytes()); zip.closeEntry()
            }
            val device = JSONObject()
                .put("app", "StudyOnTV").put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                .put("flavor", BuildConfig.FLAVOR).put("package", context.packageName)
                .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("uptimeMinutes", android.os.SystemClock.elapsedRealtime() / 60000)
                .put("monitor", GateService.diagnostics(context))
                .put("autostart", Watchdog.report(context))
                .put("update", Updater.status(context))
            zip.putNextEntry(ZipEntry("device.json")); zip.write(device.toString(2).toByteArray()); zip.closeEntry()
            try {
                val config = DeviceRuntime.store.config()
                zip.putNextEntry(ZipEntry("config.json")); zip.write(config.toString(2).toByteArray()); zip.closeEntry()
            } catch (_: Exception) { }
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
