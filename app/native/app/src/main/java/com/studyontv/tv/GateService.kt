package com.studyontv.tv

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class GateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = SystemClock.elapsedRealtime()
    private var wasUsing = false
    private var lastGate = 0L
    @Volatile private var settingsUntil = 0L
    private val systemSettingsPackage by lazy {
        packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }
    private var anchor: View? = null
    private lateinit var overlay: GateOverlay
    private val tick = object : Runnable {
        override fun run() {
            try { checkForeground() } catch (e: Exception) { android.util.Log.e("StudyOnTV", "Screen time check failed", e) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkForeground() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTick
        lastTick = now
        if (wasUsing) DeviceRuntime.learning.consume(elapsed)
        val canOverlay = GateOverlay.canDraw(this)
        updateAnchor(canOverlay)
        if (!canOverlay) overlay.hide()
        if (InputSupport.active) {
            wasUsing = false
            overlay.suspend(); WatchAccessibilityService.suspendGate()
            return
        }
        if (now < settingsUntil) { wasUsing = false; hideAll(); return }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val awake = power.isInteractive && !locked
        val monitoring = canOverlay || WatchAccessibilityService.connected || Foreground.hasUsageAccess(this)
        monitorMode = when {
            WatchAccessibilityService.connected -> "无障碍"
            Foreground.hasUsageAccess(this) -> "使用情况"
            canOverlay -> "仅悬浮窗"
            else -> "未监控"
        }
        val pkg = Foreground.current(this)
        // With only overlay permission, use awake time outside our own activities.
        val active = awake && monitoring && if (pkg == null) canOverlay else !exempt(pkg)
        val protecting = awake && monitoring && if (pkg == null) canOverlay else !exempt(pkg, false)
        val reason = DeviceRuntime.learning.status().getString("reason")
        val feedbackVisible = hasGate() && DeviceRuntime.store.get("review_feedback").isNotEmpty()
        wasUsing = active && reason.isEmpty() && !feedbackVisible
        if (!awake || !monitoring || (reason.isEmpty() && !feedbackVisible) || (pkg != null && exempt(pkg, false))) hideAll()
        if (protecting && reason.isNotEmpty() && now - lastGate > 1800 && !hasGate()) {
            lastGate = now
            val shown = WatchAccessibilityService.showGate(reason) || (canOverlay && overlay.show(reason))
            if (!shown && Build.VERSION.SDK_INT < 29) {
                try {
                    startActivity(Intent(this, GateActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reason", reason))
                } catch (_: Exception) { }
            }
        }
    }

    private fun updateAnchor(allowed: Boolean) {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!allowed) {
            anchor?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) { } }
            anchor = null
        } else if (anchor == null) {
            val view = View(this).apply { setBackgroundColor(Color.argb(1, 0, 0, 0)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val params = WindowManager.LayoutParams(1, 1, GateOverlay.applicationWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.title = "StudyOnTV anchor"
            params.alpha = 0.01f
            try { manager.addView(view, params); anchor = view } catch (_: Exception) { }
        }
    }

    private fun hideAll() { overlay.hide(); WatchAccessibilityService.hideGate() }

    private fun exempt(pkg: String, includeLauncher: Boolean = true): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("packageinstaller")) return true
        if (pkg.contains("permissioncontroller") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return true
        if (pkg in setOf("com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.emergency")) return true
        // 系统设置：计时时不算使用（includeLauncher=true）；但锁屏判断时（includeLauncher=false）不能当作安全区，
        // 否则孩子按遥控器“设置”键进系统设置，就能关掉无障碍或强行停止本应用。家长要进设置，走家长设置页里的入口（会放行 2 分钟）。
        if (includeLauncher && (pkg == systemSettingsPackage || pkg in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings"))) return true
        if (!includeLauncher) return false
        val launcher = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pkg == launcher || Foreground.isLauncher(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        DeviceRuntime.ensure(this)
        overlay = GateOverlay(this, GateOverlay.applicationWindowType())
        instance = this
        Prefs.setServiceRunning(this, true)
        val channelId = "sotv_gate"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "使用时间管理", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setContentTitle("题了个题 · 使用时间管理中").setContentText("点击打开学习任务")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideAll(); updateAnchor(false)
        instance = null
        Prefs.setServiceRunning(this, false)
        super.onDestroy()
    }

    companion object {
        private var instance: GateService? = null
        /** 当前识别前台应用的方式：无障碍 / 使用情况 / 仅悬浮窗（只能按亮屏时间粗略计时）/ 未监控 */
        @Volatile var monitorMode: String = ""
        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun hasGate(): Boolean = WatchAccessibilityService.hasGate() || instance?.overlay?.isShowing == true
        fun hideGates() {
            Handler(Looper.getMainLooper()).post { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
        }
        fun onOwnActivityResumed() {
            instance?.settingsUntil = 0L
            Handler(Looper.getMainLooper()).post {
                if (InputSupport.active) { instance?.overlay?.suspend(); WatchAccessibilityService.suspendGate() }
                else { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
            }
        }
        fun openingSystemPage() {
            instance?.let { service ->
                service.settingsUntil = SystemClock.elapsedRealtime() + 120000
                service.wasUsing = false
            }
            hideGates()
        }
        fun onForegroundChanged(context: Context, pkg: String?) {
            instance?.handler?.post { instance?.checkForeground() }
        }
    }
}
