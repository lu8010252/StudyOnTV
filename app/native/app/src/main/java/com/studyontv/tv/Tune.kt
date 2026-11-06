package com.studyontv.tv

import org.json.JSONObject
import java.util.Calendar

/**
 * 家长临时暂停管控：大人要看电视时用，不用卸载应用也不用关无障碍。
 * 暂停期间不弹拦截页、不计使用时间；到点自动恢复，也可以随时手动恢复。
 * 设置入口都需要家长验证（电视上遥控器连按 ↑ 5 次 → 输密码；手机上用管理后台）。
 */
object Pause {
    private const val KEY = "pause_until"

    /** 约定：这个时间点表示“一直暂停，直到家长手动恢复”。 */
    const val FOREVER = 4102444800000L

    fun until(store: LocalStore): Long = store.get(KEY, "0").toLongOrNull() ?: 0L

    fun active(store: LocalStore): Boolean = until(store) > System.currentTimeMillis()

    /** untilMs <= 0 表示恢复管控。 */
    fun set(store: LocalStore, untilMs: Long) {
        store.set(KEY, if (untilMs > 0) untilMs.toString() else "0")
        if (untilMs > System.currentTimeMillis()) GateService.hideGates()
    }

    fun endOfToday(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun apply(store: LocalStore, input: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        when {
            input.optBoolean("off") -> set(store, 0L)
            input.optBoolean("forever") -> set(store, FOREVER)
            input.optBoolean("today") -> set(store, endOfToday())
            input.has("minutes") -> {
                val m = input.optInt("minutes")
                require(m in 1..1440) { "暂停时长需要 1–1440 分钟" }
                set(store, now + m * 60_000L)
            }
            else -> throw IllegalArgumentException("缺少暂停参数")
        }
        return status(store)
    }

    fun status(store: LocalStore): JSONObject {
        val u = until(store)
        val on = u > System.currentTimeMillis()
        return JSONObject().put("active", on).put("until", if (on) u else 0L).put("forever", on && u >= FOREVER)
    }

    /** 给电视页右上角的诊断文字用。 */
    fun describe(store: LocalStore): String {
        val u = until(store)
        if (u >= FOREVER) return "家长已暂停管控（直到手动恢复）"
        val minutes = ((u - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
        return "家长已暂停管控（还剩约 $minutes 分钟）"
    }
}

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
        .put("retired", store.countRetired()).put("pause", Pause.status(store)).put("version", BuildConfig.VERSION_NAME)

    /** 答完一题后调用：答对且连续答对次数达标，就把这道题淘汰。 */
    fun afterAnswer(store: LocalStore, qid: String, correct: Boolean, repetitions: Int) {
        if (!correct || !retireEnabled(store) || repetitions < retireTimes(store)) return
        store.retireQuestion(qid, KEEP_AT_LEAST)
    }
}
