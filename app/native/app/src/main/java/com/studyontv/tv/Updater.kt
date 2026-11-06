package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL












object Updater {

    private const val DEFAULT_API = "https://api.github.com/repos/lu8010252/StudyOnTV/releases/latest"
    private const val CACHE_MS = 6L * 60 * 60 * 1000

    data class Info(
        val latest: String,
        val current: String,
        val force: Boolean,
        val notes: String,
        val url: String
    )

    fun api(ctx: Context): String = Prefs.updateApi(ctx).ifEmpty { DEFAULT_API }

    
    fun check(ctx: Context, forceRefresh: Boolean = false, onResult: (Info?) -> Unit) {
        val cachedAt = Prefs.updateCheckedAt(ctx)
        val cached = cachedInfo(ctx)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            onResult(cached)
            return
        }
        Thread {
            val info = try {
                fetch(api(ctx))?.let { parse(it, currentVersion(ctx)) }
            } catch (e: Exception) {
                null
            }
            if (info != null) {
                Prefs.setUpdateInfo(ctx, JSONObject()
                    .put("latest", info.latest)
                    .put("current", info.current)
                    .put("force", info.force)
                    .put("notes", info.notes)
                    .put("url", info.url).toString())
                Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
            }
            onResult(info)
        }.apply { isDaemon = true }.start()
    }

    fun cachedInfo(ctx: Context): Info? {
        val raw = Prefs.updateInfo(ctx)
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("latest"), o.optString("current"), o.optBoolean("force"),
                o.optString("notes"), o.optString("url"))
        } catch (e: Exception) {
            null
        }
    }

    
    private fun fetch(endpoint: String): JSONObject? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "StudyOnTV-Updater")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = BufferedReader(conn.inputStream.reader()).use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(release: JSONObject, current: String): Info? {
        val tag = release.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val body = release.optString("body")
        val assets = release.optJSONArray("assets")
        val candidates = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        val marker = if (BuildConfig.FLAVOR == "tv") "-TV-" else "-Mobile-"
        val selected = candidates.firstOrNull { it.optString("name").contains(marker, true) && it.optString("name").endsWith(".apk", true) }
            ?: if (BuildConfig.FLAVOR == "tv") candidates.firstOrNull { it.optString("name").matches(Regex("StudyOnTV-v[0-9.]+\\.apk")) } else null
        val url = selected?.optString("browser_download_url")?.takeIf { it.isNotEmpty() } ?: return null
        val force = body.contains("[force]", true) || body.contains("force update", true)
        return Info(tag, current, force && isNewer(tag, current), body.trim(), url)
    }

    
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" } catch (e: Exception) { "0.0.0" }

    
    fun newerThanCurrent(ctx: Context, info: Info?): Info? =
        if (info != null && isNewer(info.latest, currentVersion(ctx))) info else null
}
