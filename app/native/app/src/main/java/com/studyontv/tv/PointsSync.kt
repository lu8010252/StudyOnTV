package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 积分同步：答题得到的积分先记入本地账本，再推送到星星屋（星星屋是积分的唯一权威）。
 * - 推送带账本 id 作为幂等键 extId，断网重试、重复推送都不会重复加分
 * - 余额以星星屋为准（家长在星星屋里加扣分、兑换商品后，这里刷新即可看到）
 * - 只保存星星屋换回的令牌，不保存家庭密码
 */
object PointsSync {
    private const val KEY = "points_settings"
    private const val SOURCE = "studyontv"
    private val running = AtomicBoolean(false)

    @Volatile private var lastSync = 0L
    @Volatile private var lastError = ""

    private fun raw(store: LocalStore): JSONObject = try { JSONObject(store.get(KEY, "{}")) } catch (e: Exception) { JSONObject() }

    private fun withDefaults(saved: JSONObject): JSONObject {
        val o = JSONObject().put("enabled", false).put("baseUrl", "").put("token", "").put("childId", 0)
            .put("childName", "").put("scoreMode", "flat").put("perQuestion", 1).put("perStar", 1)
            .put("passBonus", 5).put("dailyCap", 30).put("balance", -1)
        saved.keys().forEach { o.put(it, saved.get(it)) }
        return o
    }

    fun settings(store: LocalStore): JSONObject = withDefaults(raw(store))

    private fun persist(store: LocalStore, value: JSONObject) = store.set(KEY, value.toString())

    private fun normalizeUrl(input: String): String {
        val v = input.trim().trimEnd('/')
        require(v.startsWith("http://") || v.startsWith("https://")) { "星星屋地址需以 http:// 或 https:// 开头" }
        return v
    }

    // ---------- 答题得分 ----------

    /**
     * 答对一题，受每日上限约束；返回本次实际得到的积分。计分方式可选：
     * - flat（默认）：答对一题固定加 perQuestion 分（默认 1 分），不看星级
     * - star：积分 = 题目星级 × 每星积分 perStar
     */
    fun earnForAnswer(store: LocalStore, difficulty: Int): Int {
        val s = settings(store)
        if (!s.optBoolean("enabled") || s.optInt("childId") <= 0) return 0
        return if (s.optString("scoreMode") == "star") {
            val stars = difficulty.coerceIn(1, 5)
            grant(store, s, stars * s.optInt("perStar", 1).coerceIn(0, 100), "电视答题 ${stars}星题")
        } else {
            grant(store, s, s.optInt("perQuestion", 1).coerceIn(0, 100), "电视答题 答对一题")
        }
    }

    /** 通过一组任务的额外奖励。 */
    fun earnForPass(store: LocalStore): Int {
        val s = settings(store)
        if (!s.optBoolean("enabled") || s.optInt("childId") <= 0) return 0
        return grant(store, s, s.optInt("passBonus", 5).coerceIn(0, 1000), "电视答题 通过一组")
    }

    private fun grant(store: LocalStore, s: JSONObject, want: Int, reason: String): Int {
        if (want <= 0) return 0
        val cap = s.optInt("dailyCap", 30)
        val room = if (cap > 0) (cap - store.pointsEarnedToday()).coerceAtLeast(0) else want
        val amount = minOf(want, room)
        if (amount <= 0) return 0
        store.addPoints(amount, reason)
        return amount
    }

    // ---------- 网络 ----------

    private class HttpResult(val code: Int, val body: String)

    private fun call(method: String, url: String, token: String?, body: JSONObject?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "StudyOnTV-PointsSync")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("x-token", token)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResult(code, text)
        } finally { conn.disconnect() }
    }

    private fun message(r: HttpResult): String = try { JSONObject(r.body).optString("error", "HTTP ${r.code}") } catch (e: Exception) { "HTTP ${r.code}" }

    private fun login(baseUrl: String, password: String): String {
        val r = call("POST", "$baseUrl/api/login", null, JSONObject().put("password", password))
        if (r.code != 200) throw IllegalArgumentException(if (r.code == 401) "星星屋家庭密码不对" else "登录星星屋失败：${message(r)}")
        return JSONObject(r.body).getString("token")
    }

    /** 用家庭密码登录并取回孩子列表，供后台选择要绑定的孩子。密码留空则使用已保存的令牌。 */
    fun listChildren(store: LocalStore, input: JSONObject): JSONObject {
        val saved = settings(store)
        val base = normalizeUrl(input.optString("baseUrl").ifEmpty { saved.optString("baseUrl") })
        val password = input.optString("password")
        val token = if (password.isNotEmpty()) login(base, password) else saved.optString("token")
        require(token.isNotEmpty()) { "请填写星星屋家庭密码" }
        val r = call("GET", "$base/api/ext/children", token, null)
        if (r.code != 200) throw IllegalArgumentException("读取孩子列表失败：${message(r)}（星星屋需要更新到支持 /api/ext 的版本）")
        return JSONObject().put("items", JSONArray(r.body))
    }

    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val cur = settings(store)
        val enable = input.optBoolean("enabled", cur.optBoolean("enabled"))
        val baseRaw = input.optString("baseUrl", cur.optString("baseUrl"))
        val base = if (baseRaw.isBlank() && !enable) "" else normalizeUrl(baseRaw)
        val password = input.optString("password")
        if (password.isNotEmpty() && base.isNotEmpty()) cur.put("token", login(base, password))
        if (base != cur.optString("baseUrl") && password.isEmpty()) cur.put("token", "")
        cur.put("baseUrl", base)
        if (input.has("childId")) cur.put("childId", input.optInt("childId"))
        if (input.has("childName")) cur.put("childName", input.optString("childName").take(20))
        if (input.has("scoreMode")) {
            require(input.optString("scoreMode") in listOf("flat", "star")) { "未知的计分方式" }
            cur.put("scoreMode", input.optString("scoreMode"))
        }
        if (input.has("perQuestion")) cur.put("perQuestion", input.optInt("perQuestion").coerceIn(0, 100))
        if (input.has("perStar")) cur.put("perStar", input.optInt("perStar").coerceIn(0, 100))
        if (input.has("passBonus")) cur.put("passBonus", input.optInt("passBonus").coerceIn(0, 1000))
        if (input.has("dailyCap")) cur.put("dailyCap", input.optInt("dailyCap").coerceIn(0, 10000))
        if (enable) {
            require(cur.optString("token").isNotEmpty()) { "请先填写星星屋家庭密码完成登录" }
            require(cur.optInt("childId") > 0) { "请先选择要绑定的孩子" }
        }
        cur.put("enabled", enable)
        persist(store, cur)
        return summary(store)
    }

    // ---------- 同步 ----------

    /** 异步触发一次推送，不阻塞答题。 */
    fun kick(store: LocalStore) {
        Thread {
            try { Thread.sleep(800) } catch (e: InterruptedException) { return@Thread } // 等答题事务提交
            syncNow(store)
        }.apply { isDaemon = true }.start()
    }

    /** 推送待同步账本并刷新余额；同一时间只跑一个。 */
    fun syncNow(store: LocalStore): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            val s = settings(store)
            if (!s.optBoolean("enabled")) return false
            val base = s.optString("baseUrl")
            val token = s.optString("token")
            val childId = s.optInt("childId")
            if (base.isEmpty() || token.isEmpty() || childId <= 0) return false
            try {
                for ((id, delta, reason) in store.pendingPoints()) {
                    val body = JSONObject().put("childId", childId).put("delta", delta).put("reason", reason)
                        .put("extId", "stv-$id").put("source", SOURCE)
                    val r = call("POST", "$base/api/ext/score", token, body)
                    if (r.code == 401) throw IllegalStateException("星星屋家庭密码已变更，请在后台重新登录")
                    if (r.code != 200) throw IllegalStateException("星星屋返回：${message(r)}")
                    store.markPointsSynced(id)
                    persist(store, settings(store).put("balance", JSONObject(r.body).optInt("points", -1)))
                }
                val r = call("GET", "$base/api/ext/children", token, null)
                if (r.code == 200) {
                    val list = JSONArray(r.body)
                    for (i in 0 until list.length()) {
                        val c = list.getJSONObject(i)
                        if (c.optInt("id") == childId) persist(store, settings(store).put("balance", c.optInt("points")).put("childName", c.optString("name")))
                    }
                }
                lastSync = System.currentTimeMillis()
                lastError = ""
                return true
            } catch (e: Exception) {
                lastError = e.message ?: "网络错误"
                return false
            }
        } finally { running.set(false) }
    }

    // ---------- 展示 ----------

    /** 显示用余额 = 星星屋已确认的余额 + 本地还没推送成功的积分，答完题马上就能看到变化。未获取过余额时返回 -1。 */
    private fun shownBalance(store: LocalStore, s: JSONObject): Int {
        val b = s.optInt("balance", -1)
        return if (b < 0) -1 else b + store.pendingPointsSum()
    }

    /** 轻量摘要，随学习状态一起返回给电视/平板页面。 */
    fun brief(store: LocalStore): JSONObject {
        val s = settings(store)
        return JSONObject().put("enabled", s.optBoolean("enabled"))
            .put("balance", shownBalance(store, s)).put("today", store.pointsEarnedToday())
            .put("name", s.optString("childName"))
    }

    /** 完整摘要，给家长后台；不返回令牌。 */
    fun summary(store: LocalStore): JSONObject {
        val s = settings(store)
        return JSONObject().put("enabled", s.optBoolean("enabled")).put("baseUrl", s.optString("baseUrl"))
            .put("loggedIn", s.optString("token").isNotEmpty()).put("childId", s.optInt("childId"))
            .put("childName", s.optString("childName")).put("scoreMode", s.optString("scoreMode", "flat"))
            .put("perQuestion", s.optInt("perQuestion", 1)).put("perStar", s.optInt("perStar", 1))
            .put("passBonus", s.optInt("passBonus", 5)).put("dailyCap", s.optInt("dailyCap", 30))
            .put("balance", shownBalance(store, s)).put("today", store.pointsEarnedToday())
            .put("pending", store.pendingPointsCount()).put("lastSync", lastSync).put("lastError", lastError)
            .put("version", BuildConfig.VERSION_NAME).put("recent", store.recentPoints(20))
    }
}
