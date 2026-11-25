package com.studyontv.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.SystemClock

object Foreground {
    /** 缓存超过这个时间就不再直接采信：无障碍事件可能早就停了，缓存里的包早已不在前台（#93） */
    const val STALE_MS = 30 * 60 * 1000L

    @Volatile var lastPackage: String? = null
        private set
    @Volatile private var lastPackageAt = 0L
    @Volatile private var lastClass: String? = null
    @Volatile private var lastRealClass: String? = null
    /** 最近一个「真正的」前台应用（滤掉系统临时窗口），系统界面抢前台时用它兜底 */
    @Volatile private var lastRealPackage: String? = null
    @Volatile private var lastRealAt = 0L

    /**
     * 本应用自己的包名。任何「前台判定」都不能把「我们自己」当成孩子正在用的应用：
     * 一旦返回自身包名，GateService 里的 exempt() 恒真 ⇒ 不计时且不弹页（issue #93）。
     */
    @Volatile private var selfPackage: String? = null

    private var ownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * 这些包会以 TYPE_WINDOW_STATE_CHANGED 的形式抢到前台（音量条、通知面板、权限弹窗、下拉面板…），
     * 但它们并不是「孩子正在用的应用」。一旦把它们当成前台，就会因为被豁免而停止计时，
     * 直到下一次真正切换应用才恢复（issue #69：小米电视上被识别成 com.android.systemui，时长不再扣减）。
     */
    private val transientPackages = setOf(
        "com.android.systemui", "android", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )
    fun isTransient(pkg: String?): Boolean = pkg != null && transientPackages.contains(pkg)

    @Volatile private var cachedImePackages: Set<String> = emptySet()
    @Volatile private var cachedImeAt = 0L

    internal fun isInputMethodName(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val lower = pkg.lowercase(java.util.Locale.US)
        return lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("secime") || lower.contains("input_")
    }

    /** 识别系统已启用输入法及常见厂商安全键盘（如 com.huawei.secime、com.baidu.input_huawei），避免输密码弹键盘时触发误拦截 */
    fun isInputMethod(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (isInputMethodName(pkg)) return true
        val now = SystemClock.elapsedRealtime()
        if (cachedImeAt == 0L || now - cachedImeAt > 60_000L) {
            cachedImePackages = try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet() ?: emptySet()
            } catch (_: Exception) { emptySet() }
            cachedImeAt = now
        }
        return cachedImePackages.contains(pkg)
    }

    fun resumeOwn(activity: android.app.Activity) {
        ownActivity = java.lang.ref.WeakReference(activity)
        selfPackage = activity.packageName
        update(activity.packageName, activity.javaClass.name)
    }
    fun pauseOwn(activity: android.app.Activity) {
        if (ownActivity?.get() === activity) ownActivity = null
        if (ownActivity?.get() == null && lastPackage == activity.packageName) lastPackage = null
    }
    fun update(pkg: String?, className: String? = null) {
        if (pkg.isNullOrEmpty()) return
        lastPackage = pkg
        lastClass = className
        lastPackageAt = SystemClock.elapsedRealtime()
        // 自身的 Activity / 悬浮窗事件不能进「最近真正的前台应用」：否则解锁后会把自己拉回前台，
        // 也会让 lastRealPackage 永远是自己（#93 / #96）。
        if (!isTransient(pkg) && pkg != selfPackage) {
            lastRealPackage = pkg
            lastRealClass = className
            lastRealAt = SystemClock.elapsedRealtime()
        }
    }
    fun clearExternal() {
        lastPackage = null; lastClass = null; lastRealPackage = null; lastRealClass = null
        lastPackageAt = 0L; lastRealAt = 0L
        usageLatest = null; usageLatestClass = null; usageQueriedAt = 0L; usageLatestAt = 0L
    }

    /** 仅供 instrumentation 回归使用：把缓存时间戳往前挪，验证「明显陈旧时不再采信旧值」 */
    fun backdateCaches(millis: Long) {
        lastPackageAt -= millis
        lastRealAt -= millis
        usageLatestAt -= millis
    }

    /** 缓存是否是「最近」的（0 表示从未写入） */
    private fun fresh(at: Long): Boolean = at > 0 && SystemClock.elapsedRealtime() - at <= STALE_MS

    /** 类名与包名成对返回，避免用前一个应用的 Activity 判断当前桌面。 */
    fun currentClass(pkg: String): String? {
        ownActivity?.get()?.takeIf { it.packageName == pkg }?.let { return it.javaClass.name }
        if (lastPackage == pkg && lastClass != null) return lastClass
        if (usageLatest == pkg && usageLatestClass != null) return usageLatestClass
        return if (lastRealPackage == pkg) lastRealClass else null
    }

    /** 最近一个真正的前台应用（不含系统临时窗口），供「解锁后回到原画面」使用 */
    fun lastReal(): String? = lastRealPackage?.takeUnless { it == selfPackage || (it == lastPackage && isTransient(it)) }
    fun hasResumedActivity(): Boolean {
        val activity = ownActivity?.get() ?: return false
        if (Build.VERSION.SDK_INT >= 24 && activity.isInMultiWindowMode) return false
        return true
    }
    fun resumedActivity(): android.app.Activity? = ownActivity?.get()
    fun learningActivity(): android.app.Activity? = ownActivity?.get()?.takeIf { it is MainActivity }

    private val launcherPackages = setOf(
        "com.android.tv.launcher", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.launcher", "com.android.launcher3", "com.tcl.launcher", "com.tcl.tv.launcher", "com.mitv.tvhome",
        "com.hisense.tv.launcher", "com.google.android.apps.nexuslauncher", "com.miui.home",
        "com.sec.android.app.launcher", "com.huawei.android.launcher", "com.oppo.launcher", "com.vivo.launcher",
        // 其余常见电视桌面，避免「桌面包在播放」的播放判定在真正的桌面上误触发
        "com.skyworth.launcher", "com.coocaa.launcher", "com.changhong.launcher", "com.philips.launcher",
        "com.sony.dtv.launcher", "com.mediatek.launcher", "com.amlogic.launcher", "com.dangbei.launcher",
        "com.hisense.vidaa.launcher", "com.tcl.tv.launcherx", "com.ktc.launcher", "com.haier.launcher"
    )
    fun isLauncher(pkg: String?): Boolean = pkg != null && launcherPackages.contains(pkg)

    /**
     * 当前真正在前台的外部应用；识别不到 / 只有我们自己在前台时返回 null。
     *
     * 调用方（GateService / DeviceBridge）本来就有 `pkg == null` 分支，所以「宁可返回 null
     * 也不返回不可信的值」是安全的：返回自身包名会让 exempt() 恒真，计时和拦截同时失效
     * （issue #93 Sony 20/21），返回陈旧包名会让判定停在早已退出的应用上。
     */
    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        val self = context.packageName
        // 第一次拿到 Context 时补记自己的包名：update() 用它把「我们自己」挡在 lastRealPackage 之外
        if (selfPackage == null) selfPackage = self
        // 自家 Activity 在前台（学习页 / 家长设置 / 引导页）：不是「外部应用」。
        // 调用方用 hasResumedActivity() 区分「孩子正在我们的学习页」。
        ownActivity?.get()?.let {
            if (Build.VERSION.SDK_INT < 24 || !it.isInMultiWindowMode) return null
        }
        if (WatchAccessibilityService.connected) {
            val cached = lastPackage
            // 缓存里是系统临时窗口 / 我们自己 / 已经很久没更新时不采信，先用实时窗口与使用情况统计重新确认，
            // 再退到最近一个「真正的前台应用」。否则小米电视上会被卡在 com.android.systemui 上
            // 一直判定为系统界面而不再计时（issue #69），或者在旧包名上永远不停（#93）。
            if (cached != null && cached != self && !isTransient(cached) && !isInputMethod(context, cached) && fresh(lastPackageAt)) return cached
            WatchAccessibilityService.activeWindowPackage(context)?.let { live ->
                update(live, if (live == lastPackage) lastClass else null)
                return live
            }
            if (hasUsageAccess(context)) usageFallback(context)?.takeUnless { it == self || isTransient(it) || isInputMethod(context, it) }?.let { return it }
            // 兜底时不能返回本应用自己：那会被当成「在自家学习页」而停扣时长。
            lastRealPackage?.takeIf { it != self && !isInputMethod(context, it) && fresh(lastRealAt) }?.let { return it }
            return null
        }
        if (!hasUsageAccess(context)) return lastRealPackage?.takeIf { it != self && !isInputMethod(context, it) && fresh(lastRealAt) }
        return usageFallback(context)?.takeUnless { it == self || isTransient(it) || isInputMethod(context, it) }
    }

    /**
     * 使用情况统计兜底。
     *
     * `usageLatest` 是粘性的（没有新事件时一直是旧值），所以必须带上「多久之前看到的」：
     * 超过 [STALE_MS] 就不再采信，避免把一个早已退出的应用当成当前前台（#93）。
     * 事件里属于我们自己的包名直接跳过（除非我们的 Activity 真的在前台）。
     */
    private fun usageFallback(context: Context): String? {
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // 以前每秒都查询过去 24 小时的全部事件，在电视上很耗 CPU；现在只查上次之后的新事件
            val from = if (usageQueriedAt <= 0 || now - usageQueriedAt > 3600000 || now < usageQueriedAt) now - 86400000 else usageQueriedAt - 2000
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            var latestClass: String? = null
            var latestAge = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (isTransient(pkg) || isInputMethod(context, pkg) || pkg == context.packageName) continue
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)) {
                    latest = pkg
                    latestClass = event.className
                    latestAge = (now - event.timeStamp).coerceAtLeast(0)
                    usageLatestPaused = false
                } else if ((event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                        (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_PAUSED)) &&
                    pkg == (latest ?: usageLatest)) {
                    usageLatestPaused = true
                }
            }
            usageQueriedAt = now
            if (latest != null) {
                usageLatest = latest
                usageLatestClass = latestClass
                usageLatestAt = (SystemClock.elapsedRealtime() - latestAge).coerceAtLeast(1L)
            }
            val sticky = usageLatest
            // #173：连续使用同一款不计时应用（如网课 / 阅读 / BiliPicks）超过 30 分钟不切应用时不会产生新的前台事件；
            // 只要该应用尚未收到退后台事件，且仍在播放音视频或本身在「不计时白名单」内，继续认为它在前台，避免 30 分钟后掉回 pkg=null 误计时弹题。
            val stickyWhitelisted = sticky != null && try {
                WatchWhitelist.of(DeviceRuntime.store.config()).contains(sticky)
            } catch (_: Throwable) { false }
            if (sticky != null && !isTransient(sticky) && !isInputMethod(context, sticky) &&
                (fresh(usageLatestAt) || (!usageLatestPaused && (Playback.hasActive(context) || stickyWhitelisted)))) {
                return sticky
            }
            // 没有可用事件（例如刚开机还没有新事件）时，退到最近真的用过的应用；
            // 只接受 STALE_MS 内用过的，避免把早已退出的应用当成当前前台（#93），同时过滤输入法避免污染 usageLatest（ST-104）。
            val recent = try {
                val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000, now)
                    ?.mapNotNull { stat ->
                        val pkg = stat.packageName
                        if (pkg.isNullOrEmpty()) null else UsageCandidate(pkg, stat.lastTimeUsed)
                    }.orEmpty()
                pickRecentUsage(stats, now, context.packageName) { isInputMethod(context, it) }
            } catch (_: Exception) { null }
            if (recent == null) return null
            usageLatest = recent.packageName
            usageLatestClass = null
            // 刚重启时 elapsedRealtime 很小，减去重启前的 age 会算出负数导致 fresh(at) 永远不命中缓存
            usageLatestAt = (SystemClock.elapsedRealtime() - (now - recent.lastTimeUsed).coerceAtLeast(0)).coerceAtLeast(1L)
            return recent.packageName
        } catch (_: Exception) { null }
    }

    internal data class UsageCandidate(val packageName: String, val lastTimeUsed: Long)

    internal fun pickRecentUsage(
        candidates: Iterable<UsageCandidate>,
        now: Long,
        selfPackage: String,
        isInputMethodPkg: (String) -> Boolean
    ): UsageCandidate? = candidates
        .asSequence()
        .filter {
            it.lastTimeUsed > 0L &&
                now - it.lastTimeUsed <= STALE_MS &&
                it.packageName.isNotEmpty() &&
                it.packageName != selfPackage &&
                !isTransient(it.packageName) &&
                !isInputMethodPkg(it.packageName)
        }
        .maxByOrNull { it.lastTimeUsed }

    @Volatile private var usageQueriedAt = 0L
    @Volatile private var usageLatest: String? = null
    @Volatile private var usageLatestClass: String? = null
    /** usageLatest 是什么时候被确认的（elapsedRealtime） */
    @Volatile private var usageLatestAt = 0L
    @Volatile private var usageLatestPaused = false

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
