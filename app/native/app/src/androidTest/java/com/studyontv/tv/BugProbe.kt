package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 临时诊断探针第二轮：定位「答一次后无法继续 / 剩余时间不变化」。 */
object BugProbe {
    private val out = StringBuilder()
    private fun note(line: String) { out.append(line).append('\n') }
    private fun run(label: String, block: () -> Unit) {
        try { block(); note("OK   $label") } catch (e: Throwable) { note("FAIL $label :: ${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun answer(round: JSONObject, selection: Int) = JSONObject().put("roundId", round.getString("id"))
        .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(selection))

    private fun fresh(context: Context, name: String = "probe.db"): Pair<LocalStore, LearningEngine> {
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        return store to LearningEngine(store)
    }

    private fun seed(store: LocalStore, count: Int = 12) {
        for (i in 0 until count) store.upsertQuestion(JSONObject().put("id", "q$i").put("q", "题目$i")
            .put("a", JSONArray(listOf("A", "B", "C", "D"))).put("k", 0).put("difficulty", 2))
    }

    /** 模拟看电视：一直扣到扣不动为止，返回实际扣掉的毫秒数 */
    private fun watch(engine: LearningEngine, store: LocalStore, seconds: Int): Long {
        val before = store.get("used_ms").toLongOrNull() ?: 0
        repeat(seconds) { engine.consume(1000) }
        return (store.get("used_ms").toLongOrNull() ?: 0) - before
    }

    private fun brief(engine: LearningEngine, store: LocalStore) = buildString {
        val st = engine.status()
        append("reason=${st.getString("reason")} remaining=${st.getLong("remainingSeconds")} free=${st.getLong("freeQuotaSeconds")}")
        append(" usedMin=${st.getDouble("usedMinutes")} limit=${st.opt("todayLimitMinutes")}")
        append(" credit=${store.get("credit_ms")} used=${store.get("used_ms")} cap=${store.get("cap_adjust_ms")}")
    }

    fun run(context: Context): String {
        out.setLength(0)

        // ---------- A. 旧版本遗留 used_ms 超过每日上限 ----------
        run("A 旧版本 used_ms 已超过每日上限（升级后的典型状态）") {
            val (s, e) = fresh(context)
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("allDay", true)
                .put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            s.set("credit_ms", "600000")   // 旧版本攒下的 10 分钟奖励
            s.set("used_ms", "3000000")    // 旧版本免费额度不设上限，已经看了 50 分钟
            note("     状态: " + brief(e, s))
            note("     再扣 10 秒: " + watch(e, s, 10) + "ms，之后 " + brief(e, s))
            note("     start()=" + e.start().let { it.optString("blocked").ifEmpty { it.keys().asSequence().joinToString(",") } })
            s.close()
        }

        // ---------- B. used 超过上限 + 免费额度残留 ----------
        run("B used 超过上限 + 免费额度残留") {
            val (s, e) = fresh(context)
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 10).put("allDay", true)
                .put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            s.set("credit_ms", "600000"); s.set("used_ms", "3000000"); s.set("free_quota_ms", "600000")
            note("     状态: " + brief(e, s))
            note("     再扣 10 秒: " + watch(e, s, 10) + "ms，之后 " + brief(e, s))
            s.close()
        }

        // ---------- C. 主观题待批改 → 批改 → 继续 ----------
        run("C 主观题 pendingReview 全流程") {
            val (s, e) = fresh(context, "probe-c.db")
            s.upsertQuestion(JSONObject().put("id", "sub").put("q", "写一段话").put("type", "subjective")
                .put("modelAnswer", "参考答案").put("rubric", "意思对即可"))
            seed(s, 3)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("questionsPerRound", 2).put("extraEnabled", false)
                .put("allDay", true).put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            var round = e.start()
            // 让题目一定是主观题
            if (round.getJSONObject("question").optString("type") != "subjective") {
                val rewritten = JSONObject(s.get("active_round"))
                val sub = s.questions().let { items -> (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == "sub" } }
                rewritten.put("question", sub).put("nonce", "n-sub")
                s.set("active_round", rewritten.toString())
                round = JSONObject(s.get("active_round"))
            }
            val pending = e.answer(JSONObject().put("roundId", round.getString("id")).put("nonce", round.getString("nonce"))
                .put("text", "我的答案").put("drawing", ""))
            note("     pending=${pending.optBoolean("pending")} reviewId=${pending.optString("reviewId")} awaiting=${e.status().getBoolean("awaitingReview")}")
            note("     start()=" + e.start().let { it.optString("pending") + "/" + it.optString("blocked") })
            val reviewId = pending.getString("reviewId")
            e.grade(reviewId, 90.0, "很好", "test")
            val reviewed = e.start()
            note("     reviewed=${reviewed.optBoolean("reviewed")} finished=${reviewed.optJSONObject("result")?.optBoolean("finished")}")
            e.acknowledgeReview(reviewId)
            val next = e.start()
            note("     acknowledge 后 start()=" + next.keys().asSequence().joinToString(",") + " questionId=" + next.optJSONObject("question")?.optString("id"))
            s.close()
        }

        // ---------- D. 服务重启后继续同一轮 ----------
        run("D 中途重建 engine（服务重启）继续答题") {
            val (s, e) = fresh(context, "probe-d.db")
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("questionsPerRound", 3).put("extraEnabled", false)
                .put("allDay", true).put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            var round = e.start()
            var result = e.answer(answer(round, 0))
            note("     q1 答完 answered=1 finished=${result.getBoolean("finished")}")
            val restarted = LearningEngine(s)
            val started = restarted.start()
            note("     重启后 start()=" + started.keys().asSequence().joinToString(",") + " nonce相同=" + (started.optString("nonce") == JSONObject(s.get("active_round")).optString("nonce")))
            round = JSONObject(s.get("active_round"))
            result = restarted.answer(answer(round, 0))
            round = if (result.getBoolean("finished")) round else result.getJSONObject("next")
            result = if (result.getBoolean("finished")) result else restarted.answer(answer(round, 0))
            note("     继续答 finished=${result.getBoolean("finished")} reward=${result.optDouble("rewardMinutes")}")
            s.close()
        }

        // ---------- E. 旧规则快照 ----------
        run("E 旧规则快照（改配置后按旧快照答完）") {
            val (s, e) = fresh(context, "probe-e.db")
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("questionsPerRound", 3).put("extraEnabled", false)
                .put("sessionMinutes", 10).put("allDay", true).put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            var round = e.start()
            var result = e.answer(answer(round, 0))
            e.saveConfig(JSONObject().put("questionsPerRound", 5).put("dailyMinutes", 60).put("extraEnabled", true))
            val snap = JSONObject(s.get("active_round"))
            note("     answered=${snap.optInt("answered")} roundVersion=${snap.getJSONObject("rules").optInt("version")} configVersion=${s.config().getInt("version")}")
            round = snap
            result = e.answer(answer(round, 0))
            round = if (result.getBoolean("finished")) snap else result.getJSONObject("next")
            result = if (result.getBoolean("finished")) result else e.answer(answer(round, 0))
            note("     继续答 finished=${result.getBoolean("finished")} reward=${result.optDouble("rewardMinutes")}")
            s.close()
        }

        // ---------- F. 一整天：3 组答完 → 看电视 → 是否还能再答题 ----------
        run("F 一整天：3 组 → 看电视 → 再答题") {
            val (s, e) = fresh(context, "probe-f.db")
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("questionsPerRound", 1)
                .put("extraEnabled", false).put("sessionMinutes", 10).put("allDay", true)
                .put("roundsPerDay", 3).put("roundsLimitEnabled", true).put("wrongReviewSeconds", 0))
            repeat(4) { i ->
                val started = e.start()
                val step = when {
                    started.has("blocked") -> "blocked=${started.optString("blocked")}"
                    started.has("watching") -> "watching"
                    else -> {
                        val r = started
                        val result = e.answer(answer(r, 0))
                        "answered finished=${result.getBoolean("finished")} reward=${result.optDouble("rewardMinutes")}"
                    }
                }
                note("     第${i + 1}次 start: $step | ${brief(e, s)}")
                if (!started.has("blocked")) watch(e, s, 620)
            }
            note("     最终: " + brief(e, s))
            note("     最终 start()=" + e.start().let { if (it.has("blocked")) "blocked=" + it.optString("blocked") else it.keys().asSequence().joinToString(",") })
            s.close()
        }

        // ---------- G. feedback_deadline 存在但 feedback_result 为空 ----------
        run("G feedback 倒计时但内容丢失") {
            val (s, e) = fresh(context, "probe-g.db")
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("allDay", true))
            TimePolicy(s) { -1 }.deadline("feedback_deadline", 60000)
            s.set("feedback_result", "")
            val started = e.start()
            note("     start()=" + started.toString().take(160))
            note("     reason=${e.status().getString("reason")} feedbackSeconds=${e.status().getLong("feedbackSeconds")}")
            s.close()
        }

        // ---------- H. 家长加减时与每日上限叠加 ----------
        run("H 家长加时 / 收走时长") {
            val (s, e) = fresh(context, "probe-h.db")
            seed(s)
            e.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("questionsPerRound", 1)
                .put("extraEnabled", false).put("sessionMinutes", 10).put("allDay", true)
                .put("roundsLimitEnabled", false).put("wrongReviewSeconds", 0))
            e.remote("credit", 30, "test")
            note("     +30: " + brief(e, s))
            note("     看 10 分钟: " + watch(e, s, 600) / 60000 + " 分钟 | " + brief(e, s))
            e.remote("credit", -30, "test")
            note("     -30: " + brief(e, s))
            note("     再看 10 分钟: " + watch(e, s, 600) / 60000 + " 分钟 | " + brief(e, s))
            val started = e.start()
            note("     start()=" + (if (started.has("blocked")) "blocked=" + started.optString("blocked") else "round"))
            s.close()
        }

        return out.toString()
    }
}
