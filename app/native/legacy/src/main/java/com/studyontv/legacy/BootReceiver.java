package com.studyontv.legacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 老系统没有 Android 8+ 的后台启动限制，开机广播可以直接拉起管控服务。 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LegacyBoot";

    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            LegacyServiceStarter.start(context);
            Log.i(TAG, "service requested after boot");
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot start service after boot", error);
        }
    }
}
