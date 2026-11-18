package com.studyontv.tv

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AdminAuth {
    private val random = SecureRandom()
    private val sessions = mutableMapOf<String, Long>()
    private val jvmStartNano = System.nanoTime()
    @Volatile private var blockedUntilMono = 0L
    val deviceToken: String = randomToken()

    private const val SESSION_TTL_MS = 12L * 60 * 60 * 1000L
    private const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000L
    private const val MAX_LOCKOUT_MS = 300_000L

    /**
     * 单调时钟：电视刚开机尚未完成 NTP 校时，墙上时钟可能从 1970 跳到当前年份（或被孩子修改系统时间）。
     * 会话过期与输错密码冷却统一采用单调时钟；纯 JVM 单元测试里 `elapsedRealtime()` 返回 0 时自动回退到 `nanoTime`。
     */
    internal fun monotonicMs(): Long {
        val elapsed = try { SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
        return if (elapsed > 0L) elapsed else ((System.nanoTime() - jvmStartNano) / 1_000_000L) + 1_000L
    }

    /** 累计连续输错次数对应的锁定冷却时长：第 5 次 60s、第 10 次 120s、第 15 次及以后每 5 次 300s。 */
    internal fun lockoutDurationMs(cumulativeFailures: Int): Long = when {
        cumulativeFailures < 5 || cumulativeFailures % 5 != 0 -> 0L
        cumulativeFailures == 5 -> 60_000L
        cumulativeFailures == 10 -> 120_000L
        else -> MAX_LOCKOUT_MS
    }

    private fun randomToken(): String = ByteArray(32).also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) = context.getSharedPreferences("parent_access", Context.MODE_PRIVATE)
    fun configured(context: Context): Boolean = prefs(context).contains("hash")

    private fun hash(value: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(value.toCharArray(), salt, 60000, 256)).encoded

    @Synchronized
    fun setPassword(context: Context, value: String) {
        require(value.length in 6..64) { "管理密码需要 6–64 位" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        prefs(context).edit()
            .putString("salt", MediaStore.encodeBase64(salt))
            .putString("hash", MediaStore.encodeBase64(hash(value, salt)))
            .remove("failures").remove("blocked_until").remove("remembered")
            .commit()
        blockedUntilMono = 0L
        sessions.clear()
    }

    @Synchronized
    fun verify(context: Context, value: String): Boolean {
        val p = prefs(context)
        val nowMono = monotonicMs()
        val persistedUntil = p.getLong("blocked_until", 0L)
        // 重启后若 persistedUntil 来自上次开机（剩余时间远超最大锁定时长 300s），忽略残留值防止误锁数小时
        val persistedActive = (persistedUntil - nowMono) in 1..MAX_LOCKOUT_MS
        if (!configured(context) || value.length !in 6..64 || nowMono < blockedUntilMono || persistedActive) {
            return false
        }
        val salt = MediaStore.decodeBase64(p.getString("salt", "") ?: "")
        val expected = MediaStore.decodeBase64(p.getString("hash", "") ?: "")
        val ok = MessageDigest.isEqual(expected, hash(value, salt))
        if (ok) {
            blockedUntilMono = 0L
            p.edit().remove("failures").remove("blocked_until").commit()
        } else {
            val failures = p.getInt("failures", 0) + 1
            val lockMs = lockoutDurationMs(failures)
            val until = if (lockMs > 0L) nowMono + lockMs else 0L
            if (until > 0L) blockedUntilMono = until
            p.edit().putInt("failures", failures).putLong("blocked_until", until).commit()
        }
        return ok
    }

    /** 签发新的管理会话令牌；修改密码后可直接给当前管理员续发新令牌而不必被踢回登录页。 */
    @Synchronized
    fun issueSession(remember: Boolean = false, context: Context? = null): String {
        val nowMono = monotonicMs()
        sessions.entries.removeAll { it.value <= nowMono }
        val token = randomToken()
        sessions[token] = nowMono + SESSION_TTL_MS
        if (remember) {
            val ctx = context ?: try { DeviceRuntime.appContext } catch (_: Throwable) { null }
            if (ctx != null) {
                val saved = remembered(ctx)
                saved.put(digest(token), System.currentTimeMillis() + REMEMBER_MS)
                val keys = saved.keys().asSequence().toList().sortedBy { saved.optLong(it) }
                keys.take((keys.size - 8).coerceAtLeast(0)).forEach { saved.remove(it) }
                prefs(ctx).edit().putString("remembered", saved.toString()).commit()
            }
        }
        return token
    }

    @Synchronized
    fun login(context: Context, value: String, remember: Boolean = false): String? {
        if (!verify(context, value)) return null
        return issueSession(remember = remember, context = context)
    }

    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun remembered(context: Context): org.json.JSONObject {
        val all = try { org.json.JSONObject(prefs(context).getString("remembered", "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        val now = System.currentTimeMillis()
        all.keys().asSequence().toList().forEach { if (all.optLong(it) < now) all.remove(it) }
        return all
    }

    @Synchronized
    fun forget(context: Context, token: String?) {
        if (token == null) return
        sessions.remove(token)
        val saved = remembered(context)
        if (saved.remove(digest(token)) != null) prefs(context).edit().putString("remembered", saved.toString()).commit()
    }

    @Volatile private var verifiedAt = 0L

    /** 刚在本机设置或验证过密码（2 分钟内）：从权限引导页进入家长设置时不必再输一次 */
    fun markVerified() { verifiedAt = monotonicMs() }
    fun recentlyVerified(): Boolean = verifiedAt > 0 && monotonicMs() - verifiedAt < 120_000L

    @Synchronized
    fun valid(token: String?): Boolean {
        if (token == null || token.length != 64) return false
        if ((sessions[token] ?: 0L) > monotonicMs()) return true
        return try { remembered(DeviceRuntime.appContext).has(digest(token)) } catch (_: Throwable) { false }
    }
}
