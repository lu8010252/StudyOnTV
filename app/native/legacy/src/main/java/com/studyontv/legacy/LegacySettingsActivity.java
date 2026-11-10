package com.studyontv.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** PoC 家长设置：固定 4 位 PIN，默认 1234；按菜单键或拦截层按钮进入。 */
public class LegacySettingsActivity extends Activity {
    private EditText minutes;
    private EditText start;
    private EditText end;
    private CheckBox allDay;
    private TextView status;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        askPin();
    }

    private void askPin() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("默认 1234");
        new AlertDialog.Builder(this)
                .setTitle("家长验证")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("进入", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (input.getText().toString().equals(LegacyPrefs.pin(LegacySettingsActivity.this))) {
                            showForm();
                        } else {
                            Toast.makeText(LegacySettingsActivity.this, "密码不正确", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void showForm() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(28), dp(40), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("旧版管控 · 家长设置");
        title.setTextSize(24);
        title.setGravity(Gravity.START);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        minutes = numberField(root, "每日可用分钟（0 表示立即拦截）", String.valueOf(LegacyPrefs.dailyMinutes(this)));
        start = textField(root, "可用开始时间（HH:mm）", LegacyPrefs.windowStart(this));
        end = textField(root, "可用结束时间（HH:mm）", LegacyPrefs.windowEnd(this));

        allDay = new CheckBox(this);
        allDay.setText("全天可用");
        allDay.setChecked(LegacyPrefs.allDay(this));
        root.addView(allDay);

        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button reset = new Button(this);
        reset.setText("重置今日用量");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LegacyPrefs.resetToday(LegacySettingsActivity.this);
                refreshStatus();
                Toast.makeText(LegacySettingsActivity.this, "已重置今日用量", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button unlock = new Button(this);
        unlock.setText("临时解锁 30 分钟");
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LegacyPrefs.setUnlockUntil(LegacySettingsActivity.this,
                        System.currentTimeMillis() + 30 * 60 * 1000L);
                Toast.makeText(LegacySettingsActivity.this, "已解锁 30 分钟", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        root.addView(unlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("PoC 说明：每日用量按亮屏且不在本应用内的时长累计；"
                + "Android 4.4 用 getRunningTasks 识别前台，Android 5.0+ 会退化为按本应用可见性估算。"
                + "老系统无法真正阻止卸载。");
        note.setTextSize(13);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);

        setContentView(scroll);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        refreshStatus();
    }

    private EditText numberField(LinearLayout root, String label, String value) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setPadding(0, dp(14), 0, dp(4));
        root.addView(title);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(value);
        root.addView(input);
        return input;
    }

    private EditText textField(LinearLayout root, String label, String value) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setPadding(0, dp(14), 0, dp(4));
        root.addView(title);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        root.addView(input);
        return input;
    }

    private void save() {
        int limit;
        try {
            limit = Integer.parseInt(minutes.getText().toString().trim());
        } catch (NumberFormatException error) {
            Toast.makeText(this, "请输入有效的分钟数", Toast.LENGTH_SHORT).show();
            return;
        }
        if (limit < 0 || limit > 480) {
            Toast.makeText(this, "分钟数需要在 0–480 之间", Toast.LENGTH_SHORT).show();
            return;
        }
        String startText = start.getText().toString().trim();
        String endText = end.getText().toString().trim();
        if (!startText.matches("([01][0-9]|2[0-3]):[0-5][0-9]")
                || !endText.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
            Toast.makeText(this, "时间格式应为 HH:mm", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!allDay.isChecked() && startText.equals(endText)) {
            Toast.makeText(this, "开始与结束时间不能相同", Toast.LENGTH_SHORT).show();
            return;
        }
        LegacyPrefs.setDailyMinutes(this, limit);
        LegacyPrefs.setWindow(this, startText, endText);
        LegacyPrefs.setAllDay(this, allDay.isChecked());
        refreshStatus();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        if (status == null) return;
        int used = (int) Math.ceil(LegacyPrefs.usedSeconds(this) / 60.0);
        long unlock = LegacyPrefs.unlockUntil(this);
        String extra = unlock > System.currentTimeMillis()
                ? "；临时解锁剩余 " + Math.max(1, (unlock - System.currentTimeMillis()) / 60000L) + " 分钟"
                : "";
        status.setText("今日已用 " + used + " 分钟 / 上限 " + LegacyPrefs.dailyMinutes(this) + " 分钟" + extra);
    }

    @Override protected void onResume() {
        super.onResume();
        LegacyApp.enter();
        LegacyServiceStarter.start(this);
        refreshStatus();
    }

    @Override protected void onPause() {
        LegacyApp.leave();
        super.onPause();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
