package com.studyontv.tv

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AdminAuth {
    private val random = SecureRandom()
    private val sessions = mutableMapOf<String, Long>()
    val deviceToken: String = randomToken()

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
        prefs(context).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(value, salt), Base64.NO_WRAP))
            .remove("failures").remove("blocked_until").remove("remembered").commit()
        sessions.clear()
    }

    @Synchronized
    fun verify(context: Context, value: String): Boolean {
        val p = prefs(context)
        if (!configured(context) || value.length !in 6..64 || p.getLong("blocked_until", 0) > System.currentTimeMillis()) return false
        val salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, hash(value, salt))
        if (ok) p.edit().remove("failures").apply()
        else {
            val failures = p.getInt("failures", 0) + 1
            p.edit().putInt("failures", if (failures >= 5) 0 else failures)
                .putLong("blocked_until", if (failures >= 5) System.currentTimeMillis() + 60000 else 0).commit()
        }
        return ok
    }

    @Synchronized
    fun login(context: Context, value: String, remember: Boolean = false): String? {
        if (!verify(context, value)) return null
        sessions.entries.removeAll { it.value < System.currentTimeMillis() }
        val token = randomToken()
        if (!remember) { sessions[token] = System.currentTimeMillis() + 12 * 60 * 60 * 1000L; return token }
        // 「记住这台手机」：30 天内免密码，设备重启后仍有效（只保存令牌的 SHA-256）；修改管理密码后全部失效
        val saved = remembered(context)
        saved.put(digest(token), System.currentTimeMillis() + REMEMBER_MS)
        val keys = saved.keys().asSequence().toList().sortedBy { saved.optLong(it) }
        keys.take((keys.size - 8).coerceAtLeast(0)).forEach { saved.remove(it) }   // 最多记住 8 台
        prefs(context).edit().putString("remembered", saved.toString()).commit()
        return token
    }

    private const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000
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
    fun markVerified() { verifiedAt = android.os.SystemClock.elapsedRealtime() }
    fun recentlyVerified(): Boolean = verifiedAt > 0 && android.os.SystemClock.elapsedRealtime() - verifiedAt < 120000

    @Synchronized
    fun valid(token: String?): Boolean {
        if (token == null || token.length != 64) return false
        if ((sessions[token] ?: 0) > System.currentTimeMillis()) return true
        return try { remembered(DeviceRuntime.appContext).has(digest(token)) } catch (_: Throwable) { false }
    }
}
