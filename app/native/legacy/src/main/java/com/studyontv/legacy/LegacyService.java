package com.studyontv.legacy;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Android 4.x 整机管控 PoC：前台服务 + getRunningTasks 前台识别 + 全屏悬浮拦截。
 * 老系统没有 UsageStatsManager，getRunningTasks(1) 在 API 21 以下可拿到真实顶层 Activity。
 */
public class LegacyService extends Service {
    private static final String TAG = "LegacyService";
    private static final String CHANNEL = "legacy_control";
    private static final long POLL_MS = 3000L;
    /** 看门狗间隔：进程被杀后最多约 1 分钟由系统闹钟重新拉起。 */
    private static final long WATCHDOG_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LegacyGate gate;
    private long ticks;
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                evaluate();
            } catch (RuntimeException error) {
                Log.e(TAG, "poll failed", error);
            } finally {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        gate = new LegacyGate(this);
        startInForeground();
        startPolling();
        scheduleWatchdog();
        Log.i(TAG, "service created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        startPolling();
        return START_STICKY;
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(poll);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        if (gate != null) gate.hide();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void evaluate() {
        long now = System.currentTimeMillis();
        boolean screenOn = isScreenOn();
        String top = topPackage();
        boolean ownTop = top != null && getPackageName().equals(top);
        boolean selfVisible = LegacyApp.visible() && (top == null || ownTop);
        boolean gated = shouldGate(now);
        boolean counting = screenOn && !selfVisible && !gated;

        if (counting) {
            LegacyPrefs.addUsedSeconds(this, POLL_MS / 1000L);
            gated = shouldGate(now);
        }

        if (!screenOn) {
            gate.hide();
        } else if (gated && !selfVisible) {
            gate.show(gateMessage(now));
        } else {
            gate.hide();
        }

        if (ticks++ % 5 == 0) {
            Log.i(TAG, String.format(Locale.US,
                    "screen=%s top=%s self=%s used=%ds limit=%dm gated=%s",
                    screenOn, top, selfVisible, LegacyPrefs.usedSeconds(this),
                    LegacyPrefs.dailyMinutes(this), gated));
        }
    }

    private boolean shouldGate(long now) {
        if (LegacyPrefs.unlockUntil(this) > now) return false;
        if (!LegacyPrefs.inWindow(this, now)) return true;
        return LegacyPrefs.usedSeconds(this) >= LegacyPrefs.dailyMinutes(this) * 60L;
    }

    private String gateMessage(long now) {
        if (!LegacyPrefs.inWindow(this, now)) {
            if (!LegacyPrefs.allDay(this)) {
                return "当前不在可用时段（" + LegacyPrefs.windowStart(this) + "–"
                        + LegacyPrefs.windowEnd(this) + "）";
            }
        }
        int used = (int) Math.ceil(LegacyPrefs.usedSeconds(this) / 60.0);
        int limit = LegacyPrefs.dailyMinutes(this);
        return "今日已用 " + used + " 分钟，上限 " + limit + " 分钟";
    }

    private boolean isScreenOn() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return true;
        if (Build.VERSION.SDK_INT >= 20) return Power20.isInteractive(power);
        return power.isScreenOn();
    }

    /** 独立成类，避免 Android 4.x 的 Dalvik 在加载服务类时解析 API 20 方法。 */
    @android.annotation.TargetApi(20)
    private static final class Power20 {
        static boolean isInteractive(PowerManager power) {
            return power.isInteractive();
        }
    }

    /** 进程被杀后由系统闹钟重新拉起；同一 PendingIntent 重复设置会覆盖旧闹钟。 */
    private void scheduleWatchdog() {
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(this, LegacyWatchdogReceiver.class);
        intent.setAction(LegacyWatchdogReceiver.ACTION);
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        try {
            alarms.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_MS,
                    WATCHDOG_MS, pending);
            Log.i(TAG, "watchdog scheduled");
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot schedule watchdog", error);
        }
    }

    /** API 21 以下返回真实顶层 Activity；21+ 仅返回本应用，届时用 LegacyApp.visible 兜底。 */
    @SuppressWarnings("deprecation")
    private String topPackage() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (SecurityException error) {
            Log.w(TAG, "GET_TASKS denied", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "getRunningTasks failed", error);
        }
        return null;
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        try {
            startForeground(1, notification);
        } catch (RuntimeException error) {
            Log.e(TAG, "startForeground failed", error);
        }
    }

    private Notification buildNotification() {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, LegacyActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        if (Build.VERSION.SDK_INT >= 26) {
            return Notification26.build(this, pending);
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("题了个题 · 旧版管控运行中")
                .setContentText("正在检查前台应用与今日可用时间")
                .setContentIntent(pending)
                .setOngoing(true)
                .getNotification();
    }

    /** 单独成类，避免 Android 4.x 的 Dalvik 在加载服务类时解析 API 26 构造函数。 */
    @android.annotation.TargetApi(26)
    private static final class Notification26 {
        static Notification build(Context context, PendingIntent pending) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "旧版管控", NotificationManager.IMPORTANCE_LOW));
            }
            return new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("题了个题 · 旧版管控运行中")
                    .setContentText("正在检查前台应用与今日可用时间")
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .build();
        }
    }
}
