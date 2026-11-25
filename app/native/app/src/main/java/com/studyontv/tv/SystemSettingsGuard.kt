package com.studyontv.tv

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings

/**
 * 系统设置与厂商「应用管理 / 手机管家」的识别与放行。
 *
 * 安卓平板开启普通设备管理员「防卸载」后，桌面无法直删，但若能进入系统设置或厂商管家里的
 * 「应用管理 / 设备管理应用」，孩子仍可取消激活并卸载；此外在「自由时段」或家长点击「临时解锁」
 * 给孩子看平板期间，同样需要守住系统设置入口（仅在关闭总管控、无限时关闭管控或家长刚验证密码时放行）。
 */
object SystemSettingsGuard {
    /** 原生/电视系统设置核心包名 */
    private val CORE_SETTINGS = setOf(
        "com.android.settings",
        "com.android.tv.settings",
        "com.google.android.tv.settings",
        // 三星
        "com.samsung.accessibility"
    )

    /** 国产平板/手机厂商的「应用管理 / 安全中心 / 手机管家」包名（常在其他应用上方弹出非 Activity 隐私浮层，需区分真实页面与浮层） */
    private val VENDOR_SECURITY_CENTERS = setOf(
        // 小米 / 红米（MIUI / HyperOS）：应用详情与应用管理实际运行在手机管家
        "com.miui.securitycenter",
        "com.miui.guardprovider",
        // OPPO / 一加 / 真我（ColorOS）
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.oplus.appdetail",
        // vivo / iQOO（OriginOS）
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        // 华为 / 荣耀（HarmonyOS / MagicOS）
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        // 联想拯救者 / 小新平板（ZUI）
        "com.zui.safecenter"
    )

    /** 系统设置及主流电视 / 国产平板手机的「应用管理 / 安全中心」包名 */
    private val KNOWN = CORE_SETTINGS + VENDOR_SECURITY_CENTERS

    fun isVendorSecurityCenter(pkg: String?): Boolean = pkg != null && pkg in VENDOR_SECURITY_CENTERS
    const val GRACE_MS = 120_000L
    const val TAMPER_CLEAR_GUARD_MS = 1_500L
    /** 仅悬浮窗（无法识别前台包名 pkg == null）模式下防卸载锁的最长存活时间，避免永久锁死 */
    const val TAMPER_MAX_DURATION_MS = 60_000L

    @Volatile private var resolved = false
    private var resolvedPackage: String? = null
    private var resolvedPackages: Set<String> = emptySet()

    /** 家长确认身份后的放行截止时间（elapsedRealtime） */
    @Volatile private var until = 0L

    /** 检测到取消激活设备管理员或卸载本应用时的拦截时间戳（>0 表示待拦截，由 grant/revoke 或切走应用清除） */
    @Volatile private var tamperAt = 0L

    fun active(now: Long = SystemClock.elapsedRealtime()): Boolean = until > 0L && now < until

    fun grant(durationMs: Long = GRACE_MS, now: Long = SystemClock.elapsedRealtime()) {
        tamperAt = 0L
        until = now + durationMs
    }

    fun revoke() {
        until = 0L
        tamperAt = 0L
    }

    fun flagTamperAttempt(now: Long = SystemClock.elapsedRealtime()) {
        tamperAt = now.coerceAtLeast(1L)
    }

    fun tamperActive(now: Long = SystemClock.elapsedRealtime(), hasForegroundPackage: Boolean = true): Boolean {
        val flaggedAt = tamperAt
        if (flaggedAt <= 0L) return false
        if (!hasForegroundPackage && now - flaggedAt >= TAMPER_MAX_DURATION_MS) {
            tamperAt = 0L
            return false
        }
        return true
    }

    internal fun syncTamperState(
        pkg: String?,
        selfPackage: String?,
        isSettingsPkg: Boolean,
        now: Long
    ) {
        val flaggedAt = tamperAt
        if (flaggedAt <= 0L) return
        val elapsed = now - flaggedAt
        if (pkg.isNullOrEmpty()) {
            if (elapsed >= TAMPER_MAX_DURATION_MS) tamperAt = 0L
            return
        }
        if (elapsed < TAMPER_CLEAR_GUARD_MS) return
        if (pkg == selfPackage || Foreground.isTransient(pkg)) return
        if (pkg.contains("packageinstaller", ignoreCase = true) || isSettingsPkg) return
        tamperAt = 0L
    }

    /** 周期同步当前前台包名与 tamper 状态：离开设置/安装器满 1.5s 自动清除，无包名识别能力时超上限自动过期 */
    fun syncForeground(context: Context, pkg: String?, now: Long = SystemClock.elapsedRealtime()) {
        if (tamperAt <= 0L) return
        val isSettingsPkg = !pkg.isNullOrEmpty() && isSettings(context, pkg)
        syncTamperState(pkg, context.packageName, isSettingsPkg, now)
    }

    /** 切换到其它正常外部应用超过 1.5s 后清除卸载/取消激活拦截标记，避免残留在桌面上 */
    fun onExternalWindowChanged(context: Context, pkg: String?, now: Long = SystemClock.elapsedRealtime()) {
        syncForeground(context, pkg, now)
    }

    /**
     * 设备管理员收到取消激活请求，或无障碍捕获到卸载本应用弹窗时调用。
     * 即使平板未授予无障碍 / 使用情况访问（仅开了悬浮窗），也能立刻拉起系统设置密码锁。
     */
    fun onPotentialTamper(context: Context): Boolean = try {
        DeviceRuntime.ensure(context)
        val config = DeviceRuntime.store.config()
        val status = DeviceRuntime.learning.status()
        val unlimited = status.optJSONObject("override")?.optString("mode") == "indefinite"
        if (config.optBoolean("supervisionEnabled", true) && !unlimited &&
            config.optBoolean("blockSettingsDuringControl", true) &&
            !active() && !AdminAuth.recentlyVerified()
        ) {
            Diag.warn("Guard", "检测到尝试关闭防卸载或卸载本应用，触发设置拦截锁")
            flagTamperAttempt()
            GateService.ensureRunning(context, "tamper_guard")
            GateService.refreshNow()
            true
        } else false
    } catch (_: Throwable) {
        false
    }

    private fun ensureResolved(context: Context) {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            val pm = context.packageManager
            val self = context.packageName
            fun resolvePkg(intent: Intent): String? = try {
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName
                    ?.takeIf { it.isNotEmpty() && it != "android" && it != self }
            } catch (_: Throwable) { null }

            val primary = resolvePkg(Intent(Settings.ACTION_SETTINGS))
            val set = LinkedHashSet<String>()
            if (primary != null) set.add(primary)
            listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", self, null)),
                Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
                Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            ).forEach { intent -> resolvePkg(intent)?.let { set.add(it) } }
            resolvedPackage = primary
            resolvedPackages = set
            resolved = true
        }
    }

    /** 系统「设置」主应用的包名 */
    fun settingsPackage(context: Context): String? {
        ensureResolved(context)
        return resolvedPackage
    }

    fun isSettings(context: Context, pkg: String): Boolean {
        ensureResolved(context)
        return pkg in KNOWN || pkg in resolvedPackages
    }

    internal fun evaluateBlocked(
        awake: Boolean,
        monitoring: Boolean,
        unlimited: Boolean,
        pkg: String?,
        selfPackage: String?,
        isSettingsPkg: Boolean,
        blockSettings: Boolean,
        recentlyVerified: Boolean,
        now: Long
    ): Boolean {
        if (!awake || !monitoring || unlimited || !blockSettings || recentlyVerified || active(now)) return false
        if (tamperAt > 0L) syncTamperState(pkg, selfPackage, isSettingsPkg, now)
        return (pkg != null && isSettingsPkg) || tamperActive(now, hasForegroundPackage = !pkg.isNullOrEmpty())
    }

    /**
     * 管控开启期间（含答题后可用时长、自由时段、家长临时解锁）是否要把系统设置与应用管理挡回去。
     * 仅在家长开启了「无限时关闭管控」（`unlimited = true`）、关闭此开关、或刚刚验证过管理密码时放行。
     */
    fun blocked(
        context: Context,
        awake: Boolean,
        monitoring: Boolean,
        unlimited: Boolean,
        pkg: String?,
        blockSettings: Boolean,
        recentlyVerified: Boolean,
        now: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val isSettingsPkg = !pkg.isNullOrEmpty() && isSettings(context, pkg)
        return evaluateBlocked(
            awake = awake,
            monitoring = monitoring,
            unlimited = unlimited,
            pkg = pkg,
            selfPackage = context.packageName,
            isSettingsPkg = isSettingsPkg,
            blockSettings = blockSettings,
            recentlyVerified = recentlyVerified,
            now = now
        )
    }
}
