package com.studyontv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * 接收系统 PackageInstaller 的安装结果。
 *
 * 安装成功时本应用会被系统替换，进程随即结束，所以成功分支只需要清掉「安装中 / 上次失败原因」；
 * 失败分支把系统的原始原因记进 [Prefs] 和诊断日志，更新页和家长面板都能直接显示，
 * 不用再让家长去猜「是不是网络问题」。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系统把「安装确认」交回给我们：必须由这里把确认页拉起来，否则家长只看到转圈
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            GateService.allowInstallPage()
            if (confirm != null) {
                try {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    Diag.info("Install", "user confirm page started")
                    return
                } catch (e: Exception) {
                    Diag.warn("Install", "cannot start confirm page: ${e.javaClass.simpleName}")
                }
            }
            // 拉不起确认页（部分电视限制后台启动界面）：退回交给系统安装器
            if (ApkInstaller.fallbackToView(context)) Diag.info("Install", "fallback installer started")
            else {
                Prefs.setUpdateInstallError(context, ApkInstaller.lastError.ifEmpty { "无法打开安装确认页" })
                Prefs.setUpdateInProgress(context, false)
            }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Diag.info("Install", "install succeeded")
            Prefs.setUpdateInstallError(context, "")
            Prefs.setUpdateInProgress(context, false)
            return
        }
        val text = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "等待用户确认安装"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "安装被取消"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统禁止安装这个应用"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装包与已安装版本冲突"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与这台电视的系统不兼容"
            PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效或已损坏"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "电视存储空间不足"
            else -> "安装失败（状态 $status）"
        }
        val detail = if (message.isBlank()) text else "$text：$message"
        Diag.error("Install", "install failed status=$status message=$message")
        Prefs.setUpdateInstallError(context, detail.take(300))
        Prefs.setUpdateInProgress(context, false)
    }
}
