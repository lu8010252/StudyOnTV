package com.studyontv.tv

import android.content.Context

/**
 * 「进程有没有被系统清理」的客观计数（#120 红米电视无障碍反复被关、后台反复被杀）。
 *
 * 厂商固件做后台清理时直接杀进程，`onDestroy` 不会执行，连无障碍服务也会一起被系统关掉。
 * 以前只能从 `App start` 日志里数，家长面板和诊断里看不到，issue 里也只能听描述。
 *
 * 哨兵用 `Prefs.serviceRunning`：GateService 在 `onCreate` 置 true、`onDestroy` 置 false。
 * 新进程启动时它还是 true，就说明上一个进程没走正常退出。
 */
object ProcessHealth {
    private const val FILE = "sotv_process"

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 累计「上一次进程没有正常结束」的次数 */
    fun uncleanRestarts(context: Context): Int = sp(context).getInt("unclean", 0)

    /** 在最外层 Application.onCreate 调用一次，必须早于 GateService.onCreate */
    fun noteProcessStart(context: Context) {
        try {
            val version = BuildConfig.VERSION_CODE
            val lastVersion = sp(context).getInt("version", version)
            if (Prefs.serviceRunning(context)) {
                if (lastVersion == version) {
                    val count = uncleanRestarts(context) + 1
                    sp(context).edit().putInt("unclean", count).apply()
                    Diag.warn("App", "上次进程没有正常结束（多半被系统清理），累计 $count 次")
                } else {
                    Diag.info("App", "上次进程随版本更新结束（$lastVersion→$version），不计入清理次数")
                }
                // 这一轮的存活由本进程的 GateService.onCreate 重新置位
                Prefs.setServiceRunning(context, false)
            }
            sp(context).edit().putInt("version", version).apply()
        } catch (e: Exception) { Diag.error("App", "process health check failed", e) }
    }
}
