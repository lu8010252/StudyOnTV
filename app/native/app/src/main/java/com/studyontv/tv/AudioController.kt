package com.studyontv.tv

import android.content.Context
import android.os.SystemClock

/**
 * 「静音」与「弹窗」同源的执行端（issue #110）。
 *
 * 判定本身是 [GatePresentation] 里的纯函数，这里只负责执行：Gate 真的可见才允许继续静音，
 * 弹窗失败 / 被收起就立刻解除，并在诊断日志里留下「静音了多久、为什么没弹出来」。
 * 跨 tick 的时间戳放在这里，GateService 只做协调。
 */
object AudioController {
    private var muteMismatchSince = -1L
    private var lastMuteMismatchAt = -1L
    private var lastMuteMismatchLogAt = 0L

    val active: Boolean get() = MediaPause.active
    val mismatchSince: Long get() = muteMismatchSince
    val lastMismatchAt: Long get() = lastMuteMismatchAt

    /** 服务重建时清掉上一轮的时间戳（与原来实例字段的生命周期一致） */
    fun reset() {
        muteMismatchSince = -1L
        lastMuteMismatchAt = -1L
        lastMuteMismatchLogAt = 0L
    }

    /** 静音是否允许：Gate 可见，或答题输入页在前台 */
    fun allowed(gateVisible: Boolean, free: Boolean): Boolean =
        GatePresentation.muteAllowed(gateVisible = gateVisible, inputActive = InputSupport.active, free = free)

    /** 学习页是唯一需要暂停音视频的自家页面；其他应用由 MediaPause.enforce 兜底 */
    fun syncLearningActivity(free: Boolean, gateExpected: Boolean) {
        if (free) return
        Foreground.learningActivity()?.let { if (gateExpected) MediaPause.pause(it) else MediaPause.release(it) }
    }

    /** 静音与弹窗不同源：立即解除静音，并留下可读的一笔（issue #110） */
    fun releaseWithoutGate(service: Context, source: String, gate: Boolean, visible: Boolean,
                           gateFailures: Int, lastGateError: String) {
        val now = SystemClock.elapsedRealtime()
        if (muteMismatchSince < 0) muteMismatchSince = now
        lastMuteMismatchAt = now
        if (now - lastMuteMismatchLogAt > 5000) {
            lastMuteMismatchLogAt = now
            val age = if (MediaPause.lastPauseAt > 0) (now - MediaPause.lastPauseAt) / 1000 else 0
            Diag.warn("Audio", "mute-without-gate($source): 已静音${age}s owners=${MediaPause.ownerCount} gate=$gate " +
                "visible=$visible failures=$gateFailures reason=$lastGateError → release")
        }
        MediaPause.releaseAll(service)
        muteMismatchSince = -1L
    }
}
