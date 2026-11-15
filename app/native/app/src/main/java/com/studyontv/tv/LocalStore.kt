package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class LocalStore(context: Context, databaseName: String = "studyontv.db") : SQLiteOpenHelper(context, databaseName, null, 11) {
    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
        /** 第一个孩子沿用旧版本的存储键（不带前缀），升级时不需要搬运任何数据 */
        const val DEFAULT_CHILD = ""
        /** 设备级共享的设置：不随当前孩子切换 */
        private val SHARED_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "bank_automation", "bank_automation_status",
            "children", "active_child", "parent_access", "onboarded"
        )
        const val MAX_CHILDREN = 8
    }

    override fun onCreate(db: SQLiteDatabase) {
        // bank / type 原来只存在 details 这个 JSON 字段里，题库页筛选、覆盖率统计都得全表扫描再逐行解析。
        // 第 9 版起同时写成独立列，筛选和排序能走索引，details 仍是完整题目（导出格式不变）。
        db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT, bank TEXT NOT NULL DEFAULT '默认题库', type TEXT NOT NULL DEFAULT 'single')")
        createQuestionIndexes(db)
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER, child TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        createReviews(db)
        createProgress(db)
        createPoints(db)
        createQuestionStats(db)
        createChildren(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE questions ADD COLUMN details TEXT")
        if (oldVersion < 3) createReviews(db)
        if (oldVersion < 4) createProgress(db)
        if (oldVersion < 5) createPoints(db)
        if (oldVersion < 6) compactUsage(db)
        if (oldVersion < 7) createQuestionStats(db)
        // 自动淘汰改用上游的“掌握后暂停”，之前被我们淘汰（enabled=2）的题放回题库
        if (oldVersion < 8) db.execSQL("UPDATE questions SET enabled=1 WHERE enabled=2")
        if (oldVersion < 9) {
            // 多孩子（#132）：历史 / 使用时长 / 艾宾浩斯进度都按孩子隔离，旧的记录归第一个孩子
            createChildren(db)
            addColumn(db, "events", "child", "TEXT NOT NULL DEFAULT ''")
            migrateProgressForChildren(db)
        }
        // 错题库（#136，上游在 v8 加的列；我们这边 v8/v9 已被占用，改在 v10 补，addColumn 已存在则跳过）
        if (oldVersion < 10) addColumn(db, "question_stats", "last_correct", "INTEGER NOT NULL DEFAULT -1")
        // 上游 v9：题库 / 题型提成独立列（我们这边顺延到 v11）
        if (oldVersion < 11) {
            // 题库 / 题型提成独立列并回填。回填用 Kotlin 解析 details，不依赖 JSON_EXTRACT
            // （Android 5–11 自带的 SQLite 没有这个函数）。
            addColumn(db, "questions", "bank", "TEXT NOT NULL DEFAULT '默认题库'")
            addColumn(db, "questions", "type", "TEXT NOT NULL DEFAULT 'single'")
            backfillQuestionColumns(db)
            createQuestionIndexes(db)
        }
    }

    private fun createQuestionIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_bank ON questions(bank)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_type ON questions(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_enabled ON questions(enabled)")
    }

    /** 把老库里 details 的 bank / type 抄进新列；分批读，避免几万道题一次性塞进内存。 */
    private fun backfillQuestionColumns(db: SQLiteDatabase) {
        val statement = db.compileStatement("UPDATE questions SET bank=?,type=? WHERE id=?")
        try {
            var last = -1L
            while (true) {
                val rows = ArrayList<Triple<String, String, String>>()
                db.rawQuery("SELECT rowid,id,details FROM questions WHERE rowid>? ORDER BY rowid LIMIT 500", arrayOf(last.toString())).use { c ->
                    while (c.moveToNext()) {
                        last = c.getLong(0)
                        val details = try { JSONObject(c.getString(2)?.takeIf { it.isNotBlank() } ?: "{}") } catch (_: Exception) { JSONObject() }
                        rows.add(Triple(c.getString(1), details.optString("bank").ifBlank { "默认题库" }, details.optString("type").ifBlank { "single" }))
                    }
                }
                if (rows.isEmpty()) break
                rows.forEach { (id, bank, type) ->
                    statement.clearBindings()
                    statement.bindString(1, bank)
                    statement.bindString(2, type)
                    statement.bindString(3, id)
                    statement.executeUpdateDelete()
                }
            }
        } finally {
            statement.close()
        }
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if (column !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private fun createChildren(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }

    /** 旧的 question_progress 以 qid 为主键，多孩子后必须变成 (qid, child) 复合主键 */
    private fun migrateProgressForChildren(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(question_progress)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if ("child" in columns) return
        db.execSQL("ALTER TABLE question_progress RENAME TO question_progress_legacy")
        createProgress(db)
        db.execSQL("INSERT INTO question_progress(qid,child,repetitions,interval_days,ease,due_at,updated_at) " +
            "SELECT qid,'',repetitions,interval_days,ease,due_at,updated_at FROM question_progress_legacy")
        db.execSQL("DROP TABLE question_progress_legacy")
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

    private fun createQuestionStats(db: SQLiteDatabase) {
        // IF NOT EXISTS：升级过程被打断 / 重复升级时不能整库异常，否则 /api/state、题库都读不出来
        // last_correct：最近一次作答是否正确，-1 = 还没作答过（错题库用它判断是不是错题）
        db.execSQL("CREATE TABLE IF NOT EXISTS question_stats(qid TEXT PRIMARY KEY, presented INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, last_at INTEGER NOT NULL DEFAULT 0, last_correct INTEGER NOT NULL DEFAULT -1)")
    }

    fun presented(id: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid) VALUES(?)", arrayOf(id))
        writableDatabase.execSQL("UPDATE question_stats SET presented=presented+1,last_at=? WHERE qid=?", arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun answered(id: String, correct: Boolean) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid) VALUES(?)", arrayOf(id))
        writableDatabase.execSQL("UPDATE question_stats SET answered=answered+1,correct=correct+?,last_correct=?,last_at=? WHERE qid=?",
            arrayOf<Any>(if (correct) 1 else 0, if (correct) 1 else 0, System.currentTimeMillis(), id))
    }

    /** 题库里的题目 id：删除题库前先拿到，用来判断孩子手上那一轮是不是被删掉了。 */
    fun idsInBank(bank: String): List<String> {
        require(bank.isNotBlank()) { "请选择题库" }
        val ids = mutableListOf<String>()
        // bank 是第 9 版加的独立列（有索引），不用再全表解析 details
        readableDatabase.rawQuery("SELECT id FROM questions WHERE bank=?", arrayOf(bank)).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    fun deleteBank(bank: String): Int = deleteQuestions(idsInBank(bank))

    /** 出题 / 结算时校验题目是否还在：家长可能刚在管理页删掉这一题。 */
    fun questionExists(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM questions WHERE id=? LIMIT 1", arrayOf(id)).use {
        it.moveToFirst()
    }

    private fun createProgress(db: SQLiteDatabase) {
        // 每个孩子各自一套复习进度（#132）；旧库在 migrateProgressForChildren 里搬过来，child='' 是第一个孩子
        db.execSQL("CREATE TABLE IF NOT EXISTS question_progress(qid TEXT NOT NULL, child TEXT NOT NULL DEFAULT '', repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(qid, child))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_question_due ON question_progress(due_at)")
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
        val child = activeChild()
        readableDatabase.rawQuery("SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=? AND child=?", arrayOf(id, child)).use {
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
            put("qid", id); put("child", child); put("repetitions", repetitions); put("interval_days", interval); put("ease", ease)
            put("due_at", next); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return repetitions
    }

    fun repetitionStats(): JSONObject = readableDatabase.rawQuery(
        "SELECT COUNT(*),SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END) FROM question_progress JOIN questions ON questions.id=question_progress.qid WHERE questions.enabled=1 AND question_progress.child=?",
        arrayOf(System.currentTimeMillis().toString(), activeChild())
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

    /** 当前孩子：默认孩子用空串，沿用旧版本的存储键，升级时不用搬运数据 */
    @Volatile private var cachedChild: String? = null

    fun activeChild(): String {
        cachedChild?.let { return it }
        val stored = rawGet("active_child")
        val valid = when {
            stored.isEmpty() -> DEFAULT_CHILD
            readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(stored)).use { it.moveToFirst() } -> stored
            else -> { rawSet("active_child", DEFAULT_CHILD); DEFAULT_CHILD }   // 孩子档案被删掉了
        }
        cachedChild = valid
        return valid
    }

    /** 学习相关的键按孩子隔离；朗读 / 批改 / 远程题库这类设备设置仍共用一份 */
    private fun scoped(key: String): String {
        if (key in SHARED_KEYS) return key
        val child = activeChild()
        return if (child == DEFAULT_CHILD) key else "child:$child:$key"
    }

    private fun rawGet(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    private fun rawSet(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(key: String, default: String = ""): String = rawGet(scoped(key), default)

    fun set(key: String, value: String) = rawSet(scoped(key), value)

    // ---- 多孩子档案（#132） ----

    fun children(): JSONArray {
        ensureDefaultChild()
        val active = activeChild()
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id,name,created_at FROM children ORDER BY created_at,id", null).use {
            while (it.moveToNext()) out.put(JSONObject().put("id", it.getString(0)).put("name", it.getString(1))
                .put("createdAt", it.getLong(2)).put("active", it.getString(0) == active))
        }
        return out
    }

    private fun ensureDefaultChild() {
        val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(DEFAULT_CHILD)).use { it.moveToFirst() }
        if (!exists) writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
            put("id", DEFAULT_CHILD); put("name", "孩子1"); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun validName(raw: String, except: String? = null): String {
        val name = raw.trim()
        require(name.length in 1..12) { "孩子名字需要 1–12 个字" }
        val taken = readableDatabase.rawQuery("SELECT id FROM children WHERE name=?", arrayOf(name)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(taken == null || taken == except) { "已经有同名的孩子" }
        return name
    }

    fun createChild(raw: String): JSONObject {
        ensureDefaultChild()
        val name = validName(raw)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM children", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_CHILDREN) { "最多只能添加 $MAX_CHILDREN 个孩子" }
        val id = "c" + UUID.randomUUID().toString().replace("-", "").take(8)
        // 新孩子沿用当前的规则（时长、时段、奖励…），不需要家长再配一遍；学习进度从零开始
        val inherited = rawGet(scoped("config"))
        writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
            put("id", id); put("name", name); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (inherited.isNotEmpty()) rawSet("child:$id:config", inherited)
        return JSONObject().put("id", id).put("name", name)
    }

    fun renameChild(id: String, raw: String): JSONObject {
        val name = validName(raw, id)
        val updated = writableDatabase.update("children", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id))
        require(updated > 0) { "孩子档案不存在" }
        return JSONObject().put("id", id).put("name", name)
    }

    /** 删除孩子档案：连同他的学习记录、使用时长和复习进度一起清掉，题目本身保留 */
    fun deleteChild(id: String): Boolean {
        require(id != DEFAULT_CHILD) { "第一个孩子不能删除，可以改名" }
        writableDatabase.delete("children", "id=?", arrayOf(id))
        writableDatabase.delete("kv", "k LIKE ?", arrayOf("child:$id:%"))
        writableDatabase.delete("events", "child=?", arrayOf(id))
        writableDatabase.delete("question_progress", "child=?", arrayOf(id))
        if (activeChild() == id) switchChild(DEFAULT_CHILD)
        return true
    }

    fun switchChild(id: String) {
        ensureDefaultChild()
        val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
        require(exists) { "孩子档案不存在" }
        rawSet("active_child", id)
        cachedChild = null
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    fun config(): JSONObject {
        val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true).put("spacedRepetitionEnabled", false)
            // 覆盖优先：没答对过的题先出，保证题库里的题都会被走到一次（家长面板可关）
            .put("coverageFirst", true)
            // 新题优先：一次都没出过的题排在前面（#230，默认关）
            .put("newFirst", false)
            .put("playMode", "round").put("extraEnabled", true).put("shuffleOptions", false).put("showCountdown", true)
            .put("wrongThreshold", 3).put("extraQuestions", 2)
            .put("difficultyRewardEnabled", false)
            .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
            .put("praisePhrases", JSONArray(DEFAULT_PRAISE)).put("encouragePhrases", JSONArray(DEFAULT_ENCOURAGE))
            .put("autoNextSeconds", 0).put("scheduleMode", "allow").put("blockSettingsDuringControl", true).put("theme", if (BuildConfig.FLAVOR == "tv") "dark" else "system")
            .put("holidayMode", "sunday")
            .put("bootFreeMinutes", 0).put("dailyLimitEnabled", true).put("roundsLimitEnabled", true)
            .put("restEnabled", false).put("restWatchMinutes", 40).put("restMinutes", 10)
            .put("wrongReviewSeconds", 0).put("fontScale", 100).put("childSelectLevel", false)
            .put("trafficAssistEnabled", false)
            // #122：部分固件（海信 VIDAA）会把普通应用的全屏悬浮窗压在硬件视频层下面，
            // 家长可强制改走全屏学习页；默认仍由 GateService 按可见性自动判断。
            .put("forceFullscreenGate", false)
            // 不计时应用白名单（屏保 / 网课 App）与答题页密码解锁：默认关闭，由家长在面板里打开
            .put("watchWhitelist", JSONArray()).put("allowQuizUnlock", false)
            .put("learningContent", "quiz").put("educationalVideos", JSONArray()).put("videoMinutes", 1)
            .put("quizBanks", JSONArray())
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
            // 网课时段 / 大人豁免（Issue #112）：旧配置没有这两个键，默认空数组 = 完全沿用旧行为
            .put("timePoints", JSONArray()).put("exemptWindows", JSONArray())
            // 使用时区：留空 = 跟随设备。国外用户 / 设备时区不对时由家长指定
            .put("timezone", "")
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

    /**
     * 网课时段 / 大人豁免的校验（Issue #112）。时间精确到分钟，end < start 表示跨午夜。
     * days 用真实星期几：0 = 周一 … 6 = 周日，不受 holidayMode 影响。
     */
    private fun normalizeWindows(raw: JSONArray?, counted: Boolean, label: String): JSONArray {
        val out = JSONArray()
        val source = raw ?: return out
        require(source.length() <= 50) { "${label}最多 50 条" }
        val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: throw IllegalArgumentException("${label}格式不正确")
            val name = item.optString("name").trim()
            require(name.isNotEmpty() && name.length <= 20) { "${label}名称需要 1–20 个字" }
            val start = item.optString("start")
            val end = item.optString("end")
            require(pattern.matches(start) && pattern.matches(end)) { "${label}需要有效的开始和结束时间（精确到分钟）" }
            require(start != end) { "${label}的开始与结束时间不能相同" }
            val days = JSONArray()
            val seen = mutableSetOf<Int>()
            val rawDays = item.optJSONArray("days") ?: JSONArray()
            for (d in 0 until rawDays.length()) {
                val day = rawDays.optInt(d, -1)
                require(day in 0..6) { "${label}的星期设置不正确" }
                if (seen.add(day)) days.put(day)
            }
            require(days.length() > 0) { "请为${label}选择星期" }
            val clean = JSONObject()
                .put("id", item.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= 64 }
                    ?: ((if (counted) "tp_" else "ex_") + UUID.randomUUID()))
                .put("name", name).put("days", days).put("start", start).put("end", end)
            if (counted) {
                val mode = item.optString("mode", "free")
                require(mode in listOf("free", "counted")) { "${label}的用途需要为自由使用或计入限额" }
                clean.put("mode", mode)
                if (mode == "counted") {
                    val minutes = item.optInt("dailyMinutes", 0)
                    require(minutes in 1..480) { "计入限额的${label}需要 1–480 分钟" }
                    clean.put("dailyMinutes", minutes)
                }
            }
            out.put(clean)
        }
        return out
    }

    fun saveConfig(input: JSONObject): JSONObject {
        val next = config()
        val ranges = mapOf("dailyMinutes" to 1..480, "dailyFreeMinutes" to 0..120, "sessionMinutes" to 1..120,
            "questionsPerRound" to 1..100, "roundsPerDay" to 1..100,
            "wrongThreshold" to 1..100, "extraQuestions" to 1..100,
            "bootFreeMinutes" to 0..120, "restWatchMinutes" to 1..480, "restMinutes" to 1..120,
            "wrongReviewSeconds" to 0..300, "fontScale" to 75..200, "videoMinutes" to 1..30)
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
        if (input.has("timezone")) {
            // 空串 = 跟随设备时区；其余必须是白名单里的 IANA 名称
            val zone = input.optString("timezone").trim()
            require(zone in DeviceClock.ZONES) { "不支持的时区" }
            next.put("timezone", zone)
        }
        if (input.has("passRatio")) {
            val ratio = input.getDouble("passRatio")
            require(ratio.isFinite() && ratio > 0 && ratio <= 1) { "答对比例需要大于 0 且不超过 100%" }
            next.put("passRatio", ratio)
        }
        if (input.has("learningContent")) {
            require(input.getString("learningContent") in listOf("quiz", "video", "alternate")) { "未知学习内容模式" }
            next.put("learningContent", input.getString("learningContent"))
        }
        if (input.has("educationalVideos")) {
            val urls = input.getJSONArray("educationalVideos")
            require(urls.length() <= 50) { "最多 50 个公益视频" }
            for (i in 0 until urls.length()) {
                val url = urls.getString(i)
                require(url.length <= 2048 && (url.startsWith("https://") || url.startsWith("http://"))) { "请使用 HTTP/HTTPS 视频直链" }
            }
            next.put("educationalVideos", urls)
        }
        if (input.has("watchWhitelist")) {
            val raw = input.optJSONArray("watchWhitelist") ?: throw IllegalArgumentException("白名单格式不正确")
            val clean = JSONArray()
            WatchWhitelist.normalize((0 until raw.length()).map { raw.optString(it) }).forEach { clean.put(it) }
            next.put("watchWhitelist", clean)
        }
        require(next.optString("learningContent", "quiz") == "quiz" || next.getJSONArray("educationalVideos").length() > 0) { "视频学习模式需要添加视频地址" }
        if (input.has("playMode")) {
            require(input.getString("playMode") in listOf("round", "streak", "practice", "count")) { "未知玩法" }
            next.put("playMode", input.getString("playMode"))
        }
        listOf("extraEnabled", "coverageFirst", "newFirst","difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl", "shuffleOptions", "showCountdown", "dailyLimitEnabled", "roundsLimitEnabled", "restEnabled", "childSelectLevel", "trafficAssistEnabled", "forceFullscreenGate", "wrongFirst", "allowQuizUnlock").forEach {
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
        for (key in listOf("quizSubjects", "quizLevels", "quizBanks")) if (input.has(key)) {
            val raw = input.optJSONArray(key) ?: JSONArray()
            val clean = JSONArray()
            for (i in 0 until minOf(raw.length(), 50)) raw.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= (if (key.endsWith("Banks")) 64 else 32) }?.let { value ->
                if (!(0 until clean.length()).any { clean.optString(it) == value }) clean.put(value)
            }
            next.put(key, clean)
        }
        if (input.has("weekSlots")) {
            val clean = normalizeWeek(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("可用时段格式不正确")
            next.put("weekSlots", clean)
        }
        if (input.has("timePoints")) next.put("timePoints", normalizeWindows(input.optJSONArray("timePoints"), true, "网课时段"))
        if (input.has("exemptWindows")) next.put("exemptWindows", normalizeWindows(input.optJSONArray("exemptWindows"), false, "大人豁免时段"))
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
                   enabledOnly: Boolean = true, sortBy: String = "random", wrongFirst: Boolean = false,
                   coverageFirst: Boolean = false, newFirst: Boolean = false): JSONArray {
        val where = StringBuilder(if (enabledOnly) "enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND level=?"); args.add(level) }
        val out = JSONArray()
        val joinArgs = mutableListOf<String>()
        // 复习进度是每个孩子各自一份（#132），只连当前孩子那一行，否则同一个 qid 会连出多条
        val source = (if (spaced) "questions LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?".also { joinArgs.add(activeChild()) } else "questions") +
            " LEFT JOIN question_stats stats ON questions.id=stats.qid"
        val base = if (spaced) "CASE WHEN progress.due_at<=${System.currentTimeMillis()} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
            else when (sortBy) { "newest" -> "questions.created_at DESC, questions.rowid DESC"; "oldest" -> "questions.created_at ASC, questions.rowid ASC"; else -> "RANDOM()" }
        // 错题优先（#136）：最近一次答错的题排在最前面，答对一次就回到正常顺序
        val wrongTier = "CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END"
        // 覆盖优先：还是一次都没答对的题（correct=0）排在已答对过的题前面，同一档里出题次数少的更靠前，
        // 这样没碰过的题不会被反复出的熟题挤掉，整库都能走到一遍。剩下的顺序仍沿用 $base（随机 / 到期）。
        val coverageTier = "CASE WHEN COALESCE(stats.correct,0)=0 THEN 0 ELSE 1 END, COALESCE(stats.presented,0) ASC"
        // 新题优先（#230）：一次都没出过的题排在最前面，出过之后就回到正常顺序
        val newTier = "CASE WHEN COALESCE(stats.presented,0)=0 THEN 0 ELSE 1 END"
        val tiers = mutableListOf<String>()
        // 错题优先永远排第一档：刚答错的题必须马上回来重练，
        // 不能被「没出过的题」挡在后面（#136 的行为不能被覆盖优先 / 新题优先改掉）
        if (wrongFirst) tiers.add(wrongTier)
        if (newFirst) tiers.add(newTier)
        if (coverageFirst) tiers.add(coverageTier)
        val order = (tiers + base).joinToString(", ")
        return readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count,COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 50000)}",
            (joinArgs + args).toTypedArray()
        )
    }

    /** 题目行的统一映射：出题、题库页、导出共用，保证返回字段口径一致。 */
    private fun readQuestions(sql: String, args: Array<String>): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
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
                    // 必须回传 enabled：管理端靠它区分「启用 / 已暂停」，
                    // 否则暂停的题在页面上永远看不到，用户会以为题目丢了。
                    .put("enabled", c.getInt(c.getColumnIndexOrThrow("enabled")) != 0)
                    .put("stats", JSONObject().put("presented", c.getInt(c.getColumnIndexOrThrow("presented")))
                        .put("answered", c.getInt(c.getColumnIndexOrThrow("answered_count")))
                        .put("correct", c.getInt(c.getColumnIndexOrThrow("correct_count")))
                        // null = 还没作答过；false = 最近一次答错（错题库），true = 最近一次答对
                        .put("lastCorrect", c.getInt(c.getColumnIndexOrThrow("last_correct")).let { if (it < 0) JSONObject.NULL else it == 1 }))
                if (!item.has("type")) item.put("type", "single")
                out.put(item)
            }
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
                     subjects: List<String> = emptyList(), levels: List<String> = emptyList(),
                     banks: List<String> = emptyList(), wrongOnly: Boolean = false,
                     coverageFirst: Boolean = false, newFirst: Boolean = false): JSONObject? {
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
            val base = if (spaced) "CASE WHEN p.due_at<=${System.currentTimeMillis()} THEN 0 WHEN p.qid IS NULL THEN 1 ELSE 2 END, p.due_at ASC, RANDOM()"
                else "CASE WHEN p.qid IS NULL THEN 0 ELSE 1 END, COALESCE(p.updated_at,0), RANDOM()"
            // 上游的新题优先（#230）/ 覆盖优先：档位排在 base 前面，档内仍按 base（随机 / 到期）
            val tiers = ArrayList<String>()
            if (newFirst) tiers.add("CASE WHEN COALESCE(stats.presented,0)=0 THEN 0 ELSE 1 END")
            if (coverageFirst) tiers.add("CASE WHEN COALESCE(stats.correct,0)=0 THEN 0 ELSE 1 END, COALESCE(stats.presented,0) ASC")
            val order = (tiers + base).joinToString(", ")
            // 错题优先（#136，上游功能）：只从最近一次答错的题里抽
            if (wrongOnly) where.append(" AND questions.id IN (SELECT qid FROM question_stats WHERE last_correct=0)")
            val list = ArrayList<JSONObject>()
            readableDatabase.rawQuery(
                // 艾宾浩斯进度是每个孩子各自一份（#132），只连当前孩子那一行
                "SELECT questions.* FROM questions LEFT JOIN question_progress p ON questions.id=p.qid AND p.child=? LEFT JOIN question_stats stats ON questions.id=stats.qid WHERE $where ORDER BY $order LIMIT ${if (banks.isEmpty()) 12 else 3000}",
                (listOf(activeChild()) + args).toTypedArray()
            ).use { c ->
                while (c.moveToNext() && list.size < 12) {
                    val item = rowToItem(c)
                    // 题库范围（上游功能）：题库名存在 details 里，只能取出来再过滤
                    if (banks.isNotEmpty() && item.optString("bank", "默认题库") !in banks) continue
                    list.add(item)
                }
            }
            if (list.isEmpty()) return null
            // 有档位排序时必须取第一个，否则档位白排；没有时沿用“前 12 个里随机”
            return if (spaced || tiers.isNotEmpty()) list[0] else list[java.util.Random().nextInt(list.size)]
        }
        val lastOnly = if (last.isNullOrEmpty()) emptyList() else listOf(last)
        val star = minStar.coerceIn(1, 5)
        query(skip, star)?.let { return it }
        if (star > 1) query(skip, 1)?.let { return it }
        query(lastOnly, 1)?.let { return it }
        return query(emptyList(), 1)
    }

    /** 包含已淘汰的题；用来判断是否需要导入内置题库（淘汰完不能再重新导入一遍）。 */
    fun countAllQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** LIKE 里的通配符要转义，否则家长搜索「100%」会当成通配符扫全库。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 家长面板题库页用的分页查询：筛选、搜索、排序、分页全部在 SQL 里做，一次只取一页。
     * 返回 { items, total, offset, limit }，total 是当前筛选条件下的总题数。
     */
    fun questionPage(
        subject: String? = null, level: String? = null, bank: String? = null, search: String? = null,
        type: String? = null, difficulty: Int? = null, wrongOnly: Boolean = false, enabledOnly: Boolean = true,
        sortBy: String = "newest", limit: Int = 50, offset: Int = 0
    ): JSONObject {
        val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
        if (!bank.isNullOrEmpty()) { where.append(" AND questions.bank=?"); args.add(bank) }
        if (!type.isNullOrEmpty()) { where.append(" AND questions.type=?"); args.add(type) }
        if (difficulty != null) { where.append(" AND questions.difficulty=?"); args.add(difficulty.toString()) }
        // 错题库（#136）：最近一次答错；从没作答过的题 last_correct=-1，不算错题
        if (wrongOnly) where.append(" AND stats.last_correct=0")
        val keyword = search?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            val like = "%${escapeLike(keyword)}%"
            // 答案文本存在 options JSON 里，LIKE 一样能命中，和原来前端搜「题干+答案+解析」的口径接近
            where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
            repeat(5) { args.add(like) }
        }
        val source = "questions LEFT JOIN question_stats stats ON questions.id=stats.qid"
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM $source WHERE $where", args.toTypedArray()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val order = when (sortBy) {
            "random" -> "RANDOM()"
            "oldest" -> "questions.created_at ASC, questions.rowid ASC"
            "diff", "difficulty" -> "questions.difficulty DESC, questions.rowid DESC"
            "type" -> "questions.type ASC, questions.difficulty DESC, questions.rowid DESC"
            else -> "questions.created_at DESC, questions.rowid DESC"
        }
        val page = limit.coerceIn(1, 50000)
        val start = offset.coerceAtLeast(0)
        val items = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count,COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT $page OFFSET $start",
            args.toTypedArray()
        )
        return JSONObject().put("items", items).put("total", total).put("offset", start).put("limit", page)
    }

    /** 题库页的筛选项和计数：题库名 / 科目 / 年级 + 总题数、启用、暂停、错题数，不返回题目内容。 */
    fun questionMeta(): JSONObject {
        val banks = sortedSetOf<String>()
        val subjects = sortedSetOf<String>()
        val levels = sortedSetOf<String>()
        var total = 0
        var enabled = 0
        var wrong = 0
        readableDatabase.rawQuery(
            "SELECT questions.bank,questions.subject,questions.level,questions.enabled," +
                "CASE WHEN stats.last_correct=0 THEN 1 ELSE 0 END AS wrong " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid", null
        ).use { c ->
            while (c.moveToNext()) {
                total++
                (c.getString(0) ?: "").ifBlank { "默认题库" }.let { banks.add(it) }
                (c.getString(1) ?: "").takeIf { it.isNotBlank() }?.let { subjects.add(it) }
                (c.getString(2) ?: "").takeIf { it.isNotBlank() }?.let { levels.add(it) }
                if (c.getInt(3) != 0) enabled++
                if (c.getInt(4) == 1) wrong++
            }
        }
        return JSONObject()
            .put("banks", JSONArray(banks.toList()))
            .put("subjects", JSONArray(subjects.toList()))
            .put("levels", JSONArray(levels.toList()))
            .put("total", total).put("enabled", enabled).put("paused", total - enabled).put("wrong", wrong)
    }

    fun countQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /**
     * 答题覆盖：口径是「答对过一次」（stats.correct>0），和出题时的覆盖优先用同一套判断。
     * 返回总量、覆盖率、按题库的分布，以及还没答对的题目样本（出题少的在前），供家长面板展示。
     * question_stats 目前是整机一份（不按孩子分表），所以这里是全设备口径。
     */
    fun coverage(sample: Int = 20): JSONObject {
        val banks = LinkedHashMap<String, IntArray>()
        val pending = ArrayList<JSONObject>()
        var total = 0
        var covered = 0
        var untouched = 0
        readableDatabase.rawQuery(
            "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank," +
                "COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid WHERE questions.enabled=1", null
        ).use { c ->
            while (c.moveToNext()) {
                val presented = c.getInt(5)
                val answered = c.getInt(6)
                val correct = c.getInt(7)
                val bank = (c.getString(4) ?: "").ifBlank { "默认题库" }
                val bucket = banks.getOrPut(bank) { intArrayOf(0, 0) }
                total++
                bucket[0]++
                if (correct > 0) {
                    covered++
                    bucket[1]++
                } else {
                    // 从来没出过的题单独计数：家长能一眼看出「题库里还有多少题孩子根本没碰到」
                    if (presented == 0) untouched++
                    pending.add(JSONObject().put("id", c.getString(0) ?: "")
                        .put("q", (c.getString(1) ?: "").take(80))
                        .put("bank", bank)
                        .put("subject", c.getString(2) ?: "")
                        .put("level", c.getString(3) ?: "")
                        .put("presented", presented)
                        .put("answered", answered))
                }
            }
        }
        pending.sortWith(compareBy<JSONObject>({ it.optInt("presented") }, { it.optInt("answered") }))
        val items = JSONArray()
        for (i in 0 until minOf(pending.size, sample)) items.put(pending[i])
        val bankList = JSONArray()
        banks.entries.sortedByDescending { it.value[0] }.forEach { entry ->
            val counts = entry.value
            bankList.put(JSONObject().put("name", entry.key).put("total", counts[0]).put("covered", counts[1])
                .put("percent", if (counts[0] == 0) 0 else Math.round(counts[1] * 100f / counts[0])))
        }
        return JSONObject().put("total", total).put("covered", covered)
            .put("percent", if (total == 0) 0 else Math.round(covered * 100f / total))
            .put("pending", pending.size).put("untouched", untouched)
            .put("banks", bankList).put("items", items)
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
        details.remove("stats")
        val bank = item.optString("bank", "默认题库").trim()
        require(bank.isNotEmpty() && bank.length <= 64) { "题库名称需要 1–64 个字" }
        details.put("bank", bank)
        for (key in listOf("image", "audio")) {
            val value = item.optString(key).trim()
            require(value.isEmpty() || ((value.startsWith("https://") || value.startsWith("http://")) && value.length <= 2048) ||
                (key == "image" && value.startsWith("data:image/") && value.length <= 500000)) { "图片或音频地址无效" }
            details.put(key, value)
        }
        // #126：支持粤语 / 台湾国语；留空表示跟随设备系统语言（粤语系统不再被强制读成普通话）
        val language = item.optString("language", "zh-CN")
        require(language in SpeechReader.LANGUAGES) { "朗读/识别语言需要为中文、粤语或英文，留空表示跟随系统" }
        details.put("language", language)
        writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
            put("id", id); put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "")); put("difficulty", difficulty)
            put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
            put("tip", item.optString("tip")); put("enabled", 1); put("details", details.toString())
            put("bank", bank); put("type", type)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    /** 题干去重用的归一化：忽略空白、常见标点和大小写，避免同一道题反复导入 */
    private fun stemKey(stem: String): String =
        stem.lowercase().replace(Regex("[\\s，。？?！!、；;：:（）()\"'“”‘’《》<>]"), "")

    /**
     * 批量导入。`skipDuplicates` 打开时按题干去重（题库市场一键添加会重复点），
     * 返回真正新增的条数和跳过的条数；带 id 且已存在的题仍然按覆盖处理。
     */
    fun importQuestions(items: JSONArray, skipDuplicates: Boolean = false): JSONObject {
        require(items.length() in 1..50000) { "每次导入 1–50000 道题" }
        val db = writableDatabase
        val ids = HashSet<String>()
        val stems = HashSet<String>()
        if (skipDuplicates) {
            db.rawQuery("SELECT id, stem FROM questions", null).use { cursor ->
                while (cursor.moveToNext()) {
                    ids.add(cursor.getString(0))
                    stems.add(stemKey(cursor.getString(1) ?: ""))
                }
            }
        }
        var added = 0
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                // 一次导入几十上百题时，笼统的「请填写题干」定位不到是哪道题，报错带上题号
                val item = items.optJSONObject(i) ?: throw IllegalArgumentException("第 ${i + 1} 题：不是一条题目对象")
                if (skipDuplicates) {
                    val id = item.optString("id")
                    val key = stemKey(item.optString("q"))
                    val replaces = id.isNotEmpty() && id in ids
                    if (!replaces && key.isNotEmpty() && !stems.add(key)) continue
                    if (id.isNotEmpty()) ids.add(id)
                }
                try { upsertQuestion(item) } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("第 ${i + 1} 题：${e.message}")
                }
                added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return JSONObject().put("added", added).put("skipped", items.length() - added)
    }

    fun clearQuestions(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val deleted = db.delete("questions", null, null)
            db.delete("question_progress", null, null)
            db.delete("question_stats", null, null)
            db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    fun deleteQuestions(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            ids.forEach {
                count += db.delete("questions", "id=?", arrayOf(it))
                db.delete("question_progress", "qid=?", arrayOf(it))
                db.delete("question_stats", "qid=?", arrayOf(it))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun deleteQuestion(id: String): Boolean = deleteQuestions(listOf(id)) > 0

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
        // 事件按当前孩子记账：历史、使用时长、完成组数都只统计这个孩子自己的
        writableDatabase.execSQL("INSERT INTO events(type,payload,ts,child) VALUES(?,?,?,?)",
            arrayOf(type, payload.toString(), System.currentTimeMillis(), activeChild()))
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
        readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<? AND child=?",
            arrayOf(start.toString(), end.toString(), activeChild())).use { c ->
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
    fun roundsDoneToday(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=? AND child=?",
        arrayOf(startOfToday().toString(), activeChild())).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun historyDays(days: Int = 7): JSONArray {
        val out = JSONArray()
        for (d in 0 until days.coerceIn(1, 90)) {
            val cal = DeviceClock.calendar(this)
            cal.timeInMillis = startOfToday()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            val start = cal.timeInMillis
            val label = if (d == 0) "今天" else if (d == 1) "昨天" else "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            cal.add(Calendar.DAY_OF_YEAR, 1)
            out.put(stats(start, cal.timeInMillis).put("label", label))
        }
        return out
    }

    fun startOfToday(): Long = DeviceClock.calendar(this).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
