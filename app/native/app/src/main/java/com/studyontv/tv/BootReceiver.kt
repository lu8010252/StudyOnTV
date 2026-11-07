package com.studyontv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 开机、部分电视的快速开机、以及应用自更新覆盖安装后，都要重新拉起管理服务
        if (action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")) {
            Prefs.setUpdateInProgress(context, false)
            if (action != Intent.ACTION_MY_PACKAGE_REPLACED) Watchdog.bootBroadcast(context)
            GateService.ensureRunning(context, if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "update" else "boot")
        }
        // 开机后（无论是否已初始化）都排一次自唤醒闹钟，之后由服务自己滚续
        if (Prefs.onboarded(context)) Revive.rearm(context)
    }
}
