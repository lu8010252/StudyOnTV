package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 权限引导。参考 Apple TV「设置」：左侧说明与操作，右侧是可整行选中的权限列表，
 * 每行右侧显示“已开启 / 去开启”，从系统设置返回后自动刷新。手机上为单列。
 */
class OnboardingActivity : Activity() {
    private data class Item(val row: TvUi.Row, val enabled: () -> Boolean, val optional: Boolean)
    private val items = mutableListOf<Item>()
    private lateinit var modeTitle: TextView
    private lateinit var modeDescription: TextView
    private lateinit var modeDot: View
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var notificationRequested = false
    private val refreshStatus = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }
    private val tv get() = DeviceBridge.isTv(this)
    private fun dp(value: Int) = TvUi.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        DeviceRuntime.ensure(this)
        if (tv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val p = TvUi.palette(this)
        window.decorView.setBackgroundColor(p.bg)

        // 左侧：标题、当前状态、操作
        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        intro.addView(TvUi.text(this, "使用时间管理", if (tv) 15f else 13f, p.text2, true))
        intro.addView(TvUi.text(this, "开启几项权限，\n到时间自动提醒学习", if (tv) 34f else 26f, p.text, true).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        intro.addView(TvUi.text(this, "都可以稍后在家长设置里调整。只需要悬浮窗就能开始使用，其余权限让计时更准确。", if (tv) 16f else 14f, p.text2).apply { setLineSpacing(0f, 1.3f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(p.surface); cornerRadius = dp(14).toFloat() }
        }
        modeDot = View(this)
        status.addView(modeDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
        val statusCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeTitle = TvUi.text(this, "", if (tv) 16f else 14f, p.text, true)
        modeDescription = TvUi.text(this, "", if (tv) 13f else 12f, p.text2)
        statusCopy.addView(modeTitle); statusCopy.addView(modeDescription, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        status.addView(statusCopy, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manage = TvUi.button(this, "完成，进入家长设置", true) { complete(true) }.apply { minHeight = dp(52); minimumHeight = dp(52) }
        val learn = TvUi.button(this, "先开始学习") { complete(false) }.apply { minHeight = dp(52); minimumHeight = dp(52) }
        val studyOnly = TvUi.button(this, "仅用作刷题背诵（免权限，直接进入）") { completeStudyOnly() }.apply { minHeight = dp(52); minimumHeight = dp(52) }
        actions.addView(manage, LinearLayout.LayoutParams(-1, -2))
        actions.addView(learn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (tv) 12 else 10) })
        actions.addView(studyOnly, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (tv) 12 else 10) })

        // 右侧：权限列表
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(icon: Int, title: String, description: String, optional: Boolean, enabled: () -> Boolean, action: () -> Unit) {
            val row = TvUi.row(this, icon, title, description, action)
            items.add(Item(row, enabled, optional))
            list.addView(row.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (tv) 8 else 10) })
        }
        list.addView(TvUi.text(this, "权限", if (tv) 14f else 13f, p.text3, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10); leftMargin = dp(4) })
        add(android.R.drawable.ic_menu_view, "悬浮窗", "到时间后在其他应用上方显示学习页", false, { GateOverlay.canDraw(this) }) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
        }
        add(android.R.drawable.ic_menu_manage, "无障碍服务", if (tv) "及时识别应用切换，部分电视不支持" else "及时识别应用切换", true, { WatchAccessibilityService.connected }) {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        add(android.R.drawable.ic_menu_agenda, "通知使用权", "被系统清理后由通知服务拉起；不读取通知内容", true, { RevivalListener.enabled(this) }) {
            openNotificationAccess()
        }
        add(android.R.drawable.ic_menu_recent_history, "使用情况访问", "只统计其他应用的使用时长", true, { Foreground.hasUsageAccess(this) }) {
            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
        add(android.R.drawable.ic_lock_idle_alarm, "后台运行", "关闭电池优化，减少被系统清理", true, { KeepAlive.ignoringBattery(this) }) {
            if (Build.VERSION.SDK_INT >= 23 && !KeepAlive.ignoringBattery(this)) openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
        add(android.R.drawable.ic_lock_lock, "防卸载", "孩子无法直接删除应用，家长可随时关闭", true, { UninstallGuard.active(this) }) {
            if (UninstallGuard.active(this)) openSettings(Settings.ACTION_SECURITY_SETTINGS)
            else try { GateService.openingSystemPage(parentAuthorized = true); startActivity(UninstallGuard.requestIntent(this)) } catch (_: Exception) {
                Toast.makeText(this, "这台设备不支持设备管理应用，无法开启防卸载", Toast.LENGTH_LONG).show()
            }
        }
        // 电视一般不显示通知，只在手机 / 平板上提示
        if (!tv && Build.VERSION.SDK_INT >= 33) add(android.R.drawable.ic_dialog_info, "通知", "显示“使用时间管理中”的常驻通知", true,
            { checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        // #130：不少电视 / 投影 / 平板把原生设置菜单藏起来，家长找不到授权入口。
        // 这一行不显示「已开启 / 未开启」，只是把完整系统设置直接调起来。
        val systemSettings = TvUi.row(this, android.R.drawable.ic_menu_preferences, "打开系统设置", "找不到应用管理、授权入口时从完整设置里找", ::openSystemSettings)
        list.addView(systemSettings.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (tv) 8 else 10) })
        list.addView(TvUi.text(this, if (tv) "部分电视没有“电池优化”，可在系统“应用管理”里允许自启动。" else "部分手机还需要在系统设置里允许“自启动”或“后台活动”。", if (tv) 13f else 12f, p.text3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); leftMargin = dp(4) })
        // 针对常见品牌给出更具体的自启动引导；文案与家长面板共用
        val brandTip = KeepAlive.brandTip(tv)
        if (brandTip != null) list.addView(TvUi.text(this, brandTip, if (tv) 12f else 11f, p.text3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); leftMargin = dp(4) })

        // 电视和横屏平板（宽度 ≥ 840dp）用左右两栏，手机用单列
        val wide = tv || resources.configuration.screenWidthDp >= 840
        val root: View = if (wide) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(64), dp(24), dp(64), dp(24))
                val left = LinearLayout(this@OnboardingActivity).apply { orientation = LinearLayout.VERTICAL; addView(intro); addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }) }
                addView(ScrollView(this@OnboardingActivity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(4), dp(8), dp(4), dp(8)); addView(left) },
                    LinearLayout.LayoutParams(0, -2, 0.42f))
                addView(ScrollView(this@OnboardingActivity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)); addView(list) },
                    LinearLayout.LayoutParams(0, -2, 0.58f).apply { leftMargin = dp(48) })
            }
        } else {
            ScrollView(this).apply {
                isFillViewport = true
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(24))
                    addView(intro); addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
                    addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                })
            }
        }
        setContentView(root)
        refresh()
        // 第一个还没开启的权限获得焦点；都开好了就直接落在“完成”
        (items.firstOrNull { !it.enabled() }?.row?.view ?: manage).requestFocus()
        maybeRequestNotifications()
    }

    private fun openSettings(action: String, packageSpecific: Boolean = false, fallback: String? = null) {
        GateService.openingSystemPage(parentAuthorized = true)
        val options = mutableListOf(Intent(action).apply { if (packageSpecific) data = Uri.parse("package:$packageName") })
        if (packageSpecific) options.add(Intent(action))
        if (fallback != null) options.add(Intent(fallback))
        options.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        options.add(Intent(Settings.ACTION_SETTINGS))
        for (intent in options) try { startActivity(intent); return } catch (_: Exception) { }
        Toast.makeText(this, "请在系统设置中调整权限", Toast.LENGTH_SHORT).show()
    }

    /** #130：直接调起完整系统设置，电视隐藏菜单时家长仍能找到权限页面 */
    private fun openSystemSettings() {
        if (!DeviceBridge.openSystemSettings(this)) Toast.makeText(this, "这台设备没有可调起的系统设置页面，可用电脑通过 ADB 授权", Toast.LENGTH_LONG).show()
    }

    /** 通知使用权：电视上常被裁剪掉入口，没有页面时直接给可复制的 ADB 命令。 */
    private fun openNotificationAccess() {
        GateService.openingSystemPage(parentAuthorized = true)
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return } catch (_: Exception) { }
        Toast.makeText(this, "这台设备没有“通知使用权”设置页。可用 ADB 开启：\n${RevivalListener.adbCommand(this)}", Toast.LENGTH_LONG).show()
    }

    private fun maybeRequestNotifications() {
        if (tv || Build.VERSION.SDK_INT < 33 || notificationRequested || Prefs.notificationAsked(this)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        notificationRequested = true
        Prefs.setNotificationAsked(this, true)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    private fun complete(manage: Boolean) {
        DeviceRuntime.store.saveConfig(org.json.JSONObject().put("supervisionEnabled", true))
        DeviceRuntime.learning.status()
        Prefs.setOnboarded(this, true)
        try { GateService.start(this) } catch (_: RuntimeException) {
            Toast.makeText(this, "后台管理暂未启动，您仍可继续学习", Toast.LENGTH_SHORT).show()
        }
        GateService.refreshNow()
        startActivity(Intent(this, if (manage) ParentActivity::class.java else MainActivity::class.java))
        finish()
    }

    /** 免授权纯学习模式：关闭后台时长锁屏，把应用直接当作刷题与艾宾浩斯背诵工具使用。 */
    private fun completeStudyOnly() {
        DeviceRuntime.store.saveConfig(org.json.JSONObject().put("supervisionEnabled", false))
        DeviceRuntime.learning.status()
        Prefs.setOnboarded(this, true)
        GateService.hideGates()
        GateService.refreshNow()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun refresh() {
        if (!::modeTitle.isInitialized) return
        val p = TvUi.palette(this)
        items.forEach { val on = it.enabled(); it.row.setStatus(if (on) "已开启" else if (it.optional) "未开启" else "去开启", on) }
        val overlay = GateOverlay.canDraw(this)
        val usage = Foreground.hasUsageAccess(this)
        val supervisionEnabled = try { DeviceRuntime.store.config().optBoolean("supervisionEnabled", true) } catch (_: Throwable) { true }
        val (title, description, ready) = when {
            !supervisionEnabled -> Triple("当前为纯学习模式", "点「完成」或「先开始学习」可恢复时间管理", false)
            overlay && (WatchAccessibilityService.connected || usage) -> Triple("已就绪", "到时间会自动显示学习页，计时准确", true)
            overlay -> Triple("基本可用", "离开学习页后按亮屏时间计时", true)
            usage -> Triple("可以记录使用时间", "开启悬浮窗后，才能在到时间时显示学习页", false)
            else -> Triple("尚未开启", "建议至少开启悬浮窗", false)
        }
        modeTitle.text = title; modeDescription.text = description
        modeDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ready) p.ok else p.warn) }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置回来必须重新探测悬浮窗：长虹等机型 Settings.canDrawOverlays 永远返回 false，
        // 只有真实 addView 探针能确认，否则家长授权完回到这里仍看到「未开启」（issue #92）。
        OverlayAccess.invalidate()
        handler.removeCallbacks(refreshStatus)
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }
}
