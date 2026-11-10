package com.studyontv.tv

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 防卸载（Issue #4）：激活为“设备管理应用”后，系统不允许直接卸载，孩子在“我的应用”里删除会失败。
 * 家长需要先在系统设置的“设备管理应用”里关闭，才能卸载。不申请任何管理策略。
 */
class UninstallGuard : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        context.getSharedPreferences("sotv", Context.MODE_PRIVATE).edit().putBoolean("uninstall_guard_enabled", true).apply()
        restoreOwnerPolicy(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "关闭后“题了个题”可以被卸载，使用时间管理也会失效。请确认是家长在操作。"

    companion object {
        fun component(context: Context) = ComponentName(context, UninstallGuard::class.java)
        fun owner(context: Context): Boolean = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) { false }
        fun restoreOwnerPolicy(context: Context) {
            if (!owner(context)) return
            try {
                val enabled = context.getSharedPreferences("sotv", Context.MODE_PRIVATE).getBoolean("uninstall_guard_enabled", true)
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).setUninstallBlocked(component(context), context.packageName, enabled)
            } catch (e: Exception) { Diag.warn("Guard", "cannot restore device owner uninstall policy") }
        }
        fun setEnabled(context: Context, enabled: Boolean) {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (owner(context)) manager.setUninstallBlocked(component(context), context.packageName, enabled)
            else {
                require(!enabled) { "请在被控设备上授权设备管理员" }
                manager.removeActiveAdmin(component(context))
            }
            context.getSharedPreferences("sotv", Context.MODE_PRIVATE).edit().putBoolean("uninstall_guard_enabled", enabled).apply()
        }
        fun active(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (manager.isDeviceOwnerApp(context.packageName)) manager.isUninstallBlocked(component(context), context.packageName)
            else manager.isAdminActive(component(context))
        } catch (_: Exception) { false }
        fun requestIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "开启后，孩子无法直接卸载“题了个题”。家长需要先在这里关闭，才能卸载。")
    }
}
