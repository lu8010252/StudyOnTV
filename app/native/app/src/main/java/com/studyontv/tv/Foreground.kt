package com.studyontv.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object Foreground {
    @Volatile var lastPackage: String? = null
        private set
    private var ownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun resumeOwn(activity: android.app.Activity) {
        ownActivity = java.lang.ref.WeakReference(activity)
        update(activity.packageName)
    }
    fun pauseOwn(activity: android.app.Activity) {
        if (ownActivity?.get() === activity) ownActivity = null
        if (ownActivity?.get() == null && lastPackage == activity.packageName) lastPackage = null
    }
    fun update(pkg: String?) { if (!pkg.isNullOrEmpty()) lastPackage = pkg }
    fun clearExternal() { lastPackage = null }
    fun learningActivity(): android.app.Activity? = ownActivity?.get()?.takeIf { it is MainActivity }

    private val launcherPackages = setOf(
        "com.android.tv.launcher", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.launcher", "com.android.launcher3", "com.tcl.launcher", "com.mitv.tvhome",
        "com.hisense.tv.launcher", "com.google.android.apps.nexuslauncher", "com.miui.home",
        "com.sec.android.app.launcher", "com.huawei.android.launcher", "com.oppo.launcher", "com.vivo.launcher"
    )
    fun isLauncher(pkg: String?): Boolean = pkg != null && launcherPackages.contains(pkg)

    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        ownActivity?.get()?.let {
            if (Build.VERSION.SDK_INT < 24 || !it.isInMultiWindowMode) return it.packageName
        }
        if (WatchAccessibilityService.connected) lastPackage?.let { return it }
        if (!hasUsageAccess(context)) return null
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // 以前每秒都查询过去 24 小时的全部事件，在电视上很耗 CPU；现在只查上次之后的新事件
            val from = if (usageQueriedAt <= 0 || now - usageQueriedAt > 3600000 || now < usageQueriedAt) now - 86400000 else usageQueriedAt - 2000
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) latest = event.packageName
            }
            usageQueriedAt = now
            if (latest != null) usageLatest = latest
            usageLatest ?: manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000, now)
                ?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }?.packageName?.also { usageLatest = it }
        } catch (_: Exception) { null }
    }

    @Volatile private var usageQueriedAt = 0L
    @Volatile private var usageLatest: String? = null

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}
