package com.studyontv.legacy;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 统一启动管控服务。API 26+ 必须走 startForegroundService；老系统走 startService。
 * 把 API 26 的调用放进独立嵌套类，避免 Android 4.x 的 Dalvik 在加载入口类时解析缺失方法。
 */
final class LegacyServiceStarter {
    private LegacyServiceStarter() {}

    static void start(Context context) {
        Intent intent = new Intent(context, LegacyService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            Start26.start(context, intent);
        } else {
            context.startService(intent);
        }
    }

    @android.annotation.TargetApi(26)
    private static final class Start26 {
        static void start(Context context, Intent intent) {
            context.startForegroundService(intent);
        }
    }
}
