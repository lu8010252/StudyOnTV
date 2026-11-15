package com.studyontv.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 电视版填空 / 主观题作答页：网页里的输入框无法用遥控器打字，这里用原生输入框调出系统输入法，
 * 支持遥控器语音键（若电视装有语音识别）。方向键可在输入框和按钮之间移动。
 */
class TvTextInputActivity : Activity() {
    private lateinit var edit: EditText
    private lateinit var voiceButton: Button
    /** DeepSeek 录音识别（家长面板填了 Key 才有）；为空时走系统识别器 */
    private var capture: VoiceCapture.Session? = null
    private var delivered = false
    private var cancelled = false
    private val requestId get() = intent.getStringExtra("requestId") ?: ""
    /** 家长密码模式（#201）：单行掩码输入，不提供语音 / 多行答案 */
    private val passwordMode get() = intent.getBooleanExtra("passwordMode", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(TvUi.background(this@TvTextInputActivity))
            setPadding((64 * dp).toInt(), (36 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = if (passwordMode) "家长验证" else "填写答案"
            textSize = 26f; setTextColor(TvUi.foreground(this@TvTextInputActivity))
        })
        val prompt = intent.getStringExtra("prompt").orEmpty()
        if (prompt.isNotBlank()) root.addView(TextView(this).apply {
            text = prompt.take(200); textSize = 16f; setTextColor(TvUi.muted(this@TvTextInputActivity)); maxLines = 3
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        edit = EditText(this).apply {
            hint = if (passwordMode) "输入家长管理密码" else "用遥控器或语音输入，完成后选择“确定”"
            inputType = if (passwordMode) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = if (passwordMode) 1 else 2; maxLines = if (passwordMode) 1 else 5
            gravity = Gravity.TOP or Gravity.START
            TvUi.field(this)
            setText(savedInstanceState?.getString("draft") ?: intent.getStringExtra("draft").orEmpty())
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { finishWith(true); true } else false }
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun add(label: String, primary: Boolean = false, action: () -> Unit) =
            TvUi.button(this, label, primary, action).also { button ->
                row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (8 * dp).toInt(); rightMargin = (8 * dp).toInt() })
            }
        add("确定", true) { finishWith(true) }
        if (!passwordMode) voiceButton = add("语音输入 / 重录") { voice() }
        add("清空") { edit.setText("") }
        add("取消") { finishWith(false) }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })
        setContentView(root)
        edit.requestFocus()
        edit.post {
            if (!passwordMode && intent.getBooleanExtra("voice", false) && savedInstanceState == null) voice()
            else (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 遥控器的语音键 / 搜索键直接进入语音输入（密码模式不录音）
        if (!passwordMode && event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) { voice(); return true }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 语音输入：家长面板填了 DeepSeek Key 就自己录音上传识别（电视上通常没有系统识别器），
     * 没填还是走系统识别器。录着的时候再按一次就停止并开始识别。
     */
    private fun voice() {
        if (passwordMode) return
        val key = try { SpeechReader.recognizeKey(DeviceRuntime.store) } catch (_: Exception) { "" }
        if (key.isEmpty()) { systemVoice(); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9); return
        }
        capture?.let { session ->
            if (session.active) { session.stop(); voiceButton.text = "正在识别…"; return }
        }
        val session = VoiceCapture.Session(key,
            onState = { state -> voiceButton.text = if (state == "recording") "停止并识别" else "正在识别…" },
            onResult = { text ->
                capture = null; voiceButton.text = "语音输入 / 重录"
                edit.setText(text); edit.setSelection(edit.text.length)
            },
            onError = { message ->
                capture = null; voiceButton.text = "语音输入 / 重录"
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            })
        if (!session.start()) {
            voiceButton.text = "语音输入 / 重录"
            android.widget.Toast.makeText(this, "这台电视没有可用的麦克风，改用系统语音识别", android.widget.Toast.LENGTH_LONG).show()
            systemVoice(); return
        }
        capture = session
    }

    private fun systemVoice() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, intent.getStringExtra("language") ?: "zh-CN"), 7)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "这台电视没有语音识别；家长可以在面板里填写 DeepSeek Key 用云端识别", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 9) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice()
        else {
            android.widget.Toast.makeText(this, "没有麦克风权限，改用系统语音识别", android.widget.Toast.LENGTH_LONG).show()
            systemVoice()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        edit.setText(spoken)
        edit.setSelection(edit.text.length)
    }

    private fun finishWith(save: Boolean) {
        if (delivered) return
        delivered = true
        cancelled = !save
        AnswerInput.complete(requestId, if (save) JSONObject().put("text", edit.text.toString()) else null)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", edit.text.toString())
    }

    override fun onDestroy() {
        capture?.cancel(); capture = null
        // 遥控器「返回键」退出时不算取消：把已输入的内容带回去，避免辛苦打的字白打（#64）
        if (!delivered && !isChangingConfigurations) {
            // 密码模式按返回键 = 取消，不能把半截密码当成解锁尝试提交
            if (passwordMode) AnswerInput.complete(requestId, null)
            else {
                val draft = if (::edit.isInitialized) edit.text.toString() else ""
                AnswerInput.complete(requestId, if (draft.isNotBlank()) JSONObject().put("text", draft) else null)
            }
        }
        super.onDestroy()
    }
}
