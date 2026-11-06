package com.studyontv.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * TV 版填写答案页：给遥控器用的大字号输入页，调起投影自带的输入法（如 com.lu.tvime）。
 * 「完成」把文字回填给网页，「取消」丢弃。
 */
class AnswerInputActivity : Activity() {
    private lateinit var text: EditText
    private lateinit var status: TextView
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
            setBackgroundColor(0xFF08080A.toInt())
        }
        root.addView(TextView(this).apply {
            text = "填写答案"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "方向键选到输入框，按 OK 调出键盘；输入完成后选「完成」"
            textSize = 15f
            setTextColor(0xFFB5B5BC.toInt())
            setPadding(0, dp(8), 0, dp(18))
        })
        text = EditText(this).apply {
            hint = "请输入答案"
            textSize = 26f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6E6E76.toInt())
            setBackgroundColor(0xFF16161A.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            if (savedInstanceState != null) setText(savedInstanceState.getString("draft", ""))
            setOnClickListener { showKeyboard() }
        }
        root.addView(text, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFB020.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(status)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(22), 0, 0)
        }
        row.addView(Button(this).apply {
            text = "键盘"
            setOnClickListener { showKeyboard() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "完成"
            setOnClickListener { deliver() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        setContentView(root)
        text.requestFocus()
        text.postDelayed({ showKeyboard() }, 400)
    }

    private fun showKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(text, InputMethodManager.SHOW_FORCED)
        } catch (_: Exception) { }
    }

    private fun deliver() {
        val value = text.text.toString().trim()
        if (value.isEmpty()) { status.text = "请先输入答案"; return }
        delivered = true
        AnswerInput.complete(intent.getStringExtra("requestId") ?: "", JSONObject().put("text", value))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", text.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!delivered && !isChangingConfigurations) AnswerInput.complete(intent.getStringExtra("requestId") ?: "", null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
