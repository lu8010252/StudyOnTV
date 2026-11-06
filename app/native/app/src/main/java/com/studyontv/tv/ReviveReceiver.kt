package com.studyontv.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 自唤醒：投影仪内存很小（1.7G、low_ram），打开视频类应用后本应用的前台服务常被系统 lmkd 杀掉，
 * 实测 START_STICKY 拉不回来，无障碍服务也不会自动重新绑定，于是后台就彻底不记时了。
 * 这里用 AlarmManager 每分钟自检一次：服务不在就重新启动。闹钟由系统负责触发，进程死了也照常响。
 */
class ReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!Prefs.onboarded(app)) return
        if (!GateService.isRunning()) {
            try { GateService.start(app) } catch (_: Exception) { }
        }
        // 闹钟只响一次，每次触发后重新排下一次（服务活着也照样滚续）
        Revive.rearm(app)
    }
}

object Revive {
    private const val ACTION = "com.studyontv.tv.REVIVE"
    private const val REQUEST = 1101
    private const val INTERVAL_MS = 60_000L

    fun rearm(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(app, ReviveReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getBroadcast(app, REQUEST, intent, flags)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= 23) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            else manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (e: Exception) {
            // Android 12+ 没给“精确闹钟”权限时会抛异常，退化成不精确的待机闹钟（仍会周期触发）
            try {
                if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                else manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (_: Exception) { }
        }
    }
}
