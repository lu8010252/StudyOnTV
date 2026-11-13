package com.studyontv.tv

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * 「后台保活」自检：只读取系统状态，不申请权限。
 * 品牌路径同时供开机权限引导和家长面板使用，避免两处文案漂移。
 */
object KeepAlive {
    fun ignoringBattery(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
    }

    fun notificationsEnabled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            false
        } else if (Build.VERSION.SDK_INT >= 24) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        } else true
    } catch (_: Exception) { true }

    fun usbDebugging(context: Context): Boolean = globalFlag(context, Settings.Global.ADB_ENABLED)
    fun developerOptions(context: Context): Boolean = globalFlag(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

    private fun globalFlag(context: Context, key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (_: Exception) { false }

    fun report(context: Context): JSONObject = JSONObject()
        .put("ignoringBattery", ignoringBattery(context))
        .put("notifications", notificationsEnabled(context))
        .put("usbDebugging", usbDebugging(context))
        .put("developerOptions", developerOptions(context))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("brandTip", brandTip(BuildConfig.FLAVOR == "tv"))

    /** 按厂商给出自启动设置路径；同厂商的电视和手机路径不同。 */
    fun brandTip(tv: Boolean): String? {
        val name = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (tv) return when {
            name.contains("tcl") -> "TCL 电视：设置 → 应用 → 题了个题 → 自启动，开启「允许自启动」"
            name.contains("hisense") || name.contains("vidaa") -> "海信电视：设置 → 应用管理 → 题了个题 → 权限 → 允许自启动"
            name.contains("skyworth") || name.contains("coocaa") -> "创维 / 酷开：设置 → 应用 → 题了个题 → 自启动权限，设为允许"
            // #141：HyperOS 电视有的版本把「应用管理」整个去掉了，家长找不到入口
            name.contains("xiaomi") || name.contains("redmi") -> "小米 / Redmi 电视：设置 → 应用 → 应用管理 → 题了个题，允许自启动，省电策略设为「无限制」。新版系统没有「应用管理」入口时不用强求：本页「管控服务」「开机自启」正常即可，需要跳转时点下面的「打开系统设置」"
            name.contains("changhong") -> "长虹电视：设置 → 应用管理 → 题了个题 → 允许后台运行"
            name.contains("huawei") || name.contains("honor") -> "华为 / 荣耀智慧屏：设置 → 应用 → 应用启动管理 → 题了个题 → 手动管理，全部允许"
            name.contains("sony") -> "索尼电视：设置 → 应用 → 题了个题 → 电池 → 不受限制"
            name.contains("samsung") -> "三星电视：设置 → 应用 → 题了个题 → 电池 → 不受限制"
            name.contains("lg") -> "LG 电视：设置 → 常规 → 应用 → 题了个题 → 电池 → 不限制"
            name.contains("philips") -> "飞利浦电视：设置 → 应用 → 题了个题 → 自启动 / 后台运行，设为允许"
            name.contains("sharp") -> "夏普电视：设置 → 应用管理 → 题了个题 → 自启动，设为允许"
            name.contains("konka") -> "康佳电视：设置 → 应用 → 题了个题 → 自启动，设为允许"
            name.contains("letv") -> "乐视电视：设置 → 应用管理 → 题了个题 → 自启动，设为允许"
            else -> null
        }
        return when {
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                "小米 / Redmi / POCO 手机：设置 → 应用设置 → 应用管理 → 题了个题 → 自启动；省电策略 → 无限制"
            name.contains("huawei") || name.contains("honor") ->
                "华为 / 荣耀手机：设置 → 应用 → 应用启动管理 → 题了个题 → 手动管理，全部允许"
            name.contains("oppo") || name.contains("realme") || name.contains("oneplus") ->
                "OPPO / realme / 一加：设置 → 应用 → 自启动管理 → 题了个题；电池 → 允许后台活动"
            name.contains("vivo") || name.contains("iqoo") ->
                "vivo / iQOO：设置 → 应用与权限 → 权限管理 → 自启动 → 题了个题"
            name.contains("samsung") ->
                "三星手机：设置 → 电池 → 后台使用限制 → 从不休眠的应用，加入题了个题"
            name.contains("meizu") ->
                "魅族手机：设置 → 应用管理 → 权限 → 后台管理 → 允许后台运行"
            else -> null
        }
    }
}