package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 家庭自动化（Home Assistant 等）用的独立令牌。
 * - 默认关闭，只能由家长面板生成、修改权限或吊销；
 * - 只保存令牌的 SHA-256，明文只在生成时返回一次；
 * - 权限分「只读」和「控制」：只读可看学习状态，控制可远程解锁 / 暂停。
 */
object IntegrationAuth {
    private val random = SecureRandom()
    private const val PREFIX = "sotv_"
    private const val PER_MINUTE = 240
    private var windowStart = 0L
    private var windowCount = 0

    data class Scope(val read: Boolean, val control: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences("integration", Context.MODE_PRIVATE)

    private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun state(context: Context): JSONObject {
        val p = prefs(context)
        return JSONObject()
            .put("enabled", p.getBoolean("enabled", false))
            .put("read", p.getBoolean("read", true))
            .put("control", p.getBoolean("control", false))
            .put("hasToken", p.contains("hash"))
            .put("hint", p.getString("hint", ""))
            .put("createdAt", p.getLong("created_at", 0))
    }

    @Synchronized
    fun update(context: Context, enabled: Boolean?, read: Boolean?, control: Boolean?) {
        val edit = prefs(context).edit()
        if (enabled != null) edit.putBoolean("enabled", enabled)
        if (read != null) edit.putBoolean("read", read)
        if (control != null) edit.putBoolean("control", control)
        edit.commit()
    }

    /** 生成新令牌，旧令牌立即失效。明文只在这里返回一次。 */
    @Synchronized
    fun issue(context: Context, read: Boolean, control: Boolean): String {
        val token = PREFIX + hex(ByteArray(32).also { random.nextBytes(it) })
        prefs(context).edit()
            .putBoolean("enabled", true).putBoolean("read", read).putBoolean("control", control)
            .putString("hash", hex(digest(token)))
            .putString("hint", token.take(PREFIX.length + 6) + "…")
            .putLong("created_at", System.currentTimeMillis())
            .commit()
        return token
    }

    @Synchronized
    fun revoke(context: Context) {
        prefs(context).edit().remove("hash").remove("hint").remove("created_at").putBoolean("enabled", false).commit()
    }

    /** 校验令牌并返回权限；未启用、没有令牌或不匹配都返回 null。 */
    @Synchronized
    fun verify(context: Context, token: String?): Scope? {
        if (token == null || token.length !in 16..128 || !token.startsWith(PREFIX)) return null
        val p = prefs(context)
        if (!p.getBoolean("enabled", false)) return null
        val saved = p.getString("hash", "") ?: return null
        if (saved.length != 64) return null
        if (!MessageDigest.isEqual(saved.toByteArray(), hex(digest(token)).toByteArray())) return null
        return Scope(p.getBoolean("read", true), p.getBoolean("control", false))
    }

    /** 简单的总量限流：令牌已经足够随机，这里只防脚本反复刷接口。 */
    @Synchronized
    fun takeBudget(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart > 60_000) { windowStart = now; windowCount = 0 }
        windowCount++
        return windowCount <= PER_MINUTE
    }
}
