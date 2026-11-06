package com.studyontv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            if (Prefs.onboarded(context)) {
                GateService.start(context)
            }
        }
        // 开机后（无论是否已初始化）都排一次自唤醒闹钟，之后由服务自己滚续
        if (Prefs.onboarded(context)) Revive.rearm(context)
    }
}
