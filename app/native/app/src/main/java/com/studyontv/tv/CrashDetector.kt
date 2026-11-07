package com.studyontv.tv

import android.content.Context
import java.io.File

/**
 * 崩溃检测器。
 *
 * 在进程启动时安装 UncaughtExceptionHandler：
 *   1. 把崩溃摘要（类名 + 消息）写入 filesDir/crash_pending 标记文件
 *   2. 调用原有 handler（让系统/Bugly 继续处理）
 *
 * 下次启动时 MainActivity 读取该文件，存在则弹窗询问用户是否提交。
 * 用户确认 → BuglyInit.initWithConsent()，同时删除标记。
 * 用户拒绝 → 只删标记，不上传。
 */
object CrashDetector {
    private const val PENDING_FILE = "crash_pending"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val summary = buildString {
                    append(throwable.javaClass.name)
                    throwable.message?.let { append(": ").append(it.take(200)) }
                    append("\n")
                    throwable.stackTrace.take(8).forEach { append("  at $it\n") }
                }
                File(appCtx.filesDir, PENDING_FILE).writeText(summary)
                Diag.error("Crash", "uncaught: ${throwable.javaClass.simpleName} ${throwable.message}")
            } catch (_: Exception) { }
            original?.uncaughtException(thread, throwable)
        }
    }

    fun hasPending(context: Context): Boolean =
        File(context.filesDir, PENDING_FILE).exists()

    fun readAndClear(context: Context): String {
        val f = File(context.filesDir, PENDING_FILE)
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        return text
    }

    fun clear(context: Context) {
        File(context.filesDir, PENDING_FILE).delete()
    }
}
