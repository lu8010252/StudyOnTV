package com.studyontv.tv

import org.json.JSONObject

/**
 * 抽题偏好，单独存一份，不动原来的“规则”配置：
 * - 最低星级：抽题时先从这个星级以上的题里挑（挑不出来才放宽，不会因此没题可做）
 * （答对多次自动停用，改用上游的「题库自动化 · 掌握后暂停」）
 */
object Tune {
    private const val KEY = "tune_settings"

    private fun raw(store: LocalStore): JSONObject = try { JSONObject(store.get(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun minStar(store: LocalStore): Int = raw(store).optInt("minDifficulty", 2).coerceIn(1, 5)

    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val cur = raw(store)
        if (input.has("minDifficulty")) cur.put("minDifficulty", input.optInt("minDifficulty").coerceIn(1, 5))
        store.set(KEY, cur.toString())
        return summary(store)
    }

    fun summary(store: LocalStore): JSONObject = JSONObject()
        .put("minDifficulty", minStar(store)).put("version", BuildConfig.VERSION_NAME)
}
