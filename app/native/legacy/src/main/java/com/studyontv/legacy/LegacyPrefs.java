package com.studyontv.legacy;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Android 4.x 管控 PoC 的本地配置与今日用量。 */
public final class LegacyPrefs {
    private static final String NAME = "legacy_control";

    private LegacyPrefs() {}

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int dailyMinutes(Context context) {
        return Math.max(0, Math.min(480, sp(context).getInt("dailyMinutes", 20)));
    }

    public static void setDailyMinutes(Context context, int minutes) {
        sp(context).edit().putInt("dailyMinutes", Math.max(0, Math.min(480, minutes))).apply();
    }

    public static boolean allDay(Context context) {
        return sp(context).getBoolean("allDay", true);
    }

    public static void setAllDay(Context context, boolean allDay) {
        sp(context).edit().putBoolean("allDay", allDay).apply();
    }

    public static String windowStart(Context context) {
        return sp(context).getString("windowStart", "07:00");
    }

    public static String windowEnd(Context context) {
        return sp(context).getString("windowEnd", "22:00");
    }

    public static void setWindow(Context context, String start, String end) {
        sp(context).edit().putString("windowStart", start).putString("windowEnd", end).apply();
    }

    public static String pin(Context context) {
        return sp(context).getString("pin", "1234");
    }

    public static long unlockUntil(Context context) {
        return sp(context).getLong("unlockUntil", 0L);
    }

    public static void setUnlockUntil(Context context, long timestamp) {
        sp(context).edit().putLong("unlockUntil", timestamp).apply();
    }

    private static String dateKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static long usedSeconds(Context context) {
        return sp(context).getLong("used_" + dateKey(), 0L);
    }

    public static void addUsedSeconds(Context context, long seconds) {
        if (seconds <= 0) return;
        SharedPreferences prefs = sp(context);
        String key = "used_" + dateKey();
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + seconds).apply();
    }

    public static void resetToday(Context context) {
        sp(context).edit().remove("used_" + dateKey()).apply();
    }

    /** 支持跨午夜；格式非法时按全天可用处理。 */
    public static boolean inWindow(Context context, long now) {
        if (allDay(context)) return true;
        int start = minutesOf(windowStart(context));
        int end = minutesOf(windowEnd(context));
        if (start < 0 || end < 0 || start == end) return true;
        Calendar calendar = Calendar.getInstance();
        int minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        if (start < end) return minute >= start && minute < end;
        return minute >= start || minute < end;
    }

    private static int minutesOf(String value) {
        if (value == null) return -1;
        String[] parts = value.trim().split(":");
        if (parts.length != 2) return -1;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
            return hour * 60 + minute;
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
