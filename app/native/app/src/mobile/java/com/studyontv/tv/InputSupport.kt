package com.studyontv.tv

import android.content.Context
import android.webkit.WebView

object InputSupport {
    val active: Boolean get() = AnswerInput.active
    // 语音识别语言的白名单校验在 SpeechReader.recognitionLanguage 里（白名单外降级到默认语言并记诊断，#81）
    fun openVoice(context: Context, web: WebView, draft: String, prompt: String, language: String) =
        AnswerInput.open(context, web, AnswerInputActivity::class.java) {
            putExtra("voice", true); putExtra("draft", draft); putExtra("prompt", prompt)
            putExtra("language", SpeechReader.recognitionLanguage(language))
        }
    fun open(context: Context, web: WebView, voice: Boolean) =
        AnswerInput.open(context, web, AnswerInputActivity::class.java) { putExtra("voice", voice) }
    // 手机版之前丢了题干（#81）：与 TV 版保持一致，把 prompt 一起带过去
    fun openText(context: Context, web: WebView, draft: String, prompt: String) =
        AnswerInput.open(context, web, AnswerInputActivity::class.java) {
            putExtra("draft", draft); putExtra("prompt", prompt); putExtra("language", AnswerInput.language())
        }
}
