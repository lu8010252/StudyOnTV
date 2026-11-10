package com.studyontv.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 定时看门狗：服务进程被系统或第三方清理后，由系统闹钟重新拉起服务。
 * 闹钟由 LegacyService 在创建时注册；用户“强行停止”应用时系统会一并清除闹钟，这是预期行为。
 */
public class LegacyWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "LegacyWatchdog";
    static final String ACTION = "com.studyontv.legacy.action.WATCHDOG";

    @Override public void onReceive(Context context, Intent intent) {
        try {
            LegacyServiceStarter.start(context);
            Log.i(TAG, "watchdog restarted service");
        } catch (RuntimeException error) {
            Log.e(TAG, "watchdog cannot start service", error);
        }
    }
}
