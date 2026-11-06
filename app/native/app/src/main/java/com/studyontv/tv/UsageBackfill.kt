package com.studyontv.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import kotlin.math.min

/**
 * 补记：服务被系统杀掉的那段时间，秒表停了，孩子看视频的时间就白送了。
 * 系统 UsageStats 一直在记“谁进前台/谁退后台”，即使我们的进程死了也照记，
 * 所以服务重启后回放“上次心跳到现在”的事件，把其中“非豁免应用在前台”的时长补记到屏幕时间。
 */
object UsageBackfill {
    // 最多补 30 分钟：设备关机/长时间离网时不至于一次扣掉半天额度
    private const val MAX_GAP_MS = 30 * 60 * 1000L
    // LearningEngine.consume 单次最多认 5 秒，长区间要分块调用
    private const val CHUNK_MS = 5000L
    // 回放窗口：向前多读一段，用于确定起始时刻谁在前台
    private const val STATE_LOOKBACK_MS = 6 * 60 * 60 * 1000L
    private const val LOOKBACK_MS = 24 * 60 * 60 * 1000L

    private val dialers = setOf(
        "com.google.android.dialer", "com.android.dialer", "com.android.phone",
        "com.android.server.telecom", "com.android.emergency"
    )
    private val settingsPackages = setOf(
        "com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings"
    )

    @Synchronized
    fun run(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val last = Prefs.heartbeat(app)
        val counting = Prefs.heartbeatCounting(app)
        // 先把心跳挪到现在：即使下面回放出错，也不会反复补同一段时间
        Prefs.setHeartbeat(app, now, false)
        // 被杀前本来就没在计时（例如已经弹出了拦截页、息屏、在桌面），就不要补
        if (!counting || last <= 0L || now <= last) return
        val from = maxOf(last, now - MAX_GAP_MS)
        val millis = foregroundMillis(app, from, now)
        if (millis <= 0L) return
        val before = usedMillis()
        var left = millis
        while (left > 0L) {
            val chunk = min(left, CHUNK_MS)
            DeviceRuntime.learning.consume(chunk)
            left -= chunk
        }
        val credited = (usedMillis() - before).coerceAtLeast(0L)
        if (credited <= 0L) return
        try {
            DeviceRuntime.store.logEvent("usage_backfill",
                JSONObject().put("milliseconds", credited).put("from", from).put("to", now))
        } catch (_: Exception) { }
        GateService.note("补记 " + (credited / 1000) + " 秒（服务被杀期间）")
    }

    private fun usedMillis(): Long = DeviceRuntime.store.get("used_ms").toLongOrNull() ?: 0L

    /**
     * 统计 [from, to] 区间内“非豁免应用”处于前台的毫秒数。
     * 豁免规则与 GateService.exempt 保持一致：本应用、桌面、系统 UI、输入法、系统设置、拨号器等不算使用。
     */
    @Suppress("DEPRECATION")
    private fun foregroundMillis(context: Context, from: Long, to: Long): Long {
        if (!Foreground.hasUsageAccess(context)) return 0L
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = maxOf(from - STATE_LOOKBACK_MS, to - LOOKBACK_MS)
        var total = 0L
        var current: String? = null
        var since = start
        fun close(end: Long) {
            val pkg = current ?: return
            val s = maxOf(since, from)
            val e = min(end, to)
            if (e > s && !exempt(context, pkg)) total += e - s
        }
        try {
            val events = manager.queryEvents(start, to)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        close(event.timeStamp)
                        current = pkg
                        since = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (current == pkg) {
                            close(event.timeStamp)
                            current = null
                            since = event.timeStamp
                        }
                    }
                }
            }
            close(to)
        } catch (e: Exception) {
            return 0L
        }
        return total
    }

    private fun exempt(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "com.android.systemui" || pkg == "android") return true
        if (pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) return true
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")) return true
        if (pkg in dialers || pkg in settingsPackages) return true
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        return pkg == home || Foreground.isLauncher(pkg)
    }
}
