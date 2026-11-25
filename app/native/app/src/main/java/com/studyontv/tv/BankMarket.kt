package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 题库市场：读一份共享题库仓库的索引（[index.json]），家长在面板里点一下就把它加进本机题库。
 *
 * 索引和题库文件都放在 GitHub（jsDelivr 加速），我们不需要养服务器：
 * https://github.com/chadyi/StudyOnTV-banks
 *
 * 拉取在服务端做，而不是让网页直接 fetch：电视上的 WebView 版本很老，
 * 对 jsDelivr 的 TLS 不一定撑得住；顺带还能限制大小、超时和地址协议。
 */
object BankMarket {
    const val DEFAULT_INDEX = "https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV-banks@main/index.json"
    private const val MAX_BYTES = 20_000_000
    private const val STORE_KEY = "bank_market_sources"

    private fun saved(store: LocalStore): JSONArray =
        JSONObject(store.get(STORE_KEY, "{}")).optJSONArray("items") ?: JSONArray()

    fun settings(store: LocalStore): JSONArray = JSONArray().apply {
        put(JSONObject().put("name", "官方题库").put("url", DEFAULT_INDEX).put("official", true))
        val custom = saved(store)
        for (i in 0 until custom.length()) put(custom.getJSONObject(i))
    }

    private fun persist(store: LocalStore, items: JSONArray) {
        store.set(STORE_KEY, JSONObject().put("items", items).toString())
    }

    fun addSource(store: LocalStore, name: String, url: String): JSONObject {
        val cleanUrl = validate(url).toString()
        // 面板只让填地址，名称就取主机名，列表里好认
        val cleanName = name.trim().ifEmpty { URL(cleanUrl).host ?: "自定义题库源" }.take(40)
        val items = saved(store)
        if (settings(store).let { all -> (0 until all.length()).any { all.getJSONObject(it).optString("url") == cleanUrl } })
            throw IllegalArgumentException("这个地址已经添加过了")
        require(items.length() < 20) { "最多添加 20 个题库源" }
        items.put(JSONObject().put("name", cleanName).put("url", cleanUrl))
        persist(store, items)
        return JSONObject().put("sources", settings(store))
    }

    fun removeSource(store: LocalStore, url: String): JSONObject {
        val target = url.trim()
        val items = JSONArray()
        val old = saved(store)
        for (i in 0 until old.length()) {
            val item = old.getJSONObject(i)
            if (item.optString("url") != target) items.put(item)
        }
        persist(store, items)
        return JSONObject().put("sources", settings(store))
    }

    /** 所有题库源里的题库合并成一个列表；某个源挂了不影响其他源 */
    fun market(store: LocalStore): JSONObject {
        val banks = JSONArray()
        val failed = JSONArray()
        val seen = HashSet<String>()
        val sources = settings(store)
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val name = source.optString("name")
            try {
                val root = JSONObject(fetchText(source.getString("url")))
                val list = root.optJSONArray("banks") ?: root.optJSONArray("items") ?: JSONArray()
                for (j in 0 until list.length()) {
                    val bank = list.optJSONObject(j) ?: continue
                    val url = bank.optString("url").trim()
                    if (url.isEmpty() || !seen.add(url)) continue
                    banks.put(JSONObject(bank.toString()).put("source", name))
                }
            } catch (e: Exception) {
                failed.put(JSONObject().put("source", name).put("message", e.message ?: "拉取失败"))
            }
        }
        return JSONObject().put("sources", sources).put("banks", banks).put("failed", failed)
    }

    /** 下载一份题库文件并解析成 JSON（数组或 {items:[…]} 都可以） */
    fun json(url: String): Any {
        val text = fetchText(url)
        return try {
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("这个地址返回的不是题库 JSON")
        }
    }

    private fun validate(raw: String): URL {
        LocalOnlyGuard.requireLocalUrl(raw, "题库源")
        val url = URL(raw.trim())
        require(url.userInfo == null) { "地址里不能带用户名密码" }
        require(url.toString().length <= 2048) { "地址太长" }
        val ok = url.protocol == "https" || (url.protocol == "http" && isLocal(url.host))
        require(ok) { "公网地址必须用 https；局域网地址可以用 http" }
        return url
    }

    /** 只有当所有解析地址均落在内网/本机时才放行 http，避免混合 DNS 记录明文拉公网内容 */
    internal fun areAllPrivate(addresses: Array<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }

    private fun isLocal(host: String): Boolean {
        if (host.equals("localhost", true) || host.endsWith(".local") || host.endsWith(".lan")) return true
        return try {
            areAllPrivate(InetAddress.getAllByName(host))
        } catch (_: Exception) { false }
    }

    /**
     * 同一个文件在 Gitee 镜像上也有一份：国内直连 jsDelivr / raw.githubusercontent.com 经常超时。
     * 只映射官方题库仓库的地址，家长自己填的题库源原样使用。
     */
    internal fun giteeMirror(url: String): String? {
        val match = Regex("^https://(?:cdn\\.jsdelivr\\.net/gh|raw\\.githubusercontent\\.com)/(chadyi/StudyOnTV-banks)(?:@main|/main)/(.+)$").find(url) ?: return null
        return "https://gitee.com/${match.groupValues[1]}/raw/main/${match.groupValues[2]}"
    }

    /** 上一次走镜像成功了就先试镜像，免得每次都在超时上白等 8 秒 */
    @Volatile
    private var mirrorFirst = false

    private fun fetchText(raw: String): String {
        val primary = raw.trim()
        val mirror = giteeMirror(primary)
        val order = if (mirror == null) listOf(primary) else if (mirrorFirst) listOf(mirror, primary) else listOf(primary, mirror)
        var last: Exception? = null
        for (candidate in order) {
            try {
                val text = download(candidate)
                mirrorFirst = candidate == mirror
                return text
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalArgumentException(last?.message ?: "下载失败")
    }

    private fun download(raw: String): String {
        var current = validate(raw)
        var connection = current.openConnection() as HttpURLConnection
        return try {
            var code: Int
            var redirects = 0
            while (true) {
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "StudyOnTV/${BuildConfig.VERSION_NAME}")
                code = connection.responseCode
                if (code !in listOf(301, 302, 303, 307, 308)) break
                require(redirects++ < 5) { "重定向次数过多" }
                val loc = connection.getHeaderField("Location") ?: break
                val next = validate(URL(current, loc).toString())
                connection.disconnect()
                current = next
                connection = current.openConnection() as HttpURLConnection
            }
            require(code in 200..299) { "服务器返回 HTTP $code" }
            connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    require(buffer.size() <= MAX_BYTES) { "题库文件超过 20 MB" }
                }
                buffer.toString("UTF-8").removePrefix("﻿").trim()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("下载失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }
}
