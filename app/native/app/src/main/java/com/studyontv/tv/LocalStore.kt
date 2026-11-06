package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class LocalStore(context: Context) : SQLiteOpenHelper(context, "studyontv.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        createReviews(db)
        createProgress(db)
        createPoints(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE questions ADD COLUMN details TEXT")
        if (oldVersion < 3) createReviews(db)
        if (oldVersion < 4) createProgress(db)
        if (oldVersion < 5) createPoints(db)
    }

    private fun createProgress(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE question_progress(qid TEXT PRIMARY KEY, repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_question_due ON question_progress(due_at)")
    }

    // ---- 积分账本：id 同时作为星星屋的幂等键 extId，synced=0 表示还没同步到星星屋 ----
    private fun createPoints(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points_ledger(id TEXT PRIMARY KEY, ts INTEGER NOT NULL, delta INTEGER NOT NULL, reason TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_points_ts ON points_ledger(ts)")
    }

    fun addPoints(delta: Int, reason: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insert("points_ledger", null, ContentValues().apply {
            put("id", id); put("ts", System.currentTimeMillis()); put("delta", delta); put("reason", reason.take(40)); put("synced", 0)
        })
        return id
    }

    fun pointsEarnedToday(): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(delta),0) FROM points_ledger WHERE ts>=?", arrayOf(startOfToday().toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun pendingPoints(limit: Int = 50): List<Triple<String, Int, String>> {
        val out = ArrayList<Triple<String, Int, String>>()
        readableDatabase.rawQuery("SELECT id,delta,reason FROM points_ledger WHERE synced=0 ORDER BY ts LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) out.add(Triple(it.getString(0), it.getInt(1), it.getString(2)))
        }
        return out
    }

    fun pendingPointsCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM points_ledger WHERE synced=0", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun pendingPointsSum(): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(delta),0) FROM points_ledger WHERE synced=0", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun markPointsSynced(id: String) {
        writableDatabase.execSQL("UPDATE points_ledger SET synced=1 WHERE id=?", arrayOf(id))
    }

    fun recentPoints(limit: Int = 20): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT ts,delta,reason,synced FROM points_ledger ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) out.put(JSONObject().put("ts", it.getLong(0)).put("delta", it.getInt(1))
                .put("reason", it.getString(2)).put("synced", it.getInt(3) == 1))
        }
        return out
    }

    fun recordProgress(id: String, quality: Int) {
        var repetitions = 0
        var interval = 0L
        var ease = 2.5
        readableDatabase.rawQuery("SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=?", arrayOf(id)).use {
            if (it.moveToFirst()) { repetitions = it.getInt(0); interval = it.getLong(1); ease = it.getDouble(2) }
        }
        val q = quality.coerceIn(0, 5)
        val now = System.currentTimeMillis()
        val next = if (q < 3) { repetitions = 0; interval = 0; now + 10 * 60 * 1000 } else {
            repetitions++
            interval = when (repetitions) { 1 -> 1; 2 -> 6; else -> kotlin.math.round(interval * ease).toLong().coerceIn(1, 36500) }
            now + interval * 86400000L
        }
        ease = (ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
            put("qid", id); put("repetitions", repetitions); put("interval_days", interval); put("ease", ease)
            put("due_at", next); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun repetitionStats(): JSONObject = readableDatabase.rawQuery(
        "SELECT COUNT(*),SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END) FROM question_progress JOIN questions ON questions.id=question_progress.qid WHERE questions.enabled=1", arrayOf(System.currentTimeMillis().toString())
    ).use { if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1)) else JSONObject().put("studied", 0).put("due", 0) }

    private fun createReviews(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY, nonce TEXT UNIQUE, state TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)")
    }

    fun saveReview(value: JSONObject) {
        writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
            put("id", value.getString("id")); put("nonce", value.getJSONObject("input").getString("nonce"))
            put("state", value.optString("state", "pending")); put("payload", value.toString()); put("ts", value.getLong("ts"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun noteReview(value: JSONObject) {
        writableDatabase.update("reviews", ContentValues().apply { put("payload", value.toString()) },
            "id=? AND state='pending'", arrayOf(value.getString("id")))
    }

    fun review(id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM reviews WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun reviews(state: String): JSONArray {
        val out = JSONArray()
        val filter = if (state == "all") "" else "WHERE state=?"
        readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
            while (it.moveToNext()) out.put(JSONObject(it.getString(0)))
        }
        return out
    }

    fun get(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    fun set(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    fun config(): JSONObject {
        val defaults = JSONObject().put("dailyMinutes", 40).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", false).put("spacedRepetitionEnabled", false)
            .put("playMode", "round").put("extraEnabled", true)
            .put("wrongThreshold", 3).put("extraQuestions", 2)
            .put("difficultyRewardEnabled", false)
            .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
            .put("version", 1)
        val saved = try { JSONObject(get("config", "{}")) } catch (e: Exception) { JSONObject() }
        saved.keys().forEach { defaults.put(it, saved.get(it)) }
        val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        val validStart = timePattern.matches(defaults.optString("windowStart"))
        val validEnd = timePattern.matches(defaults.optString("windowEnd"))
        if (!validStart) defaults.put("windowStart", "18:30")
        if (!validEnd) defaults.put("windowEnd", "20:00")
        if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
        return defaults
    }

    fun saveConfig(input: JSONObject): JSONObject {
        val next = config()
        val ranges = mapOf("dailyMinutes" to 1..480, "sessionMinutes" to 1..120,
            "questionsPerRound" to 1..100, "roundsPerDay" to 1..100,
            "wrongThreshold" to 1..100, "extraQuestions" to 1..100)
        ranges.forEach { (key, range) ->
            if (input.has(key)) {
                val value = input.getDouble(key)
                require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "$key 超出范围" }
                next.put(key, value.toInt())
            }
        }
        val wantsAllDay = if (input.has("allDay")) input.getBoolean("allDay") else next.optBoolean("allDay")
        listOf("windowStart", "windowEnd").forEach { key ->
            if (input.has(key)) {
                val valid = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(input.optString(key))
                require(wantsAllDay || valid) { "请输入有效的开始和结束时间" }
                if (valid) next.put(key, input.getString(key))
            }
        }
        if (input.has("passRatio")) {
            val ratio = input.getDouble("passRatio")
            require(ratio.isFinite() && ratio > 0 && ratio <= 1) { "答对比例需要大于 0 且不超过 100%" }
            next.put("passRatio", ratio)
        }
        if (input.has("playMode")) {
            require(input.getString("playMode") in listOf("round", "streak", "practice")) { "未知玩法" }
            next.put("playMode", input.getString("playMode"))
        }
        listOf("extraEnabled", "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled").forEach {
            if (input.has(it)) next.put(it, input.getBoolean(it))
        }
        if (input.has("difficultyRewards")) {
            val rewards = input.getJSONObject("difficultyRewards")
            for (star in 1..5) {
                val minutes = rewards.getDouble(star.toString())
                require(minutes.isFinite() && minutes in 0.1..120.0) { "每题奖励需要为 0.1–120 分钟" }
            }
            next.put("difficultyRewards", rewards)
        }
        require(next.optBoolean("allDay") || next.getString("windowStart") != next.getString("windowEnd")) { "开始与结束时间不能相同；全天请开启全天可用" }
        next.put("version", next.optInt("version", 1) + 1)
        set("config", next.toString())
        return next
    }

    fun questions(subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false): JSONArray {
        val where = StringBuilder("enabled=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND level=?"); args.add(level) }
        val out = JSONArray()
        val source = if (spaced) "questions LEFT JOIN question_progress progress ON questions.id=progress.qid" else "questions"
        val order = if (spaced) "CASE WHEN progress.due_at<=${System.currentTimeMillis()} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()" else "RANDOM()"
        readableDatabase.rawQuery("SELECT questions.* FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 5000)}", args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val details = c.getString(c.getColumnIndexOrThrow("details"))
                val item = if (details.isNullOrEmpty()) JSONObject() else JSONObject(details)
                item.put("id", c.getString(c.getColumnIndexOrThrow("id")))
                    .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
                    .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
                    .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
                    .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
                    .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
                if (!item.has("type")) item.put("type", "single")
                out.put(item)
            }
        }
        return out
    }

    fun countQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun upsertQuestion(item: JSONObject): String {
        val stem = item.optString("q").trim()
        require(stem.isNotEmpty() && stem.length <= 5000) { "请填写题干" }
        val type = item.optString("type", "single")
        require(type in listOf("single", "multiple", "boolean", "text", "subjective")) { "不支持此题型" }
        val options = if (type == "boolean") JSONArray(listOf("正确", "错误")) else item.optJSONArray("a") ?: JSONArray()
        if (type != "text" && type != "subjective") {
            require(options.length() in 2..8) { "需要 2–8 个选项" }
            for (i in 0 until options.length()) require(options.getString(i).isNotBlank()) { "选项不能为空" }
        }
        val indices = if (type == "multiple") item.optJSONArray("correctIndices") ?: JSONArray()
            else JSONArray().put(item.optInt("k", -1))
        if (type != "text" && type != "subjective") {
            require(indices.length() > 0) { "请选择正确答案" }
            for (i in 0 until indices.length()) require(indices.getInt(i) in 0 until options.length()) { "答案序号超出选项范围" }
        } else if (type == "text") {
            val answers = item.optJSONArray("answers") ?: JSONArray()
            require(answers.length() > 0) { "填空题需要至少一个可接受的答案" }
            for (i in 0 until answers.length()) require(answers.getString(i).isNotBlank()) { "答案不能为空" }
        }
        val difficulty = item.optInt("difficulty", 2)
        require(difficulty in 1..5) { "难度需要为 1–5 星" }
        val id = item.optString("id").ifEmpty { "q_" + UUID.randomUUID().toString() }
        val details = JSONObject(item.toString()).put("type", type)
        writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
            put("id", id); put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "")); put("difficulty", difficulty)
            put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
            put("tip", item.optString("tip")); put("enabled", 1); put("details", details.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun importQuestions(items: JSONArray): Int {
        require(items.length() in 1..5000) { "每次导入 1–5000 道题" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) upsertQuestion(items.getJSONObject(i))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return items.length()
    }

    fun deleteQuestion(id: String): Boolean = writableDatabase.delete("questions", "id=?", arrayOf(id)) > 0

    fun logEvent(type: String, payload: JSONObject) {
        writableDatabase.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf(type, payload.toString(), System.currentTimeMillis()))
    }

    fun recentEvents(limit: Int = 50): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT type,payload,ts FROM events WHERE type!='usage' ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("type", c.getString(0)).put("payload", JSONObject(c.getString(1))).put("ts", c.getLong(2)))
        }
        return out
    }

    fun stats(start: Long, end: Long): JSONObject {
        var usedMs = 0L
        var answered = 0
        var correct = 0
        var rounds = 0
        readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<?", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) {
                val p = try { JSONObject(c.getString(1)) } catch (e: Exception) { JSONObject() }
                when (c.getString(0)) {
                    "usage" -> usedMs += p.optLong("milliseconds")
                    "answer" -> { answered++; if (p.optBoolean("correct")) correct++ }
                    "quiz" -> if (!p.optBoolean("native")) { answered += p.optInt("total"); correct += p.optInt("correct") }
                    "quiz_pass" -> rounds++
                }
            }
        }
        return JSONObject().put("usedMinutes", usedMs / 60000.0).put("watch", usedMs / 60000.0)
            .put("answered", answered).put("correct", correct).put("rounds", rounds)
            .put("accuracy", if (answered > 0) correct * 100 / answered else 0)
    }

    fun todayStats(): JSONObject = stats(startOfToday(), Long.MAX_VALUE)
    fun roundsDoneToday(): Int = todayStats().optInt("rounds")

    fun historyDays(days: Int = 7): JSONArray {
        val out = JSONArray()
        for (d in 0 until days.coerceIn(1, 90)) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = startOfToday()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            val start = cal.timeInMillis
            val label = if (d == 0) "今天" else if (d == 1) "昨天" else "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            cal.add(Calendar.DAY_OF_YEAR, 1)
            out.put(stats(start, cal.timeInMillis).put("label", label))
        }
        return out
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
