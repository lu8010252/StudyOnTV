package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

/**
 * 网课时段 / 大人豁免的命中判断（Issue #112）。
 *
 * 与 [LearningEngine] 内部的 `dayIndex()` 不同：这里用**真实星期几**，不把法定节假日折算成周日，
 * 因为「周三 19:00 的网课」在节假日也仍然是周三；0 = 周一 … 6 = 周日。
 *
 * 时间精确到分钟，区间左闭右开（19:00–20:15 含 19:00 与 20:14，不含 20:15）。
 * end < start 表示跨午夜（23:30–00:30），午夜之后的部分算作**前一天**的安排。
 */
object TimePointsMath {
    private val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    /** "HH:mm" → 当天第几分钟；格式不对返回 null。 */
    fun minutes(value: String?): Int? {
        val text = value ?: return null
        if (!pattern.matches(text)) return null
        return text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()
    }

    fun label(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** [minute] 是否落在 [start, end) 内；start == end 视为空窗口。 */
    fun inRange(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }

    /** [days] 里的某一天在 [minute] 是否命中 [start, end)；跨午夜时延续到次日的部分属于前一天。 */
    fun hits(days: Set<Int>, start: Int, end: Int, day: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> day in days && inRange(start, end, minute)
        else -> (day in days && minute >= start) || ((day + 6) % 7 in days && minute < end)
    }
}

class LearningEngine(private val store: LocalStore) {
    private fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())
    private val time = TimePolicy(store) { bootCount() }
    private val videos = VideoLessons(store)

    /**
     * 今天还能扣掉的额度：每日上限 - 已用，并且不能超过「距离必须休息」的额度。
     *
     * 旧实现只看每日上限，而 consume() 扣的是 `min(上限, beforeRest)`，于是休息触顶时会出现
     * 「面板显示还有 10 分钟，实际一秒都扣不动」的死区（issue #17）。这里把休息上限并进来，
     * 保证 status() 显示的剩余 / 免费额度与 consume() 真正能扣掉的量是同一个口径。
     * 错题解析倒计时（feedback_deadline）期间 consume() 也直接返回，所以同样归零。
     */
    private fun capacity(config: JSONObject): Long {
        if (time.left("feedback_deadline") > 0) return 0
        return minOf(
            (time.dailyLimit(limitConfig(config)) - number("used_ms")).coerceAtLeast(0),
            time.beforeRest(config)
        )
    }

    /** 测试可以固定时钟（例如验证跨午夜窗口）；生产环境始终是系统时间。 */
    internal var now: () -> Calendar = { Calendar.getInstance() }

    /** 一个命中的网课时段或大人豁免窗口。 */
    private class Window(val name: String, val days: Set<Int>, val start: Int, val end: Int,
                         val mode: String, val dailyMinutes: Int, val until: String)

    /**
     * 解析 timePoints / exemptWindows。配置可能是手改过的，所以坏条目直接跳过：
     * 一个格式不对的「计入限额」条目绝不能被当成自由时段（那等于完全放开）。
     */
    private fun windows(config: JSONObject, key: String, defaultMode: String): List<Window> {
        val raw = config.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Window>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val start = TimePointsMath.minutes(item.optString("start")) ?: continue
            val end = TimePointsMath.minutes(item.optString("end")) ?: continue
            if (start == end) continue
            val days = LinkedHashSet<Int>()
            item.optJSONArray("days")?.let { arr ->
                for (d in 0 until arr.length()) arr.optInt(d, -1).takeIf { it in 0..6 }?.let { days.add(it) }
            }
            if (days.isEmpty()) continue
            val mode = if (defaultMode == "exempt") "exempt" else if (item.optString("mode") == "counted") "counted" else "free"
            val minutes = item.optInt("dailyMinutes", 0)
            if (mode == "counted" && minutes !in 1..480) continue
            out.add(Window(item.optString("name").take(20), days, start, end, mode, if (mode == "counted") minutes else 0, TimePointsMath.label(end)))
        }
        return out
    }

    /**
     * 当前命中的时间点：大人豁免优先于网课时段（Issue #112）。
     * 家长临时解锁 / 暂停（remote_override）在 [freeNow] 与 [status] 里更早判断，优先级最高。
     */
    private fun resolve(config: JSONObject, cal: Calendar): Window? {
        val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (w in windows(config, "exemptWindows", "exempt")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        for (w in windows(config, "timePoints", "free")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        return null
    }

    private fun activeWindow(config: JSONObject): Window? = resolve(config, now())

    /**
     * 命中「计入限额」的网课时段时，每日上限换成该时段自己的分钟数；时段结束后自动恢复 config 的
     * dailyMinutes。这里只换上限，used_ms 不清零（Issue #112）。
     */
    private fun limitConfig(config: JSONObject): JSONObject {
        val point = activeWindow(config) ?: return config
        if (point.mode != "counted") return config
        return JSONObject(config.toString()).put("dailyMinutes", point.dailyMinutes)
    }

    private fun freeBalance(): Long = number("free_quota_ms") + number("boot_quota_ms")

    /**
     * 面板显示的剩余必须等于 consume() 真正能扣掉的额度，否则会出现「显示还有时间却永远扣不动」
     * （用户反馈：升级后剩余时间不变化，相当于没用）。免费额度先扣，所以奖励余额只能用剩下的部分。
     */
    private fun usableFree(config: JSONObject): Long = min(freeBalance(), capacity(config))

    private fun usableCredit(config: JSONObject): Long =
        min(number("credit_ms"), (capacity(config) - usableFree(config)).coerceAtLeast(0))

    @Synchronized fun awake(awake: Boolean) { day(); time.awake(store.config(), awake) }

    private fun day() {
        val current = store.startOfToday()
        if (number("usage_day") != current) {
            if (pendingUsageMs > 0) { store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs)); pendingUsageMs = 0 }
            put("usage_day", current)
            put("credit_ms", 0)
            put("used_ms", 0)
            put("cap_adjust_ms", 0)
            store.set("video_lesson", "")
            if (time.left("rest_deadline") == 0L) put("rest_watched_ms", 0)
            // 每天重置时，把"默认免费时长"存入 free_quota_ms（和答题奖励分开记）
            val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
            put("free_quota_ms", freeMin * 60000L)
            put("free_used_ms", 0)
        } else if (store.get("free_used_ms").isEmpty()) {
            // 旧版本升级：没有 free_used_ms，用当天已用时长估算已消耗的免费额度。
            // 免费额度总是先于奖励余额扣除，所以这个估算不会把奖励时间算成免费时间。
            val daily = store.config().optLong("dailyFreeMinutes", 0L) * 60000L
            put("free_used_ms", min(number("used_ms"), daily).coerceAtLeast(0))
        }
    }

    /**
     * 修改每日免费额度时只补扣差额，已经消耗的额度不重新发放（#83）。
     *
     * 只加差额会在「调到 0 再调回来」时把已经用掉的时长重新发一遍；这里再用
     * 「新上限 - 今天已用的免费时长」封顶，同时保留家长手动收走的那部分。
     */
    @Synchronized
    fun saveConfig(input: JSONObject): JSONObject {
        day()
        val previousDaily = store.config().optLong("dailyFreeMinutes") * 60000L
        val previousFree = number("free_quota_ms")
        val usedFree = number("free_used_ms")
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val config = store.saveConfig(input)
            val daily = config.optLong("dailyFreeMinutes") * 60000L
            val granted = (previousFree + (daily - previousDaily)).coerceAtLeast(0)
            val cap = (daily - usedFree).coerceAtLeast(0)
            put("free_quota_ms", min(granted, cap))
            db.setTransactionSuccessful()
            return config
        } finally { db.endTransaction() }
    }

    private fun weekSlots(config: JSONObject): List<String>? {
        val raw = config.optJSONArray("weekSlots") ?: return null
        if (raw.length() != 7) return null
        val list = (0 until 7).map { raw.optString(it) }
        return list.takeIf { days -> days.all { it.length == 48 && it.all { ch -> ch == '0' || ch == '1' } } }
    }

    /** 周一 = 0 … 周日 = 6；法定节假日可以按周日安排 */
    private fun dayIndex(config: JSONObject, cal: Calendar): Int {
        if (config.optString("holidayMode", "sunday") == "sunday") {
            val holiday = try { HolidayService.isHoliday(DeviceRuntime.appContext, cal) } catch (_: Throwable) { false }
            if (holiday) return 6
        }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    private fun allowedAt(config: JSONObject, cal: Calendar): Boolean {
        if (config.optBoolean("allDay")) return true
        val slots = weekSlots(config) ?: return true
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return slots[dayIndex(config, cal)].getOrNull(minute / 30) == '1'
    }

    private fun inWindow(config: JSONObject): Boolean = allowedAt(config, Calendar.getInstance())

    private fun nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
        val base = Calendar.getInstance()
        if (tomorrow) {
            base.add(Calendar.DAY_OF_YEAR, 1)
            base.set(Calendar.HOUR_OF_DAY, 0); base.set(Calendar.MINUTE, 0)
        }
        base.set(Calendar.SECOND, 0); base.set(Calendar.MILLISECOND, 0)
        if (config.optBoolean("allDay")) return base.timeInMillis
        val slots = weekSlots(config) ?: return base.timeInMillis
        val now = System.currentTimeMillis()
        for (offset in 0..8) {
            val day = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val index = dayIndex(config, day)
            for (slot in 0 until 48) {
                if (slots[index][slot] != '1') continue
                val candidate = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, slot / 2); set(Calendar.MINUTE, (slot % 2) * 30)
                }.timeInMillis
                if (candidate > now) return candidate
            }
        }
        return base.timeInMillis
    }

    /**
     * 时段有两种用途（Issue #4）：
     *  allow   —— 只在这段时间内可以使用（原有行为，时段外锁定）
     *  control —— 只在这段时间内需要先答题；时段外完全不管控、不计时（例如白天老人看电视）
     */
    private fun scheduleFree(c: JSONObject): Boolean =
        c.optString("scheduleMode", "allow") == "control" && !c.optBoolean("allDay") && !inWindow(c)

    /**
     * 家长遥控（实时生效，GateService 每秒检查一次）：
     *  unlock —— 临时解锁 N 分钟：不答题、不扣时长，到点自动恢复规则（大人自己看电视）
     *  lock   —— 立即锁定：不论余额与时段都显示锁定页，直到家长解除或到达设定的时间
     * 剩余时间同时记录墙上时间和开机计时：同一次开机内以开机计时为准，孩子改系统时间不能延长解锁。
     */
    @Synchronized
    fun override(): JSONObject? {
        val raw = store.get("remote_override").takeIf { it.isNotEmpty() } ?: return null
        val o = try { JSONObject(raw) } catch (_: Exception) { store.set("remote_override", ""); return null }
        val until = o.optLong("until")
        val mode = o.optString("mode")
        if (mode == "indefinite") return o.put("remainingSeconds", -1) // 无限时，永不过期
        if (until == 0L) return o.put("remainingSeconds", -1)          // 锁定且未设结束时间
        val left = if (o.optInt("boot", -2) == bootCount()) o.optLong("untilElapsed") - android.os.SystemClock.elapsedRealtime()
            else until - System.currentTimeMillis()
        if (left <= 0) {
            store.set("remote_override", "")
            store.logEvent("remote", JSONObject().put("action", "expired").put("mode", o.optString("mode")))
            return null
        }
        return o.put("remainingSeconds", (left + 999) / 1000)
    }

    @Synchronized
    fun remote(action: String, minutes: Int, source: String): JSONObject {
        when (action) {
            "credit" -> { day(); time.adjust(minutes, store.config()) }
            "cancel" -> store.set("remote_override", "")
            "indefinite" -> {
                // 无限时解锁：管控完全关闭，直到家长手动取消
                store.set("remote_override", JSONObject().put("mode", "indefinite").put("minutes", 0)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", 0L).put("untilElapsed", 0L).toString())
            }
            "unlock", "lock" -> {
                require(action == "lock" || minutes in 1..24 * 60) { "解锁时长需要 1 分钟到 24 小时" }
                require(minutes in 0..7 * 24 * 60) { "锁定时长不正确" }
                val ms = minutes * 60000L
                store.set("remote_override", JSONObject().put("mode", action).put("minutes", minutes)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", if (ms == 0L) 0L else System.currentTimeMillis() + ms)
                    .put("untilElapsed", if (ms == 0L) 0L else android.os.SystemClock.elapsedRealtime() + ms).toString())
            }
            else -> throw IllegalArgumentException("未知操作")
        }
        flushUsage()
        store.logEvent("remote", JSONObject().put("action", action).put("minutes", minutes).put("source", source))
        return status()
    }

    private fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(DeviceRuntime.appContext.contentResolver, "boot_count", -1)
    } catch (_: Throwable) { -1 }

    /** 优先级：家长临时解锁 / 暂停 > 大人豁免 > 网课自由时段 > 时段用途（scheduleMode）。 */
    private fun freeNow(c: JSONObject, point: Window? = activeWindow(c)): Boolean {
        val o = override()
        if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
        if (point != null) return point.mode != "counted"
        return scheduleFree(c)
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val c = store.config()
        // 只用「今天真的能扣掉」的部分：余额比当天额度还多时不能显示成可用时间
        val remaining = usableCredit(c)
        val used = number("used_ms")
        val rounds = store.roundsDoneToday()
        val o = override()
        val point = activeWindow(c)
        // 网课「计入限额」时段用自己的每日上限，其余情况仍然是 config 里的 dailyMinutes
        val limit = if (point?.mode == "counted") JSONObject(c.toString()).put("dailyMinutes", point.dailyMinutes) else c
        val free = freeNow(c, point)
        val freeQuota = usableFree(c)
        val rest = time.restLeft(c)
        val hold = time.left("feedback_deadline")
        val reason = when {
            o?.optString("mode") == "lock" -> "locked"
            free -> ""
            rest > 0 -> "rest"
            hold > 0 -> "feedback"
            used >= time.dailyLimit(limit) -> "quota"
            c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
            freeQuota > 0 -> ""   // 免费时长也遵守每日上限和可用时段
            // 已达组数上限：用完最后一组的奖励时间再锁，但不再允许继续答题卷得更多时间
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") && remaining <= 0 -> "rounds"
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") -> ""   // 达组但还有剩余时间，用完才锁
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
            else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        return JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("quizGeneration", number("quiz_generation"))
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt).put("free", free)
            .put("restSeconds", (rest + 999) / 1000).put("feedbackSeconds", (hold + 999) / 1000)
            .put("todayLimitMinutes", if (c.optBoolean("dailyLimitEnabled", true)) time.dailyLimit(limit) / 60000.0 else JSONObject.NULL)
            .put("bootFreeSeconds", min(number("boot_quota_ms"), freeQuota) / 1000)
            .put("freeQuotaSeconds", freeQuota / 1000)
            .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", active()?.has("pendingReview") == true)
            .put("points", PointsSync.brief(store)).put("version", BuildConfig.VERSION_NAME).put("monitor", GateService.monitorMode).put("diag", GateService.diag)
            // 学生端用 activePoint 显示「网课时间 · 数学」/「大人豁免 · 看电影」
            .put("activePoint", point?.let { JSONObject().put("name", it.name).put("mode", it.mode).put("until", it.until) } ?: JSONObject.NULL)
    }

    private var pendingUsageMs = 0L

    /** 每秒调用一次。余额每次都持久化，但“使用记录”事件攒到 30 秒再写一条，避免一天写入上万行 */
    @Synchronized
    fun consume(milliseconds: Long) {
        day()
        val config = store.config()
        if (time.restLeft(config) > 0 || time.left("feedback_deadline") > 0) { flushUsage(); return }
        if (freeNow(config)) { flushUsage(); return }   // 自由时段不扣时长
        if (config.optString("scheduleMode", "allow") != "control" && !inWindow(config)) { flushUsage(); return }
        val available = capacity(config)   // 已含 beforeRest() 的休息上限，与 status() 同口径
        // 每日默认免费时长：先消耗，耗完才扣答题奖励
        val freeKey = if (number("free_quota_ms") > 0) "free_quota_ms" else "boot_quota_ms"
        val freeQuotaNow = number(freeKey)
        if (freeQuotaNow > 0) {
            val freeConsumed = min(milliseconds.coerceIn(0, 5000), min(freeQuotaNow, available))
            if (freeConsumed > 0) {
                put(freeKey, freeQuotaNow - freeConsumed)
                if (freeKey == "free_quota_ms") put("free_used_ms", number("free_used_ms") + freeConsumed)
                time.consumed(config, freeConsumed)
                put("used_ms", number("used_ms") + freeConsumed)
                pendingUsageMs += freeConsumed
                if (pendingUsageMs >= 30000 || freeQuotaNow == freeConsumed) flushUsage()
                return
            }
            // 额度已经扣完（家长收走时长 / 调小上限）：免费时长也扣不动，直接返回
            flushUsage(); return
        }
        val amount = min(milliseconds.coerceIn(0, 5000), min(number("credit_ms"), available))
        if (amount <= 0) { flushUsage(); return }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            put("credit_ms", number("credit_ms") - amount)
            put("used_ms", number("used_ms") + amount)
            pendingUsageMs += amount
            time.consumed(config, amount)
            if (pendingUsageMs >= 30000 || number("credit_ms") <= 0) flushUsage()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun flushUsage() {
        if (pendingUsageMs <= 0) return
        store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
        pendingUsageMs = 0
    }

    private fun active(): JSONObject? = store.get("active_round").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

    /**
     * 待批改（主观题）是孩子必须停下来的状态，所以任何一条「记录没了 / 已批改 / 已跳过」的残留
     * 都必须自愈，否则孩子答一次主观题后会永远停在「等待家长批改」页面（用户反馈：只答一次）。
     */
    private fun healPendingReview(round: JSONObject?) {
        val id = round?.optString("pendingReview")?.takeIf { it.isNotEmpty() } ?: return
        val review = store.review(id)
        val state = review?.optString("state") ?: "missing"
        if (state == "pending") return
        round.remove("pendingReview")
        store.set("active_round", round.toString())
        if (state == "graded") store.set("review_feedback", id)
        store.logEvent("review_healed", JSONObject().put("id", id).put("state", state))
        Diag.warn("Quiz", "pendingReview 已失效（$state），自动恢复 id=$id")
    }

    /**
     * 家长删题后修补进行中的一轮：孩子手上的题被删掉时立刻换成题库里还存在的题，
     * 并升高 quiz_generation 让前端（每 2 秒轮询）自动重载，不能再答已经删掉的题。
     */
    private fun repairActiveRound(deleted: Set<String>, allCleared: Boolean): Boolean {
        val round = active() ?: return false
        val question = round.optJSONObject("question")
        val pendingId = round.optString("pendingReview")
        val reviewQuestionId = pendingId.takeIf { it.isNotEmpty() }?.let { store.review(it) }?.optJSONObject("question")?.optString("id") ?: ""
        val hit = allCleared || (question != null && question.optString("id") in deleted) || reviewQuestionId.isNotEmpty() && reviewQuestionId in deleted
        if (!hit) return false
        if (pendingId.isNotEmpty()) {
            store.review(pendingId)?.let { review ->
                if (review.optString("state") == "pending") {
                    review.put("state", "skipped").put("feedback", "家长删除了这道题")
                    store.saveReview(review)
                }
            }
            round.remove("pendingReview")
        }
        val replaced = try { nextQuestion(round); true } catch (e: Exception) {
            Diag.warn("Quiz", "题目删空，作废进行中的一轮: ${e.message}")
            false
        }
        if (replaced) {
            store.set("active_round", round.toString())
            store.logEvent("round_replaced", JSONObject().put("reason", "questions_deleted"))
        } else {
            for (key in listOf("active_round", "last_answer", "feedback_result", "feedback_deadline", "review_feedback")) store.set(key, "")
            store.logEvent("round_cleared", JSONObject().put("reason", "questions_deleted"))
        }
        put("quiz_generation", number("quiz_generation") + 1)
        return true
    }

    /** 单题 / 批量删除走这里，保证删掉的正是孩子手上的题时会立刻换题并通知前端。 */
    @Synchronized
    fun deleteQuestions(ids: List<String>): Int {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val deleted = store.deleteQuestions(ids)
            repairActiveRound(ids.toSet(), false)
            db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    private fun nextQuestion(round: JSONObject) {
        val config = store.config()
        fun scope(key: String): List<String> {
            val array = config.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
        }
        val subjects = scope("quizSubjects")   // 答题范围（上游功能）
        val levels = scope("quizLevels")
        // 本轮已出过的题（最多记 30 道）不再重复；跨轮靠“没做过的优先、最久没做的优先”避免重复
        val recent = round.optJSONArray("recent") ?: JSONArray().also { round.put("recent", it) }
        val skip = HashSet<String>()
        for (i in 0 until recent.length()) skip.add(recent.optString(i))
        val last = round.optJSONObject("question")?.optString("id")?.takeIf { it.isNotEmpty() }
        if (last != null) skip.add(last)
        // 本轮指定年级（上游功能）与题库范围：和答题范围取交集
        val selectedLevel = round.optString("level")
        val banks = scope("quizBanks")
        val effLevels = if (selectedLevel.isEmpty()) levels else if (levels.isEmpty() || selectedLevel in levels) listOf(selectedLevel) else listOf("\u0000")
        var question = store.pickQuestion(skip, last, config.optBoolean("spacedRepetitionEnabled"), Tune.minStar(store), subjects, effLevels, banks)
            ?: throw IllegalArgumentException(
                if (subjects.isNotEmpty() || levels.isNotEmpty()) "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围" else "题库为空，请由家长添加题目")
        recent.put(question.optString("id"))
        if (recent.length() > 30) {
            val kept = JSONArray()
            for (i in recent.length() - 30 until recent.length()) kept.put(recent.optString(i))
            round.put("recent", kept)
        }
        if (config.optBoolean("shuffleOptions")) question = QuestionOptions.shuffled(question)
        store.presented(question.getString("id"))
        round.put("question", question).put("nonce", UUID.randomUUID().toString())
        val extraEnabled = round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
        round.put("extra", if (round.optString("mode") == "count") false else if (round.optString("mode") == "practice") number("extra_pool") > 0
            else extraEnabled && round.optInt("answered") >= round.getInt("target"))
    }

    private fun publicRound(round: JSONObject): JSONObject {
        val copy = JSONObject(round.toString())
        val question = copy.getJSONObject("question")
        listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric").forEach { question.remove(it) }
        copy.remove("rules"); copy.remove("recent")
        return copy.put("extraPool", number("extra_pool")).put("status", status())
    }

    @Synchronized
    fun start(level: String? = null): JSONObject {
        val st = status()
        val c = store.config()
        if (st.getString("reason") == "feedback") {
            // feedback_result 可能是空字符串（清库 / 跨天后残留的倒计时），不能直接 JSONObject 解析
            val held = JSONObject(store.get("feedback_result").takeIf { it.isNotEmpty() } ?: "{}")
            if (!held.has("question")) {
                store.set("feedback_deadline", "")
                store.set("feedback_result", "")
                return start(level)
            }
            return held.put("heldFeedback", true).put("status", st)
        }
        if (st.getString("reason") in listOf("locked", "rest")) return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        if (st.optBoolean("free") && c.getString("playMode") != "practice")
            return JSONObject().put("free", true).put("status", st)
        var round = active()
        healPendingReview(round)
        if (round == null && videos.wanted(c)) return JSONObject().put("videoLesson", videos.start(c)).put("status", st)
        val feedbackId = store.get("review_feedback")
        val feedback = feedbackId.takeIf { it.isNotEmpty() }?.let { store.review(it) }
        if (feedback?.optString("state") == "graded") return JSONObject().put("reviewed", true).put("status", st)
            .put("reviewId", feedbackId).put("score", feedback.getDouble("score"))
            .put("feedback", feedback.optString("feedback")).put("result", feedback.getJSONObject("result"))
        if (c.getString("playMode") != "practice" && st.getString("reason") in listOf("quota", "rounds", "schedule"))
            return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        if (st.optLong("remainingSeconds") > 0 && c.getString("playMode") != "practice")
            return JSONObject().put("watching", true).put("status", st)
        if (round?.has("pendingReview") == true) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", st)
        if (round != null && round.optInt("answered") == 0 && round.getJSONObject("rules").optInt("version") != c.optInt("version")) round = null
        if (round == null || round.optString("mode") != c.getString("playMode")) {
            if (c.optBoolean("childSelectLevel")) {
                val allowed = c.optJSONArray("quizLevels") ?: JSONArray()
                val subjects = c.optJSONArray("quizSubjects") ?: JSONArray()
                val banks = c.optJSONArray("quizBanks") ?: JSONArray()
                fun accepts(a: JSONArray, value: String) = a.length() == 0 || (0 until a.length()).any { a.optString(it) == value }
                val items = store.questions(limit = 5000)
                val choices = (0 until items.length()).map { items.getJSONObject(it) }
                    .filter { accepts(allowed, it.optString("level")) && accepts(subjects, it.optString("subject")) && accepts(banks, it.optString("bank", "默认题库")) }
                    .map { it.optString("level") }.filter { it.isNotBlank() }.distinct().sorted()
                require(choices.isNotEmpty()) { "请先为启用题目标注年级，或关闭孩子选择年级" }
                if (level == null) return JSONObject().put("chooseLevel", JSONArray(choices)).put("status", st)
                require(level in choices) { "请选择家长允许的年级" }
            }
            round = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", c.getString("playMode"))
                .put("level", if (c.optBoolean("childSelectLevel")) level else "")
                .put("target", c.getInt("questionsPerRound")).put("answered", 0).put("correct", 0)
                .put("streak", 0).put("earnedMs", 0).put("rules", c)
            nextQuestion(round)
            store.set("active_round", round.toString())
        }
        return publicRound(round)
    }

    @Synchronized
    fun clearQuestions(bank: String? = null): Int {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            // 先记下被删的题目：才能判断孩子手上的那一轮是不是真的被删掉了
            val removed = if (bank == null) emptySet() else store.idsInBank(bank).toSet()
            val deleted = if (bank == null) store.clearQuestions() else store.deleteQuestions(removed.toList())
            repairActiveRound(removed, bank == null)
            db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    @Synchronized fun videoProgress(id: String, playing: Boolean): JSONObject {
        val state = status()
        require(state.optString("reason") !in listOf("locked", "rest", "schedule", "quota", "rounds", "feedback")) { "当前暂不可进行视频学习" }
        return videos.progress(id, playing)
    }

    @Synchronized fun completeVideo(id: String): JSONObject {
        videoProgress(id, false)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            videos.complete(id)
            val config = store.config()
            // 免费额度同样占每日上限，发放奖励前先留出来
            val grant = minOf(config.optLong("sessionMinutes", 10) * 60000, (time.dailyLimit(limitConfig(config)) - number("used_ms") - number("credit_ms") - freeBalance()).coerceAtLeast(0))
            put("credit_ms", number("credit_ms") + grant)
            store.logEvent("quiz", JSONObject().put("native", true).put("video", true).put("total", 0).put("correct", 0).put("passed", true))
            store.logEvent("quiz_pass", JSONObject().put("roundId", id).put("minutes", grant / 60000.0).put("video", true))
            db.setTransactionSuccessful()
            return JSONObject().put("rewardMinutes", grant / 60000.0).put("status", status())
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun continueFeedback(): JSONObject {
        require(time.left("feedback_deadline") == 0L) { "请先等待解析倒计时结束" }
        store.set("feedback_result", "")
        store.set("feedback_deadline", "")
        return JSONObject().put("status", status())
    }

    @Synchronized
    fun acknowledgeReview(id: String): JSONObject {
        if (store.get("review_feedback") == id) store.set("review_feedback", "")
        return JSONObject().put("ok", true).put("status", status())
    }

    /** 孩子不想继续等 AI/家长批改时，放弃这道题的批改并进入下一题。 */
    @Synchronized
    fun skipPending(): JSONObject {
        val round = active() ?: return JSONObject().put("error", "round_missing")
        val id = round.optString("pendingReview")
        if (id.isEmpty()) return JSONObject().put("pending", false).put("status", status())
        store.review(id)?.let {
            it.put("state", "skipped").put("feedback", "孩子选择先跳过，等待家长处理").put("skippedAt", System.currentTimeMillis())
            store.saveReview(it)
        }
        round.remove("pendingReview")
        nextQuestion(round)
        store.set("active_round", round.toString())
        store.logEvent("review_skipped", JSONObject().put("id", id))
        Diag.warn("Quiz", "review skipped id=$id")
        return publicRound(round)
    }

    @Synchronized
    fun grade(reviewId: String, score: Double, feedback: String, source: String): JSONObject {
        require(score.isFinite() && score in 0.0..100.0) { "分数需要在 0–100 之间" }
        val review = store.review(reviewId) ?: throw IllegalArgumentException("待批题目不存在")
        require(review.getString("state") != "skipped") { "这道题已被跳过，不再批改" }
        if (review.getString("state") == "graded") return review
        val settings = JSONObject(store.get("grading_settings", "{}"))
        val threshold = settings.optDouble("passScore", 60.0)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val quality = when { score < threshold -> 1; score >= 90 -> 5; score >= 75 -> 4; else -> 3 }
            val result = answer(review.getJSONObject("input"), score >= threshold, quality)
            require(!result.has("error") && !result.has("blocked")) { "当前任务暂不可结算，请在可用时段重试" }
            review.put("state", "graded").put("score", score).put("feedback", feedback.take(5000))
                .put("source", source).put("gradedAt", System.currentTimeMillis()).put("result", result)
            store.saveReview(review)
            store.set("review_feedback", reviewId)
            store.logEvent("review_graded", JSONObject().put("id", reviewId).put("score", score).put("source", source))
            db.setTransactionSuccessful()
            return review
        } finally { db.endTransaction() }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    @Synchronized
    fun answer(input: JSONObject, verdict: Boolean? = null, quality: Int? = null): JSONObject {
        day()
        val last = store.get("last_answer")
        if (verdict == null && last.isNotEmpty()) {
            val cached = JSONObject(last)
            if (cached.optString("nonce") == input.optString("nonce")) return cached.getJSONObject("result")
        }
        val round = active() ?: return JSONObject().put("error", "round_missing")
        if (verdict == null && round.has("pendingReview")) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", status())
        // 家长可能在孩子作答的瞬间把这道题删了：不能抛错、不能白屏，换一道题并返回可恢复的结果
        if (verdict == null && round.optJSONObject("question")?.optString("id")?.let { !store.questionExists(it) } == true) {
            Diag.warn("Quiz", "作答的题目已被删除，重新出题")
            store.logEvent("answer_question_deleted", JSONObject().put("roundId", round.optString("id")))
            val replaced = try { nextQuestion(round); true } catch (e: Exception) { Diag.warn("Quiz", "删题后无题可用: ${e.message}"); false }
            if (replaced) store.set("active_round", round.toString())
            else for (key in listOf("active_round", "last_answer", "feedback_result", "feedback_deadline", "review_feedback")) store.set(key, "")
            put("quiz_generation", number("quiz_generation") + 1)
            val recovered = JSONObject().put("recovered", true).put("finished", false).put("correct", false)
                .put("correctIndices", JSONArray()).put("status", status())
            if (!replaced) recovered.put("quizEmpty", true).put("quizEmptyMessage", "题库为空，请由家长添加题目")
            return recovered
        }
        require(input.getString("roundId") == round.getString("id") && input.getString("nonce") == round.getString("nonce")) { "题目已更新，请重新加载" }
        val st = status()
        if (verdict == null && round.getString("mode") != "practice" && st.getString("reason") in listOf("schedule", "quota", "rounds", "rest", "feedback", "locked"))
            return JSONObject().put("blocked", st.getString("reason"))
        val question = round.getJSONObject("question")
        val type = question.optString("type", "single")
        if (verdict == null && (type == "subjective" || input.optString("drawing").isNotEmpty())) {
            require(input.optString("text").isNotBlank() || input.optString("drawing").isNotEmpty()) { "请先作答" }
            val drawing = input.optString("drawing")
            require(drawing.isEmpty() || (drawing.startsWith("data:image/png;base64,") && drawing.length <= 2000000)) { "手写图片格式不正确或过大" }
            val id = UUID.randomUUID().toString()
            val review = JSONObject().put("id", id).put("state", "pending").put("ts", System.currentTimeMillis())
                .put("question", question).put("input", input).put("feedback", "等待批改")
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                store.saveReview(review)
                round.put("pendingReview", id)
                store.set("active_round", round.toString())
                store.logEvent("review_submitted", JSONObject().put("id", id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return JSONObject().put("pending", true).put("reviewId", id).put("status", status())
        }
        val expected = if (type == "multiple") question.getJSONArray("correctIndices") else JSONArray().put(question.optInt("k"))
        fun indices(array: JSONArray): Set<Int> = (0 until array.length()).map { array.getInt(it) }.toSet()
        val correct = if (verdict != null) verdict else if (type == "text") {
            val value = normalize(input.optString("text"))
            val accepted = question.getJSONArray("answers")
            value.isNotEmpty() && (0 until accepted.length()).any { normalize(accepted.getString(it)) == value }
        } else {
            val selections = input.getJSONArray("selections")
            require(selections.length() > 0 && (0 until selections.length()).all { selections.getInt(it) in 0 until question.getJSONArray("a").length() }) { "请选择有效答案" }
            indices(expected) == indices(selections)
        }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            round.remove("pendingReview")
            val rules = round.getJSONObject("rules")
            val wasExtra = round.optBoolean("extra")
            val mode = round.getString("mode")
            var pointsEarned = 0
            if (!correct && mode != "count" && rules.optBoolean("extraEnabled")) {
                var wrong = number("wrong_progress") + 1
                if (wrong >= rules.getInt("wrongThreshold")) {
                    val extra = rules.getLong("extraQuestions")
                    put("extra_pool", number("extra_pool") + extra)
                    wrong -= rules.getInt("wrongThreshold")
                    store.logEvent("extra_added", JSONObject().put("count", extra))
                }
                put("wrong_progress", wrong)
            }
            if (correct && wasExtra && number("extra_pool") > 0) put("extra_pool", number("extra_pool") - 1)
            if (mode == "streak" && !correct && !wasExtra) round.put("earnedMs", 0)
            if (!wasExtra || mode == "practice") {
                round.put("answered", round.getInt("answered") + 1)
                if (correct) {
                    round.put("correct", round.getInt("correct") + 1)
                    pointsEarned += PointsSync.earnForAnswer(store, question.optInt("difficulty", 2))
                }
                round.put("streak", if (correct) round.getInt("streak") + 1 else 0)
                if (correct && rules.optBoolean("difficultyRewardEnabled")) {
                    val minutes = rules.getJSONObject("difficultyRewards").getDouble(question.optInt("difficulty", 2).toString())
                    round.put("earnedMs", round.getLong("earnedMs") + (minutes * 60000).toLong())
                }
            }
            val repetitions = store.recordProgress(question.getString("id"), quality ?: if (correct) 4 else 1)
            store.answered(question.getString("id"), correct)
            // 同一道题连续答对多次：自动淘汰，不再出现
            Tune.afterAnswer(store, question.getString("id"), correct, repetitions)
            store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
                .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
            val baseDone = when (mode) {
                "streak" -> round.getInt("streak") >= round.getInt("target")
                "count" -> round.getInt("correct") >= round.getInt("target")
                else -> round.getInt("answered") >= round.getInt("target")
            }
            val passed = mode in listOf("streak", "count") || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
            val extraEnabled = mode != "count" && rules.optBoolean("extraEnabled", true)
            val finished = mode != "practice" && baseDone && (!passed || !extraEnabled || number("extra_pool") == 0L)
            val result = JSONObject().put("correct", correct).put("correctIndices", if (type == "text") JSONArray() else expected)
                .put("answerText", if (type == "text") question.getJSONArray("answers").getString(0) else "")
                .put("tip", question.optString("tip")).put("finished", finished).put("passed", finished && passed)
            if (finished) {
                var grant = 0L
                if (passed) {
                    val requested = if (rules.optBoolean("difficultyRewardEnabled")) round.getLong("earnedMs") else rules.getLong("sessionMinutes") * 60000
                    // 免费额度也算在每日上限里，发奖励前必须先留出来（否则奖励余额会永远扣不动）
                    val budget = time.dailyLimit(limitConfig(store.config())) - number("used_ms") - number("credit_ms") - freeBalance()
                    grant = min(requested, budget.coerceAtLeast(0))
                    put("credit_ms", number("credit_ms") + grant)
                    pointsEarned += PointsSync.earnForPass(store)
                    store.logEvent("quiz_pass", JSONObject().put("roundId", round.getString("id")).put("minutes", grant / 60000.0))
                }
                store.logEvent("quiz", JSONObject().put("native", true).put("total", round.getInt("answered"))
                    .put("correct", round.getInt("correct")).put("passed", passed))
                store.set("active_round", "")
                result.put("rewardMinutes", grant / 60000.0)
            } else {
                if (mode == "streak" && !baseDone) round.put("extra", false)
                // 题库被家长删空 / 答题范围没有题目时不能把改动回滚后停在旧题上：作废本轮并通知前端
                var aborted = false
                if (mode == "count" && !correct) {
                    round.put("nonce", UUID.randomUUID().toString())
                    store.presented(question.getString("id"))
                } else {
                    try { nextQuestion(round) } catch (e: IllegalArgumentException) {
                        aborted = true
                        Diag.warn("Quiz", "无法生成下一题，作废本轮: ${e.message}")
                        store.logEvent("round_aborted", JSONObject().put("reason", "empty_bank").put("message", e.message ?: ""))
                        store.set("active_round", "")
                        put("quiz_generation", number("quiz_generation") + 1)
                        result.put("quizEmpty", true).put("quizEmptyMessage", e.message ?: "题库为空，请由家长添加题目")
                    }
                }
                if (!aborted) {
                    if (mode == "streak" && !baseDone) round.put("extra", false)
                    if (mode == "practice") round.put("extra", number("extra_pool") > 0)
                    store.set("active_round", round.toString())
                    result.put("next", publicRound(round))
                }
            }
            if (!correct && rules.optInt("wrongReviewSeconds") > 0) {
                time.deadline("feedback_deadline", rules.optInt("wrongReviewSeconds") * 1000L)
                store.set("feedback_result", JSONObject().put("result", JSONObject(result.toString()))
                    .put("question", JSONObject(question.toString())).toString())
            }
            result.put("pointsEarned", pointsEarned)
            if (pointsEarned > 0) PointsSync.kick(store)
            result.put("status", status())
            store.set("last_answer", JSONObject().put("nonce", input.getString("nonce")).put("result", result).toString())
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
}
