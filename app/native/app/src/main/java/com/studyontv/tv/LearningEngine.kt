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
            put("usage_day", current)
            put("credit_ms", 0)
            put("used_ms", 0)
        }
    }

    private fun inWindow(config: JSONObject): Boolean {
        if (config.optBoolean("allDay")) return true
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun parse(value: String): Int = value.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val start = parse(config.getString("windowStart"))
        val end = parse(config.getString("windowEnd"))
        return if (start < end) minute in start until end else if (start > end) minute >= start || minute < end else false
    }

    private fun nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
        fun parse(value: String) = value.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val start = parse(config.getString("windowStart"))
        val end = parse(config.getString("windowEnd"))
        val cal = Calendar.getInstance()
        if (tomorrow) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        }
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val allowed = config.optBoolean("allDay") || (if (start < end) minute in start until end else if (start > end) minute >= start || minute < end else false)
        if (!allowed) {
            if (minute >= start) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, start / 60); cal.set(Calendar.MINUTE, start % 60)
        }
        return cal.timeInMillis
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val c = store.config()
        val remaining = number("credit_ms")
        val used = number("used_ms")
        val rounds = store.roundsDoneToday()
        val reason = when {
            used >= c.getLong("dailyMinutes") * 60000 -> "quota"
            remaining <= 0 && rounds >= c.getInt("roundsPerDay") -> "rounds"
            !inWindow(c) -> "schedule"
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        return JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt)
            .put("roundsDoneToday", rounds).put("reason", reason).put("awaitingReview", active()?.has("pendingReview") == true)
            .put("points", PointsSync.brief(store)).put("version", BuildConfig.VERSION_NAME)
    }

    @Synchronized
    fun consume(milliseconds: Long) {
        day()
        val amount = min(milliseconds.coerceIn(0, 5000), min(number("credit_ms"),
            (store.config().getLong("dailyMinutes") * 60000 - number("used_ms")).coerceAtLeast(0)))
        if (amount <= 0) return
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            put("credit_ms", number("credit_ms") - amount)
            put("used_ms", number("used_ms") + amount)
            store.logEvent("usage", JSONObject().put("milliseconds", amount))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun active(): JSONObject? = store.get("active_round").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

    private fun nextQuestion(round: JSONObject) {
        val bank = store.questions(limit = 2, spaced = store.config().optBoolean("spacedRepetitionEnabled"))
        require(bank.length() > 0) { "题库为空，请由家长添加题目" }
        val last = round.optJSONObject("question")?.optString("id")
        var question = bank.getJSONObject(0)
        for (i in 0 until bank.length()) {
            if (bank.getJSONObject(i).optString("id") != last) { question = bank.getJSONObject(i); break }
        }
        round.put("question", question).put("nonce", UUID.randomUUID().toString())
        round.put("extra", if (round.optString("mode") == "practice") number("extra_pool") > 0 else round.optInt("answered") >= round.getInt("target"))
    }

    private fun publicRound(round: JSONObject): JSONObject {
        val copy = JSONObject(round.toString())
        val question = copy.getJSONObject("question")
        listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric").forEach { question.remove(it) }
        copy.remove("rules")
        return copy.put("extraPool", number("extra_pool")).put("status", status())
    }

    @Synchronized
    fun start(): JSONObject {
        val st = status()
        val c = store.config()
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

    @Synchronized
    fun grade(reviewId: String, score: Double, feedback: String, source: String): JSONObject {
        require(score.isFinite() && score in 0.0..100.0) { "分数需要在 0–100 之间" }
        val review = store.review(reviewId) ?: throw IllegalArgumentException("待批题目不存在")
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
            store.recordProgress(question.getString("id"), quality ?: if (correct) 4 else 1)
            store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
                .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
            val baseDone = if (mode == "streak") round.getInt("streak") >= round.getInt("target")
                else round.getInt("answered") >= round.getInt("target")
            val passed = mode == "streak" || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
            val finished = mode != "practice" && baseDone && (!passed || number("extra_pool") == 0L)
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
