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
    private var lastFallbackNotify = 0L
    private var lastFallbackActivityAt = 0L
    private var gateFailures = 0
    private var lastGateError = ""
    private var lastGateErrorAt = -1L
    private var muteMismatchSince = -1L
    private var lastMuteMismatchAt = -1L
    private var lastMuteMismatchLogAt = 0L
    /** 上一轮 tick 的状态：mediaTick / diagnostics 用它做「静音是否同源」的纯函数判定 */
    @Volatile private var freeNow = false
    @Volatile private var gateExpectedNow = false
    private var lastDiag = ""
    private var lastAccessibilityCheckAt = -1L
    private var countdownActiveAt = -1L
    private var countdownPackage: String? = null
    private var lastAwake: Boolean? = null
    /** 「一个监控能力都没有」的起始时刻 / 是否已经提示过（#17：不能静默失效） */
    private var capabilityMissingSince = -1L
    private var capabilityMissingNotified = false
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var ownActivityForeground = false
    @Volatile private var monitoringNow = false
    @Volatile private var settingsUntil = 0L
    private val systemSettingsPackage by lazy {
        packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }
    private var anchor: View? = null
    private lateinit var overlay: GateOverlay
    private val countdown by lazy { CountdownOverlay(this) }
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
        val canOverlay = OverlayAccess.canDraw(this)
        updateAnchor(canOverlay && DeviceRuntime.store.config().optBoolean("backgroundAnchorEnabled", true))
        if (!canOverlay) overlay.hide()
        val status = DeviceRuntime.learning.status()
        val free = status.optBoolean("free")
        freeNow = free
        if (free) MediaPause.releaseAll(this)
        if (InputSupport.active) {
            wasUsing = false
            overlay.suspend(); WatchAccessibilityService.suspendGate()
            countdown.hide()
            return
        }
        if (now < settingsUntil) { wasUsing = false; hideAll(); countdown.hide(); if (MediaPause.active) releaseMuteWithoutGate("settings"); return }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val awake = power.isInteractive && !locked
        if (lastAwake != awake) { DeviceRuntime.learning.awake(awake); lastAwake = awake }
        val monitoring = canOverlay || WatchAccessibilityService.connected || Foreground.hasUsageAccess(this)
        // #17 次要项：新装机一个监控权限都没有时，不能一声不响（连续 5 分钟才提示一次）
        trackCapability(monitoring, now)
        // 自家 Activity（学习页 / 家长设置 / 引导页）在前台时不计时也不拦截。
        // Foreground.current() 保证不会返回本应用包名，所以这里单独判定。
        val ownForeground = Foreground.hasResumedActivity()
        val pkg = Foreground.current(this)
        foregroundPackage = pkg
        ownActivityForeground = ownForeground
        monitoringNow = monitoring
        // 记住孩子正在用的应用，解锁后用它「回到原画面」（issue #68 / #74）
        if (pkg != null && pkg != packageName && !exempt(pkg, false)) rememberActive(pkg)
        // With only overlay permission, use awake time outside our own activities.
        // 桌面包名 == 首页包名的机型（海信 VIDAA 聚好看）：看片时前台仍是“桌面”包，
        // 此时用系统播放状态兜底，否则永远不扣时间、到点也不弹题（issue #66）。
        // #93 / #17：识别不到前台应用（pkg == null）时一律按亮屏时间计时。以前只有
        // 「仅有悬浮窗权限」才这样兜底，有使用情况 / 无障碍权限却拿到 null 时会静默停止计时，
        // 表现就是「显示还有 10 分钟却永远不减少、到点也不弹」。孩子在学习页时走 ownForeground。
        val active = awake && monitoring && !ownForeground && when {
            pkg == null -> true
            !exempt(pkg) -> true
            else -> launcherPlaying(pkg) || (pkg != packageName && (pkg == launcherPackage() || Foreground.isTransient(pkg)) &&
                DeviceRuntime.store.config().optBoolean("trafficAssistEnabled") && PlaybackTraffic.playing(this))
        }
        val protecting = awake && monitoring && !ownForeground && (pkg == null || !exempt(pkg, false))
        val reason = status.getString("reason")
        val settingsBlocked = awake && monitoring && !free && pkg != null && isSettings(pkg) &&
            DeviceRuntime.store.config().optBoolean("blockSettingsDuringControl", true) && !AdminAuth.recentlyVerified()
        val gateReason = if (settingsBlocked) "settings" else reason
        val pendingFeedback = DeviceRuntime.store.get("review_feedback").isNotEmpty()
        val practice = DeviceRuntime.store.config().optString("playMode") == "practice"
        // 一次 tick 内统一口径：什么情况下应该有 Gate（静音与弹窗共用这个判断）
        gateExpectedNow = !free && (gateReason.isNotEmpty() || pendingFeedback || practice)
        val feedbackVisible = !free && hasGate() && pendingFeedback
        if (!free) Foreground.learningActivity()?.let {
            if (gateExpectedNow) MediaPause.pause(it) else MediaPause.release(it)
        }
        wasUsing = active && !free && reason.isEmpty() && !feedbackVisible
        if (!awake || !monitoring || (!settingsBlocked && (ownForeground || (reason.isEmpty() && !feedbackVisible) || (pkg != null && exempt(pkg, false))))) hideAll()
        val snapshot = "reason=$gateReason free=$free awake=$awake monitoring=$monitoring own=$ownForeground pkg=$pkg active=$active gate=${hasGate()} overlay=$canOverlay mute=${MediaPause.active} failures=$gateFailures"
        if (snapshot != lastDiag) { lastDiag = snapshot; Diag.info("Gate", snapshot) }
        ensureGateVisible(now, gateReason, canOverlay, protecting, settingsBlocked)
        // #110：静音必须由「真的弹出来了」背书——弹窗失败或被收起后立刻解除静音，
        // 否则出现「视频被静音了，却没人来答题」。
        if (MediaPause.active && !muteBackedByVisibleGate()) releaseMuteWithoutGate("foreground")
        updateCountdown(status, active, free, canOverlay, awake, monitoring, ownForeground, settingsBlocked, pkg)
    }

    /**
     * 确保 Gate 真的被弹出来（issue #110）。
     *
     * 与旧实现的三点区别：
     * 1. `addView` 成功但窗口 0 尺寸 / 不可见也算失败（部分电视会接受窗口却不显示）；
     * 2. 失败后按 2s → 3s → 5s → 10s（上限）退避重试，不会因为成功路径的 1.8s 防抖
     *    或 10s 兜底通知节流把重试吞掉；
     * 3. 失败时全屏 Activity 每次都试一把（SINGLE_TOP 去重），通知单独节流 10s。
     */
    private fun ensureGateVisible(now: Long, gateReason: String, canOverlay: Boolean, protecting: Boolean, settingsBlocked: Boolean) {
        // attach 了却一直不可见：不能算「已弹出」，当作失败并重试（hide 会解除静音）
        if (overlay.isShowing && !overlay.presenting(now)) {
            lastGateError = overlay.lastError.ifEmpty { "overlay attached but not visible kind=${overlay.lastErrorKind}" }
            lastGateErrorAt = now
            gateFailures++
            Diag.warn("Gate", "overlay attached but never visible (attempt=$gateFailures) $lastGateError → hide and retry")
            // 用 hide 而不是 destroy：保留 WebView 供 60 秒内下次弹出复用，避免每几秒重建一次
            overlay.hide()
        }
        // 全屏学习页已经在前台 = 已经弹出来了（SDK<26 的老盒子走的就是这条路），
        // 不要再每 2 秒重复拉起 / 重复写日志。
        if (Foreground.learningActivity() != null) {
            if (gateFailures > 0) Diag.info("Gate", "gate 已由全屏学习页展示，重置 $gateFailures 次失败记录")
            gateFailures = 0
            lastGateError = ""
            lastGateErrorAt = -1L
            return
        }
        if (!(protecting || settingsBlocked) || gateReason.isEmpty() || hasGate()) return
        val delay = if (gateFailures > 0) GatePresentation.failureRetryDelayMs(gateFailures) else GatePresentation.VISIBLE_RETRY_MS
        if (now - lastGate <= delay) return
        lastGate = now
        // 斐讯 T1 等 SDK < 26 的超老盒子悬浮 WebView 易黑屏，优先走全屏 Activity。
        // 悬浮窗有权限时：无障碍覆盖层 → 悬浮 WebView；无权限时直接兜底 GateActivity（#49）。
        val shown = try {
            if (Build.VERSION.SDK_INT < 26) false
            else if (canOverlay) WatchAccessibilityService.showGate(gateReason) || overlay.show(gateReason)
            else WatchAccessibilityService.showGate(gateReason)  // 无悬浮窗时无障碍仍可用
        } catch (e: Exception) {
            lastGateError = "showGate ${e.javaClass.simpleName}: ${e.message ?: ""}"
            Diag.error("Gate", "gate show crashed", e)
            false
        }
        if (shown) {
            if (gateFailures > 0) Diag.info("Gate", "gate shown after $gateFailures failed attempts reason=$gateReason")
            gateFailures = 0
            lastGateError = ""
            lastGateErrorAt = -1L
            return
        }
        gateFailures++
        lastGateErrorAt = now
        // 注意：失败原因必须在本次尝试之后取，旧实现先打日志再取 overlay.lastError，
        // 日志里永远是上一次（或空）的原因。
        lastGateError = GatePresentation.describeShowFailure(
            canDraw = canOverlay,
            accessibility = WatchAccessibilityService.connected,
            windowType = GateOverlay.applicationWindowType(),
            overlayError = overlay.lastError.ifEmpty { "kind=${overlay.lastErrorKind}" },
            api = Build.VERSION.SDK_INT)
        Diag.warn("Gate", "cannot show gate attempt=$gateFailures reason=$gateReason retry=${GatePresentation.failureRetryDelayMs(gateFailures)}ms $lastGateError")
        // 静音与弹窗同源：弹窗没弹出来就不许继续静音
        if (MediaPause.active) {
            Diag.warn("Audio", "gate not shown → release mute (owners=${MediaPause.ownerCount})")
            MediaPause.releaseAll(this)
        }
        fallbackToActivity(now, gateReason)
    }

    /**
     * #17 次要项：悬浮窗 / 无障碍 / 使用情况访问全部缺失时，计时和拦截都不会生效。
     * 旧实现只在「曾经授权过悬浮窗又被系统撤回」时发通知，新装机完全没权限时一声不响，
     * 家长以为管控在工作。这里连续 [CAPABILITY_GRACE_MS] 没有任何监控能力才提示一次（可点击去授权），
     * 权限恢复后清除通知并重新允许下一次提示。
     */
    private fun trackCapability(monitoring: Boolean, now: Long) {
        if (monitoring) {
            capabilityMissingSince = -1L
            if (capabilityMissingNotified) {
                capabilityMissingNotified = false
                try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1006) } catch (_: Exception) { }
                Diag.info("Gate", "监控权限已恢复，清除「缺少权限」提示")
            }
            return
        }
        if (capabilityMissingSince < 0) { capabilityMissingSince = now; return }
        if (capabilityMissingNotified || now - capabilityMissingSince < CAPABILITY_GRACE_MS) return
        if (!Prefs.onboarded(this)) return
        capabilityMissingNotified = true
        Diag.warn("Gate", "no capability: 悬浮窗/无障碍/使用情况访问全部缺失 → 计时与拦截都不会生效，已提示家长")
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 6, Intent(this, OnboardingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1006, builder.setContentTitle("使用时间管理还没生效")
                .setContentText("缺少悬浮窗权限，计时和答题拦截都不会生效，请点此开启")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { Diag.error("Gate", "cannot notify missing capability", e) }
    }

    /** 悬浮层 / 无障碍覆盖层都失败时的最后手段：全屏学习页 + 可点击通知 */
    private fun fallbackToActivity(now: Long, gateReason: String) {
        // 自己已经在全屏学习页上时不再重复拉起（否则会每 10 秒重置一次答题）。
        // 但只有在学习页真的在前台时才算，不能因为 hasGate() 之类的残留标记把兜底挡掉。
        if (Foreground.learningActivity() == null) {
            lastFallbackActivityAt = now
            try {
                startActivity(Intent(this, GateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("reason", gateReason))
                Diag.info("Gate", "gate fallback activity started reason=$gateReason")
            } catch (e: Exception) {
                lastGateError += " startActivity=${e.javaClass.simpleName}: ${e.message ?: ""}"
                Diag.error("Gate", "cannot open gate activity", e)
            }
        }
        if (now - lastFallbackNotify > 10_000) {
            lastFallbackNotify = now
            notifyGateFallback(gateReason)
        }
    }

    /** Gate 是不是真的在屏幕上（悬浮层通过可见性校验 / 无障碍覆盖层 / 全屏学习页） */
    private fun gateShown(): Boolean = GatePresentation.gateVisible(
        accessibilityGate = WatchAccessibilityService.hasGate(),
        overlayPresenting = overlay.presenting(),
        learningActivity = Foreground.learningActivity() != null,
        gateExpected = gateExpectedNow,
        fallbackPending = lastFallbackActivityAt > 0 && SystemClock.elapsedRealtime() - lastFallbackActivityAt < GatePresentation.FALLBACK_GRACE_MS)

    /** 静音是否允许：只有 Gate 真的可见（或答题输入页在前台）才允许持续静音/暂停 */
    private fun muteBackedByVisibleGate(): Boolean = GatePresentation.muteAllowed(
        gateVisible = gateShown(), inputActive = InputSupport.active, free = freeNow)

    /** 静音与弹窗不同源：立即解除静音，并在诊断里留下可读的一笔（issue #110） */
    private fun releaseMuteWithoutGate(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (muteMismatchSince < 0) muteMismatchSince = now
        lastMuteMismatchAt = now
        if (now - lastMuteMismatchLogAt > 5000) {
            lastMuteMismatchLogAt = now
            val age = if (MediaPause.lastPauseAt > 0) (now - MediaPause.lastPauseAt) / 1000 else 0
            Diag.warn("Audio", "mute-without-gate($source): 已静音${age}s owners=${MediaPause.ownerCount} gate=${hasGate()} " +
                "visible=${overlay.verifiedVisible} failures=$gateFailures reason=$lastGateError → release")
        }
        MediaPause.releaseAll(this)
        muteMismatchSince = -1L
    }

    /** 看电视期间在画面右上角显示剩余分钟数；不在看电视 / 拦截中 / 自由时段 / 家长解锁时隐藏 */
    private fun updateCountdown(status: org.json.JSONObject, active: Boolean, free: Boolean, canOverlay: Boolean,
                                awake: Boolean, monitoring: Boolean, ownForeground: Boolean, settingsBlocked: Boolean, pkg: String?) {
        val now = SystemClock.elapsedRealtime()
        if (active) { countdownActiveAt = now; countdownPackage = pkg }
        // 同一播放器短暂缓冲时保留角标，切换应用、关屏或进入管控页仍立即隐藏。
        val recentlyActive = countdownActiveAt >= 0 && now - countdownActiveAt < 12_000 && pkg == countdownPackage
        val seconds = status.optLong("remainingSeconds") + status.optLong("freeQuotaSeconds")
        val show = DeviceRuntime.store.config().optBoolean("showCountdown", true) && canOverlay && awake &&
            monitoring && !ownForeground && !settingsBlocked && (pkg == null || !exempt(pkg, false)) &&
            (active || recentlyActive) && !free && status.optString("reason").isEmpty() &&
            status.optJSONObject("override") == null && seconds > 0 && !hasGate()
        if (show) countdown.update(seconds) else {
            countdown.hide()
            countdownActiveAt = -1L
            countdownPackage = null
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

    // Only dismisses the gate (overlay window / accessibility layer). The countdown badge is
    // driven separately by updateCountdown() every tick; hiding it here as well would remove
    // and re-add its window on every tick, which is what caused the per-second flicker.
    private fun hideAll() { overlay.hide(); WatchAccessibilityService.hideGate() }

    private val mediaTick = object : Runnable {
        override fun run() {
            try { checkAccessibilityLost() } catch (e: Exception) { Diag.error("Gate", "accessibility self-check failed", e) }
            try { BankAutomation.tick(DeviceRuntime.store) } catch (_: Exception) { }
            // #110：只有 Gate 真的在屏幕上才持续静音；否则立即解除，不能让「静音了但没弹窗」卡住。
            if (MediaPause.active && !InputSupport.active) {
                if (muteBackedByVisibleGate()) MediaPause.enforce(this@GateService)
                else releaseMuteWithoutGate("media")
            } else if (!MediaPause.active) {
                // 上次恢复因权限 / 前台限制没生效时保留记录，这里每秒重试一次。
                MediaPause.recover(this@GateService)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkOverlayPermission() {
        val p = getSharedPreferences("sotv", MODE_PRIVATE)
        val granted = GateOverlay.canDraw(this)
        if (granted) {
            if (!p.getBoolean("overlay_was_granted", false)) p.edit().putBoolean("overlay_was_granted", true).apply()
            return
        }
        if (!p.getBoolean("overlay_was_granted", false)) return
        p.edit().putBoolean("overlay_was_granted", false).apply()
        Diag.warn("Gate", "overlay permission was revoked by system")
        BuglyInit.reportPermissionLost("SYSTEM_ALERT_WINDOW", "revoked by system firmware")
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 2, Intent(this, OnboardingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1003, builder.setContentTitle("悬浮窗权限缺失，答题拦截可能已停止")
                .setContentText("孩子可能已绕过时间限制，请点此重新授权悬浮窗")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { Diag.error("Gate", "cannot notify overlay revoked", e) }
    }

    private fun checkAccessibilityLost() {
        val now = SystemClock.elapsedRealtime()
        if (lastAccessibilityCheckAt >= 0 && now - lastAccessibilityCheckAt < 60_000L) return
        lastAccessibilityCheckAt = now
        val p = getSharedPreferences("sotv", MODE_PRIVATE)
        val wasEnabled = p.getBoolean("accessibility_was_enabled", false)
        val nowEnabled = WatchAccessibilityService.enabled(this)
        val lost = !nowEnabled && (wasEnabled || p.getBoolean("accessibility_lost", false))
        p.edit().putBoolean("accessibility_was_enabled", nowEnabled).putBoolean("accessibility_lost", lost).apply()
        if (nowEnabled) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1005)
        if (!wasEnabled || nowEnabled) return
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 5, Intent(this, OnboardingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1005, builder
                .setContentTitle("无障碍权限缺失，答题拦截可能已停止")
                .setContentText("孩子可能已绕过时间限制，请点此重新开启无障碍服务")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(false).build())
            Diag.warn("Gate", "accessibility permission lost, notified parent")
            BuglyInit.reportPermissionLost("ACCESSIBILITY_SERVICE", "permission removed during monitoring or restart")
        } catch (e: Exception) { Diag.error("Gate", "cannot notify accessibility lost", e) }
    }

    private fun notifyGateFallback(reason: String) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reason", reason)
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            // 失败原因的人话版进通知正文：家长能看到“为什么没弹”而不用去导诊断日志
            val why = GatePresentation.failureSummary(OverlayAccess.canDraw(this), WatchAccessibilityService.connected, Build.VERSION.SDK_INT)
            manager.notify(1002, builder.setContentTitle("到学习时间了")
                .setContentText("没自动弹出学习页（$why），点这里开始答题")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(gateFailures < 3).build())
            Diag.warn("Gate", "fallback notification posted reason=$reason why=$why failures=$gateFailures")
        } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot post fallback notification", e) }
    }

    /**
     * 系统解析出来的 HOME 包。
     *
     * 不能长期缓存：用户可能先装应用再换默认桌面，部分固件也会在升级后改 HOME 包名，
     * 一旦缓存了旧值，「桌面包在播放」的判定就永远不会命中（issue #66）。
     * 这里只做 10 秒短缓存，和 exempt() 里每秒 resolveActivity 的开销相当。
     */
    private var launcherPkgCache: String? = null
    private var launcherClassCache: String? = null
    private var launcherPkgAt = 0L
    private fun launcherPackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - launcherPkgAt < 10_000) return launcherPkgCache
        val home = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo
        } catch (_: Exception) { null }
        launcherPkgCache = home?.packageName
        launcherClassCache = home?.name
        launcherPkgAt = now
        return launcherPkgCache
    }

    /**
     * 只有「厂商把播放器塞进了 HOME 包」这一个特殊情况才把桌面算成在用，
     * 已知的系统桌面（Foreground.isLauncher）永远不算，避免在桌面挂着就被计时。
     */
    private fun launcherPlaying(pkg: String): Boolean {
        if (pkg != launcherPackage()) return false
        if (Foreground.isLauncher(pkg)) return false
        // JUUI / 聚好看的 HOME 与影视页共用包名；Activity 可区分时不依赖媒体状态（#99）。
        val cls = Foreground.currentClass(pkg)
        if (cls != null && cls != launcherClassCache && !cls.contains("launcher", true) &&
            !cls.startsWith("android.") && !cls.startsWith("androidx.")) return true
        return Playback.hasActive(this)
    }

    private fun isSettings(pkg: String): Boolean = pkg == systemSettingsPackage ||
        pkg in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings")

    private fun exempt(pkg: String, includeLauncher: Boolean = true): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("packageinstaller")) return true
        if (pkg.contains("permissioncontroller") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return true
        if (pkg == systemSettingsPackage || pkg in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings", "com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.emergency")) return true
        if (!includeLauncher) return false
        val launcher = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pkg == launcher || Foreground.isLauncher(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        DeviceRuntime.ensure(this)
        UninstallGuard.restoreOwnerPolicy(this)
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
        MediaPause.recover(this)
        checkOverlayPermission()
        checkAccessibilityLost()   // 进程被杀后重启，检查无障碍是否被系统清除
        Diag.info("Service", "GateService started via=$startVia")
        Watchdog.serviceStarted(this, startVia)
        Watchdog.schedule(this)
        Watchdog.scheduleAlarm(this)
        handler.post(tick)
        handler.postDelayed(mediaTick, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    /** 手机上从“最近任务”划掉应用时，部分系统会连带停止服务；2 秒后自动拉起 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart(this, 2000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { DeviceRuntime.learning.flushUsage() } catch (_: Exception) { }
        if (Prefs.onboarded(this)) scheduleRestart(this, 5000)
        handler.removeCallbacks(tick); handler.removeCallbacks(mediaTick)
        MediaPause.release(this)
        overlay.destroy(); WatchAccessibilityService.hideGate(); countdown.destroy(); updateAnchor(false)
        instance = null
        Prefs.setServiceRunning(this, false)
        Diag.info("Service", "GateService destroyed")
        super.onDestroy()
    }

    companion object {
        /** 一个监控能力都没有时，持续这么久才提示家长（避免刚开机 / 刚装完就弹） */
        private const val CAPABILITY_GRACE_MS = 300_000L
        private var instance: GateService? = null
        private var startVia = "app"

        /**
         * 孩子最近真正在用的受控应用。答题解锁后用它把画面还原，
         * 而不是让系统跳回桌面（issue #68 小米电视4 / #74 Sony BRAVIA）。
         */
        @Volatile private var lastActivePackage: String? = null
        fun rememberActive(pkg: String) { lastActivePackage = pkg }
        fun rememberedActive(): String? = lastActivePackage
        val running: Boolean get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** 不抛异常的启动：后台启动前台服务在 Android 12+ 可能被拒绝（有悬浮窗 / 无障碍权限时通常允许） */
        fun ensureRunning(context: Context, via: String = "") {
            if (instance != null || !Prefs.onboarded(context)) return
            if (via.isNotEmpty()) startVia = via
            try { start(context) } catch (e: Exception) { android.util.Log.w("StudyOnTV", "GateService restart refused", e) }
        }

        fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val intent = Intent(context, GateService::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pending = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 7, intent, flags)
                    else PendingIntent.getService(context, 7, intent, flags)
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending)
            } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot schedule restart", e) }
        }
        fun hasGate(): Boolean = WatchAccessibilityService.hasGate() || instance?.overlay?.isShowing == true
        /** 家长面板里的能力自检；不依赖服务是否在运行 */
        fun diagnostics(context: Context): org.json.JSONObject {
            val service = instance
            val now = SystemClock.elapsedRealtime()
            val overlay = service?.overlay
            val accessibilityGate = WatchAccessibilityService.hasGate()
            val overlayPresenting = overlay?.presenting(now) == true
            val learningActivity = Foreground.learningActivity() != null
            val gateExpected = service?.gateExpectedNow == true
            val free = freeState()
            val mute = MediaPause.active
            val gateVisible = GatePresentation.gateVisible(accessibilityGate, overlayPresenting, learningActivity, gateExpected)
            val mismatch = GatePresentation.inconsistentMute(mute, gateVisible, InputSupport.active, free)
            val mismatchSince = service?.muteMismatchSince ?: -1L
            val lastMismatchAt = service?.lastMuteMismatchAt ?: -1L
            val lastErrorAt = service?.lastGateErrorAt ?: -1L
            return org.json.JSONObject()
                .put("service", running)
                .put("overlay", OverlayAccess.canDraw(context))
                .put("usage", Foreground.hasUsageAccess(context))
                .put("accessibility", WatchAccessibilityService.connected)
                .put("accessibilityEnabled", WatchAccessibilityService.enabled(context))
                .put("accessibilityLost", !WatchAccessibilityService.enabled(context) && context.getSharedPreferences("sotv", Context.MODE_PRIVATE).getBoolean("accessibility_lost", false))
                .put("gate", hasGate())
                .put("lastTickSeconds", if (service == null) -1 else ((SystemClock.elapsedRealtime() - service.lastTick) / 1000))
                .put("lastError", service?.lastGateError ?: "")
                // === #110 “静音了但弹不出来”的可读诊断（只新增，不删旧字段） ===
                .put("gateExpected", gateExpected)
                .put("gateVisible", gateVisible)
                .put("gateFailures", service?.gateFailures ?: 0)
                .put("lastGateErrorAt", lastErrorAt)
                .put("lastGateErrorAgeSeconds", if (lastErrorAt > 0) (now - lastErrorAt) / 1000 else -1)
                .put("overlayAttached", overlay?.isShowing ?: false)
                .put("overlayVisible", overlay?.verifiedVisible ?: false)
                .put("overlayPageReady", overlay?.pageReady ?: false)
                .put("overlayErrorKind", overlay?.lastErrorKind ?: "")
                .put("overlayWindowType", GateOverlay.windowTypeName(GateOverlay.applicationWindowType()))
                .put("overlayLastError", overlay?.lastError ?: "")
                .put("overlayProbeOk", OverlayAccess.probeSucceeded)
                .put("overlayProbeError", OverlayAccess.lastProbeError)
                .put("overlayProbeErrorKind", OverlayAccess.lastProbeErrorKind)
                .put("overlayProbeAgeSeconds", if (OverlayAccess.lastProbeAt < 0) -1 else (now - OverlayAccess.lastProbeAt) / 1000)
                .put("foregroundPackage", service?.foregroundPackage ?: "")
                .put("foregroundIdentified", service?.foregroundPackage != null)
                .put("ownActivityForeground", service?.ownActivityForeground ?: false)
                .put("monitoringCapability", service?.monitoringNow ?: false)
                .put("mute", mute)
                .put("muteOwners", MediaPause.ownerCount)
                .put("muteStreamMuted", MediaPause.levelMuted)
                .put("muteForSeconds", if (!mute || MediaPause.lastPauseAt <= 0) 0L else (now - MediaPause.lastPauseAt) / 1000)
                .put("muteWithoutGate", mismatch)
                .put("muteWithoutGateSeconds", if (mismatch && mismatchSince > 0) (now - mismatchSince) / 1000 else -1)
                .put("lastMuteWithoutGateAgeSeconds", if (lastMismatchAt > 0) (now - lastMismatchAt) / 1000 else -1)
        }

        /** 学习状态可能未初始化（仅看设备能力时），诊断不能因此报错 */
        private fun freeState(): Boolean = try { DeviceRuntime.learning.status().optBoolean("free") } catch (_: Throwable) { false }
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
        fun openingSystemPage(parentAuthorized: Boolean = false) {
            if (parentAuthorized) AdminAuth.markVerified()
            instance?.let { service ->
                val canIdentify = WatchAccessibilityService.connected || Foreground.hasUsageAccess(service)
                val settingsLocked = !DeviceRuntime.learning.status().optBoolean("free") &&
                    DeviceRuntime.store.config().optBoolean("blockSettingsDuringControl", true)
                // Installation is allowed; it must not grant a child access to every Settings page.
                if (!parentAuthorized && !AdminAuth.recentlyVerified() && canIdentify && settingsLocked) return
                service.settingsUntil = SystemClock.elapsedRealtime() + 120000
                service.wasUsing = false
            }
            hideGates()
        }
        /** 遥控操作后立即重新检查，不等下一秒；锁定时去掉 1.8 秒的防抖 */
        fun refreshNow() {
            instance?.handler?.post { instance?.lastGate = 0L; instance?.checkForeground() }
        }
        fun onForegroundChanged(context: Context, pkg: String?) {
            instance?.handler?.post { instance?.checkForeground() }
        }
    }
}

/**
 * 「静音」与「弹窗」是否同源的纯判定逻辑（issue #110）。
 *
 * 这里不碰任何窗口 / 音频 API，只做布尔运算，方便在 instrumentation 里直接验证；
 * GateService、GateOverlay 和 diagnostics() 共用同一份判断，避免两边口径不一致。
 */
object GatePresentation {
    /** 成功路径的防抖窗口：已经没有可见 Gate 时才需要重新检查 */
    const val VISIBLE_RETRY_MS = 1800L
    /** addView 成功但窗口还没完成一次可见性校验的宽限期（3 次校验：1.0s / 1.9s / 2.8s） */
    const val VISIBILITY_GRACE_MS = 3200L
    /** 兜底全屏 Activity 拉起后给它的启动宽限（避免刚 startActivity 就判定「没有 Gate」而解除静音） */
    const val FALLBACK_GRACE_MS = 5000L
    /** 连续失败后的重试间隔上限 */
    const val MAX_FAILURE_RETRY_MS = 10_000L

    /**
     * 失败后的重试间隔：2s → 3s → 5s → 10s（上限）。
     * 失败路径不吃成功路径的 1.8s 防抖，也不会无限高频地重建 WebView。
     */
    fun failureRetryDelayMs(failures: Int): Long = when {
        failures <= 1 -> 2_000L
        failures <= 3 -> 3_000L
        failures <= 6 -> 5_000L
        else -> MAX_FAILURE_RETRY_MS
    }

    /** 窗口 attach 了不等于可见：宽限期内算「正在弹出」，超出宽限期仍未校验通过就是失败 */
    fun presenting(attached: Boolean, verifiedVisible: Boolean, attachedForMs: Long): Boolean =
        attached && (verifiedVisible || attachedForMs < VISIBILITY_GRACE_MS)

    /** Gate 是否真的呈现在屏幕上（悬浮层 / 无障碍覆盖层 / 全屏学习页 / 刚拉起的兜底 Activity） */
    fun gateVisible(
        accessibilityGate: Boolean,
        overlayPresenting: Boolean,
        learningActivity: Boolean,
        gateExpected: Boolean,
        fallbackPending: Boolean = false
    ): Boolean = accessibilityGate || overlayPresenting || fallbackPending || (learningActivity && gateExpected)

    /** 静音是否允许：自由时段不允许；答题输入页或可见的 Gate 才算有背书 */
    fun muteAllowed(gateVisible: Boolean, inputActive: Boolean, free: Boolean): Boolean =
        !free && (inputActive || gateVisible)

    /** 「静音了但没 Gate」的不一致状态：诊断与修复都必须能看见它 */
    fun inconsistentMute(muteActive: Boolean, gateVisible: Boolean, inputActive: Boolean, free: Boolean): Boolean =
        muteActive && !muteAllowed(gateVisible, inputActive, free)

    /** 弹窗失败原因（技术细节，进 Diag / diagnostics） */
    fun describeShowFailure(canDraw: Boolean, accessibility: Boolean, windowType: Int, overlayError: String, api: Int): String {
        val window = try { GateOverlay.windowTypeName(windowType) } catch (_: Throwable) { "TYPE_$windowType" }
        val detail = overlayError.ifEmpty { "no-error-recorded" }
        return "window=$window api=$api canDraw=$canDraw accessibility=$accessibility overlayError=$detail"
    }

    /** 弹窗失败原因（人话，进家长能看到的兜底通知） */
    fun failureSummary(canDraw: Boolean, accessibility: Boolean, api: Int): String = when {
        !canDraw && !accessibility -> "缺少悬浮窗与无障碍权限"
        api < 26 -> "系统版本过低，无法悬浮显示"
        canDraw -> "悬浮窗被系统拒绝"
        else -> "无障碍覆盖层不可用"
    }
}

/**
 * 悬浮窗可用性判定（issue #92 长虹：悬浮窗已开仍提示未开启）。
 *
 * `Settings.canDrawOverlays()` 只读 AppOps 的 SYSTEM_ALERT_WINDOW，部分厂商固件把开关映射到
 * 别的 op / 另一个 uid（多用户、分身），于是系统真实允许、我们却永远读到 false；而旧代码在
 * 真正 `addView` 之前就放弃了，「打开权限也不起作用」。
 *
 * 这里在不申请新权限的前提下按三级判定：设置 API → AppOps → 一次真实 `addView` 探针。
 * 探针结果（成功 / 失败原因）在本次会话内缓存，失败后 60 秒内不重复探测（不是每秒执行）。
 */
object OverlayAccess {
    /** 探针失败后的重试间隔：不能每秒都尝试添加窗口 */
    const val RETRY_MS = 60_000L
    /** 探测成功后的复检间隔：厂商允许 addView 时也不能每分钟都建一次窗口 */
    const val SUCCESS_RECHECK_MS = 5 * 60_000L

    @Volatile private var probed = false
    @Volatile private var probeOk = false
    @Volatile var lastProbeAt: Long = -1L
        private set
    /** 探针失败原因的原始描述：含异常类名与窗口类型，便于按机型排查 */
    @Volatile var lastProbeError: String = ""
        private set
    /** 分类：permission / bad-token / invalid-display / illegal-argument / window / no-window-service */
    @Volatile var lastProbeErrorKind: String = ""
        private set
    /** 最近一次探针是否成功（Settings=false 但厂商实际允许时为 true） */
    val probeSucceeded: Boolean get() = probeOk && probed

    /** 用户从系统设置返回后调用：让下一次 canDraw() 重新做一次真实探测 */
    fun invalidate() { probed = false }

    fun canDraw(context: Context): Boolean = settingsCanDraw(context) || appOpsAllows(context) || probe(context)

    /** 旧的唯一真值：Settings.canDrawOverlays（厂商不忠实时会一直是 false） */
    @Suppress("DEPRECATION")
    fun settingsCanDraw(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            context.checkCallingOrSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) { false }

    private fun appOpsAllows(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** 真实探测：能成功 addView 一个 1×1 透明窗口就说明系统真的允许（窗口会被立即移除） */
    private fun probe(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val interval = if (probeOk) SUCCESS_RECHECK_MS else RETRY_MS
        if (probed && now - lastProbeAt < interval) return probeOk
        probed = true
        lastProbeAt = now
        if (Looper.myLooper() == Looper.getMainLooper()) return probeOnMain(context)
        // WindowManager.addView 需要当前线程有 Looper，非主线程调用时跳到主线程执行
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        val posted = try {
            Handler(Looper.getMainLooper()).post { result = probeOnMain(context); latch.countDown() }
        } catch (_: Throwable) { false }
        if (!posted) return false.also { record("no-main-looper", "cannot post probe to main thread") }
        return if (latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) result else false.also {
            record("window", "probe timed out after 3000ms")
        }
    }

    private fun probeOnMain(context: Context): Boolean {
        val type = GateOverlay.applicationWindowType()
        val name = GateOverlay.windowTypeName(type)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) return false.also { record("no-window-service", "WindowManager unavailable type=$name") }
        val view = View(context)
        val params = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "StudyOnTV overlay probe"
        params.alpha = 0.01f
        return try {
            manager.addView(view, params)
            var removed = true
            try { manager.removeViewImmediate(view) } catch (_: Throwable) { removed = false }
            probeOk = true
            lastProbeErrorKind = ""
            lastProbeError = ""
            Diag.warn("Gate", "Settings.canDrawOverlays=false 但 1×1 探针 addView 成功（removed=$removed type=$name）→ 厂商 AppOps 不一致，本次会话按有权限处理")
            true
        } catch (e: Throwable) {
            probeOk = false
            record(classify(e), "${e.javaClass.name}: ${e.message ?: ""} type=$name")
            // 探测失败必须清理干净，不能留下一个 1×1 窗口
            try { manager.removeViewImmediate(view) } catch (_: Throwable) { }
            false
        }
    }

    private fun record(kind: String, detail: String) {
        lastProbeErrorKind = kind
        lastProbeError = detail.ifEmpty { kind }
        Diag.warn("Gate", "overlay probe failed kind=$kind $lastProbeError")
    }

    private fun classify(e: Throwable): String = when (e) {
        is WindowManager.BadTokenException -> "bad-token"
        is WindowManager.InvalidDisplayException -> "invalid-display"
        is SecurityException -> "permission"
        is IllegalArgumentException -> "illegal-argument"
        else -> "window"
    }
}
