package com.studyontv.legacy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 全屏悬浮拦截层。Android 4.x 使用 TYPE_SYSTEM_ALERT，安装时即授予悬浮权限。 */
final class LegacyGate {
    private static final String TAG = "LegacyGate";

    private final Context context;
    private final WindowManager windowManager;
    private View view;
    private TextView messageView;
    private boolean shown;

    LegacyGate(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShown() {
        return shown;
    }

    void show(String message) {
        if (shown) {
            if (messageView != null) messageView.setText(message);
            return;
        }
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xEF08080A);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
                    openSettings();
                    return true;
                }
                return false;
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(56), dp(40), dp(56), dp(40));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(content, contentParams);

        TextView title = new TextView(context);
        title.setText("学习时间已用完");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        messageView = new TextView(context);
        messageView.setText(message);
        messageView.setTextColor(0xFFB9B9C1);
        messageView.setTextSize(18);
        messageView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(18);
        content.addView(messageView, messageParams);

        Button quiz = new Button(context);
        quiz.setText("去答题（+5 分钟）");
        quiz.setTextSize(20);
        quiz.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LegacyPrefs.setUnlockUntil(context, System.currentTimeMillis() + 5 * 60 * 1000L);
                hide();
                Intent intent = new Intent(context, LegacyActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);
            }
        });
        LinearLayout.LayoutParams quizParams = new LinearLayout.LayoutParams(dp(320), dp(64));
        quizParams.topMargin = dp(34);
        content.addView(quiz, quizParams);

        Button settings = new Button(context);
        settings.setText("家长设置");
        settings.setTextSize(18);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openSettings();
            }
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(320), dp(56));
        settingsParams.topMargin = dp(14);
        content.addView(settings, settingsParams);

        TextView note = new TextView(context);
        note.setText("答题或家长解锁后自动恢复；按遥控器菜单键也可进入家长设置。");
        note.setTextColor(0xFF6B6B74);
        note.setTextSize(14);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(28);
        content.addView(note, noteParams);

        view = root;
        try {
            windowManager.addView(root, params);
            shown = true;
            quiz.requestFocus();
            Log.i(TAG, "gate shown");
        } catch (RuntimeException error) {
            view = null;
            Log.e(TAG, "cannot show gate", error);
        }
    }

    /** 收起拦截层后再进入家长设置，避免服务轮询在下一次检查时重新盖住 PIN 对话框。 */
    private void openSettings() {
        hide();
        Intent intent = new Intent(context, LegacySettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    void hide() {
        if (!shown || view == null) return;
        try {
            windowManager.removeView(view);
            Log.i(TAG, "gate hidden");
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot hide gate", error);
        }
        shown = false;
        view = null;
        messageView = null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
