package com.studyontv.tv

import java.net.URL
import java.util.Locale

/**
 * 仅本地隐私版（EU Local-Only）网络边界守卫（Issue #175）。
 *
 * 当 BuildConfig.LOCAL_ONLY 为 true 时：
 * - 禁用所有崩溃/运行日志上报与在线更新检查/安装；
 * - 禁用在线题库市场与节假日联网同步；
 * - AI 与朗读只允许本机或家庭局域网服务（如 Ollama / 本地系统语音）；
 * - WebView 拦截所有公网子资源请求。
 */
object LocalOnlyGuard {
    val enabled: Boolean get() = BuildConfig.LOCAL_ONLY

    /**
     * 零 DNS 纯语法判定：绝不调用 InetAddress.getAllByName()，
     * 既防止向公网 DNS 泄漏域名查询，也避免在 UI 线程触发 NetworkOnMainThreadException。
     */
    fun isLocalHost(rawHost: String?): Boolean {
        val cleaned = rawHost?.trim()?.trimStart('[')?.trimEnd(']')?.lowercase(Locale.US).orEmpty()
        val host = cleaned.substringBefore('%')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        if (isPrivateIpv4Literal(host)) return true
        if (host.contains(':')) return isLocalIpv6Literal(host)
        return false
    }

    private fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        return a == 127 ||
            a == 10 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254)
    }

    private fun isLocalIpv6Literal(host: String): Boolean {
        if (host.startsWith("::ffff:")) {
            val mapped = host.substring(7)
            if (mapped.contains('.')) return isPrivateIpv4Literal(mapped)
        }
        val doubleColon = host.indexOf("::")
        if (doubleColon != host.lastIndexOf("::")) return false
        val leftParts = if (doubleColon >= 0) host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() } else host.split(':')
        val rightParts = if (doubleColon >= 0) host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() } else emptyList()
        if (doubleColon < 0 && leftParts.size != 8) return false
        if (doubleColon >= 0 && leftParts.size + rightParts.size >= 8) return false
        val groups = IntArray(8)
        for (i in leftParts.indices) {
            val v = parseHexGroup(leftParts[i]) ?: return false
            groups[i] = v
        }
        val rightOffset = 8 - rightParts.size
        for (i in rightParts.indices) {
            val v = parseHexGroup(rightParts[i]) ?: return false
            groups[rightOffset + i] = v
        }
        // Loopback ::1
        if ((0..6).all { groups[it] == 0 } && groups[7] == 1) return true
        // ULA fc00::/7
        if ((groups[0] and 0xFE00) == 0xFC00) return true
        // Link-local fe80::/10
        if ((groups[0] and 0xFFC0) == 0xFE80) return true
        return false
    }

    private fun parseHexGroup(part: String): Int? {
        if (part.isEmpty() || part.length > 4) return null
        return part.toIntOrNull(16)
    }

    fun isLocalUrl(rawUrl: String?): Boolean {
        val u = rawUrl?.trim().orEmpty()
        if (u.isEmpty()) return true
        val lower = u.lowercase(Locale.US)
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:") ||
            lower.startsWith("file:") || lower.startsWith("content:")
        ) return true
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ws://") || lower.startsWith("wss://"))) {
            return false
        }
        val normalized = when {
            lower.startsWith("ws://") -> "http://" + u.substring(5)
            lower.startsWith("wss://") -> "https://" + u.substring(6)
            else -> u
        }
        return try {
            isLocalHost(URL(normalized).host)
        } catch (_: Exception) {
            false
        }
    }

    fun requireLocalUrl(rawUrl: String, purpose: String) {
        if (enabled && !isLocalUrl(rawUrl)) {
            throw IllegalArgumentException("仅本地隐私版（EU Local-Only）仅允许连接本机或局域网内的$purpose")
        }
    }
}
