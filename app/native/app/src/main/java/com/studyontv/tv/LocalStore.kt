package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 艾宾浩斯 / Anki（SM-2 改良）间隔计算纯函数。
 *
 * 抽离成无副作用对象有两个原因：
 * 1. 背诵闪卡正面/背面要提前展示「重来 / 困难 / 良好 / 简单」四个按钮对应的下次复习间隔，必须和点击后实际落库的值完全一致；
 * 2. 纯 JVM 单元测试可以直接验证各评分档位的演进，不依赖 Android SQLite。
 */
object SpacedRepetition {
    data class State(val repetitions: Int = 0, val intervalDays: Long = 0L, val ease: Double = 2.5)
    data class Step(val repetitions: Int, val intervalDays: Long, val ease: Double, val dueAt: Long)

    fun next(current: State, quality: Int, nowMs: Long): Step {
        val q = quality.coerceIn(0, 5)
        var repetitions = current.repetitions
        var interval = current.intervalDays
        val dueAt: Long
        if (q < 3) {
            repetitions = 0
            interval = 0L
            dueAt = nowMs + 10 * 60 * 1000L
        } else {
            repetitions++
            val standardGood = when (repetitions) {
                1 -> 1L
                2 -> 6L
                else -> kotlin.math.round(current.intervalDays * current.ease).toLong().coerceIn(1L, 36500L)
            }
            interval = when (q) {
                3 -> if (current.repetitions == 0) 1L
                else maxOf(1L, kotlin.math.round(current.intervalDays * 1.2).toLong()).coerceIn(1L, 36500L)
                5 -> when (repetitions) {
                    1 -> 4L
                    2 -> maxOf(7L, kotlin.math.round(current.intervalDays.coerceAtLeast(1L) * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                    else -> maxOf(standardGood + 1L, kotlin.math.round(current.intervalDays * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                }
                else -> standardGood
            }
            dueAt = nowMs + interval * 86_400_000L
        }
        val nextEase = (current.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        return Step(repetitions, interval, nextEase, dueAt)
    }

    fun previewLabels(current: State): JSONObject {
        val hardDays = next(current, 3, 0L).intervalDays.coerceAtLeast(1L)
        val goodDays = next(current, 4, 0L).intervalDays.coerceAtLeast(1L)
        val easyDays = maxOf(goodDays + 1L, next(current, 5, 0L).intervalDays)
        return JSONObject()
            .put("again", "10分钟")
            .put("hard", "${hardDays}天")
            .put("good", "${goodDays}天")
            .put("easy", "${easyDays}天")
    }
}

class LocalStore(
    context: Context,
    databaseName: String = "studyontv.db",
    private val readClock: () -> DeviceClock.Reading = DeviceClock::read
) : SQLiteOpenHelper(context, databaseName, null, 13) {
    internal fun clockReading(): DeviceClock.Reading = readClock()
    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
        /** 第一个孩子沿用旧版本的存储键（不带前缀），升级时不需要搬运任何数据 */
        const val DEFAULT_CHILD = ""
        /** 设备级共享的设置：不随当前孩子切换 */
        private val SHARED_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "bank_automation", "bank_automation_status",
            "children", "active_child", "parent_access", "onboarded", "bank_market_sources"
        )
        /** 备份导出时必须排除的含密钥 / 鉴权存储键 */
        private val SECRET_KV_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "parent_access"
        )
        /** 绑定本机单调时钟、重启计数或半途会话的瞬态存储键：导出与恢复一律跳过，避免换机污染 */
        private val EPHEMERAL_KV_KEYS = setOf(
            "active_round", "last_answer", "feedback_result", "feedback_deadline",
            "review_feedback", "remote_override", "rest_deadline", "rest_watched_ms",
            "boot_quota_ms", "video_lesson"
        )
        private val CHILD_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val pinRandom = SecureRandom()
        const val MAX_CHILDREN = 8

        internal fun isValidChildId(id: String): Boolean =
            id == DEFAULT_CHILD || CHILD_ID_PATTERN.matches(id)

        /**
         * 归一化孩子切换密码输入：
         * - `null`：保持原密码不变
         * - `""`（或全空白）：清空密码
         * - 非空：校验长度为 1..16 位后返回去首尾空白的值
         */
        internal fun normalizeChildPin(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            require(trimmed.length in 1..16) { "切换密码需要 1–16 位" }
            return trimmed
        }

        internal fun generatePinSalt(): String =
            ByteArray(16).also { pinRandom.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }

        internal fun hashChildPin(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun verifyPinHash(pin: String, salt: String, expectedHash: String): Boolean {
            val candidate = pin.trim()
            if (candidate.isEmpty() || salt.isEmpty() || expectedHash.isEmpty()) return false
            val actual = hashChildPin(candidate, salt)
            return MessageDigest.isEqual(
                actual.toByteArray(Charsets.UTF_8),
                expectedHash.toByteArray(Charsets.UTF_8)
            )
        }

        /** 对外序列化孩子档案：绝不下发 pin_hash / pin_salt / 原文，仅下发 hasPin。 */
        internal fun childSummaryJson(
            id: String,
            name: String,
            createdAt: Long,
            active: Boolean,
            hasPin: Boolean
        ): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("createdAt", createdAt)
            .put("active", active)
            .put("hasPin", hasPin)

        internal fun isEphemeralKvKey(key: String): Boolean {
            val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
            return base in EPHEMERAL_KV_KEYS
        }

        /** 多设备裁剪式同步允许的范围白名单 */
        val SYNC_SCOPES = setOf("config", "questions", "speech", "grading")

        internal fun normalizeSyncScopes(scopes: Collection<String>): Set<String> =
            scopes.mapTo(LinkedHashSet()) { it.trim().lowercase(Locale.US) }.filterTo(LinkedHashSet()) { it in SYNC_SCOPES }

        /** 仅允许同步学习计划规则（`config` 与 `child:<id>:config`），严禁同步单机运行态计时、密码或设备名。 */
        internal fun isConfigSyncKvKey(key: String): Boolean {
            if (key == "config") return true
            if (key.startsWith("child:") && key.endsWith(":config")) {
                val childId = key.removePrefix("child:").removeSuffix(":config")
                return childId.isNotEmpty() && isValidChildId(childId)
            }
            return false
        }

        /**
         * 纯函数：从备份/源对象按 `scopes` 白名单精准裁剪同步载荷。
         * 严禁携带 `deviceName`、`activeChild`、`events`、`reviews`、`questionStats`、`questionProgress`
         * 及任何单机运行态 kv（如 `used_ms` / `free_used_ms` / `credit_ms` / `active_round` / `admin_token`）。
         */
        internal fun buildScopedSyncPayload(source: JSONObject, scopes: Set<String>): JSONObject {
            val validScopes = normalizeSyncScopes(scopes)
            require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
            val out = JSONObject()
                .put("format", "studyontv-backup")
                .put("version", source.optInt("version", 1))
                .put("exportedAt", source.optLong("exportedAt", System.currentTimeMillis()))
                .put("appVersion", source.optString("appVersion", BuildConfig.VERSION_NAME))
                .put("scopes", JSONArray(validScopes.sorted()))
                .put("includeQuestions", "questions" in validScopes)
            if ("config" in validScopes) {
                source.optJSONArray("children")?.let { out.put("children", it) }
                val filteredKv = JSONObject()
                source.optJSONObject("kv")?.let { kv ->
                    val keys = kv.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (isConfigSyncKvKey(k)) {
                            filteredKv.put(k, kv.optString(k, ""))
                        }
                    }
                }
                out.put("kv", filteredKv)
            }
            if ("questions" in validScopes) {
                out.put("questions", source.optJSONArray("questions") ?: JSONArray())
            }
            if ("speech" in validScopes) {
                source.optJSONObject("speech")?.let { out.put("speech", it) }
            }
            if ("grading" in validScopes) {
                source.optJSONObject("grading")?.let { out.put("grading", it) }
            }
            return out
        }

        /** EU Local-Only 模式下清洗单个备份 kv 项（剥离 config 中的公网公益视频与 bank_market_sources 中的公网源）。 */
        internal fun sanitizeKvValueForLocalOnly(key: String, value: String, localOnly: Boolean = LocalOnlyGuard.enabled): String {
            if (!localOnly) return value
            val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
            return when (base) {
                "config" -> try {
                    val cfg = JSONObject(value)
                    val videos = cfg.optJSONArray("educationalVideos")
                    if (videos != null) {
                        val kept = JSONArray()
                        for (i in 0 until videos.length()) {
                            val raw = videos.optString(i).trim()
                            if (raw.isNotEmpty() && LocalOnlyGuard.isLocalUrl(raw)) kept.put(raw)
                        }
                        cfg.put("educationalVideos", kept)
                        if (kept.length() == 0 && cfg.optString("learningContent", "quiz") != "quiz") {
                            cfg.put("learningContent", "quiz")
                        }
                    }
                    cfg.toString()
                } catch (_: Exception) { value }
                "bank_market_sources" -> try {
                    val arr = JSONArray(value)
                    val kept = JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val url = item.optString("url").trim()
                        if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
                    }
                    kept.toString()
                } catch (_: Exception) { value }
                "bank_automation" -> try {
                    sanitizeBankAutomationForLocalOnly(JSONObject(value), localOnly).toString()
                } catch (_: Exception) { value }
                else -> value
            }
        }

        /** EU Local-Only 模式下清洗备份中的 bankAutomation.sources 公网远程题库源。 */
        internal fun sanitizeBankAutomationForLocalOnly(raw: JSONObject, localOnly: Boolean = LocalOnlyGuard.enabled): JSONObject {
            val next = JSONObject(raw.toString())
            next.remove("running")
            next.remove("status")
            if (!localOnly) return next
            val sources = next.optJSONArray("sources")
            if (sources != null) {
                val kept = JSONArray()
                for (i in 0 until sources.length()) {
                    val item = sources.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
                }
                next.put("sources", kept)
            }
            return next
        }

        /** 间隔复习纳入范围判断（repetitionStats / studySummary / learningAnalytics 唯一真理源）。 */
        internal fun inSpacedScope(wrongOnly: Boolean, lastCorrect: Int, answered: Int, correct: Int): Boolean =
            !wrongOnly || lastCorrect == 0 || answered > correct

        /** 按 IANA 时区 ID 解析时区；留空或非法值回退设备默认时区。 */
        internal fun resolveTimeZone(rawZoneId: String): TimeZone {
            val id = rawZoneId.trim()
            if (id.isEmpty()) return TimeZone.getDefault()
            val zone = TimeZone.getTimeZone(id)
            return if (zone.id == "GMT" && id != "GMT" && id != "UTC") TimeZone.getDefault() else zone
        }

        /** 计算指定时区在给定时刻所在自然日的 00:00:00.000 毫秒时间戳。 */
        internal fun startOfDayMillis(nowMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** 统一 SQL 范围过滤结果：全部值通过参数化占位符绑定，杜绝 SQL 注入。 */
        internal data class SqlFilter(val clause: String, val args: List<String>)

        /** 解析加练队列 JSON 数组，过滤空值并保序去重。 */
        internal fun parseExtraQueue(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.toList()
        }

        /** 将新增题号合并进加练队列（保序去重），返回合并后的队列与实际新增数。 */
        internal fun mergeExtraQueue(existing: List<String>, additions: List<String>): Pair<List<String>, Int> {
            val merged = LinkedHashSet<String>()
            existing.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
            val before = merged.size
            additions.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
            return merged.toList() to (merged.size - before)
        }

        /** 规范化 config.gradeSubjects：自动过滤空年级/超长键、科目去重，保留空数组（表示该年级全部科目）。 */
        internal fun normalizeGradeSubjects(raw: JSONObject?): JSONObject {
            val clean = JSONObject()
            if (raw == null) return clean
            val keys = raw.keys()
            var gradeCount = 0
            while (keys.hasNext() && gradeCount < 50) {
                val rawKey = keys.next()
                val grade = rawKey.trim()
                if (grade.isEmpty() || grade.length > 32) continue
                val arr = raw.optJSONArray(rawKey)
                val cleanSubjects = JSONArray()
                val seen = LinkedHashSet<String>()
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), 50)) {
                        val sub = arr.optString(i).trim()
                        if (sub.isNotEmpty() && sub.length <= 32 && seen.add(sub)) {
                            cleanSubjects.put(sub)
                        }
                    }
                }
                clean.put(grade, cleanSubjects)
                gradeCount++
            }
            return clean
        }

        /** 将 config.gradeSubjects 解析为保序 Map<年级, 科目列表>（空列表表示该年级不限科目）。 */
        internal fun parseGradeSubjects(raw: JSONObject?): Map<String, List<String>> {
            val normalized = normalizeGradeSubjects(raw)
            val out = LinkedHashMap<String, List<String>>()
            val keys = normalized.keys()
            while (keys.hasNext()) {
                val grade = keys.next()
                val arr = normalized.optJSONArray(grade)
                val subjects = ArrayList<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val sub = arr.optString(i).trim()
                        if (sub.isNotEmpty()) subjects.add(sub)
                    }
                }
                out[grade] = subjects
            }
            return out
        }

        /** 内存态主从范围匹配判断（与 buildScopeFilter SQL 语义严格一致）。 */
        internal fun matchesGradeSubjects(
            level: String,
            subject: String,
            gradeSubjects: Map<String, List<String>>,
            selectedLevel: String = ""
        ): Boolean {
            if (gradeSubjects.isEmpty()) return true
            if (selectedLevel.isNotEmpty()) {
                if (level != selectedLevel) return false
                val allowed = gradeSubjects[selectedLevel] ?: return false
                return allowed.isEmpty() || subject in allowed
            }
            val allowed = gradeSubjects[level] ?: return false
            return allowed.isEmpty() || subject in allowed
        }

        /**
         * 构建题目范围 SQL 过滤片段（pickQuestion / pickDeck / distinctLevels / coverage 单一真理源）。
         * - gradeSubjects 为空时 100% 沿用旧的 levels × subjects 笛卡尔积语义；
         * - gradeSubjects 非空时按每个年级各自允许的科目精确匹配；若指定 selectedLevel 则只对该年级生效。
         */
        internal fun buildScopeFilter(
            subjects: List<String> = emptyList(),
            levels: List<String> = emptyList(),
            selectedLevel: String = "",
            banks: List<String> = emptyList(),
            gradeSubjects: Map<String, List<String>> = emptyMap(),
            explicitSubjectFilter: Boolean = false
        ): SqlFilter {
            fun ph(count: Int) = List(count) { "?" }.joinToString(",")
            val where = StringBuilder()
            val args = mutableListOf<String>()

            if (gradeSubjects.isNotEmpty()) {
                if (selectedLevel.isNotEmpty()) {
                    val allowedSubjects = gradeSubjects[selectedLevel]
                    if (allowedSubjects == null) {
                        where.append(" AND 1=0")
                    } else if (allowedSubjects.isEmpty()) {
                        where.append(" AND questions.level=?")
                        args.add(selectedLevel)
                    } else {
                        where.append(" AND (questions.level=? AND questions.subject IN (${ph(allowedSubjects.size)}))")
                        args.add(selectedLevel)
                        args.addAll(allowedSubjects)
                    }
                } else {
                    val branches = mutableListOf<String>()
                    for ((grade, allowedSubjects) in gradeSubjects) {
                        if (allowedSubjects.isEmpty()) {
                            branches.add("questions.level=?")
                            args.add(grade)
                        } else {
                            branches.add("(questions.level=? AND questions.subject IN (${ph(allowedSubjects.size)}))")
                            args.add(grade)
                            args.addAll(allowedSubjects)
                        }
                    }
                    if (branches.isNotEmpty()) {
                        where.append(" AND (${branches.joinToString(" OR ")})")
                    }
                }
                if (explicitSubjectFilter && subjects.isNotEmpty()) {
                    where.append(" AND questions.subject IN (${ph(subjects.size)})")
                    args.addAll(subjects)
                }
            } else {
                if (subjects.isNotEmpty()) {
                    where.append(" AND questions.subject IN (${ph(subjects.size)})")
                    args.addAll(subjects)
                }
                if (levels.isNotEmpty()) {
                    where.append(" AND questions.level IN (${ph(levels.size)})")
                    args.addAll(levels)
                }
                if (selectedLevel.isNotEmpty()) {
                    where.append(" AND questions.level=?")
                    args.add(selectedLevel)
                }
            }

            if (banks.isNotEmpty()) {
                if ("默认题库" in banks) {
                    where.append(" AND (questions.bank IN (${ph(banks.size)}) OR questions.bank='')")
                } else {
                    where.append(" AND questions.bank IN (${ph(banks.size)})")
                }
                args.addAll(banks)
            }
            return SqlFilter(where.toString(), args)
        }
    }

    private val appContext: Context = context.applicationContext ?: context

    /** 测试库使用独立目录，避免回归测试清理媒体时伤到家庭题库的图片。 */
    val mediaDir: File = File(
        appContext.filesDir,
        if (databaseName == "studyontv.db") "media"
        else "media_" + databaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    )

    /** 按孩子缓存归一化后的配置 JSON 字符串，避免每秒 tick 反复查库、跑正则和解析周计划。 */
    private val configCache = ConcurrentHashMap<String, String>()

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
        if (oldVersion < 12) {
            // 上游第 10 版（我们顺延到 12）：question_stats 改为按孩子隔离的 (qid, child) 复合主键；
            // 同时把历史题目配图与手写批改里的内联 Base64 迁出到本地媒体文件，避免撑爆 CursorWindow。
            migrateQuestionStatsForChildren(db)
            migrateInlineMedia(db)
        }
        if (oldVersion < 13) {
            // 上游第 11 版（我们顺延到 13）：多孩子独立切换密码（加盐 SHA-256 哈希，默认空串表示未设密码）
            addColumn(db, "children", "pin_hash", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "children", "pin_salt", "TEXT NOT NULL DEFAULT ''")
        }
    }

    override fun close() {
        super.close()
        configCache.clear()
        synchronized(childLock) { cachedChild = null }
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
        db.execSQL("CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, pin_hash TEXT NOT NULL DEFAULT '', pin_salt TEXT NOT NULL DEFAULT '')")
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

    /** 旧的 question_stats 整机共用一份，二胎家庭两个孩子的错题与覆盖率会互相串改；升级为 (qid, child) 复合主键并把旧统计归到默认孩子名下。 */
    private fun migrateQuestionStatsForChildren(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(question_stats)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
        if (columns.isEmpty()) {
            createQuestionStats(db)
            return
        }
        if ("child" in columns) return
        val lastCol = if ("last_correct" in columns) "last_correct" else "-1"
        db.execSQL("DROP TABLE IF EXISTS question_stats_legacy")
        db.execSQL("ALTER TABLE question_stats RENAME TO question_stats_legacy")
        createQuestionStats(db)
        db.execSQL(
            "INSERT OR REPLACE INTO question_stats(qid,child,presented,answered,correct,last_at,last_correct) " +
                "SELECT qid,'',presented,answered,correct,last_at,$lastCol FROM question_stats_legacy"
        )
        db.execSQL("DROP TABLE question_stats_legacy")
    }

    /**
     * 逐行迁移历史库里的内联 `data:image/`：
     * 用 `LIKE '%data:image/%'` 跳过绝大多数纯文字行，且每次 `LIMIT 1` 读取单行，避免多张大图同时进入 2MB CursorWindow。
     */
    private fun migrateInlineMedia(db: SQLiteDatabase) {
        val updateQ = db.compileStatement("UPDATE questions SET details=? WHERE id=?")
        try {
            var lastRow = -1L
            while (true) {
                var rowId = -1L
                var id = ""
                var raw = ""
                db.rawQuery(
                    "SELECT rowid,id,details FROM questions WHERE rowid>? AND details LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                    arrayOf(lastRow.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        rowId = c.getLong(0)
                        id = c.getString(1) ?: ""
                        raw = c.getString(2) ?: ""
                    }
                }
                if (rowId < 0) break
                lastRow = rowId
                val updated = migrateQuestionDetailsJson(raw)
                if (updated != null && updated != raw) {
                    updateQ.clearBindings()
                    updateQ.bindString(1, updated)
                    updateQ.bindString(2, id)
                    updateQ.executeUpdateDelete()
                }
            }
        } finally {
            updateQ.close()
        }

        val updateR = db.compileStatement("UPDATE reviews SET payload=? WHERE id=?")
        try {
            var lastRow = -1L
            while (true) {
                var rowId = -1L
                var id = ""
                var raw = ""
                db.rawQuery(
                    "SELECT rowid,id,payload FROM reviews WHERE rowid>? AND payload LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                    arrayOf(lastRow.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        rowId = c.getLong(0)
                        id = c.getString(1) ?: ""
                        raw = c.getString(2) ?: ""
                    }
                }
                if (rowId < 0) break
                lastRow = rowId
                val updated = migrateReviewPayloadJson(raw)
                if (updated != null && updated != raw) {
                    updateR.clearBindings()
                    updateR.bindString(1, updated)
                    updateR.bindString(2, id)
                    updateR.executeUpdateDelete()
                }
            }
        } finally {
            updateR.close()
        }
    }

    private fun migrateDataUrlField(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("data:image/", ignoreCase = true)) return raw
        return try {
            MediaStore.saveDataUrl(mediaDir, trimmed, 2_000_000)
        } catch (_: Exception) {
            // 历史脏数据无法解码时清空坏图引用，绝不让数据库升级抛异常卡死启动
            ""
        }
    }

    private fun migrateQuestionObjectMedia(obj: JSONObject): Boolean {
        var changed = false
        val image = obj.optString("image")
        if (image.startsWith("data:image/", ignoreCase = true)) {
            obj.put("image", migrateDataUrlField(image))
            changed = true
        }
        val images = obj.optJSONArray("images")
        if (images != null) {
            val next = JSONArray()
            var arrChanged = false
            for (i in 0 until images.length()) {
                val v = images.optString(i)
                val m = migrateDataUrlField(v)
                if (m != v) arrChanged = true
                if (m.isNotEmpty()) next.put(m)
            }
            if (arrChanged) {
                obj.put("images", next)
                changed = true
            }
        }
        val optImgs = obj.optJSONArray("optionImages")
        if (optImgs != null) {
            val next = JSONArray()
            var arrChanged = false
            for (i in 0 until optImgs.length()) {
                val v = optImgs.optString(i)
                val m = migrateDataUrlField(v)
                if (m != v) arrChanged = true
                next.put(m)
            }
            if (arrChanged) {
                obj.put("optionImages", next)
                changed = true
            }
        }
        return changed
    }

    private fun migrateQuestionDetailsJson(raw: String): String? = try {
        val obj = JSONObject(raw)
        if (migrateQuestionObjectMedia(obj)) obj.toString() else null
    } catch (_: Exception) { null }

    private fun migrateReviewPayloadJson(raw: String): String? = try {
        val obj = JSONObject(raw)
        var changed = false
        obj.optJSONObject("input")?.let { input ->
            val drawing = input.optString("drawing")
            if (drawing.startsWith("data:image/", ignoreCase = true)) {
                val migrated = migrateDataUrlField(drawing)
                if (migrated.isEmpty()) input.remove("drawing") else input.put("drawing", migrated)
                changed = true
            }
        }
        obj.optJSONObject("question")?.let { q ->
            if (migrateQuestionObjectMedia(q)) changed = true
        }
        if (changed) obj.toString() else null
    } catch (_: Exception) { null }

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
                if (ms > 0) db.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any>("usage", JSONObject().put("milliseconds", ms).toString(), hour))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createQuestionStats(db: SQLiteDatabase) {
        // IF NOT EXISTS：升级过程被打断 / 重复升级时不能整库异常，否则 /api/state、题库都读不出来
        // last_correct：最近一次作答是否正确，-1 = 还没作答过（错题库用它判断是不是错题）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS question_stats(" +
                "qid TEXT NOT NULL, " +
                "child TEXT NOT NULL DEFAULT '', " +
                "presented INTEGER NOT NULL DEFAULT 0, " +
                "answered INTEGER NOT NULL DEFAULT 0, " +
                "correct INTEGER NOT NULL DEFAULT 0, " +
                "last_at INTEGER NOT NULL DEFAULT 0, " +
                "last_correct INTEGER NOT NULL DEFAULT -1, " +
                "PRIMARY KEY(qid, child))"
        )
    }

    fun presented(id: String, child: String = activeChild()) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
        writableDatabase.execSQL(
            "UPDATE question_stats SET presented=presented+1,last_at=? WHERE qid=? AND child=?",
            arrayOf<Any>(clockReading().millis, id, child)
        )
    }

    fun answered(id: String, correct: Boolean, child: String = activeChild()) {
        val flag = if (correct) 1 else 0
        writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
        writableDatabase.execSQL(
            "UPDATE question_stats SET answered=answered+1,correct=correct+?,last_correct=?,last_at=? WHERE qid=? AND child=?",
            arrayOf<Any>(flag, flag, clockReading().millis, id, child)
        )
    }

    /** 题库里的题目 id：删除题库前先拿到，用来判断孩子手上那一轮是不是被删掉了。 */
    fun idsInBank(bank: String): List<String> {
        require(bank.isNotBlank()) { "请选择题库" }
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT id FROM questions WHERE bank=?", arrayOf(bank)).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    fun deleteBank(bank: String): Int = deleteQuestions(idsInBank(bank))

    /** AI 出题查重只需要题干字符串，避免把整表 details 和统计全读出来。 */
    fun allStems(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT stem FROM questions", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        return out
    }

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

    fun progressState(id: String, child: String = activeChild()): SpacedRepetition.State =
        readableDatabase.rawQuery(
            "SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=? AND child=?",
            arrayOf(id, child)
        ).use {
            if (it.moveToFirst()) SpacedRepetition.State(it.getInt(0), it.getLong(1), it.getDouble(2))
            else SpacedRepetition.State()
        }

    fun previewIntervals(id: String, child: String = activeChild()): JSONObject =
        SpacedRepetition.previewLabels(progressState(id, child))

    fun recordProgress(id: String, quality: Int) {
        val child = activeChild()
        val current = progressState(id, child)
        val now = clockReading().millis
        val step = SpacedRepetition.next(current, quality, now)
        writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
            put("qid", id); put("child", child); put("repetitions", step.repetitions)
            put("interval_days", step.intervalDays); put("ease", step.ease)
            put("due_at", step.dueAt); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @JvmOverloads
    fun repetitionStats(child: String = activeChild()): JSONObject {
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        val nowStr = clockReading().millis.toString()
        val sql = if (wrongOnly) {
            "SELECT COUNT(*),COALESCE(SUM(CASE WHEN question_progress.due_at<=? AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0)) THEN 1 ELSE 0 END),0) " +
                "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
                "LEFT JOIN question_stats stats ON stats.qid=questions.id AND stats.child=question_progress.child " +
                "WHERE questions.enabled=1 AND question_progress.child=?"
        } else {
            "SELECT COUNT(*),COALESCE(SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END),0) " +
                "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
                "WHERE questions.enabled=1 AND question_progress.child=?"
        }
        return readableDatabase.rawQuery(sql, arrayOf(nowStr, child)).use {
            if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1))
            else JSONObject().put("studied", 0).put("due", 0)
        }
    }

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

    fun reviews(state: String, child: String? = null): JSONArray {
        val out = JSONArray()
        val filter = if (state == "all") "" else "WHERE state=?"
        readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
            while (it.moveToNext()) {
                val obj = JSONObject(it.getString(0))
                if (child == null || obj.optString("child", DEFAULT_CHILD) == child) out.put(obj)
            }
        }
        return out
    }

    /** 当前孩子：默认孩子用空串，沿用旧版本的存储键，升级时不用搬运数据 */
    @Volatile private var cachedChild: String? = null
    private val childLock = Any()
    /** 批改其他孩子的主观题时在当前线程临时绑定到该孩子，避免改动全局 active_child 影响电视上前台正在学习的孩子。 */
    private val childOverride = ThreadLocal<String?>()

    fun childExists(id: String): Boolean =
        id == DEFAULT_CHILD || readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    fun <T> withChild(childId: String, block: () -> T): T {
        val prev = childOverride.get()
        childOverride.set(childId)
        return try {
            block()
        } finally {
            if (prev == null) childOverride.remove() else childOverride.set(prev)
        }
    }

    fun activeChild(): String {
        childOverride.get()?.let { return it }
        cachedChild?.let { return it }
        synchronized(childLock) {
            cachedChild?.let { return it }
            val stored = rawGet("active_child")
            val valid = when {
                stored.isEmpty() -> DEFAULT_CHILD
                readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(stored)).use { it.moveToFirst() } -> stored
                else -> { rawSet("active_child", DEFAULT_CHILD); DEFAULT_CHILD }
            }
            cachedChild = valid
            return valid
        }
    }

    /** 学习相关的键按孩子隔离；朗读 / 批改 / 远程题库这类设备设置仍共用一份 */
    private fun scoped(key: String, child: String = activeChild()): String {
        if (key in SHARED_KEYS) return key
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
        if (key == "config" || key.endsWith(":config")) {
            configCache.clear()
        }
    }

    fun get(key: String, default: String = ""): String = rawGet(scoped(key), default)

    fun set(key: String, value: String) = rawSet(scoped(key), value)

    // ---- 多孩子档案（#132） ----

    fun children(): JSONArray {
        ensureDefaultChild()
        val active = activeChild()
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id,name,created_at,pin_hash FROM children ORDER BY created_at,id", null).use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val hasPin = !(it.getString(3).isNullOrEmpty())
                out.put(childSummaryJson(id, it.getString(1), it.getLong(2), id == active, hasPin))
            }
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

    fun createChild(raw: String, pin: String? = null): JSONObject {
        ensureDefaultChild()
        val name = validName(raw)
        val normalizedPin = normalizeChildPin(pin)
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM children", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_CHILDREN) { "最多只能添加 $MAX_CHILDREN 个孩子" }
        val id = "c" + UUID.randomUUID().toString().replace("-", "").take(8)
        val salt = if (!normalizedPin.isNullOrEmpty()) generatePinSalt() else ""
        val hash = if (!normalizedPin.isNullOrEmpty()) hashChildPin(normalizedPin, salt) else ""
        // 新孩子沿用当前的规则（时长、时段、奖励…），不需要家长再配一遍；学习进度从零开始
        val inherited = rawGet(scoped("config"))
        writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
            put("id", id); put("name", name); put("created_at", System.currentTimeMillis())
            put("pin_hash", hash); put("pin_salt", salt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (inherited.isNotEmpty()) rawSet("child:$id:config", inherited)
        return JSONObject().put("id", id).put("name", name).put("hasPin", hash.isNotEmpty())
    }

    fun updateChild(id: String, raw: String, pin: String? = null): JSONObject {
        ensureDefaultChild()
        val name = validName(raw, id)
        val normalizedPin = normalizeChildPin(pin)
        val cv = ContentValues().apply {
            put("name", name)
            when {
                normalizedPin == null -> Unit // 不改动现有密码
                normalizedPin.isEmpty() -> {
                    put("pin_hash", "")
                    put("pin_salt", "")
                }
                else -> {
                    val salt = generatePinSalt()
                    put("pin_hash", hashChildPin(normalizedPin, salt))
                    put("pin_salt", salt)
                }
            }
        }
        val updated = writableDatabase.update("children", cv, "id=?", arrayOf(id))
        require(updated > 0) { "孩子档案不存在" }
        return JSONObject().put("id", id).put("name", name).put("hasPin", childHasPin(id))
    }

    fun renameChild(id: String, raw: String): JSONObject = updateChild(id, raw, null)

    fun childHasPin(childId: String): Boolean {
        ensureDefaultChild()
        return readableDatabase.rawQuery("SELECT pin_hash FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
            it.moveToFirst() && !(it.getString(0).isNullOrEmpty())
        }
    }

    fun verifyChildPin(childId: String, pin: String): Boolean {
        ensureDefaultChild()
        val candidate = pin.trim()
        if (candidate.isEmpty()) return false
        return readableDatabase.rawQuery("SELECT pin_hash,pin_salt FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
            if (!it.moveToFirst()) return false
            verifyPinHash(candidate, it.getString(1).orEmpty(), it.getString(0).orEmpty())
        }
    }

    /** 删除孩子档案：连同他的学习记录、错题统计、复习进度、主观题批改和孤儿手写图一起清掉，题目本身保留 */
    fun deleteChild(id: String): Boolean {
        require(id != DEFAULT_CHILD) { "第一个孩子不能删除，可以改名" }
        val db = writableDatabase
        // 统一锁顺序：永远先 childLock、再 SQLite 写事务，避免与 switchChild()/activeChild() 产生 AB-BA 死锁
        synchronized(childLock) {
            db.beginTransaction()
            try {
                db.delete("children", "id=?", arrayOf(id))
                db.delete("kv", "k LIKE ?", arrayOf("child:$id:%"))
                db.delete("events", "child=?", arrayOf(id))
                db.delete("question_progress", "child=?", arrayOf(id))
                db.delete("question_stats", "child=?", arrayOf(id))
                // Android 5+ 自带 SQLite 不支持 JSON_EXTRACT，在 Kotlin 层过滤属于该孩子的批改记录
                val reviewIds = ArrayList<String>()
                db.rawQuery("SELECT id,payload FROM reviews", null).use { c ->
                    while (c.moveToNext()) {
                        val rid = c.getString(0) ?: continue
                        val payload = c.getString(1) ?: continue
                        val owner = try { JSONObject(payload).optString("child", DEFAULT_CHILD) } catch (_: Exception) { DEFAULT_CHILD }
                        if (owner == id) reviewIds.add(rid)
                    }
                }
                for (rid in reviewIds) db.delete("reviews", "id=?", arrayOf(rid))
                configCache.remove(id)
                if (activeChild() == id) switchChild(DEFAULT_CHILD)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (!db.inTransaction()) pruneMedia()
        return true
    }

    fun switchChild(id: String) {
        synchronized(childLock) {
            ensureDefaultChild()
            val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
            require(exists) { "孩子档案不存在" }
            rawSet("active_child", id)
            cachedChild = id
        }
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    @JvmOverloads
    fun config(child: String = activeChild()): JSONObject {
        // 每次返回全新 JSONObject 副本，防止调用方原地修改返回对象污染缓存
        configCache[child]?.let { return JSONObject(it) }
        val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true)
            .put("supervisionEnabled", true)
            .put("spacedRepetitionEnabled", false).put("memorizeFirst", false).put("spacedScope", "all")
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
            .put("bootFreeMinutes", 0).put("bootFreeIntervalMinutes", 0)
            .put("dailyLimitEnabled", true).put("roundsLimitEnabled", true)
            .put("restEnabled", false).put("restWatchMinutes", 40).put("restMinutes", 10)
            .put("wrongReviewSeconds", 0).put("fontScale", 100).put("childSelectLevel", false)
            .put("trafficAssistEnabled", false)
            // #122：部分固件（海信 VIDAA）会把普通应用的全屏悬浮窗压在硬件视频层下面，
            // 家长可强制改走全屏学习页；默认仍由 GateService 按可见性自动判断。
            .put("forceFullscreenGate", false)
            .put("watchWhitelist", JSONArray()).put("allowQuizUnlock", false)
            .put("learningContent", "quiz").put("educationalVideos", JSONArray()).put("videoMinutes", 1)
            .put("quizBanks", JSONArray())
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
            .put("gradeSubjects", JSONObject())
            .put("timePoints", JSONArray()).put("exemptWindows", JSONArray())
            .put("timezone", "")
            .put("version", 1)
        val saved = try { JSONObject(rawGet(scoped("config", child), "{}")) } catch (_: Exception) { JSONObject() }
        saved.keys().forEach { defaults.put(it, saved.get(it)) }
        defaults.put("theme", when (defaults.optString("theme")) { "light", "daylight" -> "light"; "system" -> "system"; "dark", "midnight", "nord", "amber" -> "dark"; else -> if (BuildConfig.FLAVOR == "tv") "dark" else "system" })
        val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        val validStart = timePattern.matches(defaults.optString("windowStart"))
        val validEnd = timePattern.matches(defaults.optString("windowEnd"))
        if (!validStart) defaults.put("windowStart", "18:30")
        if (!validEnd) defaults.put("windowEnd", "20:00")
        if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
        val migrated = normalizeWeek(saved.optJSONArray("weekSlots"))
            ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
                val oldDefault = !defaults.optBoolean("allDay") &&
                    defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
                if (oldDefault) { defaults.put("allDay", true); fullWeek() }
                else weekFromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
            } else fullWeek()
        defaults.put("weekSlots", migrated)
        if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) {
            defaults.put("holidayMode", "sunday")
        }
        if (defaults.optString("spacedScope") !in listOf("all", "wrong_only")) {
            defaults.put("spacedScope", "all")
        }
        defaults.put("gradeSubjects", normalizeGradeSubjects(defaults.optJSONObject("gradeSubjects")))
        val snapshot = defaults.toString()
        configCache[child] = snapshot
        return JSONObject(snapshot)
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
            "bootFreeMinutes" to 0..120, "bootFreeIntervalMinutes" to 0..1440,
            "restWatchMinutes" to 1..480, "restMinutes" to 1..120,
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
                if (LocalOnlyGuard.enabled) {
                    require(LocalOnlyGuard.isLocalUrl(url)) { "仅本地隐私版（EU Local-Only）仅允许配置本机或局域网内的公益视频" }
                }
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
        if (input.has("spacedScope")) {
            val scope = input.optString("spacedScope")
            require(scope in listOf("all", "wrong_only")) { "未知复习范围" }
            next.put("spacedScope", scope)
        }
        listOf(
            "supervisionEnabled", "memorizeFirst", "extraEnabled", "coverageFirst", "newFirst",
            "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl",
            "shuffleOptions", "showCountdown", "dailyLimitEnabled", "roundsLimitEnabled", "restEnabled",
            "childSelectLevel", "trafficAssistEnabled", "forceFullscreenGate",
            "wrongFirst", "allowQuizUnlock"
        ).forEach {
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
        if (input.has("gradeSubjects")) {
            next.put("gradeSubjects", normalizeGradeSubjects(input.optJSONObject("gradeSubjects")))
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
        val snapshot = next.toString()
        set("config", snapshot)
        configCache[activeChild()] = snapshot
        return JSONObject(snapshot)
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    private fun appendScopeFilters(
        where: StringBuilder,
        args: MutableList<String>,
        subjects: List<String>,
        levels: List<String>,
        selectedLevel: String,
        banks: List<String>,
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ) {
        val filter = buildScopeFilter(
            subjects = subjects,
            levels = levels,
            selectedLevel = selectedLevel,
            banks = banks,
            gradeSubjects = gradeSubjects,
            explicitSubjectFilter = explicitSubjectFilter
        )
        where.append(filter.clause)
        args.addAll(filter.args)
    }

    private fun buildQuestionOrder(
        spaced: Boolean,
        sortBy: String,
        wrongFirst: Boolean,
        coverageFirst: Boolean,
        newFirst: Boolean
    ): String {
        val base = if (spaced) {
            "CASE WHEN progress.due_at<=${clockReading().millis} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
        } else when (sortBy) {
            "newest" -> "questions.created_at DESC, questions.rowid DESC"
            "oldest" -> "questions.created_at ASC, questions.rowid ASC"
            else -> "RANDOM()"
        }
        val tiers = mutableListOf<String>()
        if (wrongFirst) tiers.add("CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END")
        if (newFirst) tiers.add("CASE WHEN COALESCE(stats.presented,0)=0 THEN 0 ELSE 1 END")
        if (coverageFirst) tiers.add("CASE WHEN COALESCE(stats.correct,0)=0 THEN 0 ELSE 1 END, COALESCE(stats.presented,0) ASC")
        return (tiers + base).joinToString(", ")
    }

    /**
     * 出题单条查询：在 SQL 层完成范围筛选、排除上一题、错题筛选与排序，只读取命中的 1 道题，
     * 避免大题库每次换题都把几万行 JSON 扫进内存。
     */
    fun pickQuestion(
        subjects: List<String> = emptyList(),
        levels: List<String> = emptyList(),
        selectedLevel: String = "",
        banks: List<String> = emptyList(),
        spaced: Boolean = false,
        wrongFirst: Boolean = false,
        coverageFirst: Boolean = false,
        newFirst: Boolean = false,
        excludeId: String? = null,
        onlyWrong: Boolean = false,
        missedOnly: Boolean = false,
        minStar: Int = 1,
        excludeIds: Collection<String> = emptyList(),
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): JSONObject? {
        val items = pickDeck(
            count = 1, subjects = subjects, levels = levels, selectedLevel = selectedLevel,
            banks = banks, spaced = spaced, wrongFirst = wrongFirst, coverageFirst = coverageFirst,
            newFirst = newFirst, excludeId = excludeId, onlyWrong = onlyWrong, missedOnly = missedOnly,
            minStar = minStar, excludeIds = excludeIds, child = child,
            gradeSubjects = gradeSubjects, explicitSubjectFilter = explicitSubjectFilter
        )
        return if (items.length() > 0) items.getJSONObject(0) else null
    }

    /**
     * 为「先背再测」或闪卡学习一次性抽取一组不重复题目。
     */
    fun pickDeck(
        count: Int,
        subjects: List<String> = emptyList(),
        levels: List<String> = emptyList(),
        selectedLevel: String = "",
        banks: List<String> = emptyList(),
        spaced: Boolean = false,
        wrongFirst: Boolean = false,
        coverageFirst: Boolean = false,
        newFirst: Boolean = false,
        excludeId: String? = null,
        onlyWrong: Boolean = false,
        missedOnly: Boolean = false,
        minStar: Int = 1,
        excludeIds: Collection<String> = emptyList(),
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): JSONArray {
        val where = StringBuilder("questions.enabled=1")
        val args = mutableListOf<String>()
        appendScopeFilters(where, args, subjects, levels, selectedLevel, banks, gradeSubjects, explicitSubjectFilter)
        // (二改) 最低星级 / 本轮已出过的题不再重复（调用方在挑不出题时会放宽）
        if (minStar > 1) { where.append(" AND questions.difficulty>=?"); args.add(minStar.toString()) }
        if (excludeIds.isNotEmpty()) {
            where.append(" AND questions.id NOT IN (${placeholders(excludeIds.size)})")
            args.addAll(excludeIds)
        }
        if (!excludeId.isNullOrEmpty()) {
            where.append(" AND questions.id!=?")
            args.add(excludeId)
        }
        if (onlyWrong) {
            where.append(" AND stats.last_correct=0")
        } else if (missedOnly) {
            where.append(" AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
        }
        val joinArgs = mutableListOf<String>()
        val progressJoin = if (spaced) {
            joinArgs.add(child)
            " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
        } else ""
        joinArgs.add(child)
        val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val order = buildQuestionOrder(spaced, "random", wrongFirst, coverageFirst, newFirst)
        return readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT ${count.coerceIn(1, 100)}",
            (joinArgs + args).toTypedArray()
        )
    }

    /** 孩子自选年级时只查去重后的非空年级列，不再加载整套题库。 */
    fun distinctLevels(
        allowedLevels: List<String> = emptyList(),
        subjects: List<String> = emptyList(),
        banks: List<String> = emptyList(),
        gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): List<String> {
        val where = StringBuilder("questions.enabled=1 AND TRIM(COALESCE(questions.level,''))!=''")
        val args = mutableListOf<String>()
        appendScopeFilters(where, args, subjects, allowedLevels, "", banks, gradeSubjects, explicitSubjectFilter)
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT questions.level FROM questions WHERE $where",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val lvl = c.getString(0)?.trim().orEmpty()
                if (lvl.isNotEmpty()) out.add(lvl)
            }
        }
        return out.distinct().sorted()
    }

    /** 按题号读取单道题目（供精准加练队列优先出题使用）。 */
    fun questionById(id: String, enabledOnly: Boolean = true, child: String = activeChild()): JSONObject? {
        if (id.isBlank()) return null
        val enabledClause = if (enabledOnly) " AND questions.enabled=1" else ""
        val arr = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE questions.id=?$enabledClause LIMIT 1",
            arrayOf(child, id)
        )
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    /** 当前孩子的精准加练队列（KV JSON 数组，保序去重）。 */
    fun extraQueue(child: String = activeChild()): List<String> =
        parseExtraQueue(rawGet(scoped("extra_queue", child), "[]"))

    /** 将有效且已启用的题号去重追加到当前孩子加练队列，返回实际新增条数。 */
    fun addToExtraQueue(ids: List<String>, child: String = activeChild()): Int {
        val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return 0
        val valid = LinkedHashSet<String>()
        val chunkSize = 500
        for (start in cleaned.indices step chunkSize) {
            val chunk = cleaned.subList(start, minOf(start + chunkSize, cleaned.size))
            readableDatabase.rawQuery(
                "SELECT id FROM questions WHERE enabled=1 AND id IN (${placeholders(chunk.size)})",
                chunk.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { valid.add(it) }
            }
        }
        val orderedValid = cleaned.filter { it in valid }
        if (orderedValid.isEmpty()) return 0
        val existing = extraQueue(child)
        val (merged, added) = mergeExtraQueue(existing, orderedValid)
        if (added > 0) {
            rawSet(scoped("extra_queue", child), JSONArray(merged).toString())
        }
        return added
    }

    /** 将指定题号移出当前孩子加练队列；若存在并移出返回 true。 */
    fun popExtraQueue(id: String, child: String = activeChild()): Boolean {
        val target = id.trim()
        if (target.isEmpty()) return false
        val existing = extraQueue(child)
        if (target !in existing) return false
        val remaining = existing.filter { it != target }
        rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
        return true
    }

    /** 清空当前孩子的精准加练队列。 */
    fun clearExtraQueue(child: String = activeChild()) {
        rawSet(scoped("extra_queue", child), "[]")
    }

    /**
     * 错题库查询（ST-601 / #159）：以 question_stats.last_correct=0 为唯一真源，按孩子隔离。
     */
    fun listWrongQuestions(
        subject: String? = null,
        level: String? = null,
        bank: String? = null,
        q: String? = null,
        child: String = activeChild()
    ): JSONObject {
        val where = StringBuilder("questions.enabled=1 AND stats.last_correct=0")
        val args = mutableListOf(child)
        if (!subject.isNullOrBlank()) {
            where.append(" AND questions.subject=?")
            args.add(subject.trim())
        }
        if (!level.isNullOrBlank()) {
            where.append(" AND questions.level=?")
            args.add(level.trim())
        }
        if (!bank.isNullOrBlank()) {
            val b = bank.trim()
            if (b == "默认题库") {
                where.append(" AND (questions.bank=? OR questions.bank='')")
            } else {
                where.append(" AND questions.bank=?")
            }
            args.add(b)
        }
        val keyword = q?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            val like = "%${escapeLike(keyword)}%"
            where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
            repeat(5) { args.add(like) }
        }
        val items = JSONArray()
        readableDatabase.rawQuery(
            "SELECT questions.*,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_at,0) AS last_at," +
                "COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE $where ORDER BY stats.last_at DESC, (stats.answered - stats.correct) DESC, questions.rowid DESC LIMIT 500",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val details = c.getString(c.getColumnIndexOrThrow("details"))
                val full = if (details.isNullOrEmpty()) JSONObject() else try { JSONObject(details) } catch (_: Exception) { JSONObject() }
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val stem = c.getString(c.getColumnIndexOrThrow("stem"))
                val options = try { JSONArray(c.getString(c.getColumnIndexOrThrow("options"))) } catch (_: Exception) { JSONArray() }
                val k = c.getInt(c.getColumnIndexOrThrow("answer"))
                val tip = c.getString(c.getColumnIndexOrThrow("tip")) ?: ""
                val subj = c.getString(c.getColumnIndexOrThrow("subject")) ?: ""
                val lvl = c.getString(c.getColumnIndexOrThrow("level")) ?: ""
                val bankName = (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { full.optString("bank").ifBlank { "默认题库" } }
                val type = (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { full.optString("type").ifBlank { "single" } }
                full.put("a", options).put("k", k).put("type", type)
                val answered = c.getInt(c.getColumnIndexOrThrow("answered_count"))
                val correct = c.getInt(c.getColumnIndexOrThrow("correct_count"))
                val lastAt = c.getLong(c.getColumnIndexOrThrow("last_at"))
                val lastCorrect = c.getInt(c.getColumnIndexOrThrow("last_correct"))
                val wrongCount = (answered - correct).coerceAtLeast(if (lastCorrect == 0) 1 else 0)
                items.put(
                    JSONObject()
                        .put("id", id)
                        .put("q", stem)
                        .put("type", type)
                        .put("subject", subj)
                        .put("level", lvl)
                        .put("bank", bankName)
                        .put("options", options)
                        .put("answer", LearningEngine.formatAnswerDisplay(full))
                        .put("explain", tip)
                        .put("answered", answered)
                        .put("correct", correct)
                        .put("wrongCount", wrongCount)
                        .put("lastAt", lastAt)
                        .put("lastCorrect", lastCorrect == 1)
                )
            }
        }
        val extraPool = rawGet(scoped("extra_pool", child), "0").toLongOrNull() ?: 0L
        val extraQueueIds = extraQueue(child)
        return JSONObject()
            .put("items", items)
            .put("total", items.length())
            .put("extraPool", extraPool)
            .put("extraQueueIds", JSONArray(extraQueueIds))
    }

    /**
     * 非破坏性“标为已掌握”：不删除原题目，仅将 question_stats.last_correct 置为 1 并对齐 correct>=answered，
     * 使 wrongCount 与 onlyWrong/missedOnly 查询均不再命中，同时将其移出 extra_queue。
     */
    fun markWrongMastered(ids: List<String> = emptyList(), all: Boolean = false, child: String = activeChild()): Int {
        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            if (all) {
                val masteredIds = ArrayList<String>()
                db.rawQuery(
                    "SELECT qid FROM question_stats WHERE child=? AND (last_correct=0 OR answered>correct)",
                    arrayOf(child)
                ).use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { masteredIds.add(it) }
                }
                val stmt = db.compileStatement(
                    "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                        "WHERE child=? AND (last_correct=0 OR answered>correct)"
                )
                try {
                    stmt.bindString(1, child)
                    updated = stmt.executeUpdateDelete()
                } finally {
                    stmt.close()
                }
                if (masteredIds.isNotEmpty()) {
                    val remaining = extraQueue(child).filter { it !in masteredIds.toSet() }
                    rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
                }
            } else {
                val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (cleaned.isNotEmpty()) {
                    val stmt = db.compileStatement(
                        "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                            "WHERE qid=? AND child=? AND (last_correct=0 OR answered>correct)"
                    )
                    try {
                        for (qid in cleaned) {
                            stmt.clearBindings()
                            stmt.bindString(1, qid)
                            stmt.bindString(2, child)
                            updated += stmt.executeUpdateDelete()
                        }
                    } finally {
                        stmt.close()
                    }
                    val cleanedSet = cleaned.toSet()
                    val existing = extraQueue(child)
                    if (existing.any { it in cleanedSet }) {
                        rawSet(scoped("extra_queue", child), JSONArray(existing.filter { it !in cleanedSet }).toString())
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    /** 供 AI 错题举一反三抽取典型薄弱题样本（优先当前错题，其次累计失误次数多的题）。 */
    fun wrongQuestionsSample(subject: String? = null, limit: Int = 8, child: String = activeChild()): List<JSONObject> {
        val where = StringBuilder("questions.enabled=1 AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
        val args = mutableListOf(child)
        if (!subject.isNullOrBlank()) {
            where.append(" AND questions.subject=?")
            args.add(subject.trim())
        }
        val arr = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "WHERE $where ORDER BY CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END, " +
                "(stats.answered-stats.correct) DESC, stats.last_at DESC LIMIT ${limit.coerceIn(1, 20)}",
            args.toTypedArray()
        )
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun questions(
        subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
        enabledOnly: Boolean = true, sortBy: String = "random", wrongFirst: Boolean = false,
        coverageFirst: Boolean = false, newFirst: Boolean = false, child: String = activeChild()
    ): JSONArray {
        val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
        val joinArgs = mutableListOf<String>()
        val progressJoin = if (spaced) {
            joinArgs.add(child)
            " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
        } else ""
        joinArgs.add(child)
        val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val order = buildQuestionOrder(spaced, sortBy, wrongFirst, coverageFirst, newFirst)
        return readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 50000)}",
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
                    .put("bank", (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { item.optString("bank").ifBlank { "默认题库" } })
                    .put("enabled", c.getInt(c.getColumnIndexOrThrow("enabled")) != 0)
                    .put("stats", JSONObject().put("presented", c.getInt(c.getColumnIndexOrThrow("presented")))
                        .put("answered", c.getInt(c.getColumnIndexOrThrow("answered_count")))
                        .put("correct", c.getInt(c.getColumnIndexOrThrow("correct_count")))
                        .put("lastCorrect", c.getInt(c.getColumnIndexOrThrow("last_correct")).let { if (it < 0) JSONObject.NULL else it == 1 }))
                if (!item.has("type")) item.put("type", (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { "single" })
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

    /** 包含已淘汰的题；用来判断是否需要导入内置题库（淘汰完不能再重新导入一遍）。 */
    fun countAllQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** LIKE 里的通配符要转义，否则家长搜索「100%」会当成通配符扫全库。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 家长面板题库页用的分页查询：筛选、搜索、排序、分页全部在 SQL 里做，一次只取一页。
     * `inlineMedia = true` 用于导出题库 JSON 时将本地媒体路径还原为可跨设备导入的 Data URL。
     */
    fun questionPage(
        subject: String? = null, level: String? = null, bank: String? = null, search: String? = null,
        type: String? = null, difficulty: Int? = null, wrongOnly: Boolean = false, enabledOnly: Boolean = true,
        sortBy: String = "newest", limit: Int = 50, offset: Int = 0, inlineMedia: Boolean = false,
        child: String = activeChild()
    ): JSONObject {
        val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
        if (!bank.isNullOrEmpty()) { where.append(" AND questions.bank=?"); args.add(bank) }
        if (!type.isNullOrEmpty()) { where.append(" AND questions.type=?"); args.add(type) }
        if (difficulty != null) { where.append(" AND questions.difficulty=?"); args.add(difficulty.toString()) }
        if (wrongOnly) where.append(" AND stats.last_correct=0")
        val keyword = search?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            val like = "%${escapeLike(keyword)}%"
            where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
            repeat(5) { args.add(like) }
        }
        val source = "questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
        val queryArgs = (listOf(child) + args).toTypedArray()
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM $source WHERE $where", queryArgs).use {
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
        val rawItems = readQuestions(
            "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
                "FROM $source WHERE $where ORDER BY $order LIMIT $page OFFSET $start",
            queryArgs
        )
        val items = if (!inlineMedia) rawItems else JSONArray().also { arr ->
            for (i in 0 until rawItems.length()) arr.put(MediaStore.inlineQuestionMedia(mediaDir, rawItems.getJSONObject(i)))
        }
        return JSONObject().put("items", items).put("total", total).put("offset", start).put("limit", page)
    }

    /** 题库页的筛选项和计数：题库名 / 科目 / 年级 + 总题数、启用、暂停、当前孩子的错题数。 */
    fun questionMeta(child: String = activeChild()): JSONObject {
        val banks = sortedSetOf<String>()
        val subjects = sortedSetOf<String>()
        val levels = sortedSetOf<String>()
        var total = 0
        var enabled = 0
        var wrong = 0
        readableDatabase.rawQuery(
            "SELECT questions.bank,questions.subject,questions.level,questions.enabled," +
                "CASE WHEN stats.last_correct=0 THEN 1 ELSE 0 END AS wrong " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?",
            arrayOf(child)
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
     * 答题覆盖：口径是当前孩子「答对过一次」（stats.correct>0），和出题时的覆盖优先用同一套判断。
     */
    fun coverage(
        sample: Int = 20,
        child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap()
    ): JSONObject {
        val banks = LinkedHashMap<String, IntArray>()
        val pending = ArrayList<JSONObject>()
        var total = 0
        var covered = 0
        var untouched = 0
        val where = StringBuilder("questions.enabled=1")
        val args = mutableListOf(child)
        val filter = buildScopeFilter(gradeSubjects = gradeSubjects)
        where.append(filter.clause)
        args.addAll(filter.args)
        readableDatabase.rawQuery(
            "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank," +
                "COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
                "COALESCE(stats.correct,0) AS correct_count " +
                "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? WHERE $where",
            args.toTypedArray()
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

    /** 连续学习天数：截至今天（今天尚未学习时延续昨天）连续每天至少有 1 条作答或闪卡背诵记录。 */
    private fun computeStreakDays(child: String): Int {
        val todayStart = startOfToday(child)
        val cutoff = todayStart - 366L * 86_400_000L
        val activeDays = HashSet<Int>()
        val cal = calendarFor(child)
        readableDatabase.rawQuery(
            "SELECT ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
            arrayOf(child, cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                cal.timeInMillis = c.getLong(0)
                val key = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
                activeDays.add(key)
            }
        }
        if (activeDays.isEmpty()) return 0
        val cursor = calendarFor(child).apply { timeInMillis = todayStart }
        fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        if (dayKey(cursor) !in activeDays) {
            cursor.add(Calendar.DAY_OF_YEAR, -1)
            if (dayKey(cursor) !in activeDays) return 0
        }
        var streak = 0
        while (dayKey(cursor) in activeDays) {
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** 学习主页摘要卡片（Anki / 多邻国风格：连胜天数、今日题量、待复习、错题数、已掌握题数）。 */
    fun studySummary(child: String = activeChild()): JSONObject {
        val nowMs = clockReading().millis
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        var totalQuestions = 0
        var dueCount = 0
        var wrongCount = 0
        var masteredCount = 0
        readableDatabase.rawQuery(
            "SELECT COALESCE(stats.last_correct,-1),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
                "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
                "FROM questions " +
                "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
                "WHERE questions.enabled=1",
            arrayOf(child, child)
        ).use { c ->
            while (c.moveToNext()) {
                totalQuestions++
                val lastCorrect = c.getInt(0)
                val answered = c.getInt(1)
                val correct = c.getInt(2)
                val hasProgress = c.getInt(3) != 0
                val intervalDays = c.getLong(4)
                val dueAt = c.getLong(5)
                if (lastCorrect == 0) wrongCount++
                if (hasProgress && intervalDays >= 21) masteredCount++
                if (hasProgress && dueAt <= nowMs && inSpacedScope(wrongOnly, lastCorrect, answered, correct)) dueCount++
            }
        }
        var todayAnswered = 0
        var todayCorrect = 0
        readableDatabase.rawQuery(
            "SELECT payload FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
            arrayOf(child, startOfToday(child).toString())
        ).use { c ->
            while (c.moveToNext()) {
                todayAnswered++
                val p = try { JSONObject(c.getString(0)) } catch (_: Exception) { JSONObject() }
                if (p.optBoolean("correct")) todayCorrect++
            }
        }
        return JSONObject()
            .put("streakDays", computeStreakDays(child))
            .put("todayAnswered", todayAnswered)
            .put("todayCorrect", todayCorrect)
            .put("dueCount", dueCount)
            .put("wrongCount", wrongCount)
            .put("masteredCount", masteredCount)
            .put("totalQuestions", totalQuestions)
    }

    /** 家长端完整学情分析：记忆成熟度分布、学科/题库掌握率、近 N 日趋势、高频错题与未来 7 天复习预测。 */
    fun learningAnalytics(days: Int = 14, child: String = activeChild()): JSONObject {
        val windowDays = days.coerceIn(7, 60)
        val nowMs = clockReading().millis
        val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
        val childZone = timeZoneFor(child)
        val todayStart = startOfDayMillis(nowMs, childZone)

        class SubjectStat(val subject: String) {
            var total = 0; var answered = 0; var correct = 0; var wrong = 0; var mastered = 0
            val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
        }
        class BankStat(val bank: String) {
            var total = 0; var covered = 0; var answered = 0; var correct = 0; var wrong = 0
            val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
        }

        val subjects = LinkedHashMap<String, SubjectStat>()
        val banks = LinkedHashMap<String, BankStat>()
        val hardCandidates = ArrayList<Pair<Long, JSONObject>>()

        // 未来 7 天每天的起止时刻（按该孩子配置的时区切分日界线）
        val forecastBounds = Array(7) { offset ->
            val dayStart = Calendar.getInstance(childZone).apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val start = dayStart.timeInMillis
            val label = when (offset) {
                0 -> "今天"
                1 -> "明天"
                else -> "${dayStart.get(Calendar.MONTH) + 1}/${dayStart.get(Calendar.DAY_OF_MONTH)}"
            }
            dayStart.add(Calendar.DAY_OF_YEAR, 1)
            Triple(start, dayStart.timeInMillis, label)
        }
        val forecastCounts = IntArray(7)

        var totalQuestions = 0
        var unseen = 0
        var learning = 0
        var young = 0
        var mature = 0
        var studiedQuestions = 0
        var wrongCount = 0
        var dueNow = 0
        var totalAnswered = 0
        var totalCorrect = 0

        readableDatabase.rawQuery(
            "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank,questions.type,questions.tip," +
                "COALESCE(stats.presented,0),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
                "COALESCE(stats.last_at,0),COALESCE(stats.last_correct,-1)," +
                "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
                "FROM questions " +
                "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
                "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
                "WHERE questions.enabled=1",
            arrayOf(child, child)
        ).use { c ->
            while (c.moveToNext()) {
                totalQuestions++
                val qid = c.getString(0) ?: ""
                val stem = c.getString(1) ?: ""
                val rawSubject = (c.getString(2) ?: "").trim()
                val subjectKey = rawSubject.ifEmpty { "未分类" }
                val level = c.getString(3) ?: ""
                val bankKey = (c.getString(4) ?: "").trim().ifEmpty { "默认题库" }
                val type = (c.getString(5) ?: "").ifBlank { "single" }
                val tip = c.getString(6) ?: ""
                val presented = c.getInt(7)
                val answered = c.getInt(8)
                val correct = c.getInt(9)
                val lastAt = c.getLong(10)
                val lastCorrect = c.getInt(11)
                val hasProgress = c.getInt(12) != 0
                val intervalDays = c.getLong(13)
                val dueAt = c.getLong(14)

                totalAnswered += answered
                totalCorrect += correct
                if (lastCorrect == 0) wrongCount++

                val isMastered = hasProgress && intervalDays >= 21
                if (!hasProgress) {
                    unseen++
                } else {
                    when {
                        intervalDays <= 1L -> learning++
                        intervalDays <= 20L -> young++
                        else -> mature++
                    }
                    if (inSpacedScope(wrongOnly, lastCorrect, answered, correct)) {
                        if (dueAt <= nowMs) dueNow++
                        for (d in 0 until 7) {
                            val (fStart, fEnd, _) = forecastBounds[d]
                            val hit = if (d == 0) dueAt < fEnd else dueAt in fStart until fEnd
                            if (hit) { forecastCounts[d]++; break }
                        }
                    }
                }
                if (hasProgress || answered > 0) studiedQuestions++

                subjects.getOrPut(subjectKey) { SubjectStat(subjectKey) }.apply {
                    total++
                    this.answered += answered
                    this.correct += correct
                    if (lastCorrect == 0) wrong++
                    if (isMastered) mastered++
                }
                banks.getOrPut(bankKey) { BankStat(bankKey) }.apply {
                    total++
                    this.answered += answered
                    this.correct += correct
                    if (correct > 0) covered++
                    if (lastCorrect == 0) wrong++
                }

                val wrongTimes = answered - correct
                if (wrongTimes > 0) {
                    val sortScore = wrongTimes.toLong() * 1_000_000_000_000L + (lastAt.coerceAtLeast(0L) / 1000L)
                    hardCandidates.add(
                        sortScore to JSONObject()
                            .put("id", qid).put("q", stem).put("subject", rawSubject).put("level", level)
                            .put("bank", bankKey).put("type", type).put("presented", presented)
                            .put("answered", answered).put("correct", correct).put("wrongTimes", wrongTimes)
                            .put("lastCorrect", if (lastCorrect < 0) JSONObject.NULL else (lastCorrect == 1))
                            .put("tip", tip)
                    )
                }
            }
        }

        // 近 max(windowDays, 14) 天的每日答题、闪卡与观看时长统计
        val querySpan = maxOf(windowDays, 14)
        class DayBucket(val date: String, val label: String, val daysAgo: Int) {
            var answered = 0; var correct = 0; var flashcards = 0; var usedMs = 0L
        }
        val dayMap = LinkedHashMap<String, DayBucket>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = childZone
        }
        var earliestStart = todayStart
        for (ago in (querySpan - 1) downTo 0) {
            val dCal = Calendar.getInstance(childZone).apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, -ago)
            }
            if (ago == querySpan - 1) earliestStart = dCal.timeInMillis
            val dateStr = dayFmt.format(Date(dCal.timeInMillis))
            val label = when (ago) {
                0 -> "今天"
                1 -> "昨天"
                else -> "${dCal.get(Calendar.MONTH) + 1}/${dCal.get(Calendar.DAY_OF_MONTH)}"
            }
            dayMap[dateStr] = DayBucket(dateStr, label, ago)
        }

        readableDatabase.rawQuery(
            "SELECT type,payload,ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard','usage')",
            arrayOf(child, earliestStart.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val evType = c.getString(0) ?: continue
                val payload = try { JSONObject(c.getString(1) ?: "{}") } catch (_: Exception) { JSONObject() }
                val dateStr = dayFmt.format(Date(c.getLong(2)))
                val bucket = dayMap[dateStr] ?: continue
                when (evType) {
                    "usage" -> bucket.usedMs += payload.optLong("milliseconds")
                    "answer" -> {
                        bucket.answered++
                        if (payload.optBoolean("correct")) bucket.correct++
                    }
                    "flashcard" -> {
                        bucket.flashcards++
                        bucket.answered++
                        if (payload.optBoolean("correct")) bucket.correct++
                    }
                }
            }
        }

        var recent7Ans = 0; var recent7Cor = 0
        var prev7Ans = 0; var prev7Cor = 0
        var todayAnswered = 0; var todayCorrect = 0
        val dailyArr = JSONArray()
        for (bucket in dayMap.values) {
            if (bucket.daysAgo == 0) {
                todayAnswered = bucket.answered
                todayCorrect = bucket.correct
            }
            if (bucket.daysAgo in 0..6) {
                recent7Ans += bucket.answered
                recent7Cor += bucket.correct
            } else if (bucket.daysAgo in 7..13) {
                prev7Ans += bucket.answered
                prev7Cor += bucket.correct
            }
            if (bucket.daysAgo < windowDays) {
                dailyArr.put(
                    JSONObject()
                        .put("date", bucket.date)
                        .put("label", bucket.label)
                        .put("answered", bucket.answered)
                        .put("correct", bucket.correct)
                        .put("accuracy", if (bucket.answered > 0) Math.round(bucket.correct * 100f / bucket.answered) else 0)
                        .put("watchMinutes", Math.round(bucket.usedMs / 6000.0) / 10.0)
                        .put("flashcards", bucket.flashcards)
                )
            }
        }

        val bySubjectArr = JSONArray()
        subjects.values.sortedWith(
            compareByDescending<SubjectStat> { it.wrong }
                .thenBy { if (it.answered > 0) it.accuracy else 101 }
                .thenByDescending { it.total }
        ).forEach { s ->
            bySubjectArr.put(
                JSONObject()
                    .put("subject", s.subject).put("total", s.total)
                    .put("answered", s.answered).put("correct", s.correct)
                    .put("wrong", s.wrong).put("accuracy", s.accuracy).put("mastered", s.mastered)
            )
        }

        val byBankArr = JSONArray()
        banks.values.sortedByDescending { it.total }.forEach { b ->
            byBankArr.put(
                JSONObject()
                    .put("bank", b.bank).put("total", b.total)
                    .put("covered", b.covered).put("wrong", b.wrong).put("accuracy", b.accuracy)
            )
        }

        hardCandidates.sortByDescending { it.first }
        val hardArr = JSONArray()
        for (i in 0 until minOf(20, hardCandidates.size)) hardArr.put(hardCandidates[i].second)

        val forecastArr = JSONArray()
        for (d in 0 until 7) {
            forecastArr.put(
                JSONObject()
                    .put("dayOffset", d)
                    .put("label", forecastBounds[d].third)
                    .put("dueCount", forecastCounts[d])
            )
        }

        val overview = JSONObject()
            .put("totalQuestions", totalQuestions)
            .put("studiedQuestions", studiedQuestions)
            .put("masteredQuestions", mature)
            .put("learningQuestions", (studiedQuestions - mature).coerceAtLeast(0))
            .put("wrongCount", wrongCount)
            .put("dueNow", dueNow)
            .put("streakDays", computeStreakDays(child))
            .put("totalAnswered", totalAnswered)
            .put("totalCorrect", totalCorrect)
            .put("accuracyPercent", if (totalAnswered > 0) Math.round(totalCorrect * 100f / totalAnswered) else 0)
            .put("recent7dAccuracy", if (recent7Ans > 0) Math.round(recent7Cor * 100f / recent7Ans) else 0)
            .put("prev7dAccuracy", if (prev7Ans > 0) Math.round(prev7Cor * 100f / prev7Ans) else 0)
            .put("todayAnswered", todayAnswered)
            .put("todayCorrect", todayCorrect)

        val maturity = JSONObject()
            .put("unseen", unseen)
            .put("learning", learning)
            .put("young", young)
            .put("mature", mature)

        return JSONObject()
            .put("overview", overview)
            .put("maturity", maturity)
            .put("daily", dailyArr)
            .put("bySubject", bySubjectArr)
            .put("byBank", byBankArr)
            .put("hardQuestions", hardArr)
            .put("forecast", forecastArr)
    }

    fun upsertQuestion(item: JSONObject): String {
        val stem = item.optString("q").trim()
        require(stem.isNotEmpty() && stem.length <= 10000) { "请填写题干" }
        val type = item.optString("type", "single")
        require(type in listOf("single", "multiple", "boolean", "text", "subjective")) { "不支持此题型" }
        val options = if (type == "boolean") JSONArray(listOf("正确", "错误")) else item.optJSONArray("a") ?: JSONArray()
        val rawOptionImages = item.optJSONArray("optionImages")
        val normalizedOptionImages: JSONArray? = if (rawOptionImages != null) {
            require(rawOptionImages.length() <= 8) { "选项配图不能超过 8 张" }
            JSONArray().also { arr ->
                for (i in 0 until rawOptionImages.length()) {
                    val img = rawOptionImages.optString(i).trim()
                    arr.put(if (img.isEmpty()) "" else MediaStore.normalizeImageRef(mediaDir, img, label = "选项 ${'A' + i} 配图"))
                }
            }
        } else null
        if (type != "text" && type != "subjective") {
            require(options.length() in 2..8) { "需要 2–8 个选项" }
            for (i in 0 until options.length()) {
                val optImg = normalizedOptionImages?.optString(i).orEmpty()
                require(options.getString(i).isNotBlank() || optImg.isNotEmpty()) { "选项不能为空" }
            }
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
        if (normalizedOptionImages != null) details.put("optionImages", normalizedOptionImages) else details.remove("optionImages")
        val bank = item.optString("bank", "默认题库").trim()
        require(bank.isNotEmpty() && bank.length <= 64) { "题库名称需要 1–64 个字" }
        details.put("bank", bank)
        val rawImage = item.optString("image").trim()
        details.put("image", if (rawImage.isEmpty()) "" else MediaStore.normalizeImageRef(mediaDir, rawImage, label = "图片"))
        val rawAudio = item.optString("audio").trim()
        require(rawAudio.isEmpty() || ((rawAudio.startsWith("https://") || rawAudio.startsWith("http://")) && rawAudio.length <= 2048)) { "图片或音频地址无效" }
        details.put("audio", rawAudio)
        // 听力题：listen 只交给朗读，屏幕上不显示，等于「声音和文字解耦」；与音频地址二选一
        val listen = item.optString("listen").trim()
        require(listen.isEmpty() || listen.length <= 1000) { "听力文本不能超过 1000 字" }
        require(listen.isEmpty() || rawAudio.isEmpty()) { "听力文本和音频地址只能二选一" }
        details.put("listen", listen)
        if (listen.isEmpty()) {
            details.remove("listenTimes"); details.remove("listenRequired"); details.remove("listenRate")
        } else {
            details.put("listenTimes", item.optInt("listenTimes", 5).coerceIn(1, 5))
            details.put("listenRequired", item.optBoolean("listenRequired", false))
            val listenRate = item.optDouble("listenRate", -1.0)
            if (listenRate.isFinite() && listenRate in 0.7..1.5) details.put("listenRate", listenRate) else details.remove("listenRate")
        }
        if (item.has("images")) {
            val rawImages = item.optJSONArray("images") ?: JSONArray()
            require(rawImages.length() <= 4) { "每道题最多支持 4 张配图" }
            val validImages = JSONArray()
            for (i in 0 until rawImages.length()) {
                val value = rawImages.optString(i).trim()
                if (value.isEmpty()) continue
                validImages.put(MediaStore.normalizeImageRef(mediaDir, value, label = "第 ${i + 1} 张配图"))
            }
            details.put("images", validImages)
            if (details.optString("image").isEmpty() && validImages.length() > 0) {
                details.put("image", validImages.getString(0))
            }
        }
        val language = item.optString("language", "zh-CN")
        require(language in SpeechReader.LANGUAGES) { "朗读/识别语言需要为中文、粤语或英文，留空表示跟随系统" }
        details.put("language", language)
        val enabled = if (!item.has("enabled") || item.optBoolean("enabled", true)) 1 else 0
        val createdAt = item.optLong("createdAt", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
            put("id", id); put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "")); put("difficulty", difficulty)
            put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
            put("tip", item.optString("tip")); put("enabled", enabled); put("created_at", createdAt)
            put("details", details.toString())
            put("bank", bank); put("type", type)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    /** 扫描所有仍在被引用的本地媒体文件并删除孤儿图片。只应在数据库最外层事务提交后调用。 */
    fun pruneMedia(): Int {
        if (!mediaDir.exists()) return 0
        val referenced = HashSet<String>()
        val db = readableDatabase
        db.rawQuery("SELECT details FROM questions WHERE details LIKE '%media%'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
        }
        db.rawQuery("SELECT payload FROM reviews WHERE payload LIKE '%media%'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
        }
        db.rawQuery("SELECT v FROM kv WHERE v LIKE '%media%'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
        }
        return MediaStore.pruneUnreferenced(mediaDir, referenced)
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
        val deleted: Int
        db.beginTransaction()
        try {
            deleted = db.delete("questions", null, null)
            db.delete("question_progress", null, null)
            db.delete("question_stats", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (!db.inTransaction()) pruneMedia()
        return deleted
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
        if (!db.inTransaction()) pruneMedia()
        return count
    }

    fun deleteQuestion(id: String): Boolean = deleteQuestions(listOf(id)) > 0

    fun setEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try {
            ids.forEach { id ->
                count += db.update("questions", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    // ---- 整机备份与恢复（换电视 / 跨设备迁移，含多孩子、规则、题库内联媒体与学习记录，自动脱敏密钥） ----

    fun exportBackup(includeQuestions: Boolean = true): JSONObject {
        ensureDefaultChild()
        val kvObj = JSONObject()
        readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: continue
                if (k in SECRET_KV_KEYS || isEphemeralKvKey(k)) continue
                kvObj.put(k, c.getString(1) ?: "")
            }
        }
        val exportedQuestions = JSONArray()
        if (includeQuestions) {
            val all = readQuestions(
                "SELECT questions.*,0 AS presented,0 AS answered_count,0 AS correct_count,-1 AS last_correct FROM questions ORDER BY rowid ASC",
                emptyArray()
            )
            for (i in 0 until all.length()) {
                val q = MediaStore.inlineQuestionMedia(mediaDir, all.getJSONObject(i))
                q.remove("stats")
                exportedQuestions.put(q)
            }
        }
        val statsArr = JSONArray()
        readableDatabase.rawQuery("SELECT qid,child,presented,answered,correct,last_at,last_correct FROM question_stats", null).use { c ->
            while (c.moveToNext()) {
                statsArr.put(
                    JSONObject()
                        .put("qid", c.getString(0)).put("child", c.getString(1) ?: "")
                        .put("presented", c.getInt(2)).put("answered", c.getInt(3))
                        .put("correct", c.getInt(4)).put("lastAt", c.getLong(5))
                        .put("lastCorrect", c.getInt(6))
                )
            }
        }
        val progressArr = JSONArray()
        readableDatabase.rawQuery("SELECT qid,child,repetitions,interval_days,ease,due_at,updated_at FROM question_progress", null).use { c ->
            while (c.moveToNext()) {
                progressArr.put(
                    JSONObject()
                        .put("qid", c.getString(0)).put("child", c.getString(1) ?: "")
                        .put("repetitions", c.getInt(2)).put("intervalDays", c.getLong(3))
                        .put("ease", c.getDouble(4)).put("dueAt", c.getLong(5))
                        .put("updatedAt", c.getLong(6))
                )
            }
        }
        val eventsArr = JSONArray()
        readableDatabase.rawQuery("SELECT type,payload,ts,child FROM events ORDER BY id DESC LIMIT 5000", null).use { c ->
            while (c.moveToNext()) {
                val rawPayload = c.getString(1) ?: "{}"
                val parsedPayload: Any = try { JSONObject(rawPayload) } catch (_: Exception) { rawPayload }
                eventsArr.put(
                    JSONObject()
                        .put("type", c.getString(0) ?: "")
                        .put("payload", parsedPayload)
                        .put("ts", c.getLong(2))
                        .put("child", c.getString(3) ?: "")
                )
            }
        }
        val reviewsArr = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM reviews ORDER BY ts DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                val r = try { JSONObject(c.getString(0) ?: "{}") } catch (_: Exception) { continue }
                r.optJSONObject("input")?.let { input ->
                    val d = input.optString("drawing")
                    if (d.isNotEmpty()) {
                        val inlined = MediaStore.toDataUrl(mediaDir, d)
                        if (inlined.isEmpty()) input.remove("drawing") else input.put("drawing", inlined)
                    }
                }
                r.optJSONObject("question")?.let { q ->
                    r.put("question", MediaStore.inlineQuestionMedia(mediaDir, q))
                }
                reviewsArr.put(r)
            }
        }
        val bankAuto = BankAutomation.settings(this).apply { remove("running") }
        return JSONObject()
            .put("format", "studyontv-backup")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("deviceName", Prefs.deviceName(appContext))
            .put("includeQuestions", includeQuestions)
            .put("activeChild", activeChild())
            .put("children", children())
            .put("kv", kvObj)
            .put("questions", exportedQuestions)
            .put("questionStats", statsArr)
            .put("questionProgress", progressArr)
            .put("events", eventsArr)
            .put("reviews", reviewsArr)
            .put("speech", SpeechReader.settings(this))
            .put("grading", GradingService.settings(this, includeKey = false))
            .put("bankAutomation", bankAuto)
    }

    fun exportScopedSync(scopes: Set<String>): JSONObject {
        val validScopes = normalizeSyncScopes(scopes)
        require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
        ensureDefaultChild()
        val source = JSONObject()
            .put("format", "studyontv-backup")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
        if ("config" in validScopes) {
            source.put("children", children())
            val kvObj = JSONObject()
            readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c ->
                while (c.moveToNext()) {
                    val k = c.getString(0) ?: continue
                    if (!isConfigSyncKvKey(k)) continue
                    kvObj.put(k, c.getString(1) ?: "")
                }
            }
            source.put("kv", kvObj)
        }
        if ("questions" in validScopes) {
            val exportedQuestions = JSONArray()
            val all = readQuestions(
                "SELECT questions.*,0 AS presented,0 AS answered_count,0 AS correct_count,-1 AS last_correct FROM questions ORDER BY rowid ASC",
                emptyArray()
            )
            for (i in 0 until all.length()) {
                val q = MediaStore.inlineQuestionMedia(mediaDir, all.getJSONObject(i))
                q.remove("stats")
                exportedQuestions.put(q)
            }
            source.put("questions", exportedQuestions)
        }
        if ("speech" in validScopes) {
            source.put("speech", SpeechReader.settings(this))
        }
        if ("grading" in validScopes) {
            source.put("grading", GradingService.settings(this, includeKey = false))
        }
        return buildScopedSyncPayload(source, validScopes)
    }

    fun applyScopedSync(payload: JSONObject, scopes: Set<String>, mode: String = "merge"): JSONObject {
        require(payload.optString("format") == "studyontv-backup") { "不是有效的题了个题同步数据包" }
        require(mode in listOf("replace", "merge")) { "同步模式仅支持 replace 或 merge" }
        val validScopes = normalizeSyncScopes(scopes)
        require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }

        val replaceQuestions = mode == "replace"
        val db = writableDatabase
        val existingGrading = try { JSONObject(rawGet("grading_settings", "{}")) } catch (_: Exception) { JSONObject() }
        val existingSpeech = try { JSONObject(rawGet("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }
        var syncedQuestions = 0

        synchronized(childLock) {
            db.beginTransaction()
            try {
                if ("config" in validScopes) {
                    payload.optJSONArray("children")?.let { arr ->
                        for (i in 0 until minOf(arr.length(), MAX_CHILDREN)) {
                            val c = arr.optJSONObject(i) ?: continue
                            val id = c.optString("id")
                            if (!isValidChildId(id)) continue
                            val name = c.optString("name").trim().ifEmpty { "孩子${i + 1}" }.take(12)
                            db.insertWithOnConflict("children", null, ContentValues().apply {
                                put("id", id)
                                put("name", name)
                                put("created_at", c.optLong("createdAt", System.currentTimeMillis()))
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    ensureDefaultChild()

                    payload.optJSONObject("kv")?.let { kv ->
                        val keys = kv.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            // 严格白名单：只允许学习规则 config / child:<id>:config，绝不写入任何单机运行态或密码键
                            if (!isConfigSyncKvKey(k)) continue
                            val sanitized = sanitizeKvValueForLocalOnly(k, kv.optString(k, ""))
                            db.insertWithOnConflict("kv", null, ContentValues().apply {
                                put("k", k); put("v", sanitized)
                            }, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }

                if ("questions" in validScopes) {
                    payload.optJSONArray("questions")?.let { arr ->
                        if (replaceQuestions) {
                            db.delete("questions", null, null)
                        }
                        for (i in 0 until minOf(arr.length(), 50000)) {
                            val q = arr.optJSONObject(i) ?: continue
                            val qid = q.optString("id")
                            if (!replaceQuestions && qid.isNotEmpty() && questionExists(qid)) continue
                            upsertQuestion(q)
                            syncedQuestions++
                        }
                    }
                }

                if ("speech" in validScopes) {
                    payload.optJSONObject("speech")?.let { s ->
                        val next = JSONObject(s.toString())
                        for (uiKey in listOf("deepseekInherited", "deepseekReady")) next.remove(uiKey)
                        for (secret in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
                            next.remove(secret + "Set")
                            if (next.optString(secret).isBlank() && existingSpeech.optString(secret).isNotBlank()) {
                                next.put(secret, existingSpeech.getString(secret))
                            }
                        }
                        rawSet("speech_settings", next.toString())
                    }
                }

                if ("grading" in validScopes) {
                    payload.optJSONObject("grading")?.let { g ->
                        val next = JSONObject(g.toString())
                        for (uiKey in listOf("hasKey", "chatUrl", "providers", "clearKey")) next.remove(uiKey)
                        if (next.optString("apiKey").isBlank() && existingGrading.optString("apiKey").isNotBlank()) {
                            next.put("apiKey", existingGrading.getString("apiKey"))
                        }
                        rawSet("grading_settings", next.toString())
                    }
                }

                configCache.clear()
                cachedChild = null
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if ("questions" in validScopes && !db.inTransaction()) pruneMedia()
        val totalQ = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return JSONObject()
            .put("ok", true)
            .put("scopes", JSONArray(validScopes.sorted()))
            .put("mode", mode)
            .put("children", children().length())
            .put("questions", totalQ)
            .put("syncedQuestions", syncedQuestions)
    }

    fun restoreBackup(backup: JSONObject, mode: String = "replace", scopes: Set<String>? = null): JSONObject {
        if (scopes != null) return applyScopedSync(backup, scopes, mode)
        require(backup.optString("format") == "studyontv-backup") { "不是有效的题了个题备份文件" }
        require(mode in listOf("replace", "merge")) { "恢复模式仅支持 replace 或 merge" }
        val replace = mode == "replace"
        val db = writableDatabase

        // 先保存本机已有的 API Key，防止脱敏备份把本机配好的密钥冲成空
        val existingGrading = try { JSONObject(rawGet("grading_settings", "{}")) } catch (_: Exception) { JSONObject() }
        val existingSpeech = try { JSONObject(rawGet("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }

        // 统一锁顺序：永远先 childLock、再 SQLite 写事务，避免与 switchChild()/activeChild() 产生 AB-BA 死锁
        synchronized(childLock) {
            db.beginTransaction()
            try {
            if (replace) {
                db.delete("children", null, null)
                db.delete("events", null, null)
                db.delete("reviews", null, null)
                db.delete("question_progress", null, null)
                db.delete("question_stats", null, null)
                db.delete("kv", "k NOT IN ('admin_token','grading_settings','speech_settings','onboarded')", null)
                if (backup.optBoolean("includeQuestions", true) && backup.has("questions")) {
                    db.delete("questions", null, null)
                }
            }

            backup.optJSONArray("children")?.let { arr ->
                for (i in 0 until minOf(arr.length(), MAX_CHILDREN)) {
                    val c = arr.optJSONObject(i) ?: continue
                    val id = c.optString("id")
                    if (!isValidChildId(id)) continue
                    val name = c.optString("name").trim().ifEmpty { "孩子${i + 1}" }.take(12)
                    db.insertWithOnConflict("children", null, ContentValues().apply {
                        put("id", id)
                        put("name", name)
                        put("created_at", c.optLong("createdAt", System.currentTimeMillis()))
                    }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            ensureDefaultChild()

            backup.optJSONObject("kv")?.let { kv ->
                val keys = kv.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in SECRET_KV_KEYS || isEphemeralKvKey(k)) continue
                    val sanitized = sanitizeKvValueForLocalOnly(k, kv.optString(k, ""))
                    db.insertWithOnConflict("kv", null, ContentValues().apply {
                        put("k", k); put("v", sanitized)
                    }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
                }
            }

            val wantedActive = backup.optString("activeChild", DEFAULT_CHILD)
            if (replace && childExists(wantedActive)) {
                rawSet("active_child", wantedActive)
            }

            backup.optJSONArray("questions")?.let { arr ->
                val conflictMode = !replace
                for (i in 0 until minOf(arr.length(), 50000)) {
                    val q = arr.optJSONObject(i) ?: continue
                    val qid = q.optString("id")
                    if (conflictMode && qid.isNotEmpty() && questionExists(qid)) continue
                    upsertQuestion(q)
                }
            }

            val conflictPolicy = if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
            backup.optJSONArray("questionStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val qid = s.optString("qid").takeIf { it.isNotEmpty() } ?: continue
                    db.insertWithOnConflict("question_stats", null, ContentValues().apply {
                        put("qid", qid)
                        put("child", s.optString("child", DEFAULT_CHILD))
                        put("presented", s.optInt("presented", 0).coerceAtLeast(0))
                        put("answered", s.optInt("answered", 0).coerceAtLeast(0))
                        put("correct", s.optInt("correct", 0).coerceAtLeast(0))
                        put("last_at", s.optLong("lastAt", 0L))
                        put("last_correct", s.optInt("lastCorrect", -1).coerceIn(-1, 1))
                    }, conflictPolicy)
                }
            }

            backup.optJSONArray("questionProgress")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val qid = p.optString("qid").takeIf { it.isNotEmpty() } ?: continue
                    db.insertWithOnConflict("question_progress", null, ContentValues().apply {
                        put("qid", qid)
                        put("child", p.optString("child", DEFAULT_CHILD))
                        put("repetitions", p.optInt("repetitions", 0).coerceAtLeast(0))
                        put("interval_days", p.optLong("intervalDays", 0L).coerceAtLeast(0L))
                        put("ease", p.optDouble("ease", 2.5).takeIf { it.isFinite() }?.coerceAtLeast(1.3) ?: 2.5)
                        put("due_at", p.optLong("dueAt", 0L))
                        put("updated_at", p.optLong("updatedAt", 0L))
                    }, conflictPolicy)
                }
            }

            backup.optJSONArray("events")?.let { arr ->
                // 导出是 DESC 顺序，倒序插回保持自增 id 与时间顺序一致
                for (i in minOf(arr.length(), 5000) - 1 downTo 0) {
                    val ev = arr.optJSONObject(i) ?: continue
                    val type = ev.optString("type").takeIf { it.isNotEmpty() } ?: continue
                    val payloadObj = ev.optJSONObject("payload")
                    val payloadStr = payloadObj?.toString() ?: ev.optString("payload", "{}")
                    val ts = ev.optLong("ts", System.currentTimeMillis())
                    val child = ev.optString("child", DEFAULT_CHILD)
                    if (!replace) {
                        val dup = db.rawQuery(
                            "SELECT 1 FROM events WHERE type=? AND ts=? AND child=? LIMIT 1",
                            arrayOf(type, ts.toString(), child)
                        ).use { it.moveToFirst() }
                        if (dup) continue
                    }
                    db.execSQL("INSERT INTO events(type,payload,ts,child) VALUES(?,?,?,?)", arrayOf<Any>(type, payloadStr, ts, child))
                }
            }

            backup.optJSONArray("reviews")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 200)) {
                    val r = JSONObject((arr.optJSONObject(i) ?: continue).toString())
                    val id = r.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val nonce = r.optJSONObject("input")?.optString("nonce")?.takeIf { it.isNotEmpty() } ?: continue
                    if (!replace && review(id) != null) continue
                    r.optJSONObject("input")?.let { input ->
                        val d = input.optString("drawing").trim()
                        if (d.startsWith("data:image/", ignoreCase = true)) {
                            val saved = migrateDataUrlField(d)
                            if (saved.isEmpty()) input.remove("drawing") else input.put("drawing", saved)
                        }
                    }
                    r.optJSONObject("question")?.let { q -> migrateQuestionObjectMedia(q) }
                    db.insertWithOnConflict("reviews", null, ContentValues().apply {
                        put("id", id)
                        put("nonce", nonce)
                        put("state", r.optString("state", "pending"))
                        put("payload", r.toString())
                        put("ts", r.optLong("ts", System.currentTimeMillis()))
                    }, conflictPolicy)
                }
            }

            backup.optJSONObject("grading")?.let { g ->
                if (replace || rawGet("grading_settings").isEmpty()) {
                    val next = JSONObject(g.toString())
                    for (uiKey in listOf("hasKey", "chatUrl", "providers", "clearKey")) next.remove(uiKey)
                    if (next.optString("apiKey").isBlank() && existingGrading.optString("apiKey").isNotBlank()) {
                        next.put("apiKey", existingGrading.getString("apiKey"))
                    }
                    rawSet("grading_settings", next.toString())
                }
            }

            backup.optJSONObject("speech")?.let { s ->
                if (replace || rawGet("speech_settings").isEmpty()) {
                    val next = JSONObject(s.toString())
                    for (secret in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
                        next.remove(secret + "Set")
                        if (next.optString(secret).isBlank() && existingSpeech.optString(secret).isNotBlank()) {
                            next.put(secret, existingSpeech.getString(secret))
                        }
                    }
                    rawSet("speech_settings", next.toString())
                }
            }

            backup.optJSONObject("bankAutomation")?.let { ba ->
                if (replace || rawGet("bank_automation").isEmpty()) {
                    val next = sanitizeBankAutomationForLocalOnly(ba)
                    rawSet("bank_automation", next.toString())
                }
            }

            if (replace) {
                val devName = backup.optString("deviceName").trim()
                if (devName.length in 1..32) Prefs.setDeviceName(appContext, devName)
            }

            configCache.clear()
            cachedChild = null
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        }
        if (!db.inTransaction()) pruneMedia()
        val totalQ = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val totalEv = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val totalRev = readableDatabase.rawQuery("SELECT COUNT(*) FROM reviews", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return JSONObject()
            .put("ok", true)
            .put("children", children().length())
            .put("questions", totalQ)
            .put("events", totalEv)
            .put("reviews", totalRev)
    }

    fun logEvent(type: String, payload: JSONObject) {
        writableDatabase.execSQL("INSERT INTO events(type,payload,ts,child) VALUES(?,?,?,?)",
            arrayOf<Any>(type, payload.toString(), clockReading().millis, activeChild()))
    }

    fun recentEvents(limit: Int = 50): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT type,payload,ts FROM events WHERE type!='usage' AND child=? ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}",
            arrayOf(activeChild())).use { c ->
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
                val p = try { JSONObject(c.getString(1)) } catch (_: Exception) { JSONObject() }
                when (c.getString(0)) {
                    "usage" -> usedMs += p.optLong("milliseconds")
                    "answer", "flashcard" -> { answered++; if (p.optBoolean("correct")) correct++ }
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
    fun roundsDoneToday(dayStart: Long = startOfToday()): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=? AND child=?",
        arrayOf(dayStart.toString(), activeChild())).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun historyDays(days: Int = 7): JSONArray {
        val out = JSONArray()
        val today = todayStart()
        for (d in 0 until days.coerceIn(1, 90)) {
            val cal = today.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, -d)
            val start = cal.timeInMillis
            val label = if (d == 0) "今天" else if (d == 1) "昨天" else "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            cal.add(Calendar.DAY_OF_YEAR, 1)
            out.put(stats(start, cal.timeInMillis).put("label", label))
        }
        return out
    }

    fun timeZoneFor(child: String = activeChild()): TimeZone =
        resolveTimeZone(try { config(child).optString("timezone") } catch (_: Exception) { "" })

    fun calendarFor(child: String = activeChild()): Calendar = Calendar.getInstance(timeZoneFor(child)).apply {
        timeInMillis = clockReading().millis
    }

    private fun todayStart(child: String = activeChild()): Calendar = calendarFor(child).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    @JvmOverloads
    fun startOfToday(child: String = activeChild()): Long = startOfDayMillis(clockReading().millis, timeZoneFor(child))
}
