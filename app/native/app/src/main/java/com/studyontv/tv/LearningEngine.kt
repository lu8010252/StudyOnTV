package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

class LearningEngine(private val store: LocalStore) {
    private fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())

    private fun day() {
        val current = store.startOfToday()
        if (number("usage_day") != current) {
            if (pendingUsageMs > 0) { store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs)); pendingUsageMs = 0 }
            put("usage_day", current)
            put("credit_ms", 0)
            put("used_ms", 0)
            // 每天重置时，把"默认免费时长"存入 free_quota_ms（和答题奖励分开记）
            val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
            put("free_quota_ms", freeMin * 60000L)
        }
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

    private fun freeNow(c: JSONObject): Boolean {
        val o = override()
        if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
        return scheduleFree(c)
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val c = store.config()
        val remaining = number("credit_ms")
        val used = number("used_ms")
        val rounds = store.roundsDoneToday()
        val o = override()
        val free = freeNow(c)
        val freeQuota = number("free_quota_ms")
        val reason = when {
            o?.optString("mode") == "lock" -> "locked"
            free -> ""
            freeQuota > 0 -> ""   // 每日默认免费时长还有余额，放行
            used >= c.getLong("dailyMinutes") * 60000 -> "quota"
            c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
            // 已达组数上限：用完最后一组的奖励时间再锁，但不再允许继续答题卷得更多时间
            rounds >= c.getInt("roundsPerDay") && remaining <= 0 -> "rounds"
            rounds >= c.getInt("roundsPerDay") -> ""   // 达组但还有剩余时间，用完才锁
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
            else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        return JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt).put("free", free)
            .put("freeQuotaSeconds", freeQuota / 1000)
            .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", active()?.has("pendingReview") == true)
            .put("points", PointsSync.brief(store)).put("version", BuildConfig.VERSION_NAME).put("monitor", GateService.monitorMode).put("diag", GateService.diag)
    }

    private var pendingUsageMs = 0L

    /** 每秒调用一次。余额每次都持久化，但“使用记录”事件攒到 30 秒再写一条，避免一天写入上万行 */
    @Synchronized
    fun consume(milliseconds: Long) {
        day()
        if (freeNow(store.config())) { flushUsage(); return }   // 自由时段不扣时长
        // 每日默认免费时长：先消耗，耗完才扣答题奖励
        val freeQuotaNow = number("free_quota_ms")
        if (freeQuotaNow > 0) {
            val freeConsumed = milliseconds.coerceIn(0, freeQuotaNow)
            put("free_quota_ms", freeQuotaNow - freeConsumed)
            put("used_ms", number("used_ms") + freeConsumed)
            flushUsage(); return
        }
        val amount = min(milliseconds.coerceIn(0, 5000), min(number("credit_ms"),
            (store.config().getLong("dailyMinutes") * 60000 - number("used_ms")).coerceAtLeast(0)))
        if (amount <= 0) { flushUsage(); return }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            put("credit_ms", number("credit_ms") - amount)
            put("used_ms", number("used_ms") + amount)
            pendingUsageMs += amount
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
        val question = store.pickQuestion(skip, last, config.optBoolean("spacedRepetitionEnabled"), Tune.minStar(store), subjects, levels)
            ?: throw IllegalArgumentException(
                if (subjects.isNotEmpty() || levels.isNotEmpty()) "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围" else "题库为空，请由家长添加题目")
        recent.put(question.optString("id"))
        if (recent.length() > 30) {
            val kept = JSONArray()
            for (i in recent.length() - 30 until recent.length()) kept.put(recent.optString(i))
            round.put("recent", kept)
        }
        round.put("question", question).put("nonce", UUID.randomUUID().toString())
        val extraEnabled = round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
        round.put("extra", if (round.optString("mode") == "practice") number("extra_pool") > 0
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
    fun start(): JSONObject {
        val st = status()
        val c = store.config()
        if (st.getString("reason") == "locked") return JSONObject().put("blocked", "locked").put("status", st)
        if (st.optBoolean("free") && c.getString("playMode") != "practice")
            return JSONObject().put("free", true).put("status", st)
        val feedbackId = store.get("review_feedback")
        val feedback = feedbackId.takeIf { it.isNotEmpty() }?.let { store.review(it) }
        if (feedback?.optString("state") == "graded") return JSONObject().put("reviewed", true).put("status", st)
            .put("reviewId", feedbackId).put("score", feedback.getDouble("score"))
            .put("feedback", feedback.optString("feedback")).put("result", feedback.getJSONObject("result"))
        if (c.getString("playMode") != "practice" && st.getString("reason") in listOf("quota", "rounds", "schedule"))
            return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        if (st.optLong("remainingSeconds") > 0 && c.getString("playMode") != "practice")
            return JSONObject().put("watching", true).put("status", st)
        var round = active()
        if (round?.has("pendingReview") == true) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", st)
        if (round != null && round.optInt("answered") == 0 && round.getJSONObject("rules").optInt("version") != c.optInt("version")) round = null
        if (round == null || round.optString("mode") != c.getString("playMode")) {
            round = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", c.getString("playMode"))
                .put("target", c.getInt("questionsPerRound")).put("answered", 0).put("correct", 0)
                .put("streak", 0).put("earnedMs", 0).put("rules", c)
            nextQuestion(round)
            store.set("active_round", round.toString())
        }
        return publicRound(round)
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
        require(input.getString("roundId") == round.getString("id") && input.getString("nonce") == round.getString("nonce")) { "题目已更新，请重新加载" }
        val st = status()
        if (verdict == null && round.getString("mode") != "practice" && st.getString("reason") in listOf("schedule", "quota", "rounds"))
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
            if (!correct && rules.optBoolean("extraEnabled")) {
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
            // 同一道题连续答对多次：自动淘汰，不再出现
            Tune.afterAnswer(store, question.getString("id"), correct, repetitions)
            store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
                .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
            val baseDone = if (mode == "streak") round.getInt("streak") >= round.getInt("target")
                else round.getInt("answered") >= round.getInt("target")
            val passed = mode == "streak" || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
            val extraEnabled = rules.optBoolean("extraEnabled", true)
            val finished = mode != "practice" && baseDone && (!passed || !extraEnabled || number("extra_pool") == 0L)
            val result = JSONObject().put("correct", correct).put("correctIndices", if (type == "text") JSONArray() else expected)
                .put("answerText", if (type == "text") question.getJSONArray("answers").getString(0) else "")
                .put("tip", question.optString("tip")).put("finished", finished).put("passed", finished && passed)
            if (finished) {
                var grant = 0L
                if (passed) {
                    val requested = if (rules.optBoolean("difficultyRewardEnabled")) round.getLong("earnedMs") else rules.getLong("sessionMinutes") * 60000
                    val budget = store.config().getLong("dailyMinutes") * 60000 - number("used_ms") - number("credit_ms")
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
                nextQuestion(round)
                if (mode == "streak" && !baseDone) round.put("extra", false)
                if (mode == "practice") round.put("extra", number("extra_pool") > 0)
                store.set("active_round", round.toString())
                result.put("next", publicRound(round))
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
