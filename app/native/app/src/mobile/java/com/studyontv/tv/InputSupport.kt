package com.studyontv.tv

import android.content.Context
import android.webkit.WebView

object InputSupport {
    val active: Boolean get() = AnswerInput.active
    fun recentlyCompleted(): Boolean = AnswerInput.recentlyCompleted()
    fun open(context: Context, web: WebView, voice: Boolean) = AnswerInput.open(context, web, voice)
}
