package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

/**
 * 系统设置的识别与放行。
 *
 * 孩子一进「设置」就可能关掉管控；家长刚在电视上确认过身份、或者正在安装更新时，
 * 需要一段临时放行窗口。原来这两件事都揉在 GateService 里，这里单独拿出来，
 * 诊断和 tick 都走同一份判断。
 */
object SystemSettingsGuard {
    /** 各厂商设置应用的包名（解析不到默认设置页时兜底） */
    private val KNOWN = setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings")
    const val GRACE_MS = 120000L

    private var resolved = false
    private var resolvedPackage: String? = null

    /** 家长确认身份后的放行截止时间（elapsedRealtime） */
    @Volatile private var until = 0L

    fun active(now: Long = SystemClock.elapsedRealtime()): Boolean = now < until

    fun grant(durationMs: Long = GRACE_MS) {
        until = SystemClock.elapsedRealtime() + durationMs
    }

    fun revoke() {
        until = 0L
    }

    /** 系统「设置」应用的包名；第一次解析后缓存（各厂商可能藏在别的包名下） */
    fun settingsPackage(context: Context): String? {
        if (!resolved) synchronized(this) {
            if (!resolved) {
                resolvedPackage = context.packageManager
                    .resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
                resolved = true
            }
        }
        return resolvedPackage
    }

    fun isSettings(context: Context, pkg: String): Boolean =
        pkg == settingsPackage(context) || pkg in KNOWN

    /** 管控期间是否要把「设置」挡回去（家长刚刚验证过就不挡） */
    fun blocked(context: Context, awake: Boolean, monitoring: Boolean, free: Boolean, pkg: String?,
                blockSettings: Boolean, recentlyVerified: Boolean): Boolean =
        awake && monitoring && !free && pkg != null && isSettings(context, pkg) && blockSettings && !recentlyVerified
}
