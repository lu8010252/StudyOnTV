package com.studyontv.tv

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 原生页面（权限引导、家长验证、更新、输入）共用的样式，与学习页保持一致：
 * 参考 Apple TV / Netflix —— 选中项加一圈柔和浅灰描边并放大（不反色，参考 Netflix），
 * 远距离也一眼看出焦点在哪里。深色 / 浅色 / 跟随系统由家长面板设置，手机 / 平板同样适用。
 */
object TvUi {
    data class Palette(
        val bg: Int, val surface: Int, val surface2: Int,
        val text: Int, val text2: Int, val text3: Int,
        val focusBg: Int, val focusText: Int, val focusMuted: Int,
        val ok: Int, val warn: Int
    )

    // 方案 B（参考 Netflix）：中性灰；选中项底色几乎不变，加一圈柔和浅灰描边并放大，文字颜色不变
    private val DARK = Palette(0xFF141414.toInt(), 0xFF222222.toInt(), 0xFF2A2A2A.toInt(),
        0xFFE8E8E8.toInt(), 0xFFA3A3A3.toInt(), 0xFF6E6E6E.toInt(),
        0xFF2A2A2A.toInt(), 0xFFF0F0F0.toInt(), 0xFFB5B5B5.toInt(),
        0xFF7BC294.toInt(), 0xFFD9A066.toInt())
    private val LIGHT = Palette(0xFFF4F4F4.toInt(), 0xFFFFFFFF.toInt(), 0xFFE9E9E9.toInt(),
        0xFF1E1E1E.toInt(), 0xFF666666.toInt(), 0xFF999999.toInt(),
        0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt(), 0xFF666666.toInt(),
        0xFF2F8A4E.toInt(), 0xFFB0703A.toInt())

    fun systemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 家长在管理面板「界面与反馈」里选择的外观 */
    private fun mode(context: Context): String = try { DeviceRuntime.store.config().optString("theme") } catch (_: Throwable) { if (DeviceBridge.isTv(context)) "dark" else "system" }

    /** 默认值见 LocalStore.config()：电视深色，手机 / 平板跟随系统 */
    fun dark(context: Context): Boolean = when (mode(context)) {
        "light" -> false
        "dark" -> true
        else -> systemNight(context)
    }

    fun palette(context: Context) = if (dark(context)) DARK else LIGHT
    fun background(context: Context) = palette(context).bg
    fun foreground(context: Context) = palette(context).text
    fun muted(context: Context) = palette(context).text2

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun shape(context: Context, color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke > 0) setStroke(dp(context, stroke), strokeColor)
    }

    /** 选中时放大 + 浮起（电视）；手机上只改变底色 */
    private fun lift(view: View, scale: Float) {
        if (scale == 1f) return
        val tv = DeviceBridge.isTv(view.context)
        view.onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
            if (tv) v.animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
                .translationZ(if (focused) dp(v.context, 10).toFloat() else 0f).setDuration(160).start()
        }
    }

    private fun focusable(context: Context, normal: Int, radius: Int): StateListDrawable {
        val p = palette(context)
        return StateListDrawable().apply {
            // 选中：底色几乎不变 + 柔和浅灰描边（不反色），配合 lift() 的放大
            addState(intArrayOf(android.R.attr.state_focused), shape(context, p.focusBg, radius, 2, if (p === DARK) 0xB8E5E5E5.toInt() else 0x801E1E1E.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(context, p.surface2, radius, 2, p.text2))
            addState(intArrayOf(), shape(context, normal, radius))
        }
    }

    private fun textStates(focused: Int, normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    fun text(context: Context, value: String, size: Float, color: Int = foreground(context), bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        if (bold) paint.isFakeBoldText = size >= 24
    }

    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        val p = palette(context)
        val tv = DeviceBridge.isTv(context)
        text = label; textSize = if (tv) 18f else 16f; isAllCaps = false; elevation = 0f; stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0; minimumWidth = 0; minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setPadding(dp(context, 26), dp(context, 10), dp(context, 26), dp(context, 10))
        isFocusable = true
        backgroundTintList = null
        if (tv) {
            background = focusable(context, if (primary) p.surface2 else p.surface, 14)
            setTextColor(textStates(p.focusText, p.text))
        } else {
            background = shape(context, if (primary) p.text else p.surface2, 14)
            setTextColor(if (primary) p.bg else p.text)
        }
        lift(this, 1.06f)
        setOnClickListener { action() }
    }

    fun field(edit: EditText) {
        val context = edit.context
        val p = palette(context)
        edit.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(context, p.surface, 14, 2, p.text))
            addState(intArrayOf(), shape(context, p.surface, 14))
        }
        edit.setTextColor(p.text); edit.setHintTextColor(p.text3)
        edit.textSize = if (DeviceBridge.isTv(context)) 20f else 17f
        edit.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
    }

    /**
     * 设置列表的一行（类似 Apple TV「设置」）：整行可选中，左侧图标 + 标题 + 说明，右侧状态。
     * 选中时整行反白，状态与说明文字跟着变色。
     */
    class Row(val view: LinearLayout, val status: TextView, private val icon: ImageView, private val context: Context) {
        fun setStatus(value: String, good: Boolean) {
            val p = palette(context)
            status.text = value
            status.setTextColor(textStates(p.focusText, if (good) p.ok else p.text2))
        }
    }

    fun row(context: Context, iconRes: Int, title: String, subtitle: String, action: () -> Unit): Row {
        val p = palette(context)
        val tv = DeviceBridge.isTv(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(context, 22), dp(context, if (tv) 12 else 16), dp(context, 22), dp(context, if (tv) 12 else 16))
            background = focusable(context, p.surface, 16)
            setOnClickListener { action() }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes); imageTintList = textStates(p.focusText, p.text2)
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply { rightMargin = dp(context, 18) })
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        copy.addView(text(context, title, if (tv) 19f else 16f, bold = true).apply { setTextColor(textStates(p.focusText, p.text)); isDuplicateParentStateEnabled = true })
        copy.addView(text(context, subtitle, if (tv) 14f else 13f).apply { setTextColor(textStates(p.focusMuted, p.text2)); isDuplicateParentStateEnabled = true },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 3) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val status = text(context, "", if (tv) 15f else 13f, p.text2, true).apply { isDuplicateParentStateEnabled = true; gravity = Gravity.END }
        row.addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(context, 16) })
        row.addView(text(context, "›", if (tv) 26f else 22f).apply { setTextColor(textStates(p.focusText, p.text3)); isDuplicateParentStateEnabled = true },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(context, 12) })
        // 列表行不放大（放大后会超出滚动区域被裁切），只靠描边标出焦点，与 Apple TV「设置」一致
        return Row(row, status, icon, context)
    }
}
