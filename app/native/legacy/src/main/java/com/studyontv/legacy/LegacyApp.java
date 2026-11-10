package com.studyontv.legacy;

/** 记录本应用是否有可见页面，用于区分“孩子正在答题”和“前台识别不可用”。 */
public final class LegacyApp {
    private static volatile int visible;

    private LegacyApp() {}

    public static void enter() {
        visible++;
    }

    public static void leave() {
        visible = Math.max(0, visible - 1);
    }

    public static boolean visible() {
        return visible > 0;
    }
}
