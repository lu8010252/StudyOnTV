package com.studyontv.tv

class GateActivity : MainActivity()

internal fun gateLearningPage(reason: String): String {
    if (reason.isEmpty()) return "quiz"
    val page = if (reason in listOf("timeup", "feedback")) "quiz" else "lock"
    return "$page?reason=$reason"
}
