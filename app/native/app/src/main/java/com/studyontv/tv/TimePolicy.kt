package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONObject

/** 时长策略只保存实际观看量；期限同次开机使用单调时钟，重启后使用持久化期限。 */
class TimePolicy(private val store: LocalStore, private val boot: () -> Int) {
    private fun n(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())
    fun dailyLimit(config: JSONObject): Long = if (!config.optBoolean("dailyLimitEnabled", true)) Long.MAX_VALUE / 4
        else (config.optLong("dailyMinutes", 40) * 60000L + n("cap_adjust_ms")).coerceAtLeast(0)
    fun left(key: String): Long {
        val raw = store.get(key)
        if (raw.isEmpty()) return 0
        val deadline = JSONObject(raw)
        val elapsed = SystemClock.elapsedRealtime()
        val sameBoot = deadline.optInt("boot", -2) == boot() && elapsed >= deadline.optLong("atElapsed")
        return (if (sameBoot) deadline.optLong("untilElapsed") - elapsed else deadline.optLong("until") - System.currentTimeMillis()).coerceAtLeast(0)
    }
    fun deadline(key: String, milliseconds: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        store.set(key, JSONObject().put("boot", boot()).put("atElapsed", elapsed)
            .put("untilElapsed", elapsed + milliseconds).put("until", System.currentTimeMillis() + milliseconds).toString())
    }
    /** 强制休息的阈值：家长调小它时，已累计的观看量可能立刻超过新阈值 */
    private fun restWatchMs(config: JSONObject): Long = config.optLong("restWatchMinutes", 40) * 60000

    fun restLeft(config: JSONObject): Long {
        if (!config.optBoolean("restEnabled")) {
            if (n("rest_watched_ms") != 0L) put("rest_watched_ms", 0)
            if (store.get("rest_deadline").isNotEmpty()) store.set("rest_deadline", "")
            return 0
        }
        val left = left("rest_deadline")
        if (left == 0L && store.get("rest_deadline").isNotEmpty()) { store.set("rest_deadline", ""); put("rest_watched_ms", 0) }
        return left
    }

    /**
     * 距离「必须休息」还剩多少毫秒。
     *
     * 旧实现只看 `rest_watched_ms >= restWatchMinutes` 就返回 0，而「真的开始休息」（写
     * rest_deadline）只发生在 [consumed] 里。家长把 restWatchMinutes 调小、跨天残留、或旧数据
     * 都会造出 rest_watched_ms 已到阈值但 rest_deadline 为空的死区：`beforeRest()==0` 且
     * `restLeft()==0` ⇒ [LearningEngine.consume] 一毫秒都扣不动，而 status() 仍因为还有余额
     * 返回 `reason=""`（issue #17：显示还有时间、永远不减少、到点也不弹）。
     *
     * 现在改成「到点即进入休息」：补写 rest_deadline，让 `reason="rest"`、`restSeconds>0`，
     * 与面板显示同一口径；休息结束（restLeft()==0）时 rest_watched_ms 归零，重新可以扣时。
     */
    fun beforeRest(config: JSONObject): Long {
        if (!config.optBoolean("restEnabled")) return Long.MAX_VALUE
        val watch = restWatchMs(config)
        if (watch <= 0) return Long.MAX_VALUE          // 阈值无效（0 / 负数）等同于没有休息，不留死区
        // 顺手清理已经过期的休息（restLeft 会把 rest_watched_ms 归零）
        if (restLeft(config) > 0) return 0
        val watched = n("rest_watched_ms")
        if (watched >= watch) {
            val restMs = config.optLong("restMinutes", 10) * 60000
            deadline("rest_deadline", restMs)
            if (restMs > 0) Diag.warn("Rest", "已观看 ${watched / 60000} 分钟达到阈值 ${watch / 60000} 分钟但没有休息记录 → 立即开始休息（#17 死区自愈）")
            return 0
        }
        return watch - watched
    }
    fun consumed(config: JSONObject, milliseconds: Long) {
        if (!config.optBoolean("restEnabled") || milliseconds <= 0) return
        val watched = n("rest_watched_ms") + milliseconds
        put("rest_watched_ms", watched)
        if (watched >= restWatchMs(config) && left("rest_deadline") == 0L)
            deadline("rest_deadline", config.optLong("restMinutes", 10) * 60000)
    }
    fun awake(config: JSONObject, awake: Boolean) {
        val elapsed = SystemClock.elapsedRealtime()
        val wasAwake = store.get("boot_free_awake") == "1"
        val newBoot = store.get("boot_free_boot") != boot().toString() || elapsed < n("boot_free_elapsed")
        if (awake && (newBoot || !wasAwake)) {
            put("boot_quota_ms", config.optLong("bootFreeMinutes") * 60000)
            store.set("boot_free_boot", boot().toString())
        }
        store.set("boot_free_awake", if (awake) "1" else "0")
        put("boot_free_elapsed", elapsed)
    }
    fun adjust(minutes: Int, config: JSONObject) {
        require(minutes != 0 && minutes in -480..480) { "调整时长需要为 -480 到 480 分钟，且不能为 0" }
        val delta = minutes * 60000L
        if (delta > 0) put("credit_ms", (n("credit_ms") + delta).coerceAtMost(480 * 60000L))
        else {
            var remove = -delta
            for (key in listOf("credit_ms", "free_quota_ms", "boot_quota_ms")) {
                val take = minOf(remove, n(key)); put(key, n(key) - take); remove -= take
            }
        }
        val base = config.optLong("dailyMinutes", 40) * 60000
        put("cap_adjust_ms", (n("cap_adjust_ms") + delta).coerceIn(-base, 480 * 60000L))
    }
}
