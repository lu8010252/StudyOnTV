package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 单线程串行同步，先验证完整批次再提交，失败保留本地题库。 */
object BankAutomation {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var checkedAt = 0L
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    /** AI 补题可选题型（#125：电视上填空题输入麻烦，家长可以只补单选题） */
    private val QUESTION_TYPES = listOf("single", "multiple", "boolean", "text", "subjective")
    /** 老版本写死的题型，配置里没有 types 时沿用，避免升级后出题行为突然变化 */
    private val LEGACY_TYPES = listOf("single", "text")
    fun settings(store: LocalStore): JSONObject = JSONObject(store.get("bank_automation", "{}"))
        .apply {
            if (!has("sources")) put("sources", JSONArray())
            if (optJSONArray("types") == null || optJSONArray("types")!!.length() == 0) put("types", JSONArray(LEGACY_TYPES))
        }
        .put("running", running.get()).put("status", JSONObject(store.get("bank_automation_status", "{}")))
    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val sources = input.optJSONArray("sources") ?: JSONArray()
        require(sources.length() <= 20) { "最多配置 20 个远程题库" }
        val names = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val clean = JSONArray()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val name = source.getString("name").trim()
            require(name.length in 1..40 && names.add(name)) { "远程题库名称需唯一，1–40 个字" }
            val url = URL(source.getString("url").trim())
            require(url.protocol in listOf("http", "https") && url.userInfo == null && url.toString().length <= 2048) { "请使用不含用户名密码的 HTTP/HTTPS 地址" }
            require(urls.add(url.toString())) { "同一远程地址只能配置一次" }
            clean.put(JSONObject().put("name", name).put("url", url.toString()).put("enabled", source.optBoolean("enabled", true)))
        }
        val minimum = input.optInt("minimum", 30)
        val batch = input.optInt("batch", 10)
        val attempts = input.optInt("masteryAttempts", 5)
        val accuracy = input.optInt("masteryAccuracy", 90)
        require(minimum in 1..500 && batch in 1..20 && attempts in 1..100 && accuracy in 50..100) { "自动题库参数超出范围" }
        val topic = input.optString("topic").trim()
        require(topic.length <= 2000 && (!input.optBoolean("autoAi") || topic.isNotBlank())) { "自动补题需要填写出题范围" }
        // #125：题型筛选。前端传了 types 就必须是至少一种有效题型，没传沿用旧的两类
        val types = input.optJSONArray("types")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it in QUESTION_TYPES }.distinct()
        }
        if (input.has("types")) require(!types.isNullOrEmpty()) { "请选择至少一种自动补题的题型" }
        // 老客户端不带 types 时不覆盖已经选好的题型
        val saved = if (types?.isNotEmpty() == true) types
            else settings(store).optJSONArray("types")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it in QUESTION_TYPES } }.orEmpty()
                .ifEmpty { LEGACY_TYPES }
        store.set("bank_automation", JSONObject().put("sources", clean).put("autoAi", input.optBoolean("autoAi"))
            .put("minimum", minimum).put("batch", batch).put("masteryAttempts", attempts).put("masteryAccuracy", accuracy)
            .put("topic", topic).put("types", JSONArray(saved)).toString())
        return settings(store)
    }
    @Synchronized fun tick(store: LocalStore) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (checkedAt > 0 && now - checkedAt < 15 * 60000) return
        checkedAt = now
        start(store)
    }
    fun start(store: LocalStore) {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            val report = JSONObject().put("at", System.currentTimeMillis())
            try {
                val config = settings(store)
                val results = JSONArray()
                val sources = config.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    if (!source.optBoolean("enabled", true)) continue
                    val entry = JSONObject().put("name", source.getString("name"))
                    try { entry.put("imported", syncSource(store, source)).put("ok", true) }
                    catch (_: Exception) { entry.put("ok", false).put("message", "同步失败，请检查远程 JSON、地址和网络；本地题库已保留") }
                    results.put(entry)
                }
                report.put("sources", results)
                if (config.optBoolean("autoAi")) {
                    val all = store.questions(limit = 50000)
                    val mastered = mutableListOf<String>()
                    for (i in 0 until all.length()) {
                        val q = all.getJSONObject(i); val stats = q.optJSONObject("stats") ?: continue
                        val answered = stats.optInt("answered")
                        if (answered >= config.optInt("masteryAttempts", 5) && stats.optInt("correct") * 100 >= answered * config.optInt("masteryAccuracy", 90)) mastered.add(q.getString("id"))
                    }
                    if (mastered.isNotEmpty()) store.setEnabled(mastered, false)
                    report.put("pausedMastered", mastered.size)
                    if (store.countQuestions() < config.optInt("minimum", 30)) {
                        val result = GradingService.generateQuestions(store, JSONObject().put("topic", config.getString("topic"))
                            .put("count", config.optInt("batch", 10))
                            .put("types", config.optJSONArray("types")?.takeIf { it.length() > 0 } ?: JSONArray(LEGACY_TYPES))
                            .put("difficulty", "mixed").put("withTip", true).put("skipDuplicates", true))
                        val items = result.getJSONArray("items")
                        for (i in 0 until items.length()) items.getJSONObject(i).put("bank", "AI补题")
                        if (items.length() > 0) store.importQuestions(items)
                        report.put("generated", items.length())
                    }
                }
                report.put("ok", true)
            } catch (_: Exception) { report.put("ok", false).put("message", "自动补题失败，请检查 AI 配置和网络；已有题目保留") }
            finally { store.set("bank_automation_status", report.toString()); running.set(false) }
        }
    }
    fun syncSource(store: LocalStore, source: JSONObject): Int {
        val connection = URL(source.getString("url")).openConnection() as HttpURLConnection
        val text = try {
            connection.connectTimeout = 10000; connection.readTimeout = 20000
            require(connection.responseCode in 200..299)
            connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; buffer.write(chunk, 0, count); require(buffer.size() <= 4_000_000) }
                buffer.toString("UTF-8").removePrefix("\uFEFF").trim()
            }
        } finally { connection.disconnect() }
        val items = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("items")
        require(items.length() in 1..50000) { "远程题库需要 1–50000 题" }
        val sourceId = hash(source.getString("url"))
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val old = mutableListOf<String>()
            db.rawQuery("SELECT id,details FROM questions", null).use { cursor ->
                while (cursor.moveToNext()) if (JSONObject(cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "{}").optString("remoteSource") == sourceId) old.add(cursor.getString(0))
            }
            val keep = mutableSetOf<String>()
            for (i in 0 until items.length()) {
                val q = items.getJSONObject(i)
                val id = "remote_${sourceId}_" + hash(q.optString("id").ifBlank { q.getString("q") })
                require(keep.add(id)) { "远程题库存在重复 ID 或题干" }
                q.put("id", id).put("bank", "远程·" + source.getString("name")).put("remoteSource", sourceId)
                store.upsertQuestion(q)
            }
            store.deleteQuestions(old.filter { it !in keep })
            db.setTransactionSuccessful()
            return items.length()
        } finally { db.endTransaction() }
    }
}
