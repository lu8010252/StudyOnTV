package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 不计时的应用白名单（用户群里高频需求）：系统屏保、网课 App 等在前台时不消耗观看配额，
 * 也不弹题、不静音。名单由家长在面板里维护，孩子改不了。
 */
object WatchWhitelist {
    const val MAX = 100

    /** Android 包名：至少两段、字母开头，允许数字和下划线 */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** 校验并去重；非法包名直接抛异常，由 saveConfig 回给面板显示 */
    fun normalize(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (item in raw) {
            val pkg = item.trim()
            if (pkg.isEmpty()) continue
            require(pkg.length <= 200 && PACKAGE.matches(pkg)) { "应用包名格式不正确：${pkg.take(60)}" }
            out.add(pkg)
        }
        require(out.size <= MAX) { "白名单最多 $MAX 个应用" }
        return out.toList()
    }

    fun of(config: JSONObject): Set<String> {
        val array = config.optJSONArray("watchWhitelist") ?: return emptySet()
        val out = HashSet<String>(array.length())
        for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    fun toJson(packages: Collection<String>): JSONArray = JSONArray().apply { packages.forEach { put(it) } }
}
