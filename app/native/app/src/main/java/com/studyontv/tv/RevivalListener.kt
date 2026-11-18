package com.studyontv.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 通知监听复活通道。
 *
 * 系统会保留“通知使用权”的授权，并在进程被强停后重新绑定这个服务：小米待机清理、
 * 部分固件按 Home 强停，都会先断掉绑定，再于约 0.5 秒后把服务拉回来。借这次绑定
 * 把 [GateService] 重新启动，让计时和拦截继续。它不读取、不保存、不上传任何通知内容，
 * 也不依赖通知本身——只用系统的绑定生命周期。
 *
 * 需要一次性授权（电视系统设置里可能没有入口）：
 *   adb shell cmd notification allow_listener com.studyontv.tv/com.studyontv.tv.RevivalListener
 *
 * 这条通道只负责“把进程拉回来”。如果固件同时把无障碍服务从
 * enabled_accessibility_services 里删掉，应用没有 WRITE_SECURE_SETTINGS 时无法自己写回，
 * 仍需家长重新开启，或在系统自启动 / 后台白名单里放行。
 */
class RevivalListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var checking = false
    private var attempt = 0
    private var watchingUnlock = false
    private val check = Runnable { checkGate() }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = startRecovery("user unlocked")
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, filter, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(unlocked, filter)
                watchingUnlock = true
            } catch (_: Exception) { }
        }
        log("created")
        startRecovery("service created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        log("connected")
        startRecovery("listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        log("disconnected")
        super.onListenerDisconnected()
    }

    private fun startRecovery(reason: String) {
        if (checking) {
            if (reason == "user unlocked" || reason == "listener connected") {
                handler.removeCallbacks(check)
                attempt = 0
                log("recovery accelerated: $reason")
                handler.post(check)
            }
            return
        }
        checking = true
        attempt = 0
        log("recovery start: $reason")
        handler.post(check)
    }

    private fun checkGate() {
        if (!checking) return
        // 凭据存储还没解锁时读不到 onboarded，先等解锁广播，别把窗口耗在无效重试上。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !(getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) {
            if (attempt < DELAYS.size) handler.postDelayed(check, DELAYS[attempt++])
            else finish("still locked")
            return
        }
        if (GateService.running) { finish("gate running"); return }
        if (!Prefs.onboarded(this)) { finish("not onboarded"); return }
        GateService.ensureRunning(this, "listener")
        if (attempt >= DELAYS.size) { finish("retry window exhausted"); return }
        handler.postDelayed(check, DELAYS[attempt++])
    }

    private fun finish(result: String) {
        checking = false
        handler.removeCallbacks(check)
        log("recovery done: $result (gate=${GateService.running})")
    }

    /** 复活往往发生在 DeviceRuntime 初始化之前，Diag 还写不进去；logcat 里也要留一条。 */
    private fun log(message: String) {
        android.util.Log.i("StudyOnTV-Revival", message)
        Diag.info("Service", "RevivalListener: $message")
    }

    override fun onDestroy() {
        checking = false
        connected = false
        handler.removeCallbacks(check)
        if (watchingUnlock) try { unregisterReceiver(unlocked) } catch (_: Exception) { }
        watchingUnlock = false
        super.onDestroy()
    }

    companion object {
        /** 首次立即检查，之后逐步拉长，约 2 分钟收口（与 Aerial 的复活窗口同量级）。 */
        private val DELAYS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)

        @Volatile var connected = false
            private set

        /** 是否已在系统的“通知使用权”列表里（不代表当前已连接）。 */
        fun enabled(context: Context): Boolean = try {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val me = ComponentName(context, RevivalListener::class.java)
            flat?.split(':')?.any { entry ->
                val parsed = ComponentName.unflattenFromString(entry) ?: return@any false
                parsed.packageName == me.packageName && parsed.className == me.className
            } == true
        } catch (_: Exception) { false }

        fun component(context: Context): String =
            ComponentName(context, RevivalListener::class.java).flattenToString()

        fun adbCommand(context: Context): String =
            "adb shell cmd notification allow_listener ${component(context)}"
    }
}
