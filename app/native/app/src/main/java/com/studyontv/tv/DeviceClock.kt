package com.studyontv.tv

import android.annotation.TargetApi
import android.os.Build
import android.os.SystemClock
import java.util.Calendar
import java.util.TimeZone

/**
 * 业务日期、时间及使用者时区。
 *
 * 默认跟随设备时区；设备时区不对时（国内买的电视搬到国外、系统时间固定在中国时区、
 * 或者家长在另一个时区看规则）可以在家长面板「学习规则 → 时区」里手动指定。
 * 可用时段、全天可用、「今天」的重置与统计都按这个时区算。
 */
object DeviceClock {
    enum class Source(val id: String) { NETWORK("network"), SYSTEM("system") }
    data class Reading(val millis: Long, val source: Source)

    fun read(): Reading = read(Build.VERSION.SDK_INT, { NetworkClock.millisOrNull() }, System::currentTimeMillis)

    internal fun read(sdk: Int, networkMillis: () -> Long?, systemMillis: () -> Long): Reading {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val millis = networkMillis()
            if (millis != null) return Reading(millis, Source.NETWORK)
        }
        return Reading(systemMillis(), Source.SYSTEM)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private object NetworkClock {
        fun millisOrNull(): Long? = try {
            SystemClock.currentNetworkTimeClock().millis()
        } catch (_: Exception) {
            null
        }
    }

    /** 面板可选项；空字符串表示跟随设备 */
    val ZONES = listOf(
        "", "UTC",
        "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Taipei", "Asia/Tokyo", "Asia/Seoul", "Asia/Singapore", "Asia/Dubai",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Moscow",
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Sao_Paulo",
        "Australia/Sydney", "Pacific/Auckland"
    )

    fun setting(store: LocalStore): String = try { store.config().optString("timezone").trim() } catch (_: Exception) { "" }

    /** 配置里存的是 IANA 名称；留空或认不出来时用设备时区 */
    fun zone(store: LocalStore): TimeZone {
        val id = setting(store)
        if (id.isEmpty()) return TimeZone.getDefault()
        val zone = TimeZone.getTimeZone(id)
        // getTimeZone 认不出来会给 GMT，不能因为一个拼错的 id 把用户的时间整体平移
        return if (zone.id == "GMT" && id != "GMT" && id != "UTC") TimeZone.getDefault() else zone
    }

    fun calendar(store: LocalStore, millis: Long = store.clockReading().millis): Calendar =
        Calendar.getInstance(zone(store)).apply { timeInMillis = millis }

    /** 孩子端「下次可用」用配置的时区显示，免得设备时区不对时和家长的设置对不上 */
    fun format(millis: Long, store: LocalStore): String {
        val zone = zone(store)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).apply { timeZone = zone }
            .format(java.util.Date(millis))
        // 今明两天说「今天 / 明天」，孩子不用换算日期（Issue #147）
        val now = calendar(store)
        val target = Calendar.getInstance(zone).apply { timeInMillis = millis }
        if (sameDay(now, target)) return "今天 $time"
        now.add(Calendar.DATE, 1)
        if (sameDay(now, target)) return "明天 $time"
        return java.text.SimpleDateFormat("M 月 d 日 HH:mm", java.util.Locale.CHINA).apply { timeZone = zone }
            .format(java.util.Date(millis))
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** 面板展示用：当前生效时区与 UTC 偏移，例如 "Asia/Shanghai (UTC+08:00)" */
    fun describe(store: LocalStore, millis: Long = store.clockReading().millis): String {
        val zone = zone(store)
        val offset = zone.getOffset(millis)
        val sign = if (offset < 0) "-" else "+"
        val minutes = Math.abs(offset) / 60000
        return "%s (UTC%s%02d:%02d)".format(zone.id, sign, minutes / 60, minutes % 60)
    }
}
