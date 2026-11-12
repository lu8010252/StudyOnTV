package com.studyontv.tv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #110「答题时间到，静音后但是答题弹窗弹不出来」的回归验证。
 *
 * 只验证设备上真的能验证的东西：
 * 1. `GateService.diagnostics()` 里对外暴露的诊断字段（家长面板 / `/api/device` 读的就是它）；
 * 2. 「静音是否与弹窗同源」的纯判定函数（GatePresentation）；
 * 3. 真的调用一次 `GateOverlay.show()`，断言失败时不会把 MediaPause 的持有者遗留下来。
 *
 * 不伪造窗口、不 mock UI：没有悬浮窗权限的设备上第 3 步走失败分支，
 * 有权限的设备上走成功分支并断言「收起学习页后解除静音」。
 *
 * 入口：由 RegressionInstrumentation 调用 `GatePresentationRegression.run(targetContext)`。
 */
object GatePresentationRegression {
    fun run(context: Context) {
        diagnosticsFields(context)
        pureDecisions()
        overlayFailureLeavesNoMute(context)
    }

    /** 1. 诊断字段：既有字段不能丢，#110 新增字段必须存在且类型正确 */
    private fun diagnosticsFields(context: Context) {
        val diag = GateService.diagnostics(context)
        for (key in listOf("service", "overlay", "usage", "accessibility", "accessibilityEnabled",
            "accessibilityLost", "gate", "lastTickSeconds", "lastError")) {
            check(diag.has(key)) { "#110: 既有诊断字段被删掉了: $key" }
        }
        for (key in listOf("gateExpected", "gateVisible", "overlayAttached", "overlayVisible",
            "overlayPageReady", "mute", "muteStreamMuted", "muteWithoutGate",
            "overlayProbeOnly", "forceFullscreenGate", "lastPauseHadAudio")) {
            check(diag.has(key)) { "#110: 诊断缺少字段 $key（$diag）" }
            check(diag.get(key) is Boolean) { "#110: 诊断字段 $key 必须是布尔，实际 ${diag.get(key)?.javaClass}" }
        }
        for (key in listOf("gateFailures", "lastGateErrorAt", "lastGateErrorAgeSeconds",
            "muteOwners", "muteForSeconds", "muteWithoutGateSeconds",
            // #120 红米电视：被系统清理的次数和无障碍被关掉的次数要能从诊断里数出来
            "accessibilityLostCount", "uncleanRestarts")) {
            check(diag.has(key)) { "#110: 诊断缺少字段 $key（$diag）" }
            check(diag.get(key) is Number) { "#110: 诊断字段 $key 必须是数字，实际 ${diag.get(key)?.javaClass}" }
        }
        for (key in listOf("overlayErrorKind", "overlayWindowType", "overlayLastError")) {
            check(diag.has(key)) { "#110: 诊断缺少字段 $key（$diag）" }
            check(diag.get(key) is String) { "#110: 诊断字段 $key 必须是字符串，实际 ${diag.get(key)?.javaClass}" }
        }
        // 失败原因要能一眼看出用的是哪种窗口（否则厂商差异无从排查）
        check(diag.getString("overlayWindowType").startsWith("TYPE_")) { diag.getString("overlayWindowType") }
        if (Build.VERSION.SDK_INT >= 26) {
            check(diag.getString("overlayWindowType") == "TYPE_APPLICATION_OVERLAY") { "SDK>=26 必须用 TYPE_APPLICATION_OVERLAY" }
        }
        // 距上次失败的时间：没有失败过时是 -1，失败过则 >= 0
        val age = diag.getLong("lastGateErrorAgeSeconds")
        check(age >= -1) { "lastGateErrorAgeSeconds 不能小于 -1: $age" }
        check(diag.getInt("gateFailures") >= 0)
        // 「静音但没 Gate」必须能从诊断里读出来，而不是靠猜：
        // 一旦为 true，就必须真的「在静音 + 没有可见的 Gate + 没在输入页」。
        if (diag.getBoolean("muteWithoutGate")) {
            check(diag.getBoolean("mute")) { "muteWithoutGate=true 但 mute=false: $diag" }
            check(!diag.getBoolean("gateVisible")) { "muteWithoutGate=true 但 gateVisible=true: $diag" }
            check(!InputSupport.active) { "输入页在前台时不算「静音但没 Gate」" }
        }
    }

    /** 2. 纯判定：静音与弹窗同源、失败重试间隔有上限 */
    private fun pureDecisions() {
        // 弹窗没弹出来就静音 = 不一致（issue #110 的核心状态）
        check(GatePresentation.inconsistentMute(muteActive = true, gateVisible = false, inputActive = false, free = false))
        check(!GatePresentation.inconsistentMute(muteActive = true, gateVisible = true, inputActive = false, free = false))
        // 答题输入页属于答题流程，不算不一致
        check(!GatePresentation.inconsistentMute(muteActive = true, gateVisible = false, inputActive = true, free = false))
        // #122 海信 VIDAA：悬浮窗被硬件视频层压住，家长强制开关或「探针权限 + 正在播视频」时改走全屏学习页
        check(GatePresentation.preferFullscreen(forced = true, overlayProbeOnly = false, hadAudioAtPause = false))
        check(GatePresentation.preferFullscreen(forced = false, overlayProbeOnly = true, hadAudioAtPause = true))
        check(!GatePresentation.preferFullscreen(forced = false, overlayProbeOnly = true, hadAudioAtPause = false))
        check(!GatePresentation.preferFullscreen(forced = false, overlayProbeOnly = false, hadAudioAtPause = true))
        // 自由时段本来就不该静音（GateService 每个 tick 都会 releaseAll）：真静音了就是异常，
        // 必须能被诊断看出来（与下面 muteAllowed(free = true) == false 保持同一口径）。
        check(GatePresentation.inconsistentMute(muteActive = true, gateVisible = false, inputActive = false, free = true))
        check(!GatePresentation.inconsistentMute(muteActive = false, gateVisible = false, inputActive = false, free = false))

        check(!GatePresentation.muteAllowed(gateVisible = false, inputActive = false, free = false)) { "没有可见 Gate 时不允许静音" }
        check(GatePresentation.muteAllowed(gateVisible = true, inputActive = false, free = false))
        check(GatePresentation.muteAllowed(gateVisible = false, inputActive = true, free = false))
        check(!GatePresentation.muteAllowed(gateVisible = true, inputActive = false, free = true)) { "自由时段不静音" }

        // addView 成功不等于可见：宽限期内算「正在弹出」，超出宽限期仍未校验通过就是没弹出来
        check(GatePresentation.presenting(attached = true, verifiedVisible = false, attachedForMs = 0))
        check(GatePresentation.presenting(attached = true, verifiedVisible = false, attachedForMs = GatePresentation.VISIBILITY_GRACE_MS - 1))
        check(!GatePresentation.presenting(attached = true, verifiedVisible = false, attachedForMs = GatePresentation.VISIBILITY_GRACE_MS + 1))
        check(GatePresentation.presenting(attached = true, verifiedVisible = true, attachedForMs = GatePresentation.VISIBILITY_GRACE_MS + 1))
        check(GatePresentation.presenting(attached = true, verifiedVisible = true, attachedForMs = 60_000))
        check(!GatePresentation.presenting(attached = false, verifiedVisible = true, attachedForMs = 0))

        // 学习页在前台但当前不需要 Gate（家长在首页）不算「已弹出」
        check(!GatePresentation.gateVisible(accessibilityGate = false, overlayPresenting = false, learningActivity = true, gateExpected = false))
        check(GatePresentation.gateVisible(accessibilityGate = false, overlayPresenting = false, learningActivity = true, gateExpected = true))
        check(GatePresentation.gateVisible(accessibilityGate = true, overlayPresenting = false, learningActivity = false, gateExpected = true))
        // 刚拉起的兜底 Activity 有宽限期，不能因为还没 resume 就判定「没有 Gate」而解除静音
        check(GatePresentation.gateVisible(accessibilityGate = false, overlayPresenting = false, learningActivity = false, gateExpected = true, fallbackPending = true))

        // 失败重试：2s 起，不退化、不无限高频，上限 10s
        val delays = (1..40).map { GatePresentation.failureRetryDelayMs(it) }
        check(delays.first() == 2_000L) { "第一次失败后 2 秒重试，实际 ${delays.first()}" }
        check(delays.all { it in 2_000L..GatePresentation.MAX_FAILURE_RETRY_MS }) { "重试间隔必须落在 2s..10s: $delays" }
        check(delays.zipWithNext().all { (a, b) -> b >= a }) { "重试间隔必须单调退避，不能越试越快: $delays" }
        check(delays.last() == GatePresentation.MAX_FAILURE_RETRY_MS) { "上限必须是 10 秒，实际 ${delays.last()}" }
        // 失败路径的重试不能被成功路径的 1.8s 防抖吞掉，也不能反过来比它还慢到不可接受
        check(GatePresentation.failureRetryDelayMs(1) >= GatePresentation.VISIBLE_RETRY_MS)

        // 失败原因是人+机器都能读的
        val summary = GatePresentation.failureSummary(canDraw = true, accessibility = false, api = 30)
        check(summary.isNotEmpty())
        check(GatePresentation.failureSummary(canDraw = false, accessibility = false, api = 30) != summary)
        val detail = GatePresentation.describeShowFailure(canDraw = true, accessibility = false, windowType = 2038, overlayError = "BadTokenException: nope", api = 30)
        check(detail.contains("BadTokenException")) { detail }
        check(detail.contains("canDraw=true")) { detail }
        check(detail.contains("TYPE_")) { detail }
        check(GateOverlay.windowTypeName(GateOverlay.applicationWindowType()).startsWith("TYPE_"))
    }

    /**
     * 3. 真调用一次 GateOverlay.show()：
     *    - 失败（无悬浮窗权限 / 窗口被系统拒绝）时不允许留下静音持有者；
     *    - 成功时「弹出来」= 静音，收起后必须解除静音。
     */
    private fun overlayFailureLeavesNoMute(context: Context) {
        val overlay = GateOverlay(context, GateOverlay.applicationWindowType())
        val ownersBefore = MediaPause.ownerCount
        var shown = false
        try {
            shown = onMain { overlay.show("timeup") }
            if (!shown) {
                check(!overlay.isShowing) { "show() 失败却报告 attached" }
                check(!overlay.presenting()) { "show() 失败不能算 Gate 可见" }
                check(overlay.lastError.isNotEmpty()) { "#110: 弹窗失败必须留下可读的失败原因" }
                check(overlay.lastErrorKind in setOf("permission", "window", "layout")) {
                    "#110: 失败分类必须可读，实际 ${overlay.lastErrorKind}"
                }
                check(MediaPause.ownerCount <= ownersBefore) {
                    "#110: 弹窗失败后不允许遗留静音持有者（$ownersBefore -> ${MediaPause.ownerCount}）"
                }
                check(!GateService.diagnostics(context).getBoolean("muteWithoutGate")) {
                    "#110: 弹窗失败后诊断里不能出现「静音但没 Gate」"
                }
            } else {
                check(overlay.isShowing) { "show() 成功必须报告 attached" }
                check(overlay.presenting()) { "show() 成功后宽限期内算正在弹出" }
                check(MediaPause.ownerCount == ownersBefore + 1) {
                    "弹出成功时静音必须由悬浮页持有（owners=$ownersBefore -> ${MediaPause.ownerCount}）"
                }
                // 收起悬浮页 = Gate 不再可见 → 必须解除静音，不能让孩子「忍着静音继续看」
                onMain { overlay.hide() }
                check(!overlay.isShowing)
                check(!overlay.presenting()) { "隐藏后不能继续算可见" }
                check(MediaPause.ownerCount <= ownersBefore) {
                    "#110: 收起学习页后必须解除静音（owners=${MediaPause.ownerCount}）"
                }
            }
        } finally {
            onMain { overlay.destroy() }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var failure: Throwable? = null
        // started=true 表示任务已经开始跑（说明主线程没被卡住循环，而是卡在 block 里面）
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            started.set(true)
            try { result = block() } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        // 主线程上的 WebView 冷启动在低内存设备上可能远超 10 秒；在等待期间把主线程栈打进
        // logcat，出问题时能从 `am instrument` 的日志直接定位是「慢」还是「锁死」。
        watchMainThread(done)
        if (!done.await(MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            // 超时必须能定位到「主线程在干什么」，否则只能反复试
            throw IllegalStateException("等待主线程执行超时(started=${started.get()}, ${MAIN_TIMEOUT_SECONDS}s):\n" + mainThreadDump())
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private const val MAIN_TIMEOUT_SECONDS = 30L

    /** 等待主线程时每 5 秒打一次栈（只诊断，不改变等待语义）。 */
    private fun watchMainThread(done: CountDownLatch) {
        val watcher = Thread {
            try {
                repeat(5) { i ->
                    Thread.sleep(5_000)
                    if (done.count == 0L) return@Thread
                    android.util.Log.w("SOTVRegression", "onMain 仍在等主线程（${(i + 1) * 5}s）:\n${mainThreadDump()}")
                }
            } catch (_: InterruptedException) { }
        }
        watcher.isDaemon = true
        watcher.start()
    }

    /** 主线程当前栈：`started=true` 时这里就是卡住的位置。 */
    private fun mainThreadDump(): String {
        val thread = Looper.getMainLooper().thread
        val frames = thread.stackTrace
        val head = "main thread state=${thread.state} stackDepth=${frames.size}"
        if (frames.isEmpty()) return head
        return head + "\n" + frames.joinToString("\n") { "    at $it" }
    }
}
