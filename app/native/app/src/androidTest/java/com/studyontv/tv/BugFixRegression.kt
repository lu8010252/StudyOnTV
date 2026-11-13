package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 两个线上 bug 的回归测试（只用独立数据库，不碰家庭题库）：
 *  Bug 1 家长删题后孩子的答题页还显示已删除的题
 *  Bug 2 升级后「剩余时间不变化，相当于没用；启动软件后只答一次」
 */
object BugFixRegression {
    private fun answer(round: JSONObject, selection: Int) = JSONObject().put("roundId", round.getString("id"))
        .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(selection))

    private fun seed(store: LocalStore, id: String, bank: String = "默认题库", type: String = "single", level: String = "") {
        val item = JSONObject().put("id", id).put("q", "题目$id").put("difficulty", 2).put("bank", bank).put("level", level)
        if (type == "subjective") item.put("type", "subjective").put("modelAnswer", "参考答案")
        else item.put("type", type).put("a", JSONArray(listOf("A", "B", "C", "D"))).put("k", 0)
        store.upsertQuestion(item)
    }

    /** 基础配置：不限组数、全天可用、没有解析倒计时，专注时间额度的行为 */
    private fun base(engine: LearningEngine, vararg extra: Pair<String, Any>): JSONObject {
        val config = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 3).put("extraEnabled", false).put("allDay", true).put("roundsPerDay", 100)
            .put("roundsLimitEnabled", false).put("passRatio", 0.6).put("wrongReviewSeconds", 0)
            .put("restEnabled", false).put("dailyLimitEnabled", true).put("playMode", "round")
            .put("scheduleMode", "allow").put("childSelectLevel", false)
        extra.forEach { (key, value) -> config.put(key, value) }
        return engine.saveConfig(config)
    }

    private fun startRound(engine: LearningEngine): JSONObject {
        val started = engine.start()
        check(!started.has("blocked")) { "start 被拦截: ${started.optString("blocked")}" }
        check(!started.has("watching")) { "start 返回 watching，说明还有余额没花完" }
        check(!started.has("free")) { "start 返回 free，测试环境应为全天可用" }
        return started
    }

    private fun playRound(engine: LearningEngine, correct: Int = 0): JSONObject {
        var round = startRound(engine)
        var result = engine.answer(answer(round, correct))
        var guard = 0
        while (!result.getBoolean("finished") && guard++ < 40) {
            round = result.getJSONObject("next")
            result = engine.answer(answer(round, correct))
        }
        check(result.getBoolean("finished")) { "一轮答题没能结束" }
        return result
    }

    /** 一直按 1 秒步长扣时间，返回实际扣掉的毫秒数（用来对照面板显示） */
    private fun drain(engine: LearningEngine, store: LocalStore, seconds: Int = 3600): Long {
        var drained = 0L
        repeat(seconds) {
            val before = store.get("used_ms").toLongOrNull() ?: 0L
            engine.consume(1000)
            val after = store.get("used_ms").toLongOrNull() ?: 0L
            if (after == before) return drained
            drained += after - before
        }
        return drained
    }

    /**
     * 面板显示的可扣额度必须等于 consume() 真正能扣掉的额度（允许 1 秒误差）。
     * 以前 status() 直接回报 credit_ms，家长收走时长 / 免费额度占满上限后面板会显示
     * 有余额但一秒都扣不动（用户反馈：剩余时间不变化）。
     */
    private fun checkSelfConsistent(engine: LearningEngine, store: LocalStore, label: String) {
        val status = engine.status()
        val reported = (status.getLong("remainingSeconds") + status.getLong("freeQuotaSeconds")) * 1000
        val drained = drain(engine, store)
        check(drained <= reported + 1000) { "$label: 实际扣掉 ${drained}ms 比面板显示的 ${reported}ms 还多" }
        check(reported - drained <= 1000) { "$label: 面板显示 ${reported}ms 但只能扣 $drained ms（有余额却扣不动）" }
    }

    /** 每日额度不变式：已用 + 奖励余额 + 免费额度 <= 每日上限，破坏它就是「奖励永远用不完」 */
    private fun checkBudgetInvariant(engine: LearningEngine, store: LocalStore, label: String) {
        val status = engine.status()
        val used = store.get("used_ms").toLongOrNull() ?: 0L
        val credit = store.get("credit_ms").toLongOrNull() ?: 0L
        val free = (store.get("free_quota_ms").toLongOrNull() ?: 0L) + (store.get("boot_quota_ms").toLongOrNull() ?: 0L)
        val limit = status.optLong("todayLimitMinutes") * 60000
        if (status.isNull("todayLimitMinutes")) return
        if (used >= limit) return   // 家长收走时长后当天本来就该用完
        check(used + credit + free <= limit) { "$label: 已用 ${used}ms + 余额 ${credit}ms + 免费 ${free}ms 超过每日上限 ${limit}ms" }
    }

    private fun acrossDay(engine: LearningEngine) {
        engine.status()
    }

    fun run(context: Context) {
        deletionRepair(context)
        migration(context)
        timeBudget(context)
        roundResume(context)
        reviewRecovery(context)
    }

    // ---------------------------------------------------------------- Bug 1
    private fun deletionRepair(context: Context) {
        val name = "bugfix-delete.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            base(engine, "questionsPerRound" to 3)

            // 单题删除：删掉的正是孩子手上的题 → 必须立刻换成还存在的题，并升高 quizGeneration
            seed(store, "a")
            seed(store, "b")
            var round = startRound(engine)
            val current = round.getJSONObject("question").getString("id")
            val other = if (current == "a") "b" else "a"
            val generation = engine.status().getLong("quizGeneration")
            check(engine.deleteQuestions(listOf(current)) == 1)
            check(engine.status().getLong("quizGeneration") == generation + 1) { "单题删除必须让前端自动重载" }
            val stored = JSONObject(store.get("active_round"))
            check(stored.getJSONObject("question").getString("id") == other) { "被删的题必须立刻换成还存在的题" }
            check(stored.getString("nonce") != round.getString("nonce")) { "换题必须换 nonce，旧答案不能再生效" }
            val next = engine.answer(answer(stored, 0))
            check(!next.has("error") && !next.has("blocked")) { "换出来的题必须能正常作答: $next" }

            // 删到一道题都不剩：本轮作废，start() 给出「题库为空」的明确文案
            store.set("credit_ms", "0")
            store.set("used_ms", "0")
            val now = engine.status().getLong("quizGeneration")
            check(engine.deleteQuestions(listOf(other)) == 1)
            check(store.get("active_round").isEmpty()) { "题目删空后不能继续用旧题" }
            check(engine.status().getLong("quizGeneration") == now + 1)
            val empty = try { engine.start(); error("题库为空时必须报错") } catch (e: IllegalArgumentException) { e.message ?: "" }
            check(empty.contains("题库为空")) { "前端要能显示明确的空题库提示，实际是：$empty" }

            // 按题库删除：只删孩子正在用的那个题库，旁边题库的题要顶上
            store.deleteQuestions(store.idsInBank("默认题库"))
            seed(store, "m1", bank = "数学")
            seed(store, "m2", bank = "数学")
            round = startRound(engine)
            check(round.getJSONObject("question").optString("bank") == "数学")
            seed(store, "y1", bank = "语文")
            seed(store, "y2", bank = "语文")
            val beforeBank = engine.status().getLong("quizGeneration")
            check(engine.clearQuestions("数学") == 2)
            val afterBank = JSONObject(store.get("active_round"))
            check(afterBank.getJSONObject("question").optString("bank") == "语文") { "删掉题库后要换到别的题库的题" }
            check(engine.status().getLong("quizGeneration") == beforeBank + 1)

            // 删别的题库不能打扰孩子：题目和 nonce 都不该变
            val untouchedGeneration = engine.status().getLong("quizGeneration")
            val untouchedNonce = afterBank.getString("nonce")
            store.set("credit_ms", "0"); store.set("used_ms", "0")
            check(engine.clearQuestions("数学") == 0)
            check(engine.status().getLong("quizGeneration") == untouchedGeneration) { "没被删到的题不该刷新页面" }
            check(JSONObject(store.get("active_round")).getString("nonce") == untouchedNonce)

            // 批量删除：一次删掉孩子手上那一题（另有一道题可以顶上）
            seed(store, "y3", bank = "语文")
            store.set("credit_ms", "0"); store.set("used_ms", "0")
            val batchRound = JSONObject(store.get("active_round"))
            val batchId = batchRound.getJSONObject("question").getString("id")
            val batchGeneration = engine.status().getLong("quizGeneration")
            check(engine.deleteQuestions(listOf(batchId, "y3")) == 2)
            check(engine.status().getLong("quizGeneration") == batchGeneration + 1)
            check(store.questionExists(JSONObject(store.get("active_round")).getJSONObject("question").getString("id")))

            // 清空全部题库：本轮作废 + 前端重载
            val clearGeneration = engine.status().getLong("quizGeneration")
            check(engine.clearQuestions() > 0)
            check(store.get("active_round").isEmpty() && store.get("feedback_deadline").isEmpty())
            check(engine.status().getLong("quizGeneration") == clearGeneration + 1)

            // 孩子正好提交了已删除的题（外部直接删库，绕过 deleteQuestions）：可恢复，不崩不卡
            seed(store, "k1")
            seed(store, "k2")
            store.set("credit_ms", "0"); store.set("used_ms", "0")
            val raceRound = startRound(engine)
            val raceId = raceRound.getJSONObject("question").getString("id")
            val raceGeneration = engine.status().getLong("quizGeneration")
            store.deleteQuestions(listOf(raceId))
            val recovered = engine.answer(answer(raceRound, 0))
            check(recovered.getBoolean("recovered")) { "删题后作答必须返回可恢复结果: $recovered" }
            check(engine.status().getLong("quizGeneration") == raceGeneration + 1)
            check(recovered.getJSONObject("status").optLong("quizGeneration") == raceGeneration + 1)
            check(store.questionExists(JSONObject(store.get("active_round")).getJSONObject("question").getString("id")))
            val logged = (0 until store.recentEvents(50).length()).map { store.recentEvents(50).getJSONObject(it) }
                .any { it.getString("type") == "answer_question_deleted" }
            check(logged) { "删题作答要留一条诊断日志" }

            // 题目全删光时也一样：返回可恢复结果，并且 start() 给出空题库文案
            val lastRound = JSONObject(store.get("active_round"))
            store.deleteQuestions(listOf(lastRound.getJSONObject("question").getString("id")))
            val nothingLeft = engine.answer(answer(lastRound, 0))
            check(nothingLeft.getBoolean("recovered") && store.get("active_round").isEmpty())
            val message = try { engine.start(); error("题库为空时必须报错") } catch (e: IllegalArgumentException) { e.message ?: "" }
            check(message.contains("题库为空"))

            // 主观题待批改时被删：批改记录要标成跳过，不能让孩子一直等
            store.deleteQuestions(store.idsInBank("语文"))
            seed(store, "sub", type = "subjective")
            store.set("credit_ms", "0"); store.set("used_ms", "0")
            engine.saveConfig(JSONObject().put("questionsPerRound", 2))
            val subjective = startRound(engine)
            check(subjective.getJSONObject("question").getString("type") == "subjective")
            val pending = engine.answer(JSONObject().put("roundId", subjective.getString("id"))
                .put("nonce", subjective.getString("nonce")).put("text", "我的答案").put("drawing", ""))
            check(pending.getBoolean("pending"))
            val reviewId = pending.getString("reviewId")
            seed(store, "sub-next")
            check(engine.deleteQuestions(listOf("sub")) == 1)
            check(store.review(reviewId)?.getString("state") == "skipped") { "删掉待批改的题要把批改记录标成跳过" }
            val afterReview = JSONObject(store.get("active_round"))
            check(!afterReview.has("pendingReview")) { "孩子不能一直停在等待批改页面" }
            check(store.questionExists(afterReview.getJSONObject("question").getString("id")))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    // ------------------------------------------------- Bug 2.1 升级迁移
    private fun migration(context: Context) {
        val name = "bugfix-migrate.db"
        context.deleteDatabase(name)
        try {
            val day = LocalStore(context, name).use { it.startOfToday() }
            val activeRound = JSONObject().put("id", "r1").put("mode", "round").put("answered", 2).put("target", 5)
                .put("question", JSONObject().put("id", "keep"))
            LocalStore(context, name).use { old ->
                seed(old, "keep")
                old.saveConfig(JSONObject().put("dailyMinutes", 55))
                val values = mapOf("active_round" to activeRound.toString(), "credit_ms" to "900000", "used_ms" to "600000",
                    "free_quota_ms" to "180000", "cap_adjust_ms" to "300000", "usage_day" to day.toString(),
                    "wrong_progress" to "2", "extra_pool" to "4")
                values.forEach { (key, value) -> old.set(key, value) }
                old.writableDatabase.execSQL("DROP TABLE question_stats")
                old.writableDatabase.version = 5
            }
            // 反复启动（服务被杀 / 开机自启）都不能把旧数据冲掉
            repeat(3) { attempt ->
                LocalStore(context, name).use { migrated ->
                    check(migrated.readableDatabase.version == 8) { "第${attempt + 1}次打开应当升级到 v8" }
                    check(migrated.get("active_round") == activeRound.toString()) { "升级后必须保留进行中的一轮" }
                    check(migrated.get("credit_ms") == "900000") { "升级后必须保留剩余时间" }
                    check(migrated.get("used_ms") == "600000") { "升级后必须保留已用时长" }
                    check(migrated.get("free_quota_ms") == "180000") { "升级后必须保留免费额度" }
                    check(migrated.get("cap_adjust_ms") == "300000") { "升级后必须保留家长加减的额度" }
                    check(migrated.get("usage_day") == day.toString()) { "升级后不能重置当天用量" }
                    check(migrated.config().getInt("dailyMinutes") == 55)
                    val engine = LearningEngine(migrated)
                    val status = engine.status()
                    check(status.getLong("remainingSeconds") == 900L) { "升级后剩余时间不能清零，实际 ${status.getLong("remainingSeconds")}" }
                    check(status.getDouble("usedMinutes") == 10.0)
                    check(status.getDouble("todayLimitMinutes") == 60.0) { "55 分钟 + 家长加的 5 分钟 = 60，实际 ${status.getDouble("todayLimitMinutes")}" }
                    check(migrated.questions().length() == 1 && migrated.questions().getJSONObject(0).getJSONObject("stats").getInt("presented") == 0)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    // ------------------------------- Bug 2.2/2.3/2.4 计时额度与状态一致性
    private fun timeBudget(context: Context) {
        val name = "bugfix-time.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            seed(store, "t1")
            seed(store, "t2")

            // 场景1：免费时长与奖励余额并存 —— 发奖励时不能挤占免费额度，否则奖励永远扣不动
            base(engine, "dailyMinutes" to 5, "dailyFreeMinutes" to 3, "sessionMinutes" to 5, "questionsPerRound" to 1)
            store.set("usage_day", "0")
            acrossDay(engine)
            check(store.get("free_quota_ms") == "180000") { "跨天应写入 3 分钟免费额度" }
            val rewarded = playRound(engine)
            checkBudgetInvariant(engine, store, "免费时长 + 奖励并存")
            check(rewarded.getDouble("rewardMinutes") <= 2.0) { "免费额度占满了上限，奖励最多 2 分钟，实际 ${rewarded.getDouble("rewardMinutes")}" }
            checkSelfConsistent(engine, store, "免费时长 + 奖励并存")
            check(engine.status().getString("reason") == "quota") { "额度用完后必须明确是 quota，而不是显示还有余额" }
            check(store.get("credit_ms") == "0") { "能被扣掉的奖励余额不能残留" }

            // 场景2：已用满当日上限 —— 面板显示 0，consume 也不再扣
            store.set("used_ms", (5 * 60000L).toString()); store.set("credit_ms", "600000"); store.set("free_quota_ms", "0")
            val full = engine.status()
            check(full.getString("reason") == "quota")
            check(full.getLong("remainingSeconds") == 0L && full.getLong("freeQuotaSeconds") == 0L) { "用满额度后不能还显示剩余时间" }
            checkSelfConsistent(engine, store, "已用满当日上限")

            // 场景3：家长提前收走时长 —— 收走的部分不能再以「剩余时间」出现
            store.set("usage_day", "0")
            acrossDay(engine)
            store.set("credit_ms", "600000"); store.set("used_ms", "120000")
            val removed = engine.remote("credit", -8, "test")
            checkBudgetInvariant(engine, store, "家长收走时长")
            checkSelfConsistent(engine, store, "家长收走时长")
            check(removed.getLong("remainingSeconds") >= 0L)
            check(engine.status().getString("reason") == "timeup" || engine.status().getString("reason") == "quota") {
                "收走时长后要给明确原因，实际 ${engine.status().getString("reason")}"
            }

            // 场景4：关闭每日上限 —— 余额照常可扣，todayLimitMinutes 为 null
            store.set("usage_day", "0")
            acrossDay(engine)
            engine.saveConfig(JSONObject().put("dailyLimitEnabled", false))
            store.set("credit_ms", "600000"); store.set("used_ms", (5 * 60000L).toString())
            val unlimited = engine.status()
            check(unlimited.isNull("todayLimitMinutes"))
            check(unlimited.getLong("remainingSeconds") == 600L) { "关闭每日上限后余额不能被额度卡住" }
            checkSelfConsistent(engine, store, "关闭每日上限")
            check(store.get("credit_ms") == "0") { "关闭每日上限时必须真的扣掉余额" }
            engine.saveConfig(JSONObject().put("dailyLimitEnabled", true))
            checkSelfConsistent(engine, store, "重新打开每日上限")

            // 场景5：跨天重置 —— 额度、cap_adjust、免费时长按设计恢复
            engine.saveConfig(JSONObject().put("dailyLimitEnabled", true).put("dailyMinutes", 40).put("dailyFreeMinutes", 5))
            store.set("usage_day", "0")
            acrossDay(engine)
            store.set("credit_ms", "600000"); store.set("used_ms", "1200000"); store.set("cap_adjust_ms", "-600000")
            store.set("boot_quota_ms", "60000")
            store.set("usage_day", "0")
            val nextDay = engine.status()
            check(store.get("credit_ms") == "0" && store.get("used_ms") == "0") { "跨天必须重置已用时长和当日余额" }
            check(store.get("cap_adjust_ms") == "0") { "跨天必须清掉家长加减时留下的上限偏移" }
            // freeQuotaSeconds 是「今天实际可用」的免费额度：每日免费 300 秒 + 同一唤醒周期内未用完的开机免费 60 秒。
            // 面板与角标都直接用这个总量，所以必须包含开机免费，且两者之和不能超过当天剩余额度。
            check(nextDay.getLong("freeQuotaSeconds") == 360L) { "跨天应恢复每日免费额度，并继续保留本唤醒周期内未用完的开机免费额度" }
            check(nextDay.getLong("bootFreeSeconds") == 60L) { "开机免费余额需要单独可读" }
            check(store.get("free_quota_ms") == "300000") { "每日免费额度本身只能是配置的 5 分钟" }
            check(nextDay.getDouble("todayLimitMinutes") == 40.0)
            check(nextDay.getDouble("usedMinutes") == 0.0 && nextDay.getLong("remainingSeconds") == 0L)
            check(nextDay.getString("reason") == "") { "跨天后有免费额度应当可用，实际 ${nextDay.getString("reason")}" }
            // 再调一次不能把刚产生的用量又冲掉（day() 必须幂等）
            engine.consume(1000)
            val used = store.get("used_ms")
            engine.status()
            check(store.get("used_ms") == used) { "day() 不能每次刷新都把当天用量清零" }

            // 场景6：组数上限 —— 达组但还有余额时可以看电视，用完才锁
            // 前面的场景已经完成过组，所以按「当前完成数 + 1」设定上限，保证这一组正好是最后一组。
            val beforeRounds = engine.status().getLong("roundsDoneToday")
            engine.saveConfig(JSONObject().put("roundsLimitEnabled", true).put("roundsPerDay", (beforeRounds + 1).toInt())
                .put("questionsPerRound", 1).put("dailyMinutes", 20).put("dailyFreeMinutes", 1).put("sessionMinutes", 2))
            store.set("usage_day", "0")
            acrossDay(engine)
            playRound(engine)
            engine.flushUsage()
            check(engine.status().getLong("roundsDoneToday") == beforeRounds + 1) { "这一组应当计为完成" }
            check(engine.status().getString("reason") == "") { "达组但还有余额不应该立刻锁" }
            checkSelfConsistent(engine, store, "组数上限边界")
            // checkSelfConsistent 本身会用 1 秒步长把可扣额度扣完；扣完后才能验证「达组即锁」
            check(engine.status().getString("reason") == "rounds") { "奖励用完后应因组数上限锁定，实际 ${engine.status().getString("reason")}" }
            check(engine.start().optString("blocked") == "rounds")
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    // ------------------------- Bug 2.2 进行中的一轮：旧快照 / 服务重启 / 空题库
    private fun roundResume(context: Context) {
        val name = "bugfix-round.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            seed(store, "r1"); seed(store, "r2"); seed(store, "r3")
            base(engine, "questionsPerRound" to 3, "extraEnabled" to true)

            // 答过一题后家长改了规则：继续用旧快照，不能卡在第二题
            var round = startRound(engine)
            var result = engine.answer(answer(round, 0))
            check(JSONObject(store.get("active_round")).getInt("answered") == 1)
            engine.saveConfig(JSONObject().put("questionsPerRound", 5).put("dailyMinutes", 60).put("extraEnabled", true))
            val restarted = LearningEngine(store)   // 服务被系统杀掉后重建
            round = JSONObject(store.get("active_round"))
            result = restarted.answer(answer(round, 0))
            round = if (result.getBoolean("finished")) round else JSONObject(store.get("active_round"))
            result = if (result.getBoolean("finished")) result else restarted.answer(answer(round, 0))
            check(result.getBoolean("finished")) { "旧规则快照的一轮必须能答完" }
            check(result.getBoolean("passed") && result.getDouble("rewardMinutes") > 0)
            check(store.get("credit_ms").toLong() > 0) { "quiz_pass 必须真的加上奖励时间" }
            val pass = (0 until store.recentEvents(20).length()).map { store.recentEvents(20).getJSONObject(it) }
                .filter { it.getString("type") == "quiz_pass" }
            check(pass.isNotEmpty() && pass.first().getJSONObject("payload").getDouble("minutes") > 0) { "quiz_pass 记录的分钟数不能是 0" }

            // 题库被删空时不能停在旧题上：answer() 返回 quizEmpty，本轮作废
            store.set("credit_ms", "0"); store.set("used_ms", "0")
            val last = startRound(engine)
            store.deleteQuestions(listOf("r1", "r2", "r3"))
            val empty = engine.answer(answer(last, 0))
            check(empty.getBoolean("quizEmpty")) { "没有下一题时要告诉前端题库为空: $empty" }
            check(empty.optString("quizEmptyMessage").contains("题库为空")) { "提示文案要能被前端直接显示" }
            check(store.get("active_round").isEmpty())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    // ------------------------------------- Bug 2.2 待批改残留 / 解析倒计时残留
    private fun reviewRecovery(context: Context) {
        val name = "bugfix-review.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            seed(store, "p1", type = "subjective")
            seed(store, "p2")
            base(engine, "questionsPerRound" to 2, "extraEnabled" to false, "playMode" to "practice")

            // 批改记录丢了（数据库被清 / 记录过期）：不能把孩子永远挡在「等待家长批改」
            var round = startRound(engine)
            if (round.getJSONObject("question").optString("type") != "subjective") {
                // 随机抽到了普通题：直接改写成主观题那一轮，专测待批改逻辑
                val rewritten = JSONObject(store.get("active_round"))
                val sub = store.questions(limit = 10).let { items -> (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == "p1" } }
                rewritten.put("question", sub).put("nonce", "nonce-sub")
                store.set("active_round", rewritten.toString())
                round = rewritten
            }
            val pending = engine.answer(JSONObject().put("roundId", round.getString("id"))
                .put("nonce", round.getString("nonce")).put("text", "答案").put("drawing", ""))
            check(pending.optBoolean("pending")) { "主观题应返回 pending，实际 $pending" }
            check(engine.status().getBoolean("awaitingReview"))
            check(engine.start().getBoolean("pending"))
            // 模拟批改记录被别处删掉
            store.writableDatabase.delete("reviews", "id=?", arrayOf(pending.getString("reviewId")))
            val healed = engine.start()
            check(!healed.optBoolean("pending")) { "批改记录没了还停在等待页面 = 答一次后再也无法继续" }
            check(!JSONObject(store.get("active_round")).has("pendingReview"))
            check(engine.status().getBoolean("awaitingReview") == false)

            // 已经批改但没写回 active_round：要自动接上批改结果
            val second = JSONObject(store.get("active_round"))
            val again = engine.answer(JSONObject().put("roundId", second.getString("id"))
                .put("nonce", second.getString("nonce")).put("text", "答案2").put("drawing", ""))
            val reviewId = again.getString("reviewId")
            val payload = store.review(reviewId)!!
            payload.put("state", "graded").put("score", 88.0).put("feedback", "写得不错").put("gradedAt", System.currentTimeMillis())
                .put("result", JSONObject().put("correct", true).put("finished", false).put("correctIndices", JSONArray()))
            store.saveReview(payload)
            store.set("review_feedback", "")
            val reviewed = engine.start()
            check(reviewed.optBoolean("reviewed")) { "已批改的题要直接展示批改结果: $reviewed" }
            check(reviewed.getDouble("score") == 88.0)
            engine.acknowledgeReview(reviewId)
            val advanced = engine.start()
            check(!advanced.has("pending") && !advanced.has("reviewed")) { "批改确认后要能继续答题: $advanced" }

            // 解析倒计时还在但内容丢了：不能 500，也不能卡住
            TimePolicy(store) { -1 }.deadline("feedback_deadline", 60000)
            store.set("feedback_result", "")
            check(engine.status().getString("reason") == "feedback")
            val recovered = engine.start()
            check(!recovered.optBoolean("heldFeedback")) { "解析内容丢失时应丢弃残留倒计时，而不是白屏" }
            check(!recovered.has("blocked"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
