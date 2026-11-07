package com.studyontv.tv

import android.content.Context
import android.webkit.WebView

object InputSupport {
    val active: Boolean get() = AnswerInput.active
    fun open(context: Context, web: WebView, voice: Boolean) =
        AnswerInput.open(context, web, AnswerInputActivity::class.java) { putExtra("voice", voice) }
    fun openText(context: Context, web: WebView, draft: String, prompt: String) =
        AnswerInput.open(context, web, AnswerInputActivity::class.java) { putExtra("draft", draft) }
}
