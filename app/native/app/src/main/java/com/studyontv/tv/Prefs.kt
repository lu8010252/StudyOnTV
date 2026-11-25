package com.studyontv.tv

import android.content.Context





object Prefs {
    private const val FILE = "sotv"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    
    fun localPort(c: Context): Int = sp(c).getInt("local_port", 8099)
    fun setLocalPort(c: Context, port: Int) = sp(c).edit().putInt("local_port", port).apply()

    fun localBase(c: Context): String = "http://127.0.0.1:" + localPort(c) + "/"

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, v: Boolean) = sp(c).edit().putBoolean("onboarded", v).apply()

    

    fun deviceName(c: Context): String =
        sp(c).getString("device_name", android.os.Build.MODEL ?: "客厅电视") ?: "客厅电视"
    fun setDeviceName(c: Context, v: String) = sp(c).edit().putString("device_name", v).apply()

    @Synchronized
    fun deviceId(c: Context): String {
        val p = sp(c)
        val existing = p.getString("device_id", null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        p.edit().putString("device_id", generated).apply()
        return generated
    }

    
    fun updateInfo(c: Context): String = sp(c).getString("update_info", "") ?: ""
    fun setUpdateInfo(c: Context, v: String) = sp(c).edit().putString("update_info", v).apply()
    fun updateCheckedAt(c: Context): Long = sp(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, v: Long) = sp(c).edit().putLong("update_checked_at", v).apply()
    fun updateApi(c: Context): String = sp(c).getString("update_api", "") ?: ""
    fun setUpdateApi(c: Context, v: String) = sp(c).edit().putString("update_api", v).apply()
    /** null 表示使用默认镜像列表 */
    fun updateMirrors(c: Context): String? = sp(c).getString("update_mirrors", null)
    fun setUpdateMirrors(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("update_mirrors") else putString("update_mirrors", v) }.apply()
    fun updateLastSource(c: Context): String = sp(c).getString("update_last_source", "") ?: ""
    fun setUpdateLastSource(c: Context, v: String) = sp(c).edit().putString("update_last_source", v).apply()
    fun updateLog(c: Context): String = sp(c).getString("update_log", "") ?: ""
    fun setUpdateLog(c: Context, v: String) = sp(c).edit().putString("update_log", v).apply()

    /** 最近一次安装失败的原因（系统 PackageInstaller 返回的原文），装成功后会清空 */
    fun updateInstallError(c: Context): String = sp(c).getString("update_install_error", "") ?: ""
    fun setUpdateInstallError(c: Context, v: String) = sp(c).edit().putString("update_install_error", v).apply()

    /** 是否在发现新版本时后台自动静默预下载更新包（默认关闭，EU Local-Only 恒为 false） */
    fun autoDownloadUpdate(c: Context): Boolean = sp(c).getBoolean("auto_download_update", false)
    fun setAutoDownloadUpdate(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_download_update", v).apply()

    /** 已在本地完成校验就绪的预下载 APK 版本号与 SHA-256 */
    fun readyApkVersion(c: Context): String = sp(c).getString("ready_apk_version", "") ?: ""
    fun setReadyApkVersion(c: Context, v: String) = sp(c).edit().putString("ready_apk_version", v).apply()
    fun readyApkSha256(c: Context): String = sp(c).getString("ready_apk_sha256", "") ?: ""
    fun setReadyApkSha256(c: Context, v: String) = sp(c).edit().putString("ready_apk_sha256", v).apply()

    
    fun updateInProgress(c: Context): Boolean = sp(c).getLong("update_started_at", 0L).let { it > 0 && System.currentTimeMillis() - it < 10 * 60 * 1000 }
    fun setUpdateInProgress(c: Context, v: Boolean) = sp(c).edit().putLong("update_started_at", if (v) System.currentTimeMillis() else 0L).apply()

    fun serviceRunning(c: Context): Boolean = sp(c).getBoolean("service_running", false)
    fun setServiceRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("service_running", v).apply()

    // 计时心跳：电视内存很小，进程随时可能被系统杀掉。每走几秒表就记一次“记到哪儿了”，
    // 服务重启后据此把被杀期间漏掉的时间补回来（counting=当时是否正在计时）
    fun heartbeat(c: Context): Long = sp(c).getLong("heartbeat_at", 0L)
    fun heartbeatCounting(c: Context): Boolean = sp(c).getBoolean("heartbeat_counting", false)
    fun setHeartbeat(c: Context, at: Long, counting: Boolean) =
        sp(c).edit().putLong("heartbeat_at", at).putBoolean("heartbeat_counting", counting).apply()
    /** 无障碍被系统或用户关掉的累计次数，以及最后一次的时间（#120：红米电视会被系统清理连带关掉） */
    fun accessibilityLostCount(c: Context): Int = sp(c).getInt("accessibility_lost_count", 0)
    fun setAccessibilityLostCount(c: Context, v: Int) = sp(c).edit().putInt("accessibility_lost_count", v).apply()
    fun accessibilityLostAt(c: Context): Long = sp(c).getLong("accessibility_lost_at", 0L)
    fun setAccessibilityLostAt(c: Context, v: Long) = sp(c).edit().putLong("accessibility_lost_at", v).apply()

    /** 通知权限是否已自动询问过；拒绝后不再自动弹，仍可从列表手动再请求。 */
    fun notificationAsked(c: Context): Boolean = sp(c).getBoolean("notification_asked", false)
    fun setNotificationAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("notification_asked", v).apply()

    /** null = 未表态（尚未被问过）, true = 同意, false = 拒绝 */
    fun buglyConsent(c: Context): Boolean = sp(c).getBoolean("bugly_consent", false)
    fun setBuglyConsent(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent", v).apply()
    fun buglyConsentAsked(c: Context): Boolean = sp(c).getBoolean("bugly_consent_asked", false)
    fun setBuglyConsentAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent_asked", v).apply()

    // --- 软件体验提升计划（运行与崩溃日志上传，兼容阿里云 OSS 与自建 Worker）---

    /** 是否参与软件体验提升计划（默认开启，家长可在登录页或设置页随时关闭） */
    fun crashUploadConsent(c: Context): Boolean = sp(c).getBoolean("crash_upload_consent", true)
    fun setCrashUploadConsent(c: Context, v: Boolean) = sp(c).edit().putBoolean("crash_upload_consent", v).apply()
    fun crashUploadConsentAsked(c: Context): Boolean = sp(c).getBoolean("crash_upload_consent_asked", false)
    fun setCrashUploadConsentAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("crash_upload_consent_asked", v).apply()

    /** 上次自动上报运行诊断日志的时间戳（用于按固定间隔节流） */
    fun lastTelemetryUploadAt(c: Context): Long = sp(c).getLong("last_telemetry_upload_at", 0L)
    fun setLastTelemetryUploadAt(c: Context, ts: Long) = sp(c).edit().putLong("last_telemetry_upload_at", ts).apply()

    /**
     * Cloudflare Worker 接收端 URL。面板里填了就优先用面板的，
     * 没填则用构建时内置的 BuildConfig.CRASH_ENDPOINT（local.properties / 环境变量注入，不入库）。
     * 两处都为空表示只本地存储，不上传。
     */
    fun crashEndpointStored(c: Context): String = sp(c).getString("crash_endpoint", "") ?: ""
    fun setCrashEndpoint(c: Context, v: String) = sp(c).edit().putString("crash_endpoint", v).apply()
    fun crashEndpoint(c: Context): String = crashEndpointStored(c).ifBlank { BuildConfig.CRASH_ENDPOINT }

    /** Worker 的 Bearer Token，与 Worker 里的 SECRET_TOKEN 一致；同样支持内置默认值 */
    fun crashTokenStored(c: Context): String = sp(c).getString("crash_token", "") ?: ""
    fun setCrashToken(c: Context, v: String) = sp(c).edit().putString("crash_token", v).apply()
    fun crashToken(c: Context): String = crashTokenStored(c).ifBlank { BuildConfig.CRASH_TOKEN }

    // --- 观看计时心跳（#148：进程被固件清理后计时停止，重启后补算被杀期间） ---
    //
    // 只记录「正在计时」时的墙上时间 / 开机时间 / 深睡时间三个时钟：
    //  - elapsedRealtime 含深睡，uptimeMillis 不含；取较小者就是「设备真正醒着」的时长，
    //    这样关机待机数小时不会被当成观看时长；
    //  - 设备重启后开机时间会归零，由 WatchGap.recoverMs 判断并放弃补算。

    data class WatchBeat(val active: Boolean, val at: Long, val elapsed: Long, val uptime: Long)

    fun watchBeat(c: Context): WatchBeat? {
        val p = sp(c)
        if (!p.contains("watch_beat_elapsed")) return null
        return WatchBeat(p.getBoolean("watch_beat_active", false), p.getLong("watch_beat_at", 0L),
            p.getLong("watch_beat_elapsed", 0L), p.getLong("watch_beat_uptime", 0L))
    }

    /** 心跳用同步 commit：进程随时可能被固件直接杀掉，apply 还没落盘就丢了 */
    fun setWatchBeat(c: Context, beat: WatchBeat) = sp(c).edit()
        .putBoolean("watch_beat_active", beat.active).putLong("watch_beat_at", beat.at)
        .putLong("watch_beat_elapsed", beat.elapsed).putLong("watch_beat_uptime", beat.uptime)
        .commit()

    fun clearWatchBeat(c: Context) = sp(c).edit().remove("watch_beat_active").remove("watch_beat_at")
        .remove("watch_beat_elapsed").remove("watch_beat_uptime").commit()
}
