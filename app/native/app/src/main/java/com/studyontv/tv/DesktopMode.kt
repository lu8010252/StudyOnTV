package com.studyontv.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * 可选的「把题了个题当电视桌面」。
 *
 * Manifest 里的 [.HomeAlias] 默认是 `android:enabled="false"`：不开这个开关，
 * 电视上根本看不到这个桌面选项，锁屏仍走原来的悬浮窗 + 无障碍覆盖。
 * 家长在面板里打开时才启用 alias，再把用户送到系统的「选择桌面」对话框；
 * 关掉时把 alias 禁回去，电视回到原来的桌面。
 *
 * 之所以做成 alias 而不是直接把 HomeActivity 写死成 HOME：
 * 开和关都只动一个组件，进程不会被重启（DONT_KILL_APP），管控不会中断一次。
 */
object DesktopMode {
    private const val ALIAS_SUFFIX = ".HomeAlias"

    /** alias 的类名跟着 Manifest 的包名走（调试包的 .debug 后缀不会带上），所以按目标 Activity 反查 */
    private fun alias(context: Context): ComponentName {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        // 已经关了的时候 alias 是禁用状态，必须带 GET_DISABLED_COMPONENTS 才查得到，否则再也开不回来
        val entries = pm.queryIntentActivities(intent, PackageManager.GET_DISABLED_COMPONENTS).map { it.activityInfo }
        val name = entries.firstOrNull { it.targetActivity == HomeActivity::class.java.name }?.name
            ?: entries.firstOrNull { it.name.endsWith(ALIAS_SUFFIX) }?.name
            ?: throw IllegalStateException("找不到电视桌面入口")
        return ComponentName(context.packageName, name)
    }

    fun enabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (_: Exception) { false }

    /** 当前系统默认桌面是谁 */
    @Suppress("DEPRECATION")
    fun currentHome(context: Context): String? = try {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (_: Exception) { null }

    fun isDefault(context: Context): Boolean = currentHome(context) == context.packageName

    fun state(context: Context): JSONObject {
        val home = currentHome(context)
        val label = home?.let {
            try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }
            catch (_: Exception) { null }
        }
        return JSONObject()
            .put("enabled", enabled(context))
            .put("isDefault", home == context.packageName)
            .put("homePackage", home ?: "")
            .put("homeLabel", label ?: "")
    }

    /** 打开系统的「选择桌面」对话框；有的电视没有对话框，会直接切到当前默认桌面 */
    fun openChooser(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Diag.warn("Desktop", "open home chooser failed: ${e.javaClass.simpleName}")
        }
    }

    fun setEnabled(context: Context, on: Boolean): JSONObject {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(alias(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        if (enabled(context) != on) throw IllegalStateException(if (on) "开启电视桌面失败" else "关闭电视桌面失败")
        Diag.info("Desktop", "desktop ${if (on) "enabled" else "disabled"} by parent")
        // 开启后立刻把用户送到选择桌面；关闭时如果自己正占着 Home，也要发一次让系统接手
        if (on || isDefault(context)) openChooser(context)
        return state(context)
    }

    /** 兜底：桌面自己起不来时，禁掉自己并把电视交还给系统桌面，避免出现「电视没有桌面」 */
    fun failToSystemHome(context: Context, reason: String) {
        Diag.error("Desktop", "desktop failed, falling back to system home: $reason")
        try {
            context.packageManager.setComponentEnabledSetting(alias(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) { }
        openChooser(context)
    }
}
