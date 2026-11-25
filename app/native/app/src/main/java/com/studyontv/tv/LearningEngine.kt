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

class LearningEngine(
    private val store: LocalStore,
    private val usageDayStart: () -> Long = store::startOfToday
) {
    companion object {
        private val SECRET_QUESTION_FIELDS = listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric")

        /** 闪卡自评的纯状态机计划：在写库前统一完成轮次匹配、卡组归属与同轮幂等去重校验。 */
        internal data class FlashcardRatePlan(
            val quality: Int,
            val correct: Boolean,
            val alreadyRated: Boolean,
            val finished: Boolean,
            val updatedRatedIds: List<String>,
            val updatedCorrectIds: List<String>,
            val deckSize: Int
        )

        internal fun planFlashcardRate(round: JSONObject?, roundId: String, qid: String, quality: Int): FlashcardRatePlan {
            val q = quality.coerceIn(1, 5)
            val correct = q >= 3
            if (roundId.isNotEmpty()) {
                require(round != null && round.optString("id") == roundId) { "任务已更新，请重新加载" }
            }
            if (round == null) {
                return FlashcardRatePlan(q, correct, alreadyRated = false, finished = false, emptyList(), emptyList(), 0)
            }
            val deck = round.optJSONArray("studyDeck")
            val deckIds = LinkedHashSet<String>()
            if (deck != null) {
                for (i in 0 until deck.length()) {
                    val id = deck.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotEmpty()) deckIds.add(id)
                }
            }
            if (deckIds.isNotEmpty()) {
                require(qid in deckIds) { "该题目不在当前闪卡轮次中" }
            }
            val ratedArr = round.optJSONArray("ratedIds") ?: JSONArray()
            val ratedSet = LinkedHashSet<String>()
            for (i in 0 until ratedArr.length()) {
                val id = ratedArr.optString(i)
                if (id.isNotEmpty()) ratedSet.add(id)
            }
            val correctArr = round.optJSONArray("ratedCorrectIds") ?: JSONArray()
            val correctSet = LinkedHashSet<String>()
            for (i in 0 until correctArr.length()) {
                val id = correctArr.optString(i)
                if (id.isNotEmpty()) correctSet.add(id)
            }
            if (qid in ratedSet) {
                return FlashcardRatePlan(q, correct, alreadyRated = true, finished = false, ratedSet.toList(), correctSet.toList(), deckIds.size)
            }
            ratedSet.add(qid)
            if (correct) correctSet.add(qid) else correctSet.remove(qid)
            val finished = round.optString("focus") == "memorize" && deckIds.isNotEmpty() && ratedSet.containsAll(deckIds)
            return FlashcardRatePlan(q, correct, alreadyRated = false, finished = finished, ratedSet.toList(), correctSet.toList(), deckIds.size)
        }

        /** 本轮主阶段是否已达标（nextQuestion 与 answer 的唯一真理源）。 */
        internal fun isBaseDone(round: JSONObject): Boolean {
            val target = round.optInt("target")
            return when (round.optString("mode")) {
                "streak" -> round.optInt("streak") >= target
                "count" -> round.optInt("correct") >= target
                else -> round.optInt("answered") >= target
            }
        }

        /** 是否处于自主学习/非管控轮次（纯刷题模式、自由时段或主动发起的学习）。 */
        internal fun isVoluntary(round: JSONObject, supervisionEnabled: Boolean): Boolean =
            round.optBoolean("voluntary") || !supervisionEnabled

        /** 当前轮次是否启用错题加练机制。 */
        internal fun isExtraEnabled(round: JSONObject, supervisionEnabled: Boolean): Boolean {
            if (isVoluntary(round, supervisionEnabled)) return false
            if (round.optString("mode") == "count") return false
            return round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
        }

        /** 当前是否应进入错题加练出题阶段（统一约束 extraEnabled、extraPool 与 baseDone）。 */
        internal fun isExtraStage(round: JSONObject, extraPool: Long, supervisionEnabled: Boolean): Boolean {
            if (!isExtraEnabled(round, supervisionEnabled) || extraPool <= 0L) return false
            return round.optString("mode") == "practice" || isBaseDone(round)
        }

        /** 从精准加练队列中挑选下一道有效题号，同时收集已失效（被删或暂停）的题号以便清理。 */
        internal fun selectNextExtraQueueId(
            queueIds: List<String>,
            lastId: String?,
            isValid: (String) -> Boolean
        ): Pair<String?, List<String>> {
            val stale = mutableListOf<String>()
            var fallback: String? = null
            for (qid in queueIds) {
                if (!isValid(qid)) {
                    stale.add(qid)
                    continue
                }
                if (qid != lastId) return qid to stale
                if (fallback == null) fallback = qid
            }
            return fallback to stale
        }

        /** 清空加练池时对进行中轮次的收敛动作，防止电视端卡在「加练中 0/0」。 */
        internal enum class ClearExtraRoundAction { NONE, SETTLE_PASS, SETTLE_FAIL, CLEAR_EXTRA_FLAG }

        internal fun planClearExtraActiveRound(round: JSONObject?, supervisionEnabled: Boolean): ClearExtraRoundAction {
            if (round == null || round.has("pendingReview")) return ClearExtraRoundAction.NONE
            val mode = round.optString("mode")
            if (mode == "practice") {
                return if (round.optBoolean("extra")) ClearExtraRoundAction.CLEAR_EXTRA_FLAG else ClearExtraRoundAction.NONE
            }
            if (!isBaseDone(round)) {
                return if (round.optBoolean("extra")) ClearExtraRoundAction.CLEAR_EXTRA_FLAG else ClearExtraRoundAction.NONE
            }
            val rules = round.optJSONObject("rules")
            val passRatio = rules?.optDouble("passRatio", 0.6) ?: 0.6
            val passed = mode in listOf("streak", "count") ||
                round.optInt("correct") >= ceil(round.optInt("target") * passRatio).toInt()
            return if (passed) ClearExtraRoundAction.SETTLE_PASS else ClearExtraRoundAction.SETTLE_FAIL
        }

        /** 当前是否存在可用观看额度（答题赚取额度 或 每日/开机免费额度）。 */
        internal fun hasUsableWatchQuota(remainingMs: Long, freeQuotaMs: Long): Boolean =
            (remainingMs / 1000L) > 0L || (freeQuotaMs / 1000L) > 0L

        /** 统一 canExit 判定：非管控模式、或无阻断原因且处于自由放行/拥有可用观看额度（含免费额度）。 */
        internal fun computeCanExit(
            supervision: Boolean,
            reason: String,
            remainingMs: Long,
            freeQuotaMs: Long,
            free: Boolean
        ): Boolean = !supervision || (reason.isEmpty() && (free || hasUsableWatchQuota(remainingMs, freeQuotaMs)))

        /** 在 9 天窗口内查找下一个开放半小时槽；若周计划全禁（无任何 '1'）返回 0L，避免误报当前时刻或明日零点。 */
        internal fun findNextAvailableSlotMillis(
            slots: List<String>,
            nowMs: Long,
            tomorrow: Boolean,
            timeZone: java.util.TimeZone,
            dayIndexOf: (Calendar) -> Int
        ): Long {
            val base = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMs
                if (tomorrow) {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                }
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            for (offset in 0..8) {
                val day = (base.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val index = dayIndexOf(day)
                val daySlots = slots.getOrNull(index) ?: continue
                for (slot in 0 until minOf(48, daySlots.length)) {
                    if (daySlots[slot] != '1') continue
                    val candidate = (day.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, slot / 2); set(Calendar.MINUTE, (slot % 2) * 30)
                    }.timeInMillis
                    if (candidate > nowMs) return candidate
                }
            }
            return 0L
        }

        /** 把题目正确答案格式化成闪卡背面可直接阅读的答案文本。 */
        internal fun formatAnswerDisplay(question: JSONObject): String = when (val type = question.optString("type", "single")) {
            "boolean" -> {
                val opts = question.optJSONArray("a")
                val k = question.optInt("k", 0)
                opts?.optString(k).orEmpty().ifEmpty { if (k == 0) "正确" else "错误" }
            }
            "multiple" -> {
                val opts = question.optJSONArray("a")
                val indices = question.optJSONArray("correctIndices") ?: JSONArray()
                (0 until indices.length()).map { i ->
                    val k = indices.optInt(i)
                    val label = ('A' + k).toString()
                    val text = opts?.optString(k).orEmpty().trim()
                    if (text.isEmpty()) label else "$label. $text"
                }.joinToString(" / ")
            }
            "text" -> {
                val answers = question.optJSONArray("answers") ?: JSONArray()
                (0 until answers.length()).map { answers.optString(it).trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
            }
            "subjective" -> question.optString("modelAnswer").trim()
            else -> {
                val opts = question.optJSONArray("a")
                val k = question.optInt("k", 0)
                val label = ('A' + k).toString()
                val text = opts?.optString(k).orEmpty().trim()
                if (text.isEmpty()) label else "$label. $text"
            }
        }

        private fun isCjkChar(c: Char): Boolean =
            c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'

        /**
         * 去掉两个汉字之间的空白字符。
         * 纯字符区间循环实现，避免 Android ICU 正则不支持 OpenJDK 的 `\p{IsHan}` 导致 `<clinit>` 闪退（ST-608）。
         */
        internal fun collapseCjkSpaces(text: String): String {
            if (text.isEmpty()) return ""
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isWhitespace()) {
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    val prevIsCjk = sb.isNotEmpty() && isCjkChar(sb[sb.length - 1])
                    val nextIsCjk = j < text.length && isCjkChar(text[j])
                    if (!(prevIsCjk && nextIsCjk)) {
                        sb.append(text, i, j)
                    }
                    i = j
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        internal fun normalizeTextExact(value: String): String =
            Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .lowercase(java.util.Locale.ROOT)
                .replace(Regex("\\s+"), " ")

        /**
         * 简答/填空判分归一化：排除自然语言标点（中英文句号、逗号、顿号、问号、感叹号、引号、括号等），
         * 同时保留小数点（数字间的 '.'）与数学语义符号（+ - * / % = < > ^），避免误伤数值/公式答案。
         */
        internal fun normalizeTextIgnoringPunctuation(value: String): String {
            val base = normalizeTextExact(value)
            if (base.isEmpty()) return ""
            val sb = StringBuilder(base.length)
            for (i in base.indices) {
                val c = base[i]
                val keepMath = c == '+' || c == '-' || c == '*' || c == '/' ||
                    c == '%' || c == '=' || c == '<' || c == '>' || c == '^'
                val keepDecimal = c == '.' &&
                    i > 0 && i + 1 < base.length &&
                    base[i - 1].isDigit() && base[i + 1].isDigit()
                if (keepMath || keepDecimal) {
                    sb.append(c)
                    continue
                }
                val type = Character.getType(c).toByte()
                val isPunctuation = type == Character.DASH_PUNCTUATION ||
                    type == Character.START_PUNCTUATION ||
                    type == Character.END_PUNCTUATION ||
                    type == Character.CONNECTOR_PUNCTUATION ||
                    type == Character.OTHER_PUNCTUATION ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION ||
                    type == Character.FINAL_QUOTE_PUNCTUATION
                sb.append(if (isPunctuation) ' ' else c)
            }
            return collapseCjkSpaces(
                sb.toString()
                    .trim()
                    .replace(Regex("\\s+"), " ")
            )
        }

        internal fun matchesTextAnswer(actual: String, expected: String): Boolean {
            val exactActual = normalizeTextExact(actual)
            val exactExpected = normalizeTextExact(expected)
            if (exactActual.isEmpty() || exactExpected.isEmpty()) return false
            if (exactActual == exactExpected) return true
            val strippedActual = normalizeTextIgnoringPunctuation(actual)
            val strippedExpected = normalizeTextIgnoringPunctuation(expected)
            return strippedActual.isNotEmpty() && strippedActual == strippedExpected
        }
    }

    @Volatile var exitSnapshot = LearningExitSnapshot(false, 0)
        private set
    private fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())
    private val time = TimePolicy(store, wallTime = { store.clockReading().millis }) { bootCount() }
    private val videos = VideoLessons(store)
    private var lastLoggedRollbackDay: Long? = null

    /** Cache publication must not turn a committed operation into a reported failure. */
    private fun publishNavigation() {
        try {
            if (!store.writableDatabase.inTransaction()) status()
        } catch (e: Exception) {
            exitSnapshot = LearningExitSnapshot(false, android.os.SystemClock.elapsedRealtime())
            android.util.Log.w("StudyOnTV", "Navigation snapshot unavailable", e)
        }
    }

    @Synchronized
    fun switchChild(id: String) {
        flushUsage()
        store.switchChild(id)
        status()
    }

    @Synchronized
    fun deleteChild(id: String): Boolean {
        flushUsage()
        val deleted = store.deleteChild(id)
        status()
        return deleted
    }

    /**
     * 今天还能扣掉的额度：每日上限 - 已用，并且不能超过「距离必须休息」的额度。
     */
    private fun capacity(config: JSONObject): Long {
        if (time.left("feedback_deadline") > 0) return 0
        return minOf(
            (time.dailyLimit(limitConfig(config)) - number("used_ms")).coerceAtLeast(0),
            time.beforeRest(config)
        )
    }

    /** 测试可以固定时钟（例如验证跨午夜窗口）；生产环境统一由 DeviceClock 取时。 */
    internal var now: () -> Calendar = { DeviceClock.calendar(store) }

    /** 一个命中的网课时段或大人豁免窗口。 */
    private class Window(val name: String, val days: Set<Int>, val start: Int, val end: Int,
                         val mode: String, val dailyMinutes: Int, val until: String)

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

    private fun resolve(config: JSONObject, cal: Calendar): Window? {
        val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (w in windows(config, "exemptWindows", "exempt")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        for (w in windows(config, "timePoints", "free")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
        return null
    }

    private fun activeWindow(config: JSONObject): Window? = resolve(config, now())

    private fun limitConfig(config: JSONObject): JSONObject {
        val point = activeWindow(config) ?: return config
        if (point.mode != "counted") return config
        return JSONObject(config.toString()).put("dailyMinutes", point.dailyMinutes)
    }

    private fun freeBalance(): Long = number("free_quota_ms") + number("boot_quota_ms")

    private fun usableFree(config: JSONObject): Long = min(freeBalance(), capacity(config))

    private fun usableCredit(config: JSONObject): Long =
        min(number("credit_ms"), (capacity(config) - usableFree(config)).coerceAtLeast(0))

    @Synchronized fun awake(awake: Boolean) { day(); time.awake(store.config(), awake); publishNavigation() }

    private fun day() {
        val db = store.writableDatabase
        // 结算在开启事务前确定用量日期，事务中的 status() 不能重新日切。
        if (db.inTransaction()) return
        val current = usageDayStart()
        val previousUsageDay = store.get("usage_day").toLongOrNull()
        // 部分设备冷启动会先使用出厂日期，校时后才恢复。回拨时保留原来的用量日期和余额。
        if (previousUsageDay != null && current < previousUsageDay && lastLoggedRollbackDay != previousUsageDay) {
            lastLoggedRollbackDay = previousUsageDay
            Diag.warn("Day", "检测到日期回拨，保留记账日和额度 previous=$previousUsageDay current=$current")
        }
        if (previousUsageDay == null || current > previousUsageDay) {
            db.beginTransaction()
            try {
                if (pendingUsageMs > 0) store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
                put("usage_day", current)
                put("credit_ms", 0)
                put("used_ms", 0)
                put("cap_adjust_ms", 0)
                store.set("video_lesson", "")
                if (time.left("rest_deadline") == 0L) put("rest_watched_ms", 0)
                val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
                put("free_quota_ms", freeMin * 60000L)
                put("free_used_ms", 0)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            pendingUsageMs = 0
        } else if (store.get("free_used_ms").isEmpty()) {
            // 旧版本升级：没有 free_used_ms，用当天已用时长估算已消耗的免费额度。
            // 免费额度总是先于奖励余额扣除，所以这个估算不会把奖励时间算成免费时间。
            val daily = store.config().optLong("dailyFreeMinutes", 0L) * 60000L
            put("free_used_ms", min(number("used_ms"), daily).coerceAtLeast(0))
        }
    }

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
        } finally { db.endTransaction(); publishNavigation() }
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

    private fun inWindow(config: JSONObject): Boolean = allowedAt(config, now())

    private fun scheduleText(config: JSONObject): String {
        if (config.optBoolean("allDay")) return "全天可用"
        val slots = weekSlots(config) ?: return ""
        val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val parts = mutableListOf<String>()
        var start = 0
        while (start < 7) {
            var end = start
            while (end + 1 < 7 && slots[end + 1] == slots[start]) end++
            val label = when (end - start) {
                0 -> labels[start]
                1 -> labels[start] + "、" + labels[end]
                else -> labels[start] + "至" + labels[end]
            }
            val ranges = slotRanges(slots[start])
            parts += if (ranges.isEmpty()) "$label 不可用" else label + " " + ranges.joinToString("、")
            start = end + 1
        }
        val text = parts.joinToString("，")
        return if (text.length <= 80) text else text.take(78) + "…"
    }

    private fun slotRanges(day: String): List<String> {
        fun clock(slot: Int) = "%02d:%02d".format(slot / 2, (slot % 2) * 30)
        val out = mutableListOf<String>()
        var i = 0
        while (i < 48) {
            if (day[i] != '1') { i++; continue }
            var j = i
            while (j + 1 < 48 && day[j + 1] == '1') j++
            out += clock(i) + "–" + clock(j + 1)
            i = j + 1
        }
        return out
    }

    private fun nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
        val currentCal = now()
        val nowMs = currentCal.timeInMillis
        val base = (currentCal.clone() as Calendar).apply {
            if (tomorrow) {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            }
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (config.optBoolean("allDay")) return base.timeInMillis
        val slots = weekSlots(config) ?: return base.timeInMillis
        return findNextAvailableSlotMillis(slots, nowMs, tomorrow, currentCal.timeZone) { dayIndex(config, it) }
    }

    private fun scheduleFree(c: JSONObject): Boolean =
        c.optString("scheduleMode", "allow") == "control" && !c.optBoolean("allDay") && !inWindow(c)

    @Synchronized
    fun override(): JSONObject? {
        val raw = store.get("remote_override").takeIf { it.isNotEmpty() } ?: return null
        val o = try { JSONObject(raw) } catch (_: Exception) { store.set("remote_override", ""); return null }
        val until = o.optLong("until")
        val mode = o.optString("mode")
        if (mode == "indefinite") return o.put("remainingSeconds", -1)
        if (until == 0L) return o.put("remainingSeconds", -1)
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
            "clear_extra" -> {
                val cleared = clearExtraPool().optLong("cleared", 0L)
                store.logEvent("remote", JSONObject().put("action", action).put("cleared", cleared).put("source", source))
                return status()
            }
            else -> throw IllegalArgumentException("未知操作")
        }
        flushUsage()
        store.logEvent("remote", JSONObject().put("action", action).put("minutes", minutes).put("source", source))
        return status()
    }

    /** ST-601：将错题库中选中的有效题目去重加入精准加练队列，并同步增加加练池计数。 */
    @Synchronized
    fun addWrongToExtraPool(ids: List<String>): JSONObject {
        day()
        val db = store.writableDatabase
        var added = 0
        var nextPool = 0L
        var queue: List<String> = emptyList()
        db.beginTransaction()
        try {
            added = store.addToExtraQueue(ids)
            queue = store.extraQueue()
            val currentPool = number("extra_pool")
            nextPool = maxOf(currentPool + added.toLong(), queue.size.toLong())
            put("extra_pool", nextPool)
            if (added > 0) {
                store.logEvent("extra_added", JSONObject().put("count", added).put("source", "wrong_book"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            publishNavigation()
        }
        return JSONObject()
            .put("ok", true)
            .put("added", added)
            .put("extraPool", nextPool)
            .put("extraQueueIds", JSONArray(queue))
            .put("learning", status())
    }

    /** ST-602：原子清空加练池、错题累积进度与精准加练队列；若当前轮次处于加练阶段则妥善收敛不卡死。 */
    @Synchronized
    fun clearExtraPool(): JSONObject {
        day()
        val db = store.writableDatabase
        var cleared = 0L
        db.beginTransaction()
        try {
            cleared = number("extra_pool")
            put("extra_pool", 0)
            put("wrong_progress", 0)
            store.clearExtraQueue()

            val round = active()
            val config = store.config()
            val supervisionEnabled = config.optBoolean("supervisionEnabled", true)
            when (planClearExtraActiveRound(round, supervisionEnabled)) {
                ClearExtraRoundAction.SETTLE_PASS, ClearExtraRoundAction.SETTLE_FAIL -> {
                    val r = round!!
                    val passed = planClearExtraActiveRound(r, supervisionEnabled) == ClearExtraRoundAction.SETTLE_PASS
                    val voluntary = isVoluntary(r, supervisionEnabled)
                    val rules = r.optJSONObject("rules") ?: config
                    var grant = 0L
                    if (passed && !voluntary) {
                        val requested = if (rules.optBoolean("difficultyRewardEnabled")) r.optLong("earnedMs")
                        else rules.optLong("sessionMinutes", 10) * 60000L
                        val budget = time.dailyLimit(limitConfig(config)) - number("used_ms") - number("credit_ms") - freeBalance()
                        grant = min(requested, budget.coerceAtLeast(0))
                        put("credit_ms", number("credit_ms") + grant)
                        store.logEvent("quiz_pass", JSONObject().put("roundId", r.optString("id")).put("minutes", grant / 60000.0))
                    }
                    store.logEvent("quiz", JSONObject().put("native", true).put("voluntary", voluntary)
                        .put("total", r.optInt("answered")).put("correct", r.optInt("correct")).put("passed", passed))
                    store.set("active_round", "")
                    put("quiz_generation", number("quiz_generation") + 1)
                }
                ClearExtraRoundAction.CLEAR_EXTRA_FLAG -> {
                    val r = round!!
                    r.put("extra", false)
                    store.set("active_round", r.toString())
                    put("quiz_generation", number("quiz_generation") + 1)
                }
                ClearExtraRoundAction.NONE -> Unit
            }

            store.logEvent("extra_pool_cleared", JSONObject().put("cleared", cleared))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            publishNavigation()
        }
        return JSONObject()
            .put("ok", true)
            .put("cleared", cleared)
            .put("extraPool", 0)
            .put("learning", status())
    }

    private fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(DeviceRuntime.appContext.contentResolver, "boot_count", -1)
    } catch (_: Throwable) { -1 }

    /** 纯刷题背诵模式（supervisionEnabled=false）下不启用时长锁屏，优先于一切管控规则放行。 */
    private fun freeNow(c: JSONObject, point: Window? = activeWindow(c)): Boolean {
        if (!c.optBoolean("supervisionEnabled", true)) return true
        val o = override()
        if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
        if (point != null) return point.mode != "counted"
        return scheduleFree(c)
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val inTransaction = store.writableDatabase.inTransaction()
        val c = store.config()
        val supervision = c.optBoolean("supervisionEnabled", true)
        val remaining = usableCredit(c)
        val used = number("used_ms")
        val settledUsageDay = if (inTransaction) store.get("usage_day").toLongOrNull() else null
        val rounds = if (settledUsageDay == null) store.roundsDoneToday() else store.roundsDoneToday(settledUsageDay)
        val o = if (supervision) override() else null
        val point = if (supervision) activeWindow(c) else null
        val limit = if (point?.mode == "counted") JSONObject(c.toString()).put("dailyMinutes", point.dailyMinutes) else c
        val free = freeNow(c, point)
        val freeQuota = usableFree(c)
        val rest = if (supervision) time.restLeft(c) else 0L
        val hold = time.left("feedback_deadline")
        val reason = when {
            !supervision -> if (hold > 0) "feedback" else ""
            o?.optString("mode") == "lock" -> "locked"
            free -> ""
            rest > 0 -> "rest"
            hold > 0 -> "feedback"
            used >= time.dailyLimit(limit) -> "quota"
            c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
            freeQuota > 0 -> ""
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") && remaining <= 0 -> "rounds"
            c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") -> ""
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
            else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        // 每日免费、开机免费和奖励额度都可以放行返回键和 Home 键。
        val canExit = computeCanExit(supervision, reason, remaining, freeQuota, free)
        val result = JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("supervisionEnabled", supervision)
            .put("quizGeneration", number("quiz_generation"))
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt).put("nextAtText", if (nextAt > 0) DeviceClock.format(nextAt, store) else "").put("free", free)
            .put("restSeconds", (rest + 999) / 1000).put("feedbackSeconds", (hold + 999) / 1000)
            .put("todayLimitMinutes", if (supervision && c.optBoolean("dailyLimitEnabled", true)) time.dailyLimit(limit) / 60000.0 else JSONObject.NULL)
            .put("bootFreeSeconds", min(number("boot_quota_ms"), freeQuota) / 1000)
            .put("freeQuotaSeconds", freeQuota / 1000)
            .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", active()?.has("pendingReview") == true)
            .put("points", PointsSync.brief(store)).put("version", BuildConfig.VERSION_NAME).put("monitor", GateService.monitorMode).put("diag", GateService.diag)
            // 学生端用 activePoint 显示「网课时间 · 数学」/「大人豁免 · 看电影」
            .put("activePoint", point?.let { JSONObject().put("name", it.name).put("mode", it.mode).put("until", it.until) } ?: JSONObject.NULL)
            .put("canExit", canExit)
            .put("scheduleText", scheduleText(c))
        // A result computed inside a transaction must not expose an uncommitted reward/unlock.
        if (!inTransaction)
            exitSnapshot = LearningExitSnapshot(canExit, android.os.SystemClock.elapsedRealtime())
        return result
    }

    private var pendingUsageMs = 0L

    @Synchronized
    fun consume(milliseconds: Long) {
        try { consumeUsage(milliseconds) } finally { publishNavigation() }
    }

    private fun consumeUsage(milliseconds: Long) {
        day()
        val config = store.config()
        if (!config.optBoolean("supervisionEnabled", true)) { flushUsage(); return }
        if (time.restLeft(config) > 0 || time.left("feedback_deadline") > 0) { flushUsage(); return }
        if (freeNow(config)) { flushUsage(); return }
        if (config.optString("scheduleMode", "allow") != "control" && !inWindow(config)) { flushUsage(); return }
        val available = capacity(config)
        val freeKey = if (number("free_quota_ms") > 0) "free_quota_ms" else "boot_quota_ms"
        val freeQuotaNow = number(freeKey)
        if (freeQuotaNow > 0) {
            val freeConsumed = min(milliseconds.coerceIn(0, 5000), min(freeQuotaNow, available))
            if (freeConsumed > 0) {
                val db = store.writableDatabase
                db.beginTransaction()
                try {
                    put(freeKey, freeQuotaNow - freeConsumed)
                    if (freeKey == "free_quota_ms") put("free_used_ms", number("free_used_ms") + freeConsumed)
                    time.consumed(config, freeConsumed)
                    put("used_ms", number("used_ms") + freeConsumed)
                    pendingUsageMs += freeConsumed
                    if (pendingUsageMs >= 30000 || freeQuotaNow == freeConsumed) flushUsage()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                return
            }
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
    fun consumeRecovered(milliseconds: Long) {
        var left = milliseconds.coerceAtLeast(0)
        while (left > 0) {
            consume(minOf(left, 5000L))
            left -= 5000L
        }
    }

    @Synchronized
    fun flushUsage() {
        if (pendingUsageMs <= 0) return
        store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
        pendingUsageMs = 0
    }

    private fun active(): JSONObject? = store.get("active_round").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

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
     * 家长删题或暂停题目后修补进行中的一轮：孩子手上的题失效时立刻换成可用题目并升高 quiz_generation。
     */
    private fun repairActiveRound(removed: Set<String>, allCleared: Boolean): Boolean {
        val round = active() ?: return false
        val question = round.optJSONObject("question")
        val pendingId = round.optString("pendingReview")
        val reviewQuestionId = pendingId.takeIf { it.isNotEmpty() }?.let { store.review(it) }?.optJSONObject("question")?.optString("id") ?: ""
        val deck = round.optJSONArray("studyDeck")
        val deckHit = deck != null && (allCleared || (0 until deck.length()).any { deck.optJSONObject(it)?.optString("id") in removed })
        val hit = allCleared || deckHit || (question != null && question.optString("id") in removed) || (reviewQuestionId.isNotEmpty() && reviewQuestionId in removed)
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
        if (deck != null) {
            val filtered = JSONArray()
            if (!allCleared) {
                for (i in 0 until deck.length()) {
                    val card = deck.optJSONObject(i) ?: continue
                    if (card.optString("id") !in removed) filtered.put(card)
                }
            }
            if (filtered.length() == 0) {
                round.remove("studyDeck")
                round.put("studyPhase", "quiz")
            } else {
                round.put("studyDeck", filtered)
                round.put("target", minOf(round.optInt("target", filtered.length()), filtered.length()))
                if (round.optString("studyPhase") == "memorize") {
                    round.put("question", filtered.getJSONObject(0)).put("nonce", UUID.randomUUID().toString())
                    store.set("active_round", round.toString())
                    put("quiz_generation", number("quiz_generation") + 1)
                    return true
                }
            }
        }
        val replaced = try { nextQuestion(round); true } catch (e: Exception) {
            Diag.warn("Quiz", "题目删空或已全部暂停，作废进行中的一轮: ${e.message}")
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

    @Synchronized
    fun deleteQuestions(ids: List<String>): Int {
        val db = store.writableDatabase
        var committed = false
        val deleted: Int
        db.beginTransaction()
        try {
            deleted = store.deleteQuestions(ids)
            repairActiveRound(ids.toSet(), false)
            db.setTransactionSuccessful()
            committed = true
        } finally { db.endTransaction(); publishNavigation() }
        if (committed) store.pruneMedia()
        return deleted
    }

    /** 暂停题目时同样立即检查孩子手上的当前题，避免孩子继续答已经被家长暂停的题。 */
    @Synchronized
    fun setQuestionsEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = store.writableDatabase
        val updated: Int
        db.beginTransaction()
        try {
            updated = store.setEnabled(ids, enabled)
            if (!enabled) repairActiveRound(ids.toSet(), false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); publishNavigation() }
        return updated
    }

    /** 后台自动题库同步等外部路径删除题目后，原子修复可能受影响的进行中轮次并递增代次。 */
    @Synchronized
    fun onExternalQuestionsRemoved(ids: Set<String>): Boolean {
        if (ids.isEmpty()) return false
        val db = store.writableDatabase
        val repaired: Boolean
        db.beginTransaction()
        try {
            repaired = repairActiveRound(ids, false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); publishNavigation() }
        return repaired
    }

    private fun scopeValues(config: JSONObject, key: String): List<String> {
        val array = config.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun effectiveSubjects(config: JSONObject, round: JSONObject): List<String> {
        val specific = round.optString("subjectFilter").trim()
        return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizSubjects")
    }

    private fun effectiveBanks(config: JSONObject, round: JSONObject): List<String> {
        val specific = round.optString("bankFilter").trim()
        return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizBanks")
    }

    private fun nextQuestion(round: JSONObject) {
        val config = store.config()
        val subjects = effectiveSubjects(config, round)
        // (二改) 抽题偏好：最低星级 + 本轮最近 30 题不重复；挑不出来时依次放宽星级、已出过的题
        val minStar = Tune.minStar(store)
        val recent = round.optJSONArray("recent") ?: JSONArray().also { round.put("recent", it) }
        val skip = HashSet<String>()
        for (i in 0 until recent.length()) skip.add(recent.optString(i))
        val hasExplicitSubject = round.optString("subjectFilter").trim().isNotEmpty()
        val levels = scopeValues(config, "quizLevels")
        val selectedLevel = round.optString("level")
        val banks = effectiveBanks(config, round)
        val gradeSubjects = LocalStore.parseGradeSubjects(config.optJSONObject("gradeSubjects"))
        val focus = round.optString("focus")
        val spaced = config.optBoolean("spacedRepetitionEnabled") || focus == "due"
        val spacedWrongOnly = config.optString("spacedScope") == "wrong_only"
        val wrongFirst = config.optBoolean("wrongFirst") || focus == "wrong"
        val coverageFirst = config.optBoolean("coverageFirst")
        val newFirst = config.optBoolean("newFirst")

        val supervisionEnabled = config.optBoolean("supervisionEnabled", true)
        val extraStage = isExtraStage(round, number("extra_pool"), supervisionEnabled)

        // 「先背再测」进入小测阶段后，按刚背过的卡组逐题考查；只有触发错题加练后才回到题库随机补题
        val deck = round.optJSONArray("studyDeck")
        if (!extraStage && round.optString("studyPhase") == "quiz" && deck != null && deck.length() > 0) {
            var idx = round.optInt("deckIndex", -1) + 1
            while (idx < deck.length()) {
                val candidate = deck.optJSONObject(idx)
                val cid = candidate?.optString("id").orEmpty()
                if (cid.isNotEmpty() && store.questionExists(cid)) {
                    val presented = if (config.optBoolean("shuffleOptions")) QuestionOptions.shuffled(candidate!!) else JSONObject(candidate!!.toString())
                    store.presented(presented.getString("id"))
                    round.put("deckIndex", idx).put("question", presented).put("nonce", UUID.randomUUID().toString()).put("extra", false)
                    return
                }
                idx++
            }
        }

        val last = round.optJSONObject("question")?.optString("id")?.takeIf { it.isNotEmpty() }

        // ST-601：加练阶段优先从家长精准加入的 extra_queue 取有效题目，自动清理已删或已暂停的失效项
        var queuedQuestion: JSONObject? = null
        if (extraStage) {
            val queueIds = store.extraQueue()
            if (queueIds.isNotEmpty()) {
                val fetched = HashMap<String, JSONObject>()
                val (pickedId, staleIds) = selectNextExtraQueueId(queueIds, last) { qid ->
                    val found = store.questionById(qid, enabledOnly = true)
                    if (found != null) fetched[qid] = found
                    found != null
                }
                for (stale in staleIds) store.popExtraQueue(stale)
                if (pickedId != null) queuedQuestion = fetched[pickedId]
            }
        }

        fun query(exclude: String?, onlyWrong: Boolean = false, missedOnly: Boolean = false): JSONObject? {
            fun pick(star: Int, ids: Collection<String>) = store.pickQuestion(
                subjects = subjects, levels = levels, selectedLevel = selectedLevel, banks = banks,
                spaced = spaced, wrongFirst = wrongFirst, coverageFirst = coverageFirst, newFirst = newFirst,
                excludeId = exclude, onlyWrong = onlyWrong, missedOnly = missedOnly,
                minStar = star, excludeIds = ids,
                gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject
            )
            return pick(minStar, skip) ?: (if (minStar > 1) pick(1, skip) else null) ?: pick(1, emptyList())
        }

        val question = queuedQuestion ?: when {
            focus == "wrong" -> query(last, onlyWrong = true) ?: query(null, onlyWrong = true)
                ?: query(last) ?: query(null)
            wrongFirst && extraStage -> query(last, onlyWrong = true)
                ?: (if (spaced && spacedWrongOnly) query(last, missedOnly = true) else null)
                ?: query(last) ?: query(null)
            spaced && spacedWrongOnly -> query(last, missedOnly = true) ?: query(null, missedOnly = true)
                ?: query(last) ?: query(null)
            else -> query(last) ?: query(null)
        } ?: throw IllegalArgumentException(
            if (subjects.isNotEmpty() || levels.isNotEmpty() || banks.isNotEmpty() || gradeSubjects.isNotEmpty())
                "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围"
            else "题库为空，请由家长添加题目"
        )

        recent.put(question.optString("id"))
        if (recent.length() > 30) {
            val kept = JSONArray()
            for (i in recent.length() - 30 until recent.length()) kept.put(recent.optString(i))
            round.put("recent", kept)
        }
        val presented = if (config.optBoolean("shuffleOptions")) QuestionOptions.shuffled(question) else question
        store.presented(presented.getString("id"))
        round.put("question", presented).put("nonce", UUID.randomUUID().toString())
        round.put("extra", extraStage)
    }

    private fun buildStudyCards(deck: JSONArray, ratedIds: Set<String>): JSONArray {
        val cards = JSONArray()
        for (i in 0 until deck.length()) {
            val item = deck.optJSONObject(i) ?: continue
            val qid = item.optString("id")
            val card = JSONObject()
                .put("id", qid)
                .put("q", item.optString("q"))
                .put("type", item.optString("type", "single"))
                .put("a", item.optJSONArray("a") ?: JSONArray())
                .put("tip", item.optString("tip"))
                .put("language", item.optString("language", ""))
                .put("image", item.optString("image"))
                .put("audio", item.optString("audio"))
                .put("subject", item.optString("subject"))
                .put("level", item.optString("level"))
                .put("answerDisplay", formatAnswerDisplay(item))
                .put("intervals", store.previewIntervals(qid))
                .put("rated", qid in ratedIds)
            item.optJSONArray("optionImages")?.let { card.put("optionImages", it) }
            item.optJSONArray("images")?.let { card.put("images", it) }
            cards.put(card)
        }
        return cards
    }

    private fun publicRound(round: JSONObject): JSONObject {
        val copy = JSONObject(round.toString())
        copy.optJSONObject("question")?.let { question ->
            SECRET_QUESTION_FIELDS.forEach { question.remove(it) }
        }
        if (copy.optString("studyPhase") == "memorize") {
            val deck = round.optJSONArray("studyDeck") ?: JSONArray()
            val ratedArr = round.optJSONArray("ratedIds") ?: JSONArray()
            val ratedSet = (0 until ratedArr.length()).map { ratedArr.optString(it) }.toSet()
            copy.put("studyCards", buildStudyCards(deck, ratedSet))
        }
        copy.remove("studyDeck")
        copy.remove("rules"); copy.remove("recent")
        return copy.put("extraPool", number("extra_pool")).put("status", status()).put("study", store.studySummary())
    }

    @Synchronized
    fun start(level: String? = null, fromGate: Boolean = false): JSONObject =
        start(JSONObject().apply {
            if (level != null) put("level", level)
            if (fromGate) put("fromGate", true)
        })

    @Synchronized
    fun start(options: JSONObject): JSONObject {
        val st = status()
        val c = store.config()
        val supervision = c.optBoolean("supervisionEnabled", true)
        val requestedLevel = options.optString("level").trim().takeIf { it.isNotEmpty() }
        val requestedSubject = options.optString("subject").trim()
        val requestedBank = options.optString("bank").trim()
        val focus = options.optString("focus").trim()
        require(focus in listOf("", "due", "wrong", "memorize", "quiz")) { "未知学习模式" }
        val explicitStudy = options.optBoolean("forceStart", false) || focus.isNotEmpty() ||
            requestedSubject.isNotEmpty() || requestedBank.isNotEmpty() || options.has("memorizeFirst")
        val wantMemorizeFirst = when {
            focus == "memorize" -> true
            options.has("memorizeFirst") -> options.optBoolean("memorizeFirst")
            else -> c.optBoolean("memorizeFirst", false)
        }

        if (st.getString("reason") == "feedback") {
            val held = JSONObject(store.get("feedback_result").takeIf { it.isNotEmpty() } ?: "{}")
            if (!held.has("question")) {
                store.set("feedback_deadline", "")
                store.set("feedback_result", "")
                return start(options)
            }
            return held.put("heldFeedback", true).put("status", st)
        }
        if (supervision && st.getString("reason") in listOf("locked", "rest")) {
            return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        }
        if (supervision && !explicitStudy && st.optBoolean("free") && c.getString("playMode") != "practice") {
            return JSONObject().put("free", true).put("status", st).put("study", store.studySummary())
        }
        val feedbackId = store.get("review_feedback")
        val feedback = feedbackId.takeIf { it.isNotEmpty() }?.let { store.review(it) }
        if (feedback?.optString("state") == "graded") return JSONObject().put("reviewed", true).put("status", st)
            .put("reviewId", feedbackId).put("score", feedback.getDouble("score"))
            .put("feedback", feedback.optString("feedback")).put("result", feedback.getJSONObject("result"))
        if (supervision && !explicitStudy && c.getString("playMode") != "practice" && st.getString("reason") in listOf("quota", "rounds", "schedule")) {
            return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        }
        val hasWatchQuota = st.optLong("remainingSeconds") > 0L || st.optLong("freeQuotaSeconds") > 0L
        if (supervision && !explicitStudy && hasWatchQuota && c.getString("playMode") != "practice") {
            return JSONObject().put("watching", true).put("status", st).put("study", store.studySummary())
        }
        var round = active()
        healPendingReview(round)
        if (!explicitStudy && round == null && supervision && videos.wanted(c)) {
            return JSONObject().put("videoLesson", videos.start(c)).put("status", st)
        }
        if (round?.has("pendingReview") == true) {
            return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", st)
        }
        if (round != null && round.optInt("answered") == 0 && round.optJSONObject("rules")?.optInt("version") != c.optInt("version")) {
            round = null
        }
        // 自主学习（无管控或空闲时从学习主页点不同专项）允许切换专项；强制解锁中的半途任务不允许重置逃课
        if (round != null && explicitStudy && (!supervision || round.optBoolean("voluntary"))) {
            val changed = (focus.isNotEmpty() && focus != round.optString("focus")) ||
                requestedSubject != round.optString("subjectFilter") ||
                requestedBank != round.optString("bankFilter") ||
                (options.has("memorizeFirst") && wantMemorizeFirst != round.optBoolean("memorizeFirst"))
            if (changed) round = null
        }

        val voluntary = !supervision || st.optBoolean("free") || hasWatchQuota ||
            st.getString("reason") in listOf("quota", "rounds", "schedule")
        val desiredMode = if (voluntary && c.getString("playMode") == "practice" && !wantMemorizeFirst && focus.isEmpty()) "practice"
            else if (voluntary && focus.isNotEmpty()) "round"
            else c.getString("playMode")

        if (round == null || (!round.optBoolean("voluntary") && round.optString("mode") != c.getString("playMode"))) {
            var selectedLevel = ""
            val subjects = if (requestedSubject.isNotEmpty()) listOf(requestedSubject) else scopeValues(c, "quizSubjects")
            val hasExplicitSubject = requestedSubject.isNotEmpty()
            val banks = if (requestedBank.isNotEmpty()) listOf(requestedBank) else scopeValues(c, "quizBanks")
            val allowedLevels = scopeValues(c, "quizLevels")
            val gradeSubjects = LocalStore.parseGradeSubjects(c.optJSONObject("gradeSubjects"))
            if (c.optBoolean("childSelectLevel")) {
                val choices = store.distinctLevels(
                    allowedLevels = allowedLevels,
                    subjects = subjects,
                    banks = banks,
                    gradeSubjects = gradeSubjects,
                    explicitSubjectFilter = hasExplicitSubject
                )
                require(choices.isNotEmpty()) { "请先为启用题目标注年级，或关闭孩子选择年级" }
                if (requestedLevel == null && choices.size > 1) return JSONObject().put("chooseLevel", JSONArray(choices)).put("status", st)
                selectedLevel = requestedLevel ?: choices.single()
                require(selectedLevel in choices) { "请选择家长允许的年级" }
            }

            if (focus == "wrong") {
                val sampleWrong = store.pickQuestion(
                    subjects = subjects, levels = allowedLevels, selectedLevel = selectedLevel,
                    banks = banks, onlyWrong = true,
                    gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject
                )
                if (sampleWrong == null) {
                    return JSONObject().put("wrongEmpty", true).put("status", st).put("study", store.studySummary())
                }
            }

            round = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("mode", desiredMode)
                .put("voluntary", voluntary)
                .put("focus", focus)
                .put("subjectFilter", requestedSubject)
                .put("bankFilter", requestedBank)
                .put("memorizeFirst", wantMemorizeFirst)
                .put("level", selectedLevel)
                .put("target", c.getInt("questionsPerRound"))
                .put("answered", 0).put("correct", 0)
                .put("streak", 0).put("earnedMs", 0).put("rules", c)

            if (wantMemorizeFirst) {
                val deckCount = c.getInt("questionsPerRound").coerceIn(1, 20)
                val spaced = c.optBoolean("spacedRepetitionEnabled") || focus in listOf("due", "memorize")
                val spacedWrongOnly = c.optString("spacedScope") == "wrong_only"
                var deck = when {
                    focus == "wrong" -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = spaced, wrongFirst = true, onlyWrong = true, gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                    spaced && spacedWrongOnly -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = true, missedOnly = true, gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                        .takeIf { it.length() > 0 }
                        ?: store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = true, coverageFirst = c.optBoolean("coverageFirst"), newFirst = c.optBoolean("newFirst"), gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                    else -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = spaced, wrongFirst = c.optBoolean("wrongFirst"), coverageFirst = c.optBoolean("coverageFirst"), newFirst = c.optBoolean("newFirst"), gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                }
                require(deck.length() > 0) {
                    if (subjects.isNotEmpty() || allowedLevels.isNotEmpty() || banks.isNotEmpty() || gradeSubjects.isNotEmpty())
                        "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围"
                    else "题库为空，请由家长添加题目"
                }
                round.put("target", deck.length())
                    .put("studyPhase", "memorize")
                    .put("studyDeck", deck)
                    .put("deckIndex", -1)
                    .put("ratedIds", JSONArray())
                    .put("ratedCorrectIds", JSONArray())
                    .put("question", deck.getJSONObject(0))
                    .put("nonce", UUID.randomUUID().toString())
            } else {
                round.put("studyPhase", "quiz")
                nextQuestion(round)
            }
            store.set("active_round", round.toString())
        }
        return publicRound(round)
    }

    /** 「先背再测」背诵完毕后切入闭卷小测阶段（纯闪卡模式下也可直接结束本组背诵）。 */
    @Synchronized
    fun memorized(roundId: String = ""): JSONObject {
        val round = active() ?: return JSONObject().put("error", "round_missing")
        if (roundId.isNotEmpty()) require(round.optString("id") == roundId) { "任务已更新，请重新加载" }
        if (round.optString("focus") == "memorize") {
            val deckLen = round.optJSONArray("studyDeck")?.length() ?: 0
            val correctLen = round.optJSONArray("ratedCorrectIds")?.length() ?: deckLen
            store.logEvent("quiz", JSONObject().put("native", true).put("memorize", true).put("total", deckLen).put("correct", correctLen).put("passed", true))
            store.set("active_round", "")
            publishNavigation()
            return JSONObject().put("ok", true).put("finished", true).put("status", status()).put("study", store.studySummary())
        }
        if (round.optString("studyPhase") == "memorize") {
            round.put("studyPhase", "quiz").put("deckIndex", -1)
            nextQuestion(round)
            store.set("active_round", round.toString())
            store.logEvent("memorize_completed", JSONObject().put("roundId", round.optString("id")))
        }
        return publicRound(round)
    }

    /** Anki 风格闪卡自评：1=重来(10分钟)、3=困难、4=良好、5=简单。 */
    @Synchronized
    fun rateFlashcard(roundId: String, qid: String, quality: Int): JSONObject {
        require(qid.isNotBlank() && store.questionExists(qid)) { "题目不存在" }
        val round = active()
        val plan = planFlashcardRate(round, roundId, qid, quality)
        if (plan.alreadyRated) {
            return JSONObject()
                .put("ok", true)
                .put("alreadyRated", true)
                .put("finished", false)
                .put("intervals", store.previewIntervals(qid))
                .put("status", status())
                .put("study", store.studySummary())
        }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            store.recordProgress(qid, plan.quality)
            store.answered(qid, plan.correct)
            store.logEvent("flashcard", JSONObject().put("qid", qid).put("quality", plan.quality).put("correct", plan.correct))

            if (round != null) {
                round.put("ratedIds", JSONArray(plan.updatedRatedIds))
                round.put("ratedCorrectIds", JSONArray(plan.updatedCorrectIds))

                if (plan.finished) {
                    store.logEvent("quiz", JSONObject().put("native", true).put("memorize", true)
                        .put("total", plan.deckSize).put("correct", plan.updatedCorrectIds.size).put("passed", true))
                    store.set("active_round", "")
                    db.setTransactionSuccessful()
                    return JSONObject()
                        .put("ok", true)
                        .put("finished", true)
                        .put("intervals", store.previewIntervals(qid))
                        .put("status", status())
                        .put("study", store.studySummary())
                }
                store.set("active_round", round.toString())
            }
            db.setTransactionSuccessful()
            return JSONObject()
                .put("ok", true)
                .put("finished", false)
                .put("intervals", store.previewIntervals(qid))
                .put("status", status())
                .put("study", store.studySummary())
        } finally {
            db.endTransaction()
            publishNavigation()
        }
    }

    @Synchronized
    fun clearQuestions(bank: String? = null): Int {
        val db = store.writableDatabase
        var committed = false
        val deleted: Int
        db.beginTransaction()
        try {
            val removed = if (bank == null) emptySet() else store.idsInBank(bank).toSet()
            deleted = if (bank == null) store.clearQuestions() else store.deleteQuestions(removed.toList())
            repairActiveRound(removed, bank == null)
            db.setTransactionSuccessful()
            committed = true
        } finally { db.endTransaction(); publishNavigation() }
        if (committed) store.pruneMedia()
        return deleted
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
            val supervision = config.optBoolean("supervisionEnabled", true)
            val grant = if (!supervision) 0L else minOf(
                config.optLong("sessionMinutes", 10) * 60000,
                (time.dailyLimit(limitConfig(config)) - number("used_ms") - number("credit_ms") - freeBalance()).coerceAtLeast(0)
            )
            if (grant > 0) put("credit_ms", number("credit_ms") + grant)
            store.logEvent("quiz", JSONObject().put("native", true).put("video", true).put("total", 0).put("correct", 0).put("passed", true))
            if (supervision) store.logEvent("quiz_pass", JSONObject().put("roundId", id).put("minutes", grant / 60000.0).put("video", true))
            val result = JSONObject().put("rewardMinutes", grant / 60000.0).put("status", status())
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction(); publishNavigation() }
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

    @Synchronized
    fun skipPending(): JSONObject {
        val round = active() ?: return JSONObject().put("error", "round_missing")
        val id = round.optString("pendingReview")
        if (id.isEmpty()) return JSONObject().put("pending", false).put("status", status())
        store.review(id)?.let {
            it.put("state", "skipped").put("feedback", "孩子选择先跳过，等待家长处理").put("skippedAt", store.clockReading().millis)
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
        val reviewChild = review.optString("child", LocalStore.DEFAULT_CHILD)
        require(store.childExists(reviewChild)) { "该题目所属的孩子档案已删除" }
        val settings = JSONObject(store.get("grading_settings", "{}"))
        val threshold = settings.optDouble("passScore", 60.0)
        if (reviewChild != store.activeChild()) flushUsage()
        store.withChild(reviewChild) { day() }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            // 即使电视当前已切换到另一个孩子，批改奖励、错题统计、复习进度与通知也必须结清到原答题孩子名下
            store.withChild(reviewChild) {
                val quality = when { score < threshold -> 1; score >= 90 -> 5; score >= 75 -> 4; else -> 3 }
                val result = answer(review.getJSONObject("input"), score >= threshold, quality)
                require(!result.has("error") && !result.has("blocked")) { "当前任务暂不可结算，请在可用时段重试" }
                review.put("state", "graded").put("score", score).put("feedback", feedback.take(5000))
                    .put("source", source).put("gradedAt", store.clockReading().millis).put("result", result)
                store.saveReview(review)
                store.set("review_feedback", reviewId)
                store.logEvent("review_graded", JSONObject().put("id", reviewId).put("score", score).put("source", source))
            }
            db.setTransactionSuccessful()
            return review
        } finally { db.endTransaction(); publishNavigation() }
    }

    private fun normalize(value: String): String = normalizeTextExact(value)

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
        val supervisionEnabled = store.config().optBoolean("supervisionEnabled", true)
        val voluntary = isVoluntary(round, supervisionEnabled)
        if (verdict == null && !voluntary && round.getString("mode") != "practice" &&
            st.getString("reason") in listOf("schedule", "quota", "rounds", "rest", "feedback", "locked")) {
            return JSONObject().put("blocked", st.getString("reason"))
        }
        val question = round.getJSONObject("question")
        val type = question.optString("type", "single")
        if (verdict == null && (type == "subjective" || input.optString("drawing").isNotEmpty())) {
            val rawDrawing = input.optString("drawing").trim()
            require(input.optString("text").isNotBlank() || rawDrawing.isNotEmpty()) { "请先作答" }
            require(
                rawDrawing.isEmpty() ||
                    MediaStore.isMediaPath(rawDrawing) ||
                    (rawDrawing.startsWith("data:image/png;base64,", ignoreCase = true) && rawDrawing.length <= 2_000_000)
            ) { "手写图片格式不正确或过大" }
            val savedDrawing = when {
                rawDrawing.isEmpty() -> ""
                MediaStore.isMediaPath(rawDrawing) -> rawDrawing
                else -> MediaStore.saveDataUrl(store.mediaDir, rawDrawing, 2_000_000)
            }
            val cleanInput = JSONObject(input.toString()).apply {
                if (savedDrawing.isEmpty()) remove("drawing") else put("drawing", savedDrawing)
            }
            val id = UUID.randomUUID().toString()
            val review = JSONObject().put("id", id).put("state", "pending").put("ts", store.clockReading().millis)
                .put("child", store.activeChild())
                .put("question", question).put("input", cleanInput).put("feedback", "等待批改")
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                store.saveReview(review)
                round.put("pendingReview", id)
                store.set("active_round", round.toString())
                store.logEvent("review_submitted", JSONObject().put("id", id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction(); publishNavigation() }
            return JSONObject().put("pending", true).put("reviewId", id).put("status", status())
        }
        val expected = when (type) {
            "multiple" -> question.getJSONArray("correctIndices")
            "text", "subjective" -> JSONArray()
            else -> JSONArray().put(question.optInt("k"))
        }
        fun indices(array: JSONArray): Set<Int> = (0 until array.length()).map { array.getInt(it) }.toSet()
        val correct = if (verdict != null) verdict else if (type == "text") {
            val rawText = input.optString("text")
            val accepted = question.getJSONArray("answers")
            rawText.isNotBlank() && (0 until accepted.length()).any { matchesTextAnswer(rawText, accepted.getString(it)) }
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
            val extraEnabled = isExtraEnabled(round, supervisionEnabled)
            if (!correct && !voluntary && mode != "count" && extraEnabled) {
                var wrong = number("wrong_progress") + 1
                if (wrong >= rules.getInt("wrongThreshold")) {
                    val extra = rules.getLong("extraQuestions")
                    put("extra_pool", number("extra_pool") + extra)
                    wrong -= rules.getInt("wrongThreshold")
                    store.logEvent("extra_added", JSONObject().put("count", extra))
                }
                put("wrong_progress", wrong)
            }
            if (correct && wasExtra && extraEnabled && number("extra_pool") > 0) put("extra_pool", number("extra_pool") - 1)
            if (correct) store.popExtraQueue(question.getString("id"))
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
            store.answered(question.getString("id"), correct)
            store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
                .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
            val baseDone = isBaseDone(round)
            val passed = mode in listOf("streak", "count") || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
            val finished = mode != "practice" && baseDone && (!passed || !extraEnabled || number("extra_pool") == 0L)
            val answerText = when (type) {
                "text" -> question.optJSONArray("answers")?.optString(0).orEmpty()
                "subjective" -> question.optString("modelAnswer")
                else -> ""
            }
            val result = JSONObject()
                .put("correct", correct)
                .put("correctIndices", if (type == "text" || type == "subjective") JSONArray() else expected)
                .put("answerText", answerText)
                .put("tip", question.optString("tip"))
                .put("finished", finished)
                .put("passed", finished && passed)
            if (finished) {
                var grant = 0L
                if (passed && !voluntary) {
                    val requested = if (rules.optBoolean("difficultyRewardEnabled")) round.getLong("earnedMs") else rules.getLong("sessionMinutes") * 60000
                    val budget = time.dailyLimit(limitConfig(store.config())) - number("used_ms") - number("credit_ms") - freeBalance()
                    grant = min(requested, budget.coerceAtLeast(0))
                    put("credit_ms", number("credit_ms") + grant)
                    pointsEarned += PointsSync.earnForPass(store)
                    store.logEvent("quiz_pass", JSONObject().put("roundId", round.getString("id")).put("minutes", grant / 60000.0))
                }
                store.logEvent("quiz", JSONObject().put("native", true).put("voluntary", voluntary)
                    .put("total", round.getInt("answered")).put("correct", round.getInt("correct")).put("passed", passed))
                store.set("active_round", "")
                result.put("rewardMinutes", grant / 60000.0)
            } else {
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
                    store.set("active_round", round.toString())
                    result.put("next", publicRound(round))
                }
            }
            if (!correct && rules.optInt("wrongReviewSeconds") > 0) {
                time.deadline("feedback_deadline", rules.optInt("wrongReviewSeconds") * 1000L)
                val sanitizedQuestion = JSONObject(question.toString()).apply {
                    SECRET_QUESTION_FIELDS.forEach { remove(it) }
                }
                store.set("feedback_result", JSONObject().put("result", JSONObject(result.toString()))
                    .put("question", sanitizedQuestion).toString())
            }
            result.put("pointsEarned", pointsEarned)
            if (pointsEarned > 0) PointsSync.kick(store)
            result.put("status", status()).put("study", store.studySummary())
            store.set("last_answer", JSONObject().put("nonce", input.getString("nonce")).put("result", result).toString())
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction(); publishNavigation() }
    }
}
