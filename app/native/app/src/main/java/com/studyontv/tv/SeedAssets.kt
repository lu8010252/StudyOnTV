package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject





object SeedAssets {
    fun ensureQuestions(context: Context, store: LocalStore) {
        // 用总数判断：题被“自动淘汰”后 countQuestions() 会变小，不能因此重新导入一遍内置题库
        if (store.countAllQuestions() > 0) return
        try {
            val raw = context.assets.open("questions.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(raw)
            val normalized = JSONArray()
            for (i in 0 until arr.length()) {
                val src = arr.getJSONObject(i)
                normalized.put(
                    JSONObject()
                        .put("id", src.optString("id"))
                        .put("subject", src.optString("subject", "常识"))
                        .put("level", src.optString("level", src.optString("grade", "三年级")))
                        .put("difficulty", src.optInt("difficulty", 2))
                        .put("q", src.optString("q", src.optString("stem")))
                        .put("a", src.optJSONArray("a") ?: src.optJSONArray("options"))
                        .put("k", if (src.has("k")) src.optInt("k") else src.optInt("answerIndex", 0))
                        .put("tip", src.optString("tip", ""))
                )
            }
            store.importQuestions(normalized)
        } catch (e: Exception) {
            
        }
    }
}
