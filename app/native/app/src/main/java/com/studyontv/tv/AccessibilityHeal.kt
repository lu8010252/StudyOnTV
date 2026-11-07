package com.studyontv.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 无障碍自愈：进程被系统杀掉后，无障碍服务不会自己重新绑定，有的 ROM 甚至会把它从“已启用”列表里清掉。
 * 一键授权时会通过本机 ADB 给本应用授予 WRITE_SECURE_SETTINGS，之后本应用就能自己把无障碍写回去，
 * 不用再人工去设置里开、也不用每次都连 ADB。
 * 由前台服务心跳和每分钟的自唤醒闹钟调用；没有授权时什么都不做（授权状态见 canHeal）。
 */
object AccessibilityHeal {
    private const val MIN_GAP_MS = 20_000L
    @Volatile private var lastTry = 0L

    fun canHeal(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 无障碍没连上就尝试恢复，返回是否做了修复动作 */
    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (WatchAccessibilityService.connected) return false
        if (!canHeal(ctx)) return false
        val now = System.currentTimeMillis()
        if (now - lastTry < MIN_GAP_MS) return false
        lastTry = now
        return try {
            val resolver = ctx.contentResolver
            val service = ComponentName(ctx, WatchAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = if (current.isEmpty()) emptyList() else current.split(':')
            val others = list.filter { it != service }
            if (list.size != others.size) {
                // 列表里已有但服务没连上：先拿掉再加回来，系统才会重新绑定
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Thread.sleep(600)
            }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + service).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            GateService.note("无障碍未连接，已自动写回并重新绑定")
            true
        } catch (e: Exception) {
            GateService.note("无障碍自愈失败：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }
}
