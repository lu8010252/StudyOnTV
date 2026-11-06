package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 出题：题库“水位”低于阈值时，自动调用 OpenAI 兼容接口（可用中转站）批量出新题并入库。
 * - 不是答题时现场出题：提前生成、存本地，电视上答题不等网络，也不依赖 AI 在线
 * - 每批题先由 AI 出题，再由另一次独立调用“不看答案重新做一遍”，两次答案不一致的题直接丢弃，降低答错题的概率
 * - 入库前按题干去重，选项顺序在本地打乱（防止 AI 总把答案放在 A）
 */
object AiQuestionGen {
    private const val KEY = "aigen_settings"
    private val running = AtomicBoolean(false)

    @Volatile private var lastRun = 0L
    @Volatile private var lastError = ""
    @Volatile private var lastNote = ""

    private val DEFAULT_MATH = "升和毫升的换算与应用\n三位数除以两位数的口算、笔算和有余数除法\n商不变的规律\n观察物体\n条形统计图\n用列举等策略解决问题\n可能性\n万以内与大数的读写、小数的认识与大小比较\n垂线与平行线\n四则混合应用题"
    private val DEFAULT_ENGLISH = "教室与学习用品 (classroom, desk, chair, blackboard)\n书包与文具 (schoolbag, notebook, storybook, maths book)\n朋友与外貌 (strong, quiet, tall, short, thin)\n房间与家具 (bedroom, living room, bed, sofa, fridge)\n食物与餐具 (beef, chicken, noodles, soup, bowl, fork)\n家庭成员与职业 (nurse, doctor, driver, farmer, cook)\n句型 What's in ...? / Is he/she ...? / Do you have ...? / What does he do?"

    private fun defaultProfiles(): JSONArray = JSONArray()
        .put(JSONObject().put("subject", "数学").put("level", "四年级上册·苏教版·AI").put("grade", "四年级").put("textbook", "苏教版").put("focus", DEFAULT_MATH))
        .put(JSONObject().put("subject", "英语").put("level", "四年级上册·人教PEP·AI").put("grade", "四年级").put("textbook", "人教PEP三起").put("focus", DEFAULT_ENGLISH))

    private fun raw(store: LocalStore): JSONObject = try { JSONObject(store.get(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    private fun dayKey(): Long { val now = System.currentTimeMillis(); return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L }

    fun settings(store: LocalStore, includeKey: Boolean = false): JSONObject {
        val o = JSONObject().put("enabled", false).put("endpoint", "").put("apiKey", "").put("model", "")
            .put("profiles", defaultProfiles()).put("batchSize", 15).put("lowWater", 80).put("dailyBatches", 6)
            .put("batchesToday", 0).put("day", 0L)
        val saved = raw(store)
        saved.keys().forEach { o.put(it, saved.get(it)) }
        if (o.optLong("day") != dayKey()) o.put("batchesToday", 0).put("day", dayKey())
        o.put("hasKey", o.optString("apiKey").isNotEmpty())
        if (!includeKey) o.remove("apiKey")
        return o
    }

    @Synchronized
    private fun persist(store: LocalStore, s: JSONObject) {
        val copy = JSONObject(s.toString()); copy.remove("hasKey")
        store.set(KEY, copy.toString())
    }

    /** 接口、Key、模型留空时，沿用“AI 批改”里已经填好的那一套。 */
    private fun effective(store: LocalStore, s: JSONObject): Triple<String, String, String> {
        val g = GradingService.settings(store, true)
        val endpoint = s.optString("endpoint").ifBlank { if (g.optString("apiKey").isNotEmpty()) g.optString("endpoint") else "" }
        val key = s.optString("apiKey").ifBlank { g.optString("apiKey") }
        val model = s.optString("model").ifBlank { g.optString("model") }
        return Triple(endpoint, key, model)
    }

    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val cur = settings(store, true)
        for (k in listOf("enabled", "endpoint", "model")) if (input.has(k)) cur.put(k, input.get(k))
        if (input.optString("apiKey").isNotBlank()) cur.put("apiKey", input.getString("apiKey").trim())
        if (input.has("batchSize")) cur.put("batchSize", input.optInt("batchSize").coerceIn(5, 30))
        if (input.has("lowWater")) cur.put("lowWater", input.optInt("lowWater").coerceIn(10, 500))
        if (input.has("dailyBatches")) cur.put("dailyBatches", input.optInt("dailyBatches").coerceIn(1, 30))
        if (input.has("profiles")) {
            val src = input.getJSONArray("profiles")
            require(src.length() in 0..6) { "最多配置 6 门科目" }
            val out = JSONArray()
            for (i in 0 until src.length()) {
                val p = src.getJSONObject(i)
                val subject = p.optString("subject").trim()
                require(subject.isNotEmpty() && subject.length <= 10) { "科目名称需要 1–10 个字" }
                out.put(JSONObject().put("subject", subject)
                    .put("level", p.optString("level").trim().ifEmpty { "AI 出题" }.take(30))
                    .put("grade", p.optString("grade").trim().take(20)).put("textbook", p.optString("textbook").trim().take(30))
                    .put("focus", p.optString("focus").trim().take(2000)))
            }
            cur.put("profiles", out)
        }
        val ep = cur.optString("endpoint").trim()
        if (ep.isNotEmpty()) {
            val u = URL(ep)
            require(u.protocol == "https" || u.protocol == "http") { "接口地址需要以 http:// 或 https:// 开头" }
            require(u.userInfo == null && u.query == null && u.ref == null) { "接口地址请填写完整的 chat/completions 地址，不要带参数" }
        }
        cur.put("endpoint", ep)
        if (cur.optBoolean("enabled")) {
            val (e, k, m) = effective(store, cur)
            require(e.isNotEmpty() && k.isNotEmpty() && m.isNotEmpty()) { "请先填写接口地址、API Key 和模型名称（或先配置好 AI 批改）" }
            require(cur.getJSONArray("profiles").length() > 0) { "请至少配置一门科目" }
        }
        persist(store, cur)
        return summary(store)
    }

    fun summary(store: LocalStore): JSONObject {
        val s = settings(store, true)
        val (e, k, m) = effective(store, s)
        val subjects = JSONArray()
        val profiles = s.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val sub = profiles.getJSONObject(i).optString("subject")
            subjects.put(JSONObject().put("subject", sub).put("unseen", store.unseenCount(sub)).put("total", store.countSubject(sub)))
        }
        s.remove("apiKey")
        return s.put("hasKey", k.isNotEmpty()).put("sharedWithGrading", s.optString("apiKey").isEmpty())
            .put("effectiveEndpoint", e).put("effectiveModel", m)
            .put("running", running.get()).put("lastRun", lastRun).put("lastError", lastError).put("lastNote", lastNote)
            .put("aiTotal", store.countAiQuestions()).put("subjects", subjects).put("version", BuildConfig.VERSION_NAME)
    }

    fun deleteAll(store: LocalStore): Int = store.deleteAiQuestions()

    // ---------- 调度 ----------

    /** 手动：每门科目各出一批，后台执行。 */
    fun runNowAsync(store: LocalStore): Boolean {
        if (running.get()) return false
        Thread { runAll(store, manual = true) }.apply { isDaemon = true; name = "aigen-now" }.start()
        return true
    }

    /** 定时检查：某科“没做过的题”少于阈值就补一批，每次只补一门。 */
    fun tick(store: LocalStore) {
        val s = settings(store, true)
        if (!s.optBoolean("enabled")) return
        val (e, k, m) = effective(store, s)
        if (e.isEmpty() || k.isEmpty() || m.isEmpty()) return
        if (s.optInt("batchesToday") >= s.optInt("dailyBatches")) return
        val profiles = s.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            if (store.unseenCount(p.optString("subject")) < s.optInt("lowWater")) {
                if (running.compareAndSet(false, true)) {
                    try { runOne(store, p) } finally { running.set(false) }
                }
                return
            }
        }
    }

    private fun runAll(store: LocalStore, manual: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try {
            val s = settings(store, true)
            val profiles = s.getJSONArray("profiles")
            val notes = ArrayList<String>()
            for (i in 0 until profiles.length()) notes.add(runOne(store, profiles.getJSONObject(i)))
            if (manual) lastNote = notes.joinToString("；")
        } finally { running.set(false) }
    }

    // ---------- 一批题 ----------

    private fun runOne(store: LocalStore, profile: JSONObject): String {
        val subject = profile.optString("subject")
        val s = settings(store, true)
        s.put("batchesToday", s.optInt("batchesToday") + 1).put("day", dayKey())
        persist(store, s)
        val (endpoint, key, model) = effective(store, s)
        return try {
            val note = generate(store, s, profile, endpoint, key, model)
            lastError = ""; lastRun = System.currentTimeMillis(); lastNote = note
            note
        } catch (ex: Exception) {
            lastError = subject + "：" + (ex.message ?: "出题失败")
            lastRun = System.currentTimeMillis()
            lastError
        }
    }

    private fun generate(store: LocalStore, s: JSONObject, profile: JSONObject, endpoint: String, key: String, model: String): String {
        val subject = profile.optString("subject")
        val n = s.optInt("batchSize", 15).coerceIn(5, 30)
        val topics = profile.optString("focus").split('\n', '；', ';').map { it.trim() }.filter { it.isNotEmpty() }.shuffled().take(3)
        val existing = store.stemsOf(subject, 5000)
        val seen = HashSet<String>(); existing.forEach { seen.add(normStem(it)) }
        val avoid = existing.shuffled().take(20).joinToString("\n") { "- " + it.take(60) }

        val gen = chat(endpoint, key, model, GEN_SYSTEM,
            "科目：$subject\n年级：${profile.optString("grade")}\n教材：${profile.optString("textbook")}\n" +
                "本批请围绕这些知识点出题（可混合）：\n${topics.joinToString("\n") { "· $it" }.ifEmpty { "· 本年级常规知识点" }}\n" +
                "出 $n 道题，题型分布：单选约 50%，填空约 25%，判断约 15%，多选约 10%；难度 1–4 星都要有，以 2–3 星为主。\n" +
                "随机种子：${UUID.randomUUID().toString().take(8)}（请据此换一批新的情境和数字）\n" +
                (if (avoid.isNotEmpty()) "已有题目，不要重复，也不要只是改个数字：\n$avoid\n" else "") +
                GEN_FORMAT, 0.9, 170_000)
        val raw = parseJson(gen).optJSONArray("items") ?: throw IllegalStateException("AI 返回的内容里没有题目")

        // 本地规范化
        val cands = ArrayList<JSONObject>()
        for (i in 0 until raw.length()) {
            val c = normalize(raw.optJSONObject(i) ?: continue, profile) ?: continue
            val sn = normStem(c.optString("q"))
            if (seen.add(sn)) cands.add(c)
        }
        if (cands.isEmpty()) throw IllegalStateException("这一批题没有通过格式检查，稍后会再试")

        // 独立复核：不给答案，让 AI 重新做一遍
        val blind = JSONArray()
        cands.forEachIndexed { i, c ->
            val b = JSONObject().put("i", i).put("type", c.getString("type")).put("q", c.getString("q"))
            if (c.getString("type") != "text") b.put("a", if (c.getString("type") == "boolean") JSONArray().put("正确").put("错误") else c.getJSONArray("a"))
            blind.put(b)
        }
        val verifyRaw = chat(endpoint, key, model, VERIFY_SYSTEM, JSONObject().put("items", blind).toString() + VERIFY_FORMAT, 0.0, 170_000)
        val results = parseJson(verifyRaw).optJSONArray("results") ?: throw IllegalStateException("复核没有返回结果，这一批题已丢弃")
        val answerOf = HashMap<Int, JSONObject>()
        for (i in 0 until results.length()) results.optJSONObject(i)?.let { answerOf[it.optInt("i", -1)] = it }

        var ok = 0; var dropped = 0
        cands.forEachIndexed { i, c ->
            val r = answerOf[i]
            if (r == null || !agree(c, r)) { dropped++; return@forEachIndexed }
            try { store.upsertQuestion(c); ok++ } catch (e: Exception) { dropped++ }
        }
        return "$subject：新增 $ok 题" + (if (dropped > 0) "，复核没通过丢弃 $dropped 题" else "")
    }

    private fun normalize(src: JSONObject, profile: JSONObject): JSONObject? {
        val type = src.optString("type", "single")
        val q = src.optString("q").trim()
        if (q.isEmpty() || q.length > 400 || type !in listOf("single", "multiple", "boolean", "text")) return null
        val d = src.optInt("difficulty", 2).coerceIn(1, 4)
        val out = JSONObject().put("id", "ai-" + UUID.randomUUID().toString().replace("-", "").take(14)).put("type", type)
            .put("subject", profile.optString("subject")).put("level", profile.optString("level")).put("difficulty", d)
            .put("q", q).put("tip", src.optString("tip").take(300)).put("source", "ai")
        when (type) {
            "single", "multiple" -> {
                val a = src.optJSONArray("a") ?: return null
                if (a.length() !in 2..6) return null
                val opts = ArrayList<String>()
                for (i in 0 until a.length()) { val t = a.optString(i).trim(); if (t.isEmpty()) return null; opts.add(t) }
                if (opts.toSet().size != opts.size) return null
                val correct = HashSet<Int>()
                if (type == "single") { val k = src.optInt("k", -1); if (k !in opts.indices) return null; correct.add(k) }
                else {
                    val ci = src.optJSONArray("correctIndices") ?: return null
                    for (i in 0 until ci.length()) { val k = ci.optInt(i, -1); if (k !in opts.indices) return null; correct.add(k) }
                    if (correct.size < 2 || correct.size >= opts.size) return null
                }
                val order = opts.indices.shuffled()
                val shuffled = JSONArray(); order.forEach { shuffled.put(opts[it]) }
                out.put("a", shuffled)
                if (type == "single") out.put("k", order.indexOf(correct.first()))
                else out.put("correctIndices", JSONArray().also { arr -> order.indices.filter { order[it] in correct }.forEach { arr.put(it) } })
            }
            "boolean" -> { val k = src.optInt("k", -1); if (k !in 0..1) return null; out.put("k", k) }
            "text" -> {
                val a = src.optJSONArray("answers") ?: return null
                val list = ArrayList<String>()
                for (i in 0 until a.length()) { val t = a.optString(i).trim(); if (t.isNotEmpty() && t.length <= 40) list.add(t) }
                if (list.isEmpty() || list.size > 6) return null
                out.put("answers", JSONArray(list))
            }
        }
        return out
    }

    private fun agree(c: JSONObject, r: JSONObject): Boolean = when (c.getString("type")) {
        // 单选的选项在本地已打乱，复核看到的就是打乱后的顺序，所以直接比序号
        "single", "boolean" -> r.has("k") && r.optInt("k", -99) == c.optInt("k", -1)
        "multiple" -> {
            val a = r.optJSONArray("correctIndices"); val b = c.optJSONArray("correctIndices")
            if (a == null || b == null) false else toSet(a) == toSet(b)
        }
        "text" -> {
            val given = r.optJSONArray("answers")
            val accepted = c.getJSONArray("answers")
            var hit = false
            if (given != null) for (i in 0 until given.length()) for (j in 0 until accepted.length()) {
                if (normAnswer(given.optString(i)) == normAnswer(accepted.optString(j))) hit = true
            }
            hit
        }
        else -> false
    }

    private fun toSet(a: JSONArray): Set<Int> { val s = HashSet<Int>(); for (i in 0 until a.length()) s.add(a.optInt(i, -1)); return s }

    private fun normStem(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun normAnswer(s: String): String {
        val t = s.trim().lowercase().filter { !it.isWhitespace() && it != '，' && it != ',' && it != '。' && it != '.' || it == '.' }
        val m = Regex("^-?\\d+(\\.\\d+)?").find(t)
        // 数字加单位（如 12个、3000毫升）只比数字部分；带余数（10……8）或复合单位（3升500毫升）保持原样
        val rest = if (m != null) t.substring(m.value.length) else ""
        return if (m != null && rest.isNotEmpty() && rest.length <= 4 && rest.none { it.isDigit() || it == '…' || it == '.' }) m.value else t.trimEnd('.')
    }

    private fun parseJson(text: String): JSONObject {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw IllegalStateException("AI 返回的不是 JSON")
        return JSONObject(t.substring(a, b + 1))
    }

    private fun chat(endpoint: String, key: String, model: String, system: String, user: String, temperature: Double, timeoutMs: Int): String {
        val body = JSONObject().put("model", model).put("temperature", temperature).put("max_tokens", 6000)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 12_000; conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException(when (code) { 401, 403 -> "API Key 无效或没有权限"; 404 -> "接口地址不对（404）"; 429 -> "接口限流或额度不足（429）"; else -> "接口返回 HTTP $code" })
            require(bytes.size <= 2_000_000) { "AI 返回内容过大" }
            return JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { conn.disconnect() }
    }

    private const val GEN_SYSTEM = "你是经验丰富的小学老师，为小学生出高质量的练习题。题目必须符合教材和年级水平，表述清晰，答案唯一且正确。" +
        "不要出需要看图、听音或实物的题；题干必须自包含。计算题请逐位认真核算，确保答案精确无误。" +
        "英语题的题干和选项用英文，必要时括号里加中文提示；不要出考察发音或音标的题。只输出 JSON，不要任何解释文字。"

    private const val GEN_FORMAT = "\n输出格式（只输出这个 JSON）：\n" +
        "{\"items\":[\n" +
        " {\"type\":\"single\",\"q\":\"题干\",\"a\":[\"选项1\",\"选项2\",\"选项3\",\"选项4\"],\"k\":0,\"difficulty\":2,\"tip\":\"一句话解析\"},\n" +
        " {\"type\":\"multiple\",\"q\":\"题干（选出所有正确的）\",\"a\":[\"..\",\"..\",\"..\",\"..\"],\"correctIndices\":[0,2],\"difficulty\":3,\"tip\":\"..\"},\n" +
        " {\"type\":\"boolean\",\"q\":\"一个可判断对错的陈述\",\"k\":0,\"difficulty\":1,\"tip\":\"..\"},\n" +
        " {\"type\":\"text\",\"q\":\"填空题（答案用（　）表示）\",\"answers\":[\"12\",\"12个\"],\"difficulty\":2,\"tip\":\"..\"}\n" +
        "]}\n" +
        "说明：k 与 correctIndices 是正确选项的序号（从 0 开始）；boolean 的 k：0 表示“正确”，1 表示“错误”；" +
        "text 的 answers 列出所有可接受的写法（带单位和不带单位各写一个，答案要短）；difficulty 为 1–4 的整数。"

    private const val VERIFY_SYSTEM = "你是认真细致的小学老师。下面是若干道题，请你独立、认真地解答每一道，不要猜测。只输出 JSON。"

    private const val VERIFY_FORMAT = "\n请对每道题给出你的答案，输出：\n" +
        "{\"results\":[{\"i\":0,\"k\":1},{\"i\":1,\"correctIndices\":[0,2]},{\"i\":2,\"answers\":[\"12\"]}]}\n" +
        "single 题给 k（选项序号，从 0 开始）；boolean 题给 k（0 表示“正确”，1 表示“错误”）；" +
        "multiple 题给 correctIndices；text 题给 answers（只写最终答案）。i 是题目的序号。"
}
