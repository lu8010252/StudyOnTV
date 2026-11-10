package com.studyontv.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Remaining-time badge shown in the top-right corner while the child is watching video.
 * Minutes only; blinks during the final minute.
 *
 * Display only: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, so it never consumes remote-control
 * keys or touches. Driven by GateService's one-second tick. Not used on the quiz screen, which
 * has its own top pill.
 */
class CountdownOverlay(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var attached = false
    private var blinkOn = true
    private var lastUrgent: Boolean? = null
    private var lastText: String? = null
    var lastError: String = ""
        private set

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun chip(): TextView = view ?: TextView(context).apply {
        textSize = 15f
        setPadding(dp(14), dp(6), dp(14), dp(6))
        // Some launchers and players have a very bright background; a shadow keeps the text
        // readable on any frame.
        setShadowLayer(4f, 0f, 1f, Color.argb(200, 0, 0, 0))
        view = this
    }

    private fun paint(urgent: Boolean) {
        val chip = view ?: return
        // null means "never painted yet". Without that, the first call (urgent = false) would
        // return early and neither the text color nor the background would ever be applied,
        // leaving a default grey label with no backdrop that is unreadable on dark footage.
        if (lastUrgent == urgent) return
        lastUrgent = urgent
        chip.setTextColor(if (urgent) Color.rgb(255, 92, 92) else Color.WHITE)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (urgent) Color.argb(180, 40, 0, 0) else Color.argb(150, 0, 0, 0))
        }
    }

    /** [seconds]: total remaining seconds (quiz credit + daily free quota). Hidden when <= 0. */
    fun update(seconds: Long) {
        if (seconds <= 0) { hide(); return }
        val urgent = seconds <= 60
        val chip = chip()
        // Skip setText when the minute value has not changed: re-assigning the same text every
        // second still triggers a layout pass, which shows up as visible jitter on low-end TVs.
        val label = "剩余 ${(seconds + 59) / 60} 分钟"
        if (label != lastText) { chip.text = label; lastText = label }
        paint(urgent)
        if (!attached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                GateOverlay.applicationWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.END
            params.x = dp(20); params.y = dp(12)
            params.title = "StudyOnTV countdown"
            try {
                manager.addView(chip, params)
                attached = true
                lastError = ""
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                return
            }
        }
        chip.visibility = if (urgent) { blinkOn = !blinkOn; if (blinkOn) View.VISIBLE else View.INVISIBLE } else View.VISIBLE
    }

    fun hide() {
        val chip = view ?: return
        if (attached) try { manager.removeViewImmediate(chip) } catch (_: Exception) { }
        attached = false
    }

    fun destroy() {
        hide()
        view = null
        lastText = null
        lastUrgent = null
    }
}
