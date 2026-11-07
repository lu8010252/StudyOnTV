package com.studyontv.tv

import org.json.JSONObject

/**
 * 出题与题库的几项偏好，单独存一份，不动原来的“规则”配置：
 * - 最低星级：抽题时先从这个星级以上的题里挑（挑不出来才放宽，不会因此没题可做）
 * - 自动淘汰：同一道题连续答对达到次数后，自动从题库里撤下（保留记录，AI 出题仍会避开它）
 */
object Tune {
    private const val KEY = "tune_settings"
    /** 淘汰后每个科目至少还要留这么多题，防止把题库做空。 */
    const val KEEP_AT_LEAST = 10

    private fun raw(store: LocalStore): JSONObject = try { JSONObject(store.get(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    fun minStar(store: LocalStore): Int = raw(store).optInt("minDifficulty", 2).coerceIn(1, 5)
    fun retireEnabled(store: LocalStore): Boolean = raw(store).optBoolean("autoRetire", true)
    fun retireTimes(store: LocalStore): Int = raw(store).optInt("retireTimes", 3).coerceIn(2, 20)

    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val cur = raw(store)
        if (input.has("minDifficulty")) cur.put("minDifficulty", input.optInt("minDifficulty").coerceIn(1, 5))
        if (input.has("autoRetire")) cur.put("autoRetire", input.optBoolean("autoRetire"))
        if (input.has("retireTimes")) cur.put("retireTimes", input.optInt("retireTimes").coerceIn(2, 20))
        store.set(KEY, cur.toString())
        return summary(store)
    }

    fun summary(store: LocalStore): JSONObject = JSONObject()
        .put("minDifficulty", minStar(store)).put("autoRetire", retireEnabled(store)).put("retireTimes", retireTimes(store))
        .put("retired", store.countRetired()).put("version", BuildConfig.VERSION_NAME)

    /** 答完一题后调用：答对且连续答对次数达标，就把这道题淘汰。 */
    fun afterAnswer(store: LocalStore, qid: String, correct: Boolean, repetitions: Int) {
        if (!correct || !retireEnabled(store) || repetitions < retireTimes(store)) return
        store.retireQuestion(qid, KEEP_AT_LEAST)
    }
}
