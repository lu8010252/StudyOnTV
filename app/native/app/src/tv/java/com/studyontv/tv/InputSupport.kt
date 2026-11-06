package com.studyontv.tv

import android.content.Context
import android.webkit.WebView

/**
 * TV 版输入：遥控器没法在网页输入框里打字，交给原生输入页（AnswerInputActivity）。
 */
object InputSupport {
    val active: Boolean get() = AnswerInput.active
    fun open(context: Context, web: WebView, voice: Boolean) = AnswerInput.open(context, web, voice)
}
