package com.studyontv.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 隐藏桌面图标：桌面 / 电视应用列表里的入口是 Manifest 里的 activity-alias（[.Launcher]），
 * 关掉它只是不再显示图标，MainActivity、前台服务、无障碍和开机广播都不受影响，
 * 也不会像厂商的「隐藏应用」那样把应用在系统里藏起来导致被清后台。
 *
 * 恢复入口：通知栏里的「使用时间管理」通知点一下就打开应用，家长面板「设置 → 设备」也能一键恢复。
 */
object LauncherIcon {
    /**
     * alias 的类名跟着 Manifest 的包名走，而不是 applicationId（调试包的 .debug 后缀不会带上），
     * 所以从已安装信息里按「目标是 MainActivity 的 alias」来找。
     * 必须带 GET_DISABLED_COMPONENTS：隐藏之后 alias 本身就是禁用状态，默认查不到，
     * 否则就再也恢复不了图标了。
     */
    @Suppress("DEPRECATION")
    private fun alias(context: Context): ComponentName {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val entries = pm.queryIntentActivities(intent, PackageManager.GET_DISABLED_COMPONENTS).map { it.activityInfo }
        val name = entries.firstOrNull { it.targetActivity == MainActivity::class.java.name }?.name
            ?: entries.firstOrNull { it.name.endsWith(".Launcher") }?.name
            ?: throw IllegalStateException("找不到桌面入口")
        return ComponentName(context.packageName, name)
    }

    fun hidden(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { false }

    /** DONT_KILL_APP：改完不能重启进程，否则管控会中断一次 */
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        val now = hidden(context)
        if (now != hidden) throw IllegalStateException(if (hidden) "隐藏桌面图标失败" else "恢复桌面图标失败")
        return now
    }
}
