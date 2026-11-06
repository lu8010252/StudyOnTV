package com.studyontv.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * 自唤醒：投影仪内存很小（1.7G、low_ram），打开视频类应用后本应用的前台服务常被系统 lmkd 杀掉，
 * 实测 START_STICKY 拉不回来，无障碍服务也不会自动重新绑定，于是后台就彻底不记时、也不弹拦截页了。
 * 这里用 AlarmManager 每分钟自检一次：服务不在就重新启动。闹钟由系统负责触发，进程死了也照常响。
 */
class ReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // 先把闹钟续上：哪怕下面启动服务时进程又被杀，下一次闹钟也已经排好了，不会断链
        if (Prefs.onboarded(app)) Revive.rearm(app)
        log(app, "tap " + System.currentTimeMillis() + (if (GateService.isRunning()) " alive" else " dead"))
        if (!Prefs.onboarded(app)) return
        if (!GateService.isRunning()) {
            try { GateService.start(app) } catch (_: Exception) { }
        }
    }

    /** 留一点持久日志，设备日志被厂商关了，排查自唤醒有没有触发只能靠它 */
    private fun log(app: Context, text: String) {
        try {
            val file = File(app.filesDir, "revive.log")
            if (file.exists() && file.length() > 8192) file.delete()
            FileOutputStream(file, true).use { it.write((text + "\n").toByteArray()) }
        } catch (_: Exception) { }
    }
}

object Revive {
    private const val ACTION = "com.studyontv.tv.REVIVE"
    private const val REQUEST_EXACT = 1101
    private const val REQUEST_REPEAT = 1102
    private const val INTERVAL_MS = 60_000L

    fun rearm(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        // 一次性精确闹钟：待机时也能响，用来快速拉起
        val exact = pending(app, REQUEST_EXACT)
        try {
            if (Build.VERSION.SDK_INT >= 23) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, exact)
            else manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, exact)
        } catch (e: Exception) {
            // Android 12+ 没给“精确闹钟”权限时会抛异常，退化成不精确闹钟
            try { manager.set(AlarmManager.RTC_WAKEUP, triggerAt, exact) } catch (_: Exception) { }
        }
        // 重复闹钟兜底：一次性闹钟如果哪次没来得及续上就彻底断链，重复闹钟不会
        try { manager.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, INTERVAL_MS, pending(app, REQUEST_REPEAT)) }
        catch (_: Exception) { }
    }

    private fun pending(app: Context, request: Int): PendingIntent {
        val intent = Intent(app, ReviveReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(app, request, intent, flags)
    }
}
