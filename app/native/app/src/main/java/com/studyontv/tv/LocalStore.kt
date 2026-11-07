package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class LocalStore(context: Context) : SQLiteOpenHelper(context, "studyontv.db", null, 6) {
    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
    }

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
        if (oldVersion < 6) compactUsage(db)
    }

    /**
     * 旧版本每秒写一条 usage 事件，一天可达上万行，导致 /api/state、成长记录越来越慢（Issue #2：后台加载卡）。
     * 升级时按小时合并成一条。
     */
    private fun compactUsage(db: SQLiteDatabase) {
        val buckets = LinkedHashMap<Long, Long>()
        db.rawQuery("SELECT payload,ts FROM events WHERE type='usage'", null).use { c ->
            while (c.moveToNext()) {
                val ms = try { JSONObject(c.getString(0)).optLong("milliseconds") } catch (_: Exception) { 0L }
                val hour = c.getLong(1) / 3600000 * 3600000
                buckets[hour] = (buckets[hour] ?: 0L) + ms
            }
        }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events WHERE type='usage'")
            buckets.forEach { (hour, ms) ->
                if (ms > 0) db.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf("usage", JSONObject().put("milliseconds", ms).toString(), hour))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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

    /** 记录一次作答，返回这道题目前“连续答对（质量≥3）”的次数。 */
    fun recordProgress(id: String, quality: Int): Int {
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
        return repetitions
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
        val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true).put("spacedRepetitionEnabled", false)
            .put("playMode", "round").put("extraEnabled", true)
            .put("wrongThreshold", 3).put("extraQuestions", 2)
            .put("difficultyRewardEnabled", false)
            .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
            .put("praisePhrases", JSONArray(DEFAULT_PRAISE)).put("encouragePhrases", JSONArray(DEFAULT_ENCOURAGE))
            .put("autoNextSeconds", 0).put("scheduleMode", "allow").put("blockSettingsDuringControl", true).put("theme", if (BuildConfig.FLAVOR == "tv") "dark" else "system")
            .put("holidayMode", "sunday")
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
            .put("version", 1)
        val saved = try { JSONObject(get("config", "{}")) } catch (e: Exception) { JSONObject() }
        saved.keys().forEach { defaults.put(it, saved.get(it)) }
        // 旧版的四种配色主题合并为深色 / 浅色
        defaults.put("theme", when (defaults.optString("theme")) { "light", "daylight" -> "light"; "system" -> "system"; "dark", "midnight", "nord", "amber" -> "dark"; else -> if (BuildConfig.FLAVOR == "tv") "dark" else "system" })
        val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        val validStart = timePattern.matches(defaults.optString("windowStart"))
        val validEnd = timePattern.matches(defaults.optString("windowEnd"))
        if (!validStart) defaults.put("windowStart", "18:30")
        if (!validEnd) defaults.put("windowEnd", "20:00")
        if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
        // 旧版的单个时段自动迁移成一周七天；新版按 30 分钟格子保存
        val migrated = normalizeWeek(saved.optJSONArray("weekSlots"))
            ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
                val oldDefault = !defaults.optBoolean("allDay") &&
                    defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
                if (oldDefault) { defaults.put("allDay", true); fullWeek() }
                else weekFromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
            } else fullWeek()
        defaults.put("weekSlots", migrated)
        if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) defaults.put("holidayMode", "sunday")
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
        return defaults
    }

    private fun weekFromWindow(allDay: Boolean, start: String, end: String): JSONArray {
        fun minutes(value: String): Int? = try { value.split(":").let { it[0].toInt() * 60 + it[1].toInt() } } catch (_: Exception) { null }
        val from = minutes(start) ?: 18 * 60 + 30
        val to = minutes(end) ?: 20 * 60
        val chars = CharArray(48) { index ->
            val minute = index * 30
            val on = allDay || if (from < to) minute in from until to else minute >= from || minute < to
            if (on) '1' else '0'
        }
        val day = String(chars)
        return JSONArray().apply { repeat(7) { put(day) } }
    }

    private fun fullWeek(): JSONArray = JSONArray().apply { repeat(7) { put("1".repeat(48)) } }

    /** 旧版默认周末时段，仅用于迁移判断 */
    private fun weekendDefault(): JSONArray {
        val empty = "0".repeat(48)
        val weekend = weekFromWindow(false, "09:00", "20:00").optString(0)
        return JSONArray().apply { repeat(5) { put(empty) }; put(weekend); put(weekend) }
    }

    private fun normalizeWeek(raw: JSONArray?): JSONArray? {
        if (raw == null || raw.length() != 7) return null
        val out = JSONArray()
        for (i in 0 until 7) {
            val day = raw.optString(i)
            if (day.length != 48 || day.any { it != '0' && it != '1' }) return null
            out.put(day)
        }
        return out
    }

    fun saveConfig(input: JSONObject): JSONObject {
        val next = config()
        val ranges = mapOf("dailyMinutes" to 1..480, "dailyFreeMinutes" to 0..120, "sessionMinutes" to 1..120,
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
        listOf("extraEnabled", "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl").forEach {
            if (input.has(it)) next.put(it, input.getBoolean(it))
        }
        for (key in listOf("praisePhrases", "encouragePhrases")) if (input.has(key)) {
            val raw = input.getJSONArray(key)
            val clean = JSONArray()
            for (i in 0 until raw.length()) raw.optString(i).trim().takeIf { it.isNotEmpty() }?.let {
                require(it.length <= 40) { "每句鼓励语不超过 40 个字" }
                clean.put(it)
            }
            require(clean.length() <= 30) { "鼓励语最多 30 句" }
            next.put(key, if (clean.length() == 0) JSONArray(if (key == "praisePhrases") DEFAULT_PRAISE else DEFAULT_ENCOURAGE) else clean)
        }
        if (input.has("scheduleMode")) {
            require(input.getString("scheduleMode") in listOf("allow", "control")) { "未知的时段用途" }
            next.put("scheduleMode", input.getString("scheduleMode"))
        }
        for (key in listOf("quizSubjects", "quizLevels")) if (input.has(key)) {
            val raw = input.optJSONArray(key) ?: JSONArray()
            val clean = JSONArray()
            for (i in 0 until minOf(raw.length(), 50)) raw.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= 32 }?.let { value ->
                if (!(0 until clean.length()).any { clean.optString(it) == value }) clean.put(value)
            }
            next.put(key, clean)
        }
        if (input.has("weekSlots")) {
            val clean = normalizeWeek(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("可用时段格式不正确")
            next.put("weekSlots", clean)
        }
        if (input.has("holidayMode")) {
            require(input.getString("holidayMode") in listOf("sunday", "normal")) { "未知的节假日规则" }
            next.put("holidayMode", input.getString("holidayMode"))
        }
        if (input.has("theme")) {
            require(input.getString("theme") in listOf("dark", "light", "system")) { "外观需要为深色、浅色或跟随系统" }
            next.put("theme", input.getString("theme"))
        }
        if (input.has("autoNextSeconds")) {
            val value = input.getInt("autoNextSeconds")
            require(value in 0..30) { "自动下一题需要为 0–30 秒，0 表示手动" }
            next.put("autoNextSeconds", value)
        }
        if (input.has("difficultyRewards")) {
            val rewards = input.getJSONObject("difficultyRewards")
            for (star in 1..5) {
                val minutes = rewards.getDouble(star.toString())
                require(minutes.isFinite() && minutes in 0.1..120.0) { "每题奖励需要为 0.1–120 分钟" }
            }
            next.put("difficultyRewards", rewards)
        }
        next.put("version", next.optInt("version", 1) + 1)
        set("config", next.toString())
        return next
    }

    fun questions(subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
                   enabledOnly: Boolean = true, sortBy: String = "random"): JSONArray {
        val where = StringBuilder(if (enabledOnly) "enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND level=?"); args.add(level) }
        val out = JSONArray()
        val source = if (spaced) "questions LEFT JOIN question_progress progress ON questions.id=progress.qid" else "questions"
        val order = if (spaced) "CASE WHEN progress.due_at<=${System.currentTimeMillis()} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
            else when (sortBy) { "newest" -> "questions.created_at DESC, questions.rowid DESC"; "oldest" -> "questions.created_at ASC, questions.rowid ASC"; else -> "RANDOM()" }
        readableDatabase.rawQuery("SELECT questions.* FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 5000)}", args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.put(rowToItem(c))
        }
        return out
    }

    private fun rowToItem(c: android.database.Cursor): JSONObject {
        val details = c.getString(c.getColumnIndexOrThrow("details"))
        val item = if (details.isNullOrEmpty()) JSONObject() else JSONObject(details)
        item.put("id", c.getString(c.getColumnIndexOrThrow("id")))
            .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
            .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
            .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
            .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
            .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
            .put("subject", c.getString(c.getColumnIndexOrThrow("subject")) ?: "")
            .put("level", c.getString(c.getColumnIndexOrThrow("level")) ?: "")
        if (!item.has("type")) item.put("type", "single")
        return item
    }

    /**
     * 抽下一道题：尽量不重复。
     * - 没做过的题优先；都做过了就挑“最久没做”的（间隔重复模式则按到期顺序）
     * - skip 里的题（本轮已出过的、上一题）先排除；实在没得选才放宽
     * - minStar>1 时先只从这个星级以上的题里挑，挑不出来就放宽星级，不会因此没题
     * - 题库被“自动淘汰”做空时，会把淘汰的题放回来，保证总有题可做
     */
    fun pickQuestion(skip: Set<String>, last: String?, spaced: Boolean, minStar: Int,
                     subjects: List<String> = emptyList(), levels: List<String> = emptyList()): JSONObject? {
        fun query(skipIds: Collection<String>, star: Int): JSONObject? {
            val where = StringBuilder("questions.enabled=1")
            val args = ArrayList<String>()
            if (star > 1) { where.append(" AND questions.difficulty>=?"); args.add(star.toString()) }
            // 答题范围（上游功能）：家长限定科目 / 年级后只从范围内抽题
            if (subjects.isNotEmpty()) { where.append(" AND questions.subject IN (").append(subjects.joinToString(",") { "?" }).append(")"); args.addAll(subjects) }
            if (levels.isNotEmpty()) { where.append(" AND questions.level IN (").append(levels.joinToString(",") { "?" }).append(")"); args.addAll(levels) }
            if (skipIds.isNotEmpty()) {
                where.append(" AND questions.id NOT IN (").append(skipIds.joinToString(",") { "?" }).append(")")
                args.addAll(skipIds)
            }
            val order = if (spaced) "CASE WHEN p.due_at<=${System.currentTimeMillis()} THEN 0 WHEN p.qid IS NULL THEN 1 ELSE 2 END, p.due_at ASC, RANDOM()"
                else "CASE WHEN p.qid IS NULL THEN 0 ELSE 1 END, COALESCE(p.updated_at,0), RANDOM()"
            val list = ArrayList<JSONObject>()
            readableDatabase.rawQuery(
                "SELECT questions.* FROM questions LEFT JOIN question_progress p ON questions.id=p.qid WHERE $where ORDER BY $order LIMIT 12",
                args.toTypedArray()
            ).use { c -> while (c.moveToNext()) list.add(rowToItem(c)) }
            if (list.isEmpty()) return null
            return if (spaced) list[0] else list[java.util.Random().nextInt(list.size)]
        }
        val lastOnly = if (last.isNullOrEmpty()) emptyList() else listOf(last)
        val star = minStar.coerceIn(1, 5)
        query(skip, star)?.let { return it }
        if (star > 1) query(skip, 1)?.let { return it }
        query(lastOnly, 1)?.let { return it }
        if (restoreRetired() > 0) return query(lastOnly, 1)
        return query(emptyList(), 1)
    }

    // ---- 自动淘汰：enabled=2 表示“答对多次，已撤下”，题目记录还在，AI 出题去重仍会参考它 ----
    fun retireQuestion(id: String, keepAtLeast: Int): Boolean {
        val stmt = writableDatabase.compileStatement(
            "UPDATE questions SET enabled=2 WHERE id=? AND enabled=1 AND " +
                "(SELECT COUNT(*) FROM questions q2 WHERE q2.enabled=1 AND q2.subject=questions.subject) > ?"
        )
        try {
            stmt.bindString(1, id)
            stmt.bindLong(2, keepAtLeast.toLong())
            return stmt.executeUpdateDelete() > 0
        } finally { stmt.close() }
    }

    fun countRetired(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=2", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** 把淘汰的题全部放回题库，并清掉它们的答题进度，重新当作新题。 */
    fun restoreRetired(): Int {
        val db = writableDatabase
        db.execSQL("DELETE FROM question_progress WHERE qid IN (SELECT id FROM questions WHERE enabled=2)")
        val stmt = db.compileStatement("UPDATE questions SET enabled=1 WHERE enabled=2")
        try { return stmt.executeUpdateDelete() } finally { stmt.close() }
    }

    /** 包含已淘汰的题；用来判断是否需要导入内置题库（淘汰完不能再重新导入一遍）。 */
    fun countAllQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
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

    // ---- AI 出题用的查询 ----
    fun unseenCount(subject: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM questions LEFT JOIN question_progress p ON questions.id=p.qid WHERE questions.enabled=1 AND questions.subject=? AND p.qid IS NULL", arrayOf(subject)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countSubject(subject: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM questions WHERE enabled=1 AND subject=?", arrayOf(subject)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun stemsOf(subject: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        // 最新的在前（包含已淘汰的题），AI 出题据此避开重复
        readableDatabase.rawQuery("SELECT stem FROM questions WHERE subject=? ORDER BY rowid DESC LIMIT ?", arrayOf(subject, limit.toString())).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }


    fun deleteQuestions(ids: List<String>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            ids.forEach { count += db.delete("questions", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun deleteQuestion(id: String): Boolean = writableDatabase.delete("questions", "id=?", arrayOf(id)) > 0

    fun setEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try {
            ids.forEach { id ->
                count += db.update("questions", android.content.ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

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
    // status() 每秒都会调用，这里直接计数，不再解析今天的全部事件
    fun roundsDoneToday(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=?", arrayOf(startOfToday().toString())).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

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
