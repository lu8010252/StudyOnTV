package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Issue #112「自定义时间点管理」回归：网课时段（自由 / 计入限额）+ 大人豁免时段。
 *
 * 覆盖：分钟边界、跨午夜、多天、free 不扣时长、counted 换每日上限且不清零 used_ms、
 * 豁免优先于网课、旧配置（timePoints 为空 / 完全没有这些键）不回归，以及保存校验。
 */
object TimePointsRegression {
    private fun point(id: String, name: String, days: List<Int>, start: String, end: String,
                      mode: String = "free", dailyMinutes: Int? = null): JSONObject {
        val item = JSONObject().put("id", id).put("name", name).put("days", JSONArray(days))
            .put("start", start).put("end", end).put("mode", mode)
        if (dailyMinutes != null) item.put("dailyMinutes", dailyMinutes)
        return item
    }

    private fun exempt(id: String, name: String, days: List<Int>, start: String, end: String): JSONObject =
        JSONObject().put("id", id).put("name", name).put("days", JSONArray(days)).put("start", start).put("end", end)

    /** 0 = 周一 … 6 = 周日 → Calendar 的 SUNDAY=1 … SATURDAY=7。 */
    private fun at(day: Int, hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, ((day + 1) % 7) + 1)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun m(value: String): Int = TimePointsMath.minutes(value) ?: error("测试时间格式不对: $value")

    fun run(context: Context) {
        android.util.Log.i("SOTVRegression", "TimePointsRegression start")
        boundaries()
        val name = "time-points-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.status()   // 先把「今天」初始化好，后面直接改 used_ms / credit_ms 才不会被跨天重置
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0)
                .put("dailyLimitEnabled", true).put("roundsLimitEnabled", false)
                .put("restEnabled", false).put("allDay", true).put("scheduleMode", "allow"))

            freeWindow(engine, store)
            countedWindow(engine, store)
            exemptWins(engine, store)
            legacyConfig(engine, store)
            validation(store)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
        android.util.Log.i("SOTVRegression", "TimePointsRegression PASS: boundaries/free/counted/exempt/legacy/validation")
    }

    /** 纯函数边界：18:59 不命中、19:00 / 20:14 命中、20:15 不命中；跨午夜与多天。 */
    private fun boundaries() {
        val school = setOf(0, 2, 4)                        // 周一三五
        val start = m("19:00")
        val end = m("20:15")
        check(!TimePointsMath.hits(school, start, end, 0, m("18:59"))) { "18:59 不应命中 19:00–20:15" }
        check(TimePointsMath.hits(school, start, end, 0, m("19:00"))) { "19:00 必须命中（左闭）" }
        check(TimePointsMath.hits(school, start, end, 0, m("20:14"))) { "20:14 必须命中（精确到分钟）" }
        check(!TimePointsMath.hits(school, start, end, 0, m("20:15"))) { "20:15 不应命中（右开）" }
        check(TimePointsMath.hits(school, start, end, 2, m("19:30"))) { "周三有课" }
        check(TimePointsMath.hits(school, start, end, 4, m("19:30"))) { "周五有课" }
        check(!TimePointsMath.hits(school, start, end, 1, m("19:30"))) { "周二没有课" }
        check(!TimePointsMath.hits(school, start, end, 6, m("19:30"))) { "周日没有课" }

        val night = setOf(2)                               // 周三 23:30–00:30
        val nightStart = m("23:30")
        val nightEnd = m("00:30")
        check(TimePointsMath.hits(night, nightStart, nightEnd, 2, m("23:45"))) { "23:45 命中跨午夜窗口" }
        check(TimePointsMath.hits(night, nightStart, nightEnd, 3, m("00:10"))) { "跨午夜后 00:10 仍属于周三的窗口" }
        check(!TimePointsMath.hits(night, nightStart, nightEnd, 3, m("12:00"))) { "12:00 不命中" }
        check(!TimePointsMath.hits(night, nightStart, nightEnd, 2, m("00:10"))) { "周三 00:10 属于周二晚上的窗口" }
        check(!TimePointsMath.hits(night, nightStart, nightEnd, 3, m("00:30"))) { "00:30 是结束时刻，不命中" }
        check(!TimePointsMath.hits(night, nightStart, nightEnd, 3, m("23:29")))
        check(!TimePointsMath.hits(school, start, start, 0, start)) { "开始等于结束视为空窗口" }
    }

    /** free 网课：status().free=true、reason 为空、consume() 不扣 used_ms。 */
    private fun freeWindow(engine: LearningEngine, store: LocalStore) {
        engine.saveConfig(JSONObject().put("timePoints", JSONArray()
            .put(point("tp_math", "数学网课", listOf(2), "19:00", "20:15", "free"))))
        store.set("used_ms", "0")
        store.set("credit_ms", (20 * 60000L).toString())
        engine.now = { at(2, 19, 30) }
        val st = engine.status()
        check(st.getBoolean("free")) { "free 网课时段 status().free 必须为 true" }
        check(st.getString("reason").isEmpty()) { "free 网课时段不应有锁定原因" }
        val active = st.getJSONObject("activePoint")
        check(active.getString("name") == "数学网课" && active.getString("mode") == "free" && active.getString("until") == "20:15") {
            "activePoint 应回传名称 / 用途 / 结束时间，实际 $active"
        }
        engine.consume(5000)
        check(engine.status().getDouble("usedMinutes") == 0.0) { "自由网课时段不扣时长" }
        check(store.get("credit_ms") == (20 * 60000L).toString()) { "自由网课时段不扣余额" }

        // 时段外回到正常扣时逻辑
        engine.now = { at(2, 21, 0) }
        val after = engine.status()
        check(!after.getBoolean("free") && after.isNull("activePoint")) { "离开 free 网课时段后应恢复正常" }
        engine.consume(5000)
        check(engine.status().getDouble("usedMinutes") > 0.0) { "离开自由时段后必须继续计时" }
    }

    /** counted 网课：每日上限换成时段自己的分钟数；离开后恢复；used_ms 不清零。 */
    private fun countedWindow(engine: LearningEngine, store: LocalStore) {
        engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("timePoints", JSONArray()
            .put(point("tp_en", "英语网课", listOf(2), "17:00", "18:40", "counted", 60))))
        engine.status()
        store.set("used_ms", (10 * 60000L).toString())
        store.set("credit_ms", (60 * 60000L).toString())

        engine.now = { at(2, 17, 30) }
        val inside = engine.status()
        check(!inside.getBoolean("free")) { "counted 网课时段照常计时" }
        check(inside.getDouble("todayLimitMinutes") == 60.0) { "counted 时段内每日上限应为该时段的 60 分钟，实际 ${inside.getDouble("todayLimitMinutes")}" }
        check(inside.getDouble("usedMinutes") == 10.0)
        check(inside.getJSONObject("activePoint").getString("mode") == "counted")
        check(inside.getJSONObject("activePoint").getString("until") == "18:40")

        store.set("used_ms", (55 * 60000L).toString())
        check(engine.status().getLong("remainingSeconds") == 300L) { "counted 上限 60 分钟、已用 55 分钟 → 只剩 5 分钟" }

        engine.now = { at(2, 12, 0) }
        val outside = engine.status()
        check(outside.getDouble("todayLimitMinutes") == 40.0) { "离开网课时段后应恢复 config 的每日上限" }
        check(outside.getDouble("usedMinutes") == 55.0) { "切换上限不能清零 used_ms" }
        check(outside.getLong("remainingSeconds") == 0L) { "回到 40 分钟上限后已用 55 分钟 → 没有剩余" }
        check(outside.isNull("activePoint"))

        store.set("used_ms", (10 * 60000L).toString())
    }

    /** 豁免窗口优先于网课：同时命中时 activePoint.mode = exempt 且 free。 */
    private fun exemptWins(engine: LearningEngine, store: LocalStore) {
        engine.saveConfig(JSONObject()
            .put("timePoints", JSONArray().put(point("tp_math2", "数学网课", listOf(2), "19:00", "20:15", "counted", 30)))
            .put("exemptWindows", JSONArray().put(exempt("ex_1", "大人看电影", listOf(2), "19:10", "21:00"))))
        engine.now = { at(2, 19, 30) }
        val st = engine.status()
        val active = st.getJSONObject("activePoint")
        check(active.getString("mode") == "exempt") { "豁免窗口必须优先于网课，实际 ${active.getString("mode")}" }
        check(active.getString("name") == "大人看电影")
        check(st.getBoolean("free")) { "大人豁免时段不扣时长" }
        val used = st.getDouble("usedMinutes")
        engine.consume(5000)
        check(engine.status().getDouble("usedMinutes") == used) { "大人豁免时段不扣时长" }

        // 豁免窗口之外、网课之内 → 回到网课的 counted 上限
        engine.now = { at(2, 19, 5) }
        val counted = engine.status()
        check(counted.getJSONObject("activePoint").getString("mode") == "counted")
        check(counted.getDouble("todayLimitMinutes") == 30.0)

        // 家长暂停（remote_override）优先级最高：豁免时段内也必须锁定
        engine.now = { at(2, 19, 30) }
        engine.remote("lock", 30, "test")
        val locked = engine.status()
        check(!locked.getBoolean("free") && locked.getString("reason") == "locked") { "家长暂停必须优先于豁免窗口" }
        engine.remote("cancel", 0, "test")
        check(engine.status().getBoolean("free"))
        engine.saveConfig(JSONObject().put("timePoints", JSONArray()).put("exemptWindows", JSONArray()))
        store.set("used_ms", "0")
    }

    /** 旧配置回归：timePoints / exemptWindows 为空或不存在时，行为与修改前一致。 */
    private fun legacyConfig(engine: LearningEngine, store: LocalStore) {
        engine.saveConfig(JSONObject().put("scheduleMode", "allow").put("allDay", false)
            .put("weekSlots", JSONArray(List(7) { "0".repeat(48) })))
        engine.now = { at(2, 12, 0) }
        val blocked = engine.status()
        check(!blocked.getBoolean("free") && blocked.getString("reason") == "schedule") { "weekSlots 全空时仍应提示不在可用时段" }
        check(blocked.isNull("activePoint")) { "没有时间点时应回传 activePoint=null" }

        engine.saveConfig(JSONObject().put("scheduleMode", "control"))
        check(engine.status().getBoolean("free")) { "control 模式下时段外仍然自由（不回归）" }

        // 完全没有这两个键的旧配置：默认空数组，等价于旧行为
        store.set("config", JSONObject().put("dailyMinutes", 40).put("allDay", true).put("scheduleMode", "allow").toString())
        val config = store.config()
        check(config.getJSONArray("timePoints").length() == 0) { "旧配置应补出空的 timePoints" }
        check(config.getJSONArray("exemptWindows").length() == 0) { "旧配置应补出空的 exemptWindows" }
        engine.now = { at(2, 12, 0) }
        check(!engine.status().getBoolean("free")) { "空时间点不能凭空放出自由时段" }
        check(engine.status().isNull("activePoint"))
    }

    private fun validation(store: LocalStore) {
        fun rejects(input: JSONObject, hint: String) {
            try {
                store.saveConfig(input)
                error("应拒绝：$hint")
            } catch (_: IllegalArgumentException) { }
        }

        val good = point("tp_ok", "数学网课", listOf(0, 2, 4), "19:00", "20:15", "counted", 60)
        val saved = store.saveConfig(JSONObject().put("timePoints", JSONArray().put(good))
            .put("exemptWindows", JSONArray().put(exempt("ex_ok", "大人看电影", listOf(5, 6), "21:30", "23:10"))))
        val stored = saved.getJSONArray("timePoints").getJSONObject(0)
        check(stored.getString("id") == "tp_ok" && stored.getString("name") == "数学网课")
        check(stored.getJSONArray("days").length() == 3 && stored.getString("mode") == "counted" && stored.getInt("dailyMinutes") == 60)
        check(stored.getString("start") == "19:00" && stored.getString("end") == "20:15")
        val storedExempt = saved.getJSONArray("exemptWindows").getJSONObject(0)
        check(storedExempt.getString("id") == "ex_ok" && !storedExempt.has("mode")) { "豁免窗口不需要 mode" }

        // 缺 id 时自动生成
        val generated = store.saveConfig(JSONObject().put("timePoints", JSONArray().put(point("", "临时课", listOf(1), "08:00", "08:45"))))
            .getJSONArray("timePoints").getJSONObject(0)
        check(generated.getString("id").startsWith("tp_")) { "缺少 id 时应生成 tp_ 前缀的 id" }
        // 重复的星期去重
        val dedup = store.saveConfig(JSONObject().put("timePoints", JSONArray().put(point("tp_d", "去重", listOf(1, 1, 3), "08:00", "08:45"))))
            .getJSONArray("timePoints").getJSONObject(0)
        check(dedup.getJSONArray("days").length() == 2)

        val tooMany = JSONArray()
        repeat(51) { tooMany.put(point("tp_$it", "第 $it 节", listOf(0), "19:00", "20:00")) }
        rejects(JSONObject().put("timePoints", tooMany), "超过 50 条网课时段")
        val manyExempt = JSONArray()
        repeat(51) { manyExempt.put(exempt("ex_$it", "第 $it 段", listOf(0), "19:00", "20:00")) }
        rejects(JSONObject().put("exemptWindows", manyExempt), "超过 50 条大人豁免")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "一二三四五六七八九十一二三四五六七八九十一", listOf(0), "19:00", "20:00"))), "名称超过 20 字")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "  ", listOf(0), "19:00", "20:00"))), "名称为空")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:60", "20:00"))), "分钟越界")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:00", "19:00"))), "开始等于结束")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", emptyList(), "19:00", "20:00"))), "没有选择星期")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(7), "19:00", "20:00"))), "星期越界")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:00", "20:00", "unknown"))), "未知用途")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:00", "20:00", "counted"))), "计入限额缺少分钟数")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:00", "20:00", "counted", 0))), "计入限额 0 分钟")
        rejects(JSONObject().put("timePoints", JSONArray().put(point("tp_x", "课", listOf(0), "19:00", "20:00", "counted", 481))), "计入限额 481 分钟")
        rejects(JSONObject().put("exemptWindows", JSONArray().put(exempt("ex_x", "看电影", listOf(0), "25:00", "26:00"))), "豁免时段非法时间")

        // 保存失败不能污染已有配置
        check(store.config().getJSONArray("timePoints").length() == 1) { "校验失败时不能改动已保存的时间点" }
        // 不带这些键的保存（其它页面改设置）不能清掉时间点
        store.saveConfig(JSONObject().put("dailyMinutes", 45))
        check(store.config().getJSONArray("timePoints").length() == 1) { "其它设置保存后时间点必须保留" }
        check(store.config().getJSONArray("exemptWindows").length() == 1)
        // 显式传空数组可以清空
        check(store.saveConfig(JSONObject().put("timePoints", JSONArray()).put("exemptWindows", JSONArray()))
            .getJSONArray("timePoints").length() == 0)
    }
}
