package com.studyontv.tv

import android.content.Context
import android.webkit.WebView

object InputSupport {
    val active: Boolean get() = AnswerInput.active
    // 语音识别语言的白名单校验在 SpeechReader.recognitionLanguage 里（白名单外降级到默认语言并记诊断，#81）
    fun openVoice(context: Context, web: WebView, draft: String, prompt: String, language: String) =
        AnswerInput.open(context, web, TvTextInputActivity::class.java) {
            putExtra("voice", true); putExtra("draft", draft); putExtra("prompt", prompt)
            putExtra("language", SpeechReader.recognitionLanguage(language))
        }
    // 电视版没有手写 / 语音页，统一走遥控器友好的文字输入页
    fun open(context: Context, web: WebView, voice: Boolean) = openText(context, web, "", "")
    fun openText(context: Context, web: WebView, draft: String, prompt: String) =
        AnswerInput.open(context, web, TvTextInputActivity::class.java) {
            putExtra("draft", draft); putExtra("prompt", prompt); putExtra("language", AnswerInput.language())
        }
}
