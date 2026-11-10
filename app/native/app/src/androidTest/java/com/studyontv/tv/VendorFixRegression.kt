package com.studyontv.tv

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * 本轮「厂商问题分析」（docs/vendor-issues-analysis.md）里判定成我们自己的 bug 的回归验证：
 *
 *  #17 强制休息死区 + 无权限静默失效 → 面板显示还有时间却一毫秒都扣不动
 *  #93 前台识别回落到自身 / 陈旧包名 → exempt() 恒真，计时与拦截同时失效
 *  #92 长虹「悬浮窗已开仍提示未开启」→ 只信 Settings.canDrawOverlays，从不真实 addView
 *
 * 只用独立数据库（跑完删掉），不碰家庭题库，也不依赖任何第三方测试库。
 * 入口：由 [RegressionInstrumentation] 调用 `VendorFixRegression.run(targetContext)`。
 */
object VendorFixRegression {
    fun run(context: Context) {
        restNoDeadZone(context)
        restConfigToggle(context)
        feedbackNoContradiction(context)
        foregroundNeverSelf(context)
        overlayProbe(context)
        diagnostics(context)
    }

    // ------------------------------------------------------------------ #17 休息死区

    private fun drain(engine: LearningEngine, store: LocalStore, calls: Int = 120): Long {
        var total = 0L
        repeat(calls) {
            val before = store.get("used_ms").toLongOrNull() ?: 0L
            engine.consume(1000)
            val after = store.get("used_ms").toLongOrNull() ?: 0L
            if (after == before) return total
            total += after - before
        }
        return total
    }

    private fun expire(store: LocalStore, key: String) {
        store.set(key, JSONObject().put("boot", -1).put("atElapsed", 0)
            .put("untilElapsed", SystemClock.elapsedRealtime() - 1).put("until", 0).toString())
    }

    /**
     * status() 显示的额度必须等于 consume() 真正能扣掉的额度，且不得出现
     * `beforeRest()==0 && restLeft()==0` 的死区（#17：显示还有时间、永远不减少）。
     */
    private fun checkNoContradiction(engine: LearningEngine, store: LocalStore, label: String) {
        val status = engine.status()
        val reason = status.getString("reason")
        val rest = status.getLong("restSeconds")
        val reported = (status.getLong("remainingSeconds") + status.getLong("freeQuotaSeconds")) * 1000
        val calls = 120
        val drained = drain(engine, store, calls)
        val capped = drained >= calls * 1000L     // 扣到测试上限还没扣完：不需要精确比较
        if (rest > 0) {
            check(reason == "rest") { "$label: 休息中 reason 必须是 rest，实际是 $reason（$status）" }
            check(reported == 0L) { "$label: 休息中不能再显示可扣额度：显示 ${reported}ms，与 consume() 口径矛盾" }
            check(drained == 0L) { "$label: 休息中不应该再扣时长，实际扣了 ${drained}ms" }
        } else {
            check(reason != "rest") { "$label: 没有休息却报 reason=rest（rest 状态和 rest_deadline 不一致）" }
            check(drained <= reported + 1000) { "$label: 实际扣了 $drained ms，比面板显示的 $reported ms 还多" }
            if (!capped) check(reported - drained <= 1000) {
                "$label: 面板显示 ${reported}ms 但只能扣 $drained ms —— 这就是 #17 的「有余额却扣不动」死区"
            }
            if (reported > 0) check(drained > 0) { "$label: 面板显示还有 ${reported}ms，却一毫秒都扣不动（#17 死区）" }
        }
    }

    private fun restNoDeadZone(context: Context) {
        val name = "vendor-fix-rest.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0)
                .put("dailyLimitEnabled", true).put("allDay", true).put("scheduleMode", "allow")
                .put("roundsLimitEnabled", false).put("restEnabled", true)
                .put("restWatchMinutes", 40).put("restMinutes", 10))
            engine.remote("credit", 30, "regression")
            check(store.get("rest_watched_ms").isEmpty() || store.get("rest_watched_ms") == "0")
            // 正常扣 1 分钟观看量（阈值还是 40 分钟，不会进入休息）
            repeat(12) { engine.consume(5000) }
            check(store.get("rest_watched_ms") == "60000") { "前置条件：应已累计 1 分钟观看量，实际 ${store.get("rest_watched_ms")}" }
            check(engine.status().getLong("restSeconds") == 0L)

            // 家长把 restWatchMinutes 从 40 调到 1：观看量已到阈值但还没有 rest_deadline
            // → 旧代码这里 beforeRest()==0 且 restLeft()==0，一毫秒都扣不动（#17 死区）
            engine.saveConfig(JSONObject().put("restWatchMinutes", 1))
            val due = engine.status()
            check(due.getString("reason") == "rest") {
                "#17: 达到阈值但没有休息记录时必须立即进入休息，实际 reason=${due.getString("reason")}（$due）"
            }
            check(due.getLong("restSeconds") > 0) { "#17: 死区自愈后必须给出 restSeconds>0" }
            check(store.get("rest_deadline").isNotEmpty()) { "#17: 死区自愈必须写入 rest_deadline" }
            checkNoContradiction(engine, store, "阈值调小（死区自愈）")

            // 跨天：休息还在进行时不能因为跨天重置出现「显示有余额却扣不动」或幽灵休息
            store.set("usage_day", "0")
            checkNoContradiction(engine, store, "跨天")
            check(engine.status().getLong("restSeconds") > 0) { "跨天不能把正在进行的休息弄丢（会变成「显示有余额却不动」）" }

            // 休息过期后必须能正常扣时（跨天重置已把余额清零，重新发一点奖励时间）
            expire(store, "rest_deadline")
            engine.remote("credit", 20, "regression")
            val recovered = engine.status()
            check(recovered.getLong("restSeconds") == 0L) { "休息结束后 restSeconds 必须归零" }
            check(store.get("rest_watched_ms") == "0") { "休息结束后观看量必须归零（否则立刻又进入休息）" }
            val usedBefore = store.get("used_ms").toLongOrNull() ?: 0L
            engine.consume(5000)
            check((store.get("used_ms").toLongOrNull() ?: 0L) > usedBefore) { "#17: 休息结束后必须能正常扣时" }
            checkNoContradiction(engine, store, "休息结束后恢复")

            // 阈值调大：不能凭空进入休息，余额仍然要能扣
            expire(store, "rest_deadline")
            engine.saveConfig(JSONObject().put("restWatchMinutes", 40))
            check(engine.status().getLong("restSeconds") == 0L) { "阈值调大后不应残留休息" }
            checkNoContradiction(engine, store, "阈值调大")
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun restConfigToggle(context: Context) {
        val name = "vendor-fix-rest-toggle.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0)
                .put("dailyLimitEnabled", true).put("allDay", true).put("scheduleMode", "allow")
                .put("roundsLimitEnabled", false).put("restEnabled", true)
                .put("restWatchMinutes", 1).put("restMinutes", 5))
            engine.remote("credit", 30, "regression")
            repeat(12) { engine.consume(5000) }
            check(engine.status().getString("reason") == "rest") { "前置条件：观看满 1 分钟应进入休息" }

            // 关掉休息：不能留下幽灵休息，也不能留下死区
            engine.saveConfig(JSONObject().put("restEnabled", false))
            val off = engine.status()
            check(off.getLong("restSeconds") == 0L) { "关掉休息后不能残留 restSeconds" }
            check(store.get("rest_deadline").isEmpty()) { "关掉休息后必须清除 rest_deadline" }
            check(store.get("rest_watched_ms") == "0") { "关掉休息后必须清除观看量" }
            checkNoContradiction(engine, store, "关掉 restEnabled")

            // 重新打开：观看量已归零，不能立刻又进入休息
            engine.saveConfig(JSONObject().put("restEnabled", true))
            check(engine.status().getLong("restSeconds") == 0L) { "重新打开休息后不能立刻进入休息（观看量已归零）" }
            checkNoContradiction(engine, store, "重新打开 restEnabled")
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    /** 解析倒计时期间 consume() 直接返回：面板显示的额度也必须为 0（同一类「两套口径」） */
    private fun feedbackNoContradiction(context: Context) {
        val name = "vendor-fix-feedback.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0)
                .put("dailyLimitEnabled", true).put("allDay", true).put("scheduleMode", "allow")
                .put("roundsLimitEnabled", false).put("restEnabled", false))
            engine.remote("credit", 20, "regression")
            check(engine.status().getString("reason").isEmpty())
            val now = SystemClock.elapsedRealtime()
            val boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
            store.set("feedback_deadline", JSONObject().put("boot", boot).put("atElapsed", now)
                .put("untilElapsed", now + 60000).put("until", System.currentTimeMillis() + 60000).toString())
            val held = engine.status()
            check(held.getString("reason") == "feedback") { "前置条件：解析倒计时期间 reason 必须是 feedback，实际 ${held.getString("reason")}" }
            check(held.getLong("remainingSeconds") == 0L && held.getLong("freeQuotaSeconds") == 0L) {
                "解析倒计时期间 consume() 不扣时，面板不能再显示可扣额度：$held"
            }
            val before = store.get("used_ms").toLongOrNull() ?: 0L
            engine.consume(5000)
            check((store.get("used_ms").toLongOrNull() ?: 0L) == before) { "解析倒计时期间不应该扣时" }
            checkNoContradiction(engine, store, "解析倒计时")
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    // ------------------------------------------------------------------ #93 前台识别

    private fun foregroundNeverSelf(context: Context) {
        val self = context.packageName
        val identifiable = WatchAccessibilityService.connected || Foreground.hasUsageAccess(context)
        Foreground.clearExternal()
        check(Foreground.current(context) != self) { "#93: 什么都识别不到时不能返回自身包名（应返回 null）" }

        // 自身的 Activity / 悬浮窗事件（#96）不得被当成「孩子正在用的应用」
        Foreground.update(self, "com.studyontv.tv.MainActivity")
        check(Foreground.lastPackage == self) { "前置条件：自身包名事件应已进入缓存" }
        check(Foreground.current(context) != self) { "#93: 自身包名必须是 null 或被过滤，不能当成前台应用" }
        check(Foreground.lastReal() != self) { "#93: 自身包名不能进「最近真正的前台应用」" }

        // 外部应用仍然要被正常识别（没有其它可识别信号时以最近事件为准）
        Foreground.update("com.example.player", "com.example.player.PlayerActivity")
        val external = Foreground.current(context)
        check(external != self) { "#93: 外部应用场景下返回了自身包名" }
        if (!identifiable) check(external == "com.example.player") { "外部应用应能被识别，实际 $external" }

        // 明显陈旧的缓存不能被直接采信（无障碍事件早就停了）
        Foreground.backdateCaches(Foreground.STALE_MS + 60_000)
        val stale = Foreground.current(context)
        check(stale != self) { "#93: 陈旧缓存场景下返回了自身包名" }
        check(stale != "com.example.player") { "#93: 超过 30 分钟没更新的缓存不能被当成当前前台" }

        Foreground.clearExternal()
        check(Foreground.lastPackage == null && Foreground.lastReal() == null) { "clearExternal 之后缓存必须清空" }
        check(Foreground.current(context) != self) { "clearExternal 之后仍然不能返回自身包名" }
    }

    /** 家长面板 / 诊断导出读的就是这些字段：#17 要知道「现在识别不到前台应用」，#92 要知道探测失败原因 */
    private fun diagnostics(context: Context) {
        val diag = GateService.diagnostics(context)
        for (key in listOf("foregroundPackage", "foregroundIdentified", "ownActivityForeground", "monitoringCapability",
            "overlayProbeOk", "overlayProbeError", "overlayProbeErrorKind", "overlayProbeAgeSeconds")) {
            check(diag.has(key)) { "#17/#92: 诊断缺少字段 $key（$diag）" }
        }
        if (!OverlayAccess.settingsCanDraw(context)) {
            check(diag.getString("overlayProbeError").isNotEmpty()) { "#92: 悬浮窗探测失败原因必须进诊断（$diag）" }
            check(diag.getString("overlayProbeErrorKind").isNotEmpty()) { "#92: 悬浮窗探测失败必须分类（$diag）" }
            check(diag.getString("overlay").isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ #92 悬浮窗探针

    private fun overlayProbe(context: Context) {
        val settings = OverlayAccess.settingsCanDraw(context)
        val accessible = GateOverlay.canDraw(context)
        if (settings) {
            // 这台设备本来就授权了：只保证不抛异常、结论一致（探针在 Settings 为 true 时不会执行）
            check(accessible) { "Settings 允许时 canDraw() 必须为 true" }
            return
        }
        check(OverlayAccess.lastProbeAt > 0) {
            "#92: Settings.canDrawOverlays=false 时必须真的尝试过一次 addView 探针"
        }
        check(!accessible) { "#92: 系统真的不允许时 canDraw() 必须仍然是 false" }
        check(OverlayAccess.lastProbeError.isNotEmpty()) { "#92: 探测失败必须记录可读原因（异常类名 + 窗口类型）" }
        check(OverlayAccess.lastProbeError.contains("TYPE_")) {
            "#92: 探测失败原因必须写明窗口类型，实际：${OverlayAccess.lastProbeError}"
        }
        check(OverlayAccess.lastProbeError.contains("Exception") || OverlayAccess.lastProbeError.contains("Security")) {
            "#92: 探测失败原因必须写明异常类名，实际：${OverlayAccess.lastProbeError}"
        }
        check(OverlayAccess.lastProbeErrorKind.isNotEmpty()) { "#92: 探测失败必须分类（permission / bad-token / …）" }
        // 不许把探测做成每秒一次
        check(SystemClock.elapsedRealtime() - OverlayAccess.lastProbeAt < OverlayAccess.RETRY_MS) {
            "刚探测过就必须复用结果（不允许每次 canDraw 都 addView）"
        }

        // 探测失败必须把窗口移除干净：再来一次结论必须一致，且不能报「已经被添加过」
        OverlayAccess.invalidate()
        val again = GateOverlay.canDraw(context)
        check(again == accessible) { "重复探测的结论必须一致（残留窗口会让第二次探测失败）" }
        check(!OverlayAccess.lastProbeError.contains("already been added", true)) {
            "探针窗口必须被移除干净，不能残留：${OverlayAccess.lastProbeError}"
        }
    }
}
