package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/** 只改出题快照，答案索引、选项文本与选项配图一起按同一排列移动，原题库保持原顺序。 */
object QuestionOptions {
    fun shuffled(question: JSONObject): JSONObject {
        if (question.optString("type", "single") !in listOf("single", "multiple")) return question
        val options = question.optJSONArray("a") ?: return question
        if (options.length() <= 1) return JSONObject(question.toString())
        val order = (0 until options.length()).shuffled()
        return permute(question, order)
    }

    /** 按给定新旧下标映射重排选项、选项配图与正确答案下标（抽成确定性函数便于单测验证所有置换）。 */
    internal fun permute(question: JSONObject, order: List<Int>): JSONObject {
        val options = question.optJSONArray("a") ?: return question
        val copy = JSONObject(question.toString())
        copy.put("a", JSONArray(order.map { options.getString(it) }))
        val optionImages = question.optJSONArray("optionImages")
        if (optionImages != null && optionImages.length() > 0) {
            copy.put("optionImages", JSONArray(order.map { optionImages.optString(it) }))
        }
        if (question.optString("type", "single") == "multiple") {
            val correct = question.getJSONArray("correctIndices")
            copy.put("correctIndices", JSONArray((0 until correct.length()).map { order.indexOf(correct.getInt(it)) }.sorted()))
        } else {
            copy.put("k", order.indexOf(question.getInt("k")))
        }
        return copy
    }
}
