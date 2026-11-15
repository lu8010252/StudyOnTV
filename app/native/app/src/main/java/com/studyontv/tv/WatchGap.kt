package com.studyontv.tv

/**
 * 进程被系统清理期间漏掉的观看时长（#148）。
 *
 * 计时依赖 GateService 每秒 tick；极米 GMUI、小米等精简固件会频繁强杀后台进程，
 * 进程死掉的这段时间没有任何 tick，余额自然不减少。用户在 issue 里实测 512 秒只扣了 59 秒。
 *
 * 恢复只能靠「上一个心跳 + 现在」来估算，所以刻意保守：
 *  - 用 `min(elapsedRealtime 差, uptimeMillis 差)`：后者不含深睡，电视待机 / 关机期间不会补扣；
 *  - 设备重启（两个开机时钟都变小）直接放弃；
 *  - 只补 5 秒–10 分钟：更大的空档无法证明孩子在不在看，宁可不扣。
 *
 * 纯函数，方便回归测试直接断言边界。
 */
object WatchGap {
    /** 小于这个值多半是正常重启 / 心跳刚写过，没必要补 */
    const val MIN_RECOVER_MS = 5_000L
    /** 超过 10 分钟的空档不再补算：无法区分「一直开着电视」和「待机回来」 */
    const val MAX_RECOVER_MS = 10 * 60 * 1000L

    /**
     * @param lastElapsed 上次心跳的 SystemClock.elapsedRealtime()
     * @param lastUptime  上次心跳的 SystemClock.uptimeMillis()
     * @return 应当补扣的毫秒数；0 表示不补
     */
    fun recoverMs(
        lastElapsed: Long,
        lastUptime: Long,
        nowElapsed: Long,
        nowUptime: Long,
        maxMs: Long = MAX_RECOVER_MS,
    ): Long {
        if (lastElapsed <= 0L || lastUptime <= 0L) return 0L
        // 设备重启：开机计时归零（也可能被系统调小），两边都不能作为连续时间使用
        if (nowElapsed < lastElapsed || nowUptime < lastUptime) return 0L
        val awake = minOf(nowElapsed - lastElapsed, nowUptime - lastUptime)
        return if (awake > MIN_RECOVER_MS && awake <= maxMs) awake else 0L
    }

    /** 心跳节流：计时中每 5 秒写一次；停止计时时立刻写一次 inactive */
    fun beatDue(lastBeatAt: Long, now: Long, counting: Boolean, alreadyInactive: Boolean): Boolean =
        counting && (lastBeatAt <= 0L || now - lastBeatAt >= 5_000L) || !counting && !alreadyInactive
}
