package com.studyontv.tv

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * TV 版填写答案页。
 * 投影的电视输入法（com.lu.tvime）在应用里调不出来（showSoftInput 请求不生效），
 * 所以这里自带一个遥控器可操作的大按钮键盘：方向键移动、OK 输入，输完选「完成」。
 */
class AnswerInputActivity : Activity() {
    private lateinit var display: TextView
    private lateinit var status: TextView
    private var delivered = false
    private val answer = StringBuilder()

    private val rows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "⌫"),
        listOf("z", "x", "c", "v", "b", "n", "m", "，", ".", "空格"),
        listOf("+", "-", "×", "÷", "=", "/", "(", ")", "%", "清空")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) answer.append(savedInstanceState.getString("answer", ""))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(20), dp(36), dp(20))
            setBackgroundColor(0xFF08080A.toInt())
        }
        root.addView(TextView(this).apply {
            text = "填写答案"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "方向键移动，OK 键输入；数字/字母/符号都可以，输完选「完成」"
            textSize = 13f
            setTextColor(0xFFB5B5BC.toInt())
            setPadding(0, dp(4), 0, dp(10))
        })
        display = TextView(this).apply {
            textSize = 24f
            setBackgroundColor(0xFF16161A.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(display, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFB020.toInt())
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(status)

        // 底部按钮先建好，把 id 交给最后一排按键做“向下焦点”目标，
        // 否则系统按几何位置猜，焦点会跑到「取消」上（用户按完成反而取消了）
        val doneKey = Button(this).apply {
            id = View.generateViewId()
            text = "完成"
            textSize = 20f
            applyKeyStyle()
            setOnClickListener { deliver() }
        }
        val cancelKey = Button(this).apply {
            id = View.generateViewId()
            text = "取消"
            textSize = 20f
            applyKeyStyle()
            setOnClickListener { finish() }
        }
        val systemKey = Button(this).apply {
            id = View.generateViewId()
            text = "系统键盘"
            textSize = 14f
            applyKeyStyle()
            setOnClickListener { showSystemKeyboard() }
        }
        val actionKeys = listOf(doneKey, cancelKey, systemKey)

        var firstKey: Button? = null
        var lastRowKeys: List<Button> = emptyList()
        for ((rowIndex, row) in rows.withIndex()) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val built = mutableListOf<Button>()
            for (label in row) {
                val key = Button(this).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 18f
                    isAllCaps = false
                    applyKeyStyle()
                    setOnClickListener { press(label) }
                }
                line.addView(key, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                built.add(key)
                if (firstKey == null) firstKey = key
            }
            if (rowIndex == rows.lastIndex) lastRowKeys = built
            root.addView(line)
        }
        // 最后一排按列对应到底部按钮：0-2→完成，3-6→取消，7-9→系统键盘
        for ((index, key) in lastRowKeys.withIndex()) {
            key.nextFocusDownId = actionKeys[minOf(actionKeys.lastIndex, index * actionKeys.size / lastRowKeys.size)].id
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        for (key in actionKeys) actions.addView(key, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        root.addView(actions)

        setContentView(root)
        refresh()
        firstKey?.requestFocus()
    }

    // 投影上用遥控器操作，必须让用户一眼看出当前选中哪个键：选中时换成橙色底、深色字
    private fun Button.applyKeyStyle() {
        setBackground(keyBackground())
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(0xFF08080A.toInt(), 0xFF08080A.toInt(), Color.WHITE)
        ))
        stateListAnimator = null
    }

    private fun keyBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(0xFFFFB020.toInt())
            cornerRadius = dp(6).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(0xFF2A2A30.toInt())
            cornerRadius = dp(6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun press(label: String) {
        when (label) {
            "⌫" -> if (answer.isNotEmpty()) answer.deleteCharAt(answer.length - 1)
            "清空" -> answer.setLength(0)
            "空格" -> answer.append(' ')
            else -> if (answer.length < 200) answer.append(label)
        }
        status.text = ""
        refresh()
    }

    private fun refresh() {
        if (answer.isEmpty()) {
            display.text = "请输入答案"
            display.setTextColor(0xFF6E6E76.toInt())
        } else {
            display.text = answer
            display.setTextColor(Color.WHITE)
        }
    }

    private fun showSystemKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        } catch (_: Exception) { }
    }

    private fun deliver() {
        val value = answer.toString().trim()
        if (value.isEmpty()) { status.text = "请先输入答案"; return }
        delivered = true
        AnswerInput.complete(intent.getStringExtra("requestId") ?: "", JSONObject().put("text", value))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("answer", answer.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!delivered && !isChangingConfigurations) AnswerInput.complete(intent.getStringExtra("requestId") ?: "", null)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
