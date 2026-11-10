package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/** 只改出题快照，答案索引与选项一起移动，原题库保持原顺序。 */
object QuestionOptions {
    fun shuffled(question: JSONObject): JSONObject {
        if (question.optString("type", "single") !in listOf("single", "multiple")) return question
        val options = question.optJSONArray("a") ?: return question
        val order = (0 until options.length()).shuffled()
        val copy = JSONObject(question.toString())
        copy.put("a", JSONArray(order.map { options.getString(it) }))
        if (question.optString("type", "single") == "multiple") {
            val correct = question.getJSONArray("correctIndices")
            copy.put("correctIndices", JSONArray((0 until correct.length()).map { order.indexOf(correct.getInt(it)) }.sorted()))
        } else copy.put("k", order.indexOf(question.getInt("k")))
        return copy
    }
}
