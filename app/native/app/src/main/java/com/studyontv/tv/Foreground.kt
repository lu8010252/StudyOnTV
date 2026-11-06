package com.studyontv.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object Foreground {
    @Volatile var lastPackage: String? = null
        private set
    private var ownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
    private var usageLast: String? = null
    private var usageCheckedAt = 0L

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
            // 第一次读最近 24 小时，之后只读上次之后的新事件，并记住最近一次进入前台的应用：
            // 应用连续用了很久没有新事件时，也不会误判成别的应用
            val from = if (usageCheckedAt == 0L) now - 86400000 else usageCheckedAt - 3000
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) usageLast = event.packageName
            }
            usageCheckedAt = now
            usageLast ?: manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000, now)
                ?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) { null }
    }

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
