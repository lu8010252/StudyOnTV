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

    
    fun updateInfo(c: Context): String = sp(c).getString("update_info", "") ?: ""
    fun setUpdateInfo(c: Context, v: String) = sp(c).edit().putString("update_info", v).apply()
    fun updateCheckedAt(c: Context): Long = sp(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, v: Long) = sp(c).edit().putLong("update_checked_at", v).apply()
    fun updateApi(c: Context): String = sp(c).getString("update_api", "") ?: ""
    fun setUpdateApi(c: Context, v: String) = sp(c).edit().putString("update_api", v).apply()

    
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
}
