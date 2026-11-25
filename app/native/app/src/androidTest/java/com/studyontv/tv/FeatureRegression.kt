package com.studyontv.tv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object FeatureRegression {
    /** 单独跑隐藏桌面图标（-e launcher 1），改这块时不用等整套回归 */
    fun launcher(context: Context) = launcherIcon(context)

    fun run(context: Context) {
        migration(context)
        childMigration(context)
        levelSelection(context)
        bootFreeInterval(context)
        businessClock(context)
        dailyQuotaSurvivesClockRollback(context)
        videoSettlementAcrossMidnight(context)
        val name = "feature-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            var engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0))
            store.set("used_ms", (40 * 60000L).toString())
            check(engine.status().getString("reason") == "quota")
            val added = engine.remote("credit", 15, "test")
            check(added.getDouble("todayLimitMinutes") == 55.0 && added.getLong("remainingSeconds") == 900L)
            check(added.getString("reason").isEmpty())
            check(store.config().getInt("dailyMinutes") == 40)
            val removed = engine.remote("credit", -30, "test")
            check(removed.getLong("remainingSeconds") == 0L)
            for (minutes in listOf(0, 481, -481)) {
                try { engine.remote("credit", minutes, "test"); error("invalid credit accepted") } catch (_: IllegalArgumentException) { }
            }
            store.set("usage_day", "0"); engine.status()
            check(engine.status().getDouble("todayLimitMinutes") == 40.0)

            engine.saveConfig(JSONObject().put("bootFreeMinutes", 3))
            engine.awake(true)
            check(engine.status().getLong("bootFreeSeconds") == 180L)
            engine.consume(5000)
            engine = LearningEngine(store)
            engine.awake(true)
            check(engine.status().getLong("bootFreeSeconds") == 175L) { "重开服务不能重复发放开机额度" }
            engine.awake(false); engine.awake(true)
            check(engine.status().getLong("bootFreeSeconds") == 180L)

            engine.saveConfig(JSONObject().put("restEnabled", true).put("restWatchMinutes", 1).put("restMinutes", 1))
            repeat(12) { engine.consume(5000) }
            val resting = engine.status()
            check(resting.getString("reason") == "rest" && resting.getLong("restSeconds") > 0)
            val used = resting.getDouble("usedMinutes")
            engine.consume(5000)
            check(engine.status().getDouble("usedMinutes") == used)
            engine = LearningEngine(store)
            check(engine.start().getString("blocked") == "rest")
            expire(store, "rest_deadline")
            check(engine.status().getString("reason").isEmpty())
            check(store.get("rest_watched_ms") == "0")
            engine.saveConfig(JSONObject().put("restEnabled", false).put("bootFreeMinutes", 0).put("dailyFreeMinutes", 0)
                .put("dailyLimitEnabled", false).put("roundsLimitEnabled", false).put("playMode", "count")
                .put("questionsPerRound", 2).put("childSelectLevel", true).put("wrongReviewSeconds", 60))
            store.set("boot_quota_ms", "0");store.set("credit_ms", "0")
            store.upsertQuestion(JSONObject("""{"id":"a","q":"one","a":["yes","no"],"k":0,"level":"一年级","bank":"数学"}"""))
            store.upsertQuestion(JSONObject("""{"id":"b","q":"two","a":["yes","no"],"k":0,"level":"二年级","bank":"数学"}"""))
            check(engine.start().getJSONArray("chooseLevel").length() == 2)
            try { engine.start("三年级"); error("disallowed grade accepted") } catch (_: IllegalArgumentException) { }
            var round = engine.start("一年级")
            check(round.getJSONObject("question").getString("id") == "a")
            val wrong = engine.answer(answer(round, 1))
            check(!wrong.getBoolean("finished") && !wrong.getBoolean("correct"))
            check(engine.status().getString("reason") == "feedback")
            check(engine.start().getBoolean("heldFeedback"))
            try { engine.continueFeedback(); error("feedback skipped early") } catch (_: IllegalArgumentException) { }
            round = wrong.getJSONObject("next")
            check(engine.answer(answer(round, 0)).getString("blocked") == "feedback")
            check(round.getJSONObject("question").getString("id") == "a")
            expire(store, "feedback_deadline");engine.continueFeedback()
            val right = engine.answer(answer(round, 0))
            check(!right.getBoolean("finished"))
            round = right.getJSONObject("next")
            val finished = engine.answer(answer(round, 0))
            check(finished.getBoolean("finished") && finished.getBoolean("passed"))
            check(finished.getDouble("rewardMinutes") > 0)
            val questions = store.questions(enabledOnly = false)
            val stats = (0 until questions.length()).map { questions.getJSONObject(it) }.first { it.getString("id") == "a" }.getJSONObject("stats")
            check(stats.getInt("answered") == 3 && stats.getInt("correct") == 2 && stats.getInt("presented") >= 3)
            check(store.deleteBank("数学") == 2)
            check(store.questions(enabledOnly = false).length() == 0)
            store.set("credit_ms", "0")
            engine.saveConfig(JSONObject().put("learningContent", "video").put("videoMinutes", 1)
                .put("educationalVideos", JSONArray().put("https://example.test/lesson.mp4")))
            val lesson = engine.start().getJSONObject("videoLesson")
            val lessonId = lesson.getString("id")
            try { engine.completeVideo(lessonId);error("video reward granted early") } catch (_: IllegalArgumentException) { }
            val storedVideo = JSONObject(store.get("video_lesson")).put("watchedMs", 59900)
                .put("playing", true).put("tick", SystemClock.elapsedRealtime() - 200)
            store.set("video_lesson", storedVideo.toString())
            check(engine.videoProgress(lessonId, true).getLong("remainingSeconds") == 0L)
            check(engine.completeVideo(lessonId).getDouble("rewardMinutes") > 0)
            try { engine.completeVideo(lessonId);error("video reward duplicated") } catch (_: IllegalArgumentException) { }
            // 家长改「每次视频学习分钟」要立刻生效：以前直接复用旧任务，改时长等于没改
            engine.saveConfig(JSONObject().put("learningContent", "video").put("videoMinutes", 1)
                .put("educationalVideos", JSONArray().put("https://example.test/lesson.mp4")))
            val retry = engine.start().getJSONObject("videoLesson")
            val retryId = retry.getString("id")
            store.set("video_lesson", JSONObject(store.get("video_lesson")).put("watchedMs", 30000).toString())
            engine.saveConfig(JSONObject().put("learningContent", "video").put("videoMinutes", 5)
                .put("educationalVideos", JSONArray().put("https://example.test/lesson.mp4")))
            val longer = engine.start().getJSONObject("videoLesson")
            check(longer.getString("id") == retryId) { "同一天同一地址应复用同一个任务: $longer" }
            check(longer.getLong("requiredSeconds") == 300L) { "改时长应立即生效: $longer" }
            check(longer.getLong("remainingSeconds") == 270L) { "已经看过的 30 秒不能因为改时长丢掉: $longer" }
            // 任务跨天或被重建：播放页要能自己退出，而不是每秒抛一次异常把人卡在黑屏上
            store.set("video_lesson", JSONObject(store.get("video_lesson")).put("day", 1).toString())
            val expired = engine.videoProgress(retryId, true)
            check(expired.optBoolean("expired")) { "过期任务应返回 expired: $expired" }
            check(!expired.has("remainingSeconds")) { "过期任务不能带 remainingSeconds，否则会被当成已完成: $expired" }
            try { engine.completeVideo(retryId);error("expired lesson must not grant reward") } catch (_: IllegalArgumentException) { }
            store.set("video_lesson", "")
            remoteSync(store)
            store.set("active_round", JSONObject().put("id", "deleted-round").put("question", JSONObject().put("bank", "测试")).toString())
            TimePolicy(store) { -1 }.deadline("feedback_deadline", 60000)
            val generation = engine.status().getLong("quizGeneration")
            check(engine.clearQuestions() == 1)
            check(store.get("active_round").isEmpty() && store.get("feedback_deadline").isEmpty())
            check(engine.status().getLong("quizGeneration") == generation + 1)

            val speech = SpeechReader.save(store, JSONObject().put("mode", "system").put("azureKey", "test-secret").put("aliyunSecret", "other-secret"))
            check(!speech.has("azureKey") && !speech.has("aliyunSecret") && speech.getBoolean("azureKeySet"))
            SpeechReader.save(store, JSONObject().put("mode", "auto").put("azureKey", ""))
            check(JSONObject(store.get("speech_settings")).getString("azureKey") == "test-secret")
            check(!SpeechReader.save(store, JSONObject().put("mode", "system").put("clearKeys", true)).getBoolean("azureKeySet"))
            val config = BankAutomation.save(store, JSONObject().put("sources", JSONArray().put(JSONObject().put("name", "测试").put("url", "https://example.test/bank.json"))))
            check(config.getJSONArray("sources").length() == 1)
            // #125：自动补题题型筛选
            val typed = BankAutomation.save(store, JSONObject().put("sources", JSONArray()).put("types", JSONArray().put("single").put("boolean")))
            check(typed.getJSONArray("types").length() == 2 && typed.getJSONArray("types").getString(1) == "boolean")
            try { BankAutomation.save(store, JSONObject().put("sources", JSONArray()).put("types", JSONArray()));error("empty types accepted") } catch (_: IllegalArgumentException) { }
            check(BankAutomation.settings(store).getJSONArray("types").length() == 2) { "保存失败不能改掉已存的题型" }
            // #126：朗读语言支持粤语，且留空表示跟随系统
            check(SpeechReader.save(store, JSONObject().put("mode", "system").put("readLanguage", "zh-HK")).getString("readLanguage") == "zh-HK")
            try { SpeechReader.save(store, JSONObject().put("mode", "system").put("readLanguage", "ja-JP"));error("invalid read language accepted") } catch (_: IllegalArgumentException) { }
            check(SpeechReader.settings(store).getString("readLanguage") == "zh-HK")
            SpeechReader.save(store, JSONObject().put("mode", "system").put("readLanguage", ""))
            check(SpeechReader.settings(store).getString("readLanguage").isEmpty()) { "留空 = 跟随每道题的语言" }
            // DeepSeek 语音：老电视没有系统朗读时用它（夜读 / 识别都走同一份 Key）
            val deepseek = SpeechReader.save(store, JSONObject().put("mode", "deepseek").put("deepseekKey", "sk-test").put("deepseekVoice", "tide").put("recognizeMode", "auto"))
            check(deepseek.getString("mode") == "deepseek" && deepseek.getBoolean("deepseekKeySet") && !deepseek.has("deepseekKey")) { "DeepSeek Key 不能回传给面板: $deepseek" }
            check(deepseek.getString("deepseekVoice") == "tide" && deepseek.getString("recognizeMode") == "auto")
            try { SpeechReader.save(store, JSONObject().put("mode", "deepseek").put("deepseekVoice", "nova")); error("bad voice accepted") } catch (_: IllegalArgumentException) { }
            try { SpeechReader.save(store, JSONObject().put("mode", "novoice")); error("bad mode accepted") } catch (_: IllegalArgumentException) { }
            check(SpeechReader.recognizeKey(store) == "sk-test") { "识别通路为自动时填了 Key 就用 DeepSeek" }
            SpeechReader.save(store, JSONObject().put("mode", "deepseek").put("recognizeMode", "system"))
            check(SpeechReader.recognizeKey(store).isEmpty()) { "识别通路选系统时不录音上传" }
            SpeechReader.save(store, JSONObject().put("mode", "deepseek").put("recognizeMode", "auto"))
            // 分段：按句子攒到 400 字，拼起来要和原文一致（长题不能读丢字）
            val long = "第一句话。第二句话，稍长一点点。".repeat(40)
            val chunks = SpeechReader.cloudChunks(long, "deepseek")
            check(chunks.all { it.length <= 400 } && chunks.joinToString("") == long.replace(" ", "").replace("\n", "")) { "DeepSeek 分段丢字: ${chunks.size}" }
            check(SpeechReader.cloudChunks("短题", "deepseek") == listOf("短题"))
            // 录音转 WAV：16 kHz / 单声道 / 16 位
            val wav = VoiceCapture.wav(ByteArray(3200))
            check(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE" && wav.size == 3244)
            check((wav[24].toInt() and 0xff) == 0x80 && (wav[25].toInt() and 0xff) == 0x3e) { "采样率需要是 16000" }
            check((wav[22].toInt() and 0xff) == 1 && (wav[34].toInt() and 0xff) == 16) { "单声道 16 位" }
            try { SpeechReader.transcribe("", wav); error("empty key accepted") } catch (_: IllegalArgumentException) { }
            store.upsertQuestion(JSONObject("""{"id":"canto","q":"粵語題目","a":["A","B"],"k":0,"language":"zh-HK"}"""))
            store.deleteQuestions(listOf("canto"))
            children(store, engine)
            wrongBank(context)
            coverage(context)
            questionPaging(context)
            launcherIcon(context)
            try { BankAutomation.save(store, JSONObject().put("sources", JSONArray().put(JSONObject().put("name", "invalid").put("url", "file:///tmp/bank.json"))));error("invalid source accepted") } catch (_: IllegalArgumentException) { }
            timezone(store)
        } finally { store.close();context.deleteDatabase(name) }
    }
    /** #132：多孩子档案——学习状态、规则、事件、复习进度都按孩子隔离 */
    private fun children(store: LocalStore, engine: LearningEngine) {
        check(store.activeChild() == LocalStore.DEFAULT_CHILD) { "默认孩子沿用旧存储键" }
        val first = JSONObject(store.children().getJSONObject(0).toString())
        check(store.children().length() == 1 && first.getString("name").isNotEmpty() && first.getBoolean("active"))

        engine.saveConfig(JSONObject().put("dailyMinutes", 25))
        store.set("used_ms", "60000")
        val firstRounds = store.roundsDoneToday()
        val second = store.createChild("小红").getString("id")
        check(store.children().length() == 2)
        try { store.createChild("小红"); error("duplicate child name accepted") } catch (_: IllegalArgumentException) { }

        store.switchChild(second)
        check(store.get("used_ms").ifEmpty { "0" } == "0") { "使用时长必须按孩子分开" }
        check(store.config().getInt("dailyMinutes") == 25) { "新孩子沿用家长的规则，不用重配" }
        engine.saveConfig(JSONObject().put("dailyMinutes", 10))
        check(store.config().getInt("dailyMinutes") == 10)

        // 事件统计只看当前孩子
        store.logEvent("quiz_pass", JSONObject())
        check(store.roundsDoneToday() == 1)
        store.switchChild(LocalStore.DEFAULT_CHILD)
        check(store.config().getInt("dailyMinutes") == 25) { "改一个孩子的规则不能影响另一个" }
        check(store.roundsDoneToday() == firstRounds) { "完成组数不能串到别的孩子头上" }

        // 复习进度按孩子分开
        val studied = store.repetitionStats().getInt("studied")
        store.upsertQuestion(JSONObject("""{"id":"sr","q":"间隔重复","a":["A","B"],"k":0}"""))
        store.recordProgress("sr", 5)
        check(store.repetitionStats().getInt("studied") == studied + 1)
        val total = store.questions().length()
        check(store.questions(spaced = true).length() == total) { "联表后不能把别的孩子的进度行连出来" }
        store.switchChild(second)
        check(store.repetitionStats().getInt("studied") == 0) { "复习进度必须按孩子隔离" }

        // 删除孩子：连记录一起清掉，题目保留，第一个孩子不能删
        store.switchChild(LocalStore.DEFAULT_CHILD)
        store.deleteChild(second)
        check(store.children().length() == 1 && store.questions().length() == total)
        try { store.deleteChild(LocalStore.DEFAULT_CHILD); error("default child deleted") } catch (_: IllegalArgumentException) { }
        try { store.switchChild("missing"); error("missing child accepted") } catch (_: IllegalArgumentException) { }
        store.set("used_ms", "0")
    }

    /** #136 错题库：记录每题最近一次作答结果，错题优先，加练题从错题里出 */
    private fun wrongBank(context: Context) {
        val name = "feature-wrong.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("allDay", true).put("dailyLimitEnabled", false).put("roundsLimitEnabled", false)
                .put("playMode", "practice").put("extraEnabled", true)
                .put("wrongThreshold", 1).put("extraQuestions", 1).put("wrongReviewSeconds", 0).put("wrongFirst", true))
            check(store.config().getBoolean("wrongFirst")) { "错题优先开关要能保存" }
            listOf("q0", "q1", "q2", "q3").forEach {
                store.upsertQuestion(JSONObject("""{"id":"$it","q":"$it","a":["对","错"],"k":0}"""))
            }
            fun statsOf(id: String): JSONObject {
                val all = store.questions()
                for (i in 0 until all.length()) if (all.getJSONObject(i).getString("id") == id) return all.getJSONObject(i).getJSONObject("stats")
                error("题库里找不到 $id")
            }
            check(statsOf("q0").isNull("lastCorrect")) { "还没作答过的题不能算错题" }

            store.answered("q0", false)
            store.answered("q2", false)
            check(statsOf("q0").get("lastCorrect") == false) { "答错后要记成错题" }
            val ordered = store.questions(wrongFirst = true)
            check(ordered.getJSONObject(0).getString("id") in listOf("q0", "q2") && ordered.getJSONObject(1).getString("id") in listOf("q0", "q2")) { "错题要排在最前面" }

            var round = engine.start()
            val firstId = round.getJSONObject("question").getString("id")
            check(firstId in listOf("q0", "q2")) { "错题优先：第一题应当先出错题，实际 $round" }
            var result = engine.answer(answer(round, 1)) // 答错，攒出 1 道加练题
            check(!result.getBoolean("correct")) { "这题应当判错: $result" }

            // 加练阶段：错题里还有另一道，必须从错题库里出
            val extra = result.getJSONObject("next")
            val extraId = extra.getJSONObject("question").getString("id")
            check(extra.getBoolean("extra")) { "下一题应当是加练题: $extra" }
            check(extraId != firstId && statsOf(extraId).get("lastCorrect") == false) { "加练题必须从错题里出，实际 $extra" }

            result = engine.answer(answer(extra, 0)) // 答对加练题，错题库清空
            check(result.getBoolean("correct"))
            check(statsOf(firstId).get("lastCorrect") == false && statsOf(extraId).get("lastCorrect") == true) { "答对一次后要退出错题库" }
            val back = result.getJSONObject("next")
            check(back.getJSONObject("question").getString("id") == firstId) { "答错的题要重新排到前面，实际 $back" }

            // 错题清空后抽题照常进行，不能把一轮卡住
            result = engine.answer(answer(back, 0))
            check(result.getBoolean("correct"))
            val clean = store.questions(wrongFirst = true)
            check((0 until clean.length()).none { clean.getJSONObject(it).getJSONObject("stats").get("lastCorrect") == false }) { "都已经答对，不该再有错题" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    /** 覆盖优先：还没答对过的题排在前面，家长面板的 coverage() 与出题顺序用同一套口径 */
    private fun coverage(context: Context) {
        val name = "feature-coverage.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("allDay", true).put("dailyLimitEnabled", false).put("roundsLimitEnabled", false)
                .put("playMode", "practice").put("coverageFirst", true))
            check(store.config().getBoolean("coverageFirst")) { "覆盖优先开关要能保存" }
            listOf("c0", "c1", "c2").forEach {
                store.upsertQuestion(JSONObject("""{"id":"$it","q":"$it","a":["对","错"],"k":0}"""))
            }
            // c0 已经答对过，c1 / c2 还没答对：覆盖优先时已答对的必须排到最后
            store.answered("c0", true)
            val ordered = store.questions(coverageFirst = true)
            val ids = (0 until ordered.length()).map { ordered.getJSONObject(it).getString("id") }
            check(ids.size == 3 && ids.last() == "c0") { "已答对的题应当排在最后，实际 $ids" }
            // 覆盖统计必须在出题之前看：start() 会把出过的那道题记成 presented，untouched 就少一道
            val cov = store.coverage()
            check(cov.getInt("total") == 3) { "覆盖率统计的题量不对: $cov" }
            check(cov.getInt("covered") == 1) { "答对过的题只该有 1 道: $cov" }
            check(cov.getInt("pending") == 2) { "未答对的题该有 2 道: $cov" }
            check(cov.getInt("untouched") == 2) { "没出过的题只该有 2 道: $cov" }
            check(cov.getInt("percent") == 33) { "覆盖率应当是 33%: $cov" }
            check(cov.getJSONArray("items").length() == 2) { "未答对的样本数量不对: $cov" }

            val round = engine.start()
            check(round.getJSONObject("question").getString("id") != "c0") { "第一题不该出已答对的题: $round" }

            // 关掉开关后回到原来的随机顺序
            engine.saveConfig(JSONObject().put("coverageFirst", false))
            check(!store.config().getBoolean("coverageFirst")) { "关掉覆盖优先要能保存" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * 题库页分页：筛选 / 搜索 / 分页都在 SQL 里做，只返回一页；
     * bank / type 提成独立列后，题库筛选和删除题库不再全表解析 details。
     */
    private fun questionPaging(context: Context) {
        val name = "feature-page.db"
        context.deleteDatabase(name)
        LocalStore(context, name).use { store ->
            repeat(60) { i ->
                val bank = if (i % 3 == 0) "语文" else "数学"
                store.upsertQuestion(JSONObject()
                    .put("id", "p$i")
                    .put("q", if (i % 10 == 0) "分数 100% 练习 $i" else "题目 $i")
                    .put("a", JSONArray(listOf("A", "B"))).put("k", 0)
                    .put("bank", bank).put("level", if (i % 3 == 0) "二年级" else "一年级")
                    .put("subject", "常识").put("type", "single"))
            }
            store.upsertQuestion(JSONObject("""{"id":"paused","q":"暂停题","a":["A","B"],"correctIndices":[0],"bank":"数学","type":"multiple"}"""))
            store.setEnabled(listOf("paused"), false)

            val first = store.questionPage(limit = 25, offset = 0)
            check(first.getInt("total") == 60) { "启用题应当是 60 道: $first" }
            check(first.getJSONArray("items").length() == 25) { "一页只该返回 25 道: $first" }
            check(first.getInt("limit") == 25 && first.getInt("offset") == 0)
            check(store.questionPage(limit = 25, offset = 50).getJSONArray("items").length() == 10) { "最后一页只该有 10 道" }
            check(store.questionPage(enabledOnly = false, limit = 1).getInt("total") == 61) { "含暂停题应当是 61 道" }

            // 题库 / 题型筛选走独立列
            check(store.questionPage(bank = "数学", limit = 100).getInt("total") == 40) { "数学题库应当是 40 道" }
            check(store.idsInBank("数学").size == 41) { "删除题库要按 bank 列找题，含已暂停的题" }
            check(store.questionPage(type = "multiple", limit = 100).getInt("total") == 0) { "唯一的多选题已暂停，默认不该出现" }
            check(store.questionPage(type = "multiple", enabledOnly = false, limit = 100).getInt("total") == 1) { "多选筛选要按 type 列" }

            // 搜索：题干命中；% 是普通字符，不能当通配符扫全库
            check(store.questionPage(search = "分数", limit = 100).getInt("total") == 6)
            check(store.questionPage(search = "100%", limit = 100).getInt("total") == 6) { "百分号要当普通字符搜索" }
            check(store.questionPage(search = "题目 7", limit = 100).getInt("total") == 1) { "搜索要能定位到单题" }

            // 错题筛选与题库页计数
            store.answered("p0", false)
            check(store.questionPage(wrongOnly = true).getInt("total") == 1) { "错题筛选只应有 1 道" }
            val meta = store.questionMeta()
            check(meta.getInt("total") == 61 && meta.getInt("paused") == 1 && meta.getInt("wrong") == 1) { "题库页计数不对: $meta" }
            val banks = meta.getJSONArray("banks")
            check(banks.length() == 2 && banks.getString(0) == "数学" && banks.getString(1) == "语文") { "题库名列表不对: $meta" }
            check(meta.getJSONArray("levels").length() == 2) { "年级列表不对: $meta" }
        }
        context.deleteDatabase(name)
    }

    /** 隐藏桌面图标：只关启动入口 alias，MainActivity 和进程不受影响，随时可以恢复 */
    private fun launcherIcon(context: Context) {
        val pm = context.packageManager
        // alias 名跟着 Manifest 包名走，不是 applicationId（调试包带 .debug 后缀也不会变）
        val alias = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
            .activities!!.first { it.targetActivity == MainActivity::class.java.name }
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val before = LauncherIcon.hidden(context)
        try {
            check(LauncherIcon.setHidden(context, true)) { "隐藏后组件应当处于禁用状态" }
            check(LauncherIcon.hidden(context)) { "隐藏状态要能读回来" }
            check(pm.queryIntentActivities(launcher, 0).none { it.activityInfo.name == alias.name }) {
                "隐藏后桌面不应该再列出这个入口"
            }
            // 应用本身还在：入口只是 alias，打开主界面和通知栏跳转都不受影响
            check(pm.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java.name), 0) != null) {
                "隐藏图标不能动到 MainActivity"
            }
            check(!LauncherIcon.setHidden(context, false)) { "恢复后组件应当重新启用" }
            check(pm.queryIntentActivities(launcher, 0).any { it.activityInfo.name == alias.name }) {
                "恢复后桌面入口要重新出现"
            }
        } finally {
            LauncherIcon.setHidden(context, before)
        }
    }

    /** 旧库（v6）升级：事件与复习进度补上 child 列，旧数据归第一个孩子 */
    private fun childMigration(context: Context) {
        val name = "children-migration-regression.db"
        context.deleteDatabase(name)
        try {
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT)")
            db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER)")
            db.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY, nonce TEXT UNIQUE, state TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER)")
            db.execSQL("CREATE TABLE question_progress(qid TEXT PRIMARY KEY, repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE question_stats(qid TEXT PRIMARY KEY, presented INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, last_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO questions(id,stem,options,answer) VALUES('old','旧题','[\"A\",\"B\"]',0)")
            db.execSQL("INSERT INTO kv(k,v) VALUES('config','{\"dailyMinutes\":33}')")
            db.execSQL("INSERT INTO events(type,payload,ts) VALUES('quiz_pass','{}',${System.currentTimeMillis()})")
            db.execSQL("INSERT INTO question_progress(qid,repetitions,interval_days,ease,due_at,updated_at) VALUES('old',1,1,2.5,0,0)")
            db.version = 6
            db.close()
            LocalStore(context, name).use { store ->
                check(store.config().getInt("dailyMinutes") == 33) { "升级不能丢配置" }
                check(store.activeChild() == LocalStore.DEFAULT_CHILD)
                check(store.children().length() == 1) { "升级后要有第一个孩子" }
                check(store.roundsDoneToday() == 1) { "旧的使用记录归第一个孩子" }
                check(store.repetitionStats().getInt("studied") == 1) { "旧的复习进度归第一个孩子" }
                store.recordProgress("old", 5)
                check(store.repetitionStats().getInt("studied") == 1) { "复合主键下重复答题不能插出第二行" }
            }
        } finally { context.deleteDatabase(name) }
    }

    fun levelSelection(context: Context) {
        val name = "level-selection-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("playMode", "practice").put("childSelectLevel", true)
                .put("quizBanks", JSONArray().put("数学")).put("quizSubjects", JSONArray().put("数学")))
            fun expectNoLevels() {
                try { engine.start(); error("没有可选年级时不能开始答题") }
                catch (e: IllegalArgumentException) { check(e.message == "请先为启用题目标注年级，或关闭孩子选择年级") }
                check(store.get("active_round").isEmpty())
            }
            fun add(id: String, level: String, bank: String = "数学", subject: String = "数学") {
                store.upsertQuestion(JSONObject().put("id", id).put("q", id).put("type", "single")
                    .put("a", JSONArray().put("yes").put("no")).put("k", 0)
                    .put("level", level).put("bank", bank).put("subject", subject))
            }
            expectNoLevels()
            add("four-a", "四年级"); add("four-b", "四年级")
            add("other-bank", "一年级", bank = "其他")
            add("other-subject", "二年级", subject = "英语")
            add("paused", "三年级"); store.setEnabled(listOf("paused"), false)
            add("unassigned", "")
            val automatic = engine.start()
            check(!automatic.has("chooseLevel") && automatic.getString("level") == "四年级") { "唯一可选年级应直接开始: $automatic" }
            check(automatic.getJSONObject("question").getString("level") == "四年级") { "自动选择后不能抽到未标注年级的题" }
            check(JSONObject(store.get("active_round")).getString("level") == "四年级")
            check(store.config().getBoolean("childSelectLevel")) { "自动选择不能关闭多选时的年级选择功能" }
            add("five", "五年级")
            val resumed = engine.start("五年级")
            check(resumed.getString("id") == automatic.getString("id") && resumed.getString("level") == "四年级") { "进行中的一轮不能切换年级" }
            store.set("active_round", "")
            val choices = engine.start().getJSONArray("chooseLevel")
            check((0 until choices.length()).map { choices.getString(it) }.toSet() == setOf("四年级", "五年级"))
            check(store.get("active_round").isEmpty()) { "等待选择时不能提前创建任务" }
            try { engine.start("一年级"); error("不能选择范围外的年级") } catch (_: IllegalArgumentException) { }
            check(engine.start("五年级").getJSONObject("question").getString("id") == "five")
            store.set("active_round", "")
            engine.saveConfig(JSONObject().put("quizLevels", JSONArray().put("四年级")))
            check(engine.start().getString("level") == "四年级") { "家长筛选后只剩一个年级也应直接开始" }
            store.set("active_round", "")
            try { engine.start("五年级"); error("只有一个可选年级时也不能接受范围外的年级") } catch (_: IllegalArgumentException) { }
            store.setEnabled(listOf("four-a", "four-b"), false)
            expectNoLevels()
            engine.saveConfig(JSONObject().put("childSelectLevel", false).put("quizLevels", JSONArray()))
            val withoutSelection = engine.start()
            check(!withoutSelection.has("chooseLevel") && withoutSelection.getString("level").isEmpty())
        } finally { store.close(); context.deleteDatabase(name) }
    }

    private fun migration(context: Context) {
        val name = "migration-regression.db"
        context.deleteDatabase(name)
        try {
            LocalStore(context, name).use { old ->
                old.upsertQuestion(JSONObject("""{"id":"preserved","q":"kept","a":["A","B"],"k":0}"""))
                old.saveConfig(JSONObject().put("dailyMinutes", 55))
                old.writableDatabase.execSQL("DROP TABLE question_stats")
                old.writableDatabase.version = 5
            }
            LocalStore(context, name).use { migrated ->
                check(migrated.config().getInt("dailyMinutes") == 55)
                val question = migrated.questions().getJSONObject(0)
                check(question.getString("id") == "preserved" && question.getJSONObject("stats").getInt("presented") == 0)
            }
        } finally { context.deleteDatabase(name) }
        // v7 老库的 question_stats 没有 last_correct，升级时要补上列，且老数据不丢
        val v7 = "migration-v7-regression.db"
        context.deleteDatabase(v7)
        try {
            LocalStore(context, v7).use { old ->
                old.upsertQuestion(JSONObject("""{"id":"kept","q":"kept","a":["A","B"],"k":0}"""))
                old.answered("kept", true)
                old.writableDatabase.execSQL("DROP TABLE question_stats")
                old.writableDatabase.execSQL("CREATE TABLE question_stats(qid TEXT PRIMARY KEY, presented INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, last_at INTEGER NOT NULL DEFAULT 0)")
                old.writableDatabase.execSQL("INSERT INTO question_stats(qid,answered,correct) VALUES('kept',1,1)")
                old.writableDatabase.version = 7
            }
            LocalStore(context, v7).use { migrated ->
                check(migrated.readableDatabase.version == 10)
                val stats = migrated.questions().getJSONObject(0).getJSONObject("stats")
                check(stats.getInt("answered") == 1 && stats.getInt("correct") == 1) { "升级不能丢掉旧统计: $stats" }
                check(stats.isNull("lastCorrect")) { "旧库没有最近一次对错，应当当作未作答: $stats" }
                migrated.answered("kept", false)
                check(migrated.questions().getJSONObject(0).getJSONObject("stats").get("lastCorrect") == false) { "升级后要能记错题" }
            }
        } finally { context.deleteDatabase(v7) }
        // 第 9 版：bank / type 从 details 回填成独立列，老数据升级后题库筛选、删除题库要照常可用
        val v8 = "migration-v8-regression.db"
        context.deleteDatabase(v8)
        try {
            LocalStore(context, v8).use { old ->
                // 直接按第 8 版的列写一行，bank / type 只存在 details 里
                old.writableDatabase.execSQL(
                    "INSERT INTO questions(id,subject,level,difficulty,stem,options,answer,tip,enabled,created_at,details) VALUES('legacy','常识','一年级',2,'老题','[\"A\",\"B\"]',0,'',1,0,?)",
                    arrayOf(JSONObject().put("bank", "老题库").put("type", "multiple").toString()))
                old.writableDatabase.version = 8
            }
            LocalStore(context, v8).use { migrated ->
                check(migrated.readableDatabase.version == 10)
                check(migrated.idsInBank("老题库") == listOf("legacy")) { "升级时要按 details 回填题库列" }
                check(migrated.questionPage(bank = "老题库", type = "multiple").getInt("total") == 1) { "升级后题库筛选要能用" }
            }
        } finally { context.deleteDatabase(v8) }
        // 第 10 版：question_stats 改为按孩子复合主键，且内联 data:image 自动落盘为本地媒体文件
        val v9 = "migration-v9-regression.db"
        context.deleteDatabase(v9)
        try {
            val tinyPng = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            LocalStore(context, v9).use { old ->
                old.writableDatabase.execSQL("DROP TABLE question_stats")
                old.writableDatabase.execSQL("CREATE TABLE question_stats(qid TEXT PRIMARY KEY, presented INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, last_at INTEGER NOT NULL DEFAULT 0, last_correct INTEGER NOT NULL DEFAULT -1)")
                old.writableDatabase.execSQL(
                    "INSERT INTO questions(id,subject,level,difficulty,stem,options,answer,tip,enabled,created_at,details,bank,type) VALUES('img-q','数学','一年级',2,'看图','[\"A\",\"B\"]',0,'',1,0,?,'默认题库','single')",
                    arrayOf(JSONObject().put("type", "single").put("image", tinyPng).toString())
                )
                old.writableDatabase.execSQL("INSERT INTO question_stats(qid,presented,answered,correct,last_correct) VALUES('img-q',3,2,1,0)")
                old.writableDatabase.version = 9
            }
            LocalStore(context, v9).use { migrated ->
                check(migrated.readableDatabase.version == 10)
                val q = migrated.questions().getJSONObject(0)
                check(MediaStore.isMediaPath(q.optString("image"))) { "升级后内联配图应迁出到本地媒体目录: ${q.optString("image")}" }
                check(q.getJSONObject("stats").getInt("answered") == 2) { "默认孩子的旧统计必须保留" }
                val sibling = migrated.createChild("弟弟").getString("id")
                migrated.switchChild(sibling)
                check(migrated.questions().getJSONObject(0).getJSONObject("stats").getInt("answered") == 0) { "新孩子的统计应独立从零开始" }
            }
        } finally { context.deleteDatabase(v9) }
    }
    private fun remoteSync(store: LocalStore) {
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val body = java.util.concurrent.atomic.AtomicReference("""{"items":[{"id":"one","q":"original","a":["A","B"],"k":0}]}""")
        val thread = Thread {
            try { repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                    val bytes = body.get().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                    }
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true;start() }
        try {
            val source = JSONObject().put("name", "remote-test").put("url", "http://127.0.0.1:${server.localPort}/bank.json")
            check(BankAutomation.syncSource(store, source) == 1)
            val original = store.questions(enabledOnly = false).getJSONObject(0)
            body.set("""{"items":[{"id":"one","q":"changed","a":["A","B"],"k":0},{"id":"two","q":"invalid","a":[],"k":0}]}""")
            try { BankAutomation.syncSource(store, source);error("invalid remote bank accepted") } catch (_: IllegalArgumentException) { }
            val after = store.questions(enabledOnly = false)
            check(after.length() == 1 && after.getJSONObject(0).getString("q") == "original") { "同步失败必须回滚已更新的题目" }
            check(after.getJSONObject(0).getString("id") == original.getString("id"))
        } finally { server.close();thread.join(2000) }
    }
    /**
     * 时区：可用时段和「今天」的界线按配置的时区算。
     * 场景是电视机系统时区固定在国内、人已经搬到国外，或者反过来。
     */
    private fun timezone(store: LocalStore) {
        val engine = LearningEngine(store)
        val before = store.config().optString("timezone")
        try {
            // 官方题库仓库在国内走 Gitee 镜像，只映射这个仓库的地址
            check(BankMarket.giteeMirror("https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV-banks@main/banks/open-play.json")
                == "https://gitee.com/chadyi/StudyOnTV-banks/raw/main/banks/open-play.json")
            check(BankMarket.giteeMirror("https://raw.githubusercontent.com/chadyi/StudyOnTV-banks/main/index.json")
                == "https://gitee.com/chadyi/StudyOnTV-banks/raw/main/index.json")
            check(BankMarket.giteeMirror("https://example.com/bank.json") == null) { "家长自己填的源不改写" }

            store.saveConfig(JSONObject().put("timezone", "America/New_York"))
            try { store.saveConfig(JSONObject().put("timezone", "Mars/Olympus"));error("unknown zone accepted") } catch (_: IllegalArgumentException) { }
            check(DeviceClock.setting(store) == "America/New_York") { "未知时区不能写进配置" }
            // 引擎和「今天」的起点都跟着配置的时区走
            val newYork = TimeZone.getTimeZone("America/New_York")
            val offset = (engine.now().get(Calendar.ZONE_OFFSET) + engine.now().get(Calendar.DST_OFFSET)) / 3600000
            check(offset == newYork.getOffset(store.clockReading().millis) / 3600000) { "引擎应使用配置的时区" }
            val day = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = newYork }
            val start = day.format(java.util.Date(store.startOfToday()))
            check(start.endsWith("00:00") && start == day.format(java.util.Date(store.clockReading().millis)).take(10) + " 00:00") { "「今天」的起点按配置时区算: $start" }

            // 同一个时刻：上海 09:10、纽约前一天 20:10，窗口只开 09:00–10:00
            val instant = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                set(2030, Calendar.JANUARY, 7, 9, 10, 0);set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val slots = JSONArray().apply { repeat(7) { put("0".repeat(18) + "11" + "0".repeat(28)) } }
            store.saveConfig(JSONObject().put("timezone", "Asia/Shanghai").put("allDay", false).put("scheduleMode", "allow")
                .put("weekSlots", slots).put("dailyLimitEnabled", false).put("roundsLimitEnabled", false)
                .put("dailyFreeMinutes", 0).put("bootFreeMinutes", 0).put("restEnabled", false)
                .put("timePoints", JSONArray()).put("exemptWindows", JSONArray()))
            store.set("remote_override", "");store.set("feedback_deadline", "")
            engine.remote("credit", 30, "tz-test")
            fun at(zone: String) = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = instant }
            engine.now = { at("Asia/Shanghai") }
            check(engine.status().getString("reason").isEmpty()) { "上海时间在时段内应可用: ${engine.status()}" }
            // 学习页的「可用时段」提示要跟着周计划走，不能停在旧版 windowStart/windowEnd（默认 18:30–20:00）
            check(engine.status().getString("scheduleText") == "周一至周日 09:00–10:00") { "可用时段文案应按周计划生成: ${engine.status()}" }
            store.saveConfig(JSONObject().put("allDay", true))
            check(engine.status().getString("scheduleText") == "全天可用") { "全天模式下应显示全天可用" }
            store.saveConfig(JSONObject().put("allDay", false))
            check(engine.status().getString("scheduleText") == "周一至周日 09:00–10:00") { "关掉全天模式后文案要跟着回来" }
            engine.now = { at("America/New_York") }
            check(engine.status().getString("reason") == "schedule") { "纽约时间在时段外应锁定: ${engine.status()}" }
            engine.now = { at("UTC") }
            val utc = engine.status()
            check(utc.getString("reason") == "schedule")
            // 「下次可用」按配置的时区显示，而不是设备时区
            check(utc.optString("nextAtText").let { it.contains("月") && it.contains("日") && it.endsWith(":00") }) { "下次可用文案: $utc" }
            val fourAm = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(2026, Calendar.JANUARY, 5, 4, 0, 0);set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            check(DeviceClock.format(fourAm, store) == "1 月 5 日 12:00") { "UTC 04:00 在上海应是 12:00" }
            // 今明两天说「今天 / 明天」（Issue #147），再远仍显示日期
            val realNow = System.currentTimeMillis()
            check(DeviceClock.format(realNow, store).startsWith("今天 ")) { "当天应显示今天: ${DeviceClock.format(realNow, store)}" }
            check(DeviceClock.format(realNow + 86400000L, store).startsWith("明天 ")) { "次日应显示明天: ${DeviceClock.format(realNow + 86400000L, store)}" }
            check(DeviceClock.format(realNow + 3 * 86400000L, store).contains("月")) { "更远仍显示日期: ${DeviceClock.format(realNow + 3 * 86400000L, store)}" }
        } finally {
            engine.now = { DeviceClock.calendar(store) }
            store.saveConfig(JSONObject().put("timezone", before))
        }
    }
    private fun answer(round: JSONObject, selection: Int) = JSONObject().put("roundId", round.getString("id"))
        .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(selection))
    private fun bootFreeInterval(context: Context) {
        val name = "boot-free-interval-regression.db"
        context.deleteDatabase(name)
        var store = LocalStore(context, name)
        try {
            val minute = 60_000L
            var bootId = 1
            var elapsed = 20 * minute
            val firstGrantAt = System.currentTimeMillis()
            var wall = firstGrantAt
            fun policy() = TimePolicy(store, { elapsed }, { wall }) { bootId }

            var engine = LearningEngine(store)
            check(store.config().getInt("bootFreeIntervalMinutes") == 0)
            engine.saveConfig(JSONObject().put("bootFreeMinutes", 30).put("bootFreeIntervalMinutes", 120)
                .put("dailyMinutes", 90))
            check(runCatching {
                engine.saveConfig(JSONObject().put("bootFreeIntervalMinutes", 1441))
            }.exceptionOrNull() is IllegalArgumentException)
            var time = policy()
            time.awake(store.config(), true)
            check(engine.status().getLong("bootFreeSeconds") == 1800L)
            engine.consume(5000)

            elapsed += minute
            wall += 180 * minute
            time.awake(store.config(), false)
            time.awake(store.config(), true)
            check(engine.status().getLong("bootFreeSeconds") == 1795L) { "同次开机改系统时间不能提前发放" }

            store.close()
            store = LocalStore(context, name)
            engine = LearningEngine(store)
            time = policy()
            bootId++
            elapsed = 5000
            wall = firstGrantAt + 30 * minute
            time.awake(store.config(), true)
            check(engine.status().getLong("bootFreeSeconds") == 1795L) { "冷启动后必须保留原余额" }
            repeat(359) { engine.consume(5000) }
            check(engine.status().getString("reason") == "timeup")
            check(engine.status().getDouble("usedMinutes") == 30.0)

            bootId++
            wall = firstGrantAt - minute
            time.awake(store.config(), true)
            check(engine.status().getString("reason") == "timeup") { "重启后日期回拨不能重新发放" }
            wall = firstGrantAt + 120 * minute - 1
            time.awake(store.config(), false)
            time.awake(store.config(), true)
            check(engine.status().getString("reason") == "timeup") { "间隔到期前不能发放" }

            wall++
            policy().awake(store.config(), true)
            check(engine.status().getString("reason") == "timeup") { "间隔到期后重开服务不能自动发放" }
            time.awake(store.config(), false)
            time.awake(store.config(), true)
            check(engine.status().getLong("bootFreeSeconds") == 1800L) { "间隔到期后下次唤醒才发放" }
            check(engine.status().getDouble("usedMinutes") == 30.0)

            engine.saveConfig(JSONObject().put("bootFreeIntervalMinutes", 0))
            engine.consume(5000)
            time.awake(store.config(), false)
            time.awake(store.config(), true)
            check(engine.status().getLong("bootFreeSeconds") == 1800L) { "间隔为 0 时保持每次唤醒发放" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun businessClock(context: Context) {
        fun at(day: Int, hour: Int, minute: Int = 0): Long =
            Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                clear()
                set(2030, Calendar.JANUARY, day, hour, minute)
            }.timeInMillis

        val wall = at(6, 20)
        val networkTime = at(7, 9, 10)
        val legacy = DeviceClock.read(Build.VERSION_CODES.S_V2, { error("旧系统不能调用网络时钟") }, { wall })
        check(legacy == DeviceClock.Reading(wall, DeviceClock.Source.SYSTEM))
        val preferred = DeviceClock.read(Build.VERSION_CODES.TIRAMISU, { networkTime }, { error("有网络时间时不能回退") })
        check(preferred == DeviceClock.Reading(networkTime, DeviceClock.Source.NETWORK))

        var network: Long? = null
        val name = "business-clock-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name) {
            DeviceClock.read(Build.VERSION_CODES.TIRAMISU, { network }, { wall })
        }
        try {
            val engine = LearningEngine(store)
            val slots = JSONArray(List(7) { "0".repeat(18) + "11" + "0".repeat(28) })
            engine.saveConfig(JSONObject().put("timezone", "Asia/Shanghai").put("holidayMode", "normal")
                .put("dailyFreeMinutes", 30).put("allDay", false).put("weekSlots", slots))
            check(store.clockReading() == legacy && store.startOfToday() == at(6, 0))
            check(engine.status().getString("reason") == "schedule")
            store.set("used_ms", "1800000")
            store.set("free_quota_ms", "0")
            store.set("free_used_ms", "1800000")

            network = at(7, 11)
            val rollover = engine.status()
            check(store.startOfToday() == at(7, 0) && store.get("usage_day") == at(7, 0).toString())
            check(rollover.getLong("freeQuotaSeconds") == 1800L && rollover.getDouble("usedMinutes") == 0.0)
            check(rollover.getString("reason") == "schedule" && rollover.getLong("nextAvailableAt") == at(8, 9))

            network = networkTime
            check(engine.status().getBoolean("canExit"))
            store.logEvent("quiz_pass", JSONObject())
            check(store.recentEvents(1).getJSONObject(0).getLong("ts") == networkTime)
            check(store.roundsDoneToday() == 1 && store.todayStats().getInt("rounds") == 1)
            val history = store.historyDays(2)
            check(history.getJSONObject(0).getInt("rounds") == 1)
            check(history.getJSONObject(1).getInt("rounds") == 0)
            engine.consume(5000)

            network = null
            engine.status()
            check(store.get("usage_day") == at(7, 0).toString()) { "回退到旧的系统日期时要保留已记账日期" }
            network = networkTime
            check(engine.status().getLong("freeQuotaSeconds") == 1795L)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun dailyQuotaSurvivesClockRollback(context: Context) {
        val name = "daily-quota-clock-regression.db"
        context.deleteDatabase(name)
        var store = LocalStore(context, name)
        try {
            val today = store.startOfToday()
            val rollbackDay = today - TimeUnit.DAYS.toMillis(3650)
            val tomorrow = today + TimeUnit.DAYS.toMillis(1)
            var day = today
            fun usageEvents(): List<Long> {
                return store.readableDatabase.rawQuery("SELECT payload FROM events WHERE type='usage' ORDER BY id", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(JSONObject(cursor.getString(0)).getLong("milliseconds"))
                    }
                }
            }
            var engine = LearningEngine(store) { day }
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 30).put("bootFreeMinutes", 0)
                .put("dailyMinutes", 120))
            val used = (30 * 60_000L - 5000).toString()
            store.set("free_quota_ms", "5000")
            store.set("free_used_ms", used)
            store.set("used_ms", used)
            engine.consume(5000)
            check(engine.status().getString("reason") == "timeup")

            store.close()
            store = LocalStore(context, name)
            day = rollbackDay
            engine = LearningEngine(store) { day }
            engine.awake(true)
            val beforeSync = engine.status()
            check(beforeSync.getString("reason") == "timeup")
            check(beforeSync.getLong("freeQuotaSeconds") == 0L)
            check(beforeSync.getDouble("usedMinutes") == 30.0)
            check(store.get("usage_day") == today.toString())

            day = today
            val afterSync = engine.status()
            check(afterSync.getString("reason") == "timeup")
            check(afterSync.getLong("freeQuotaSeconds") == 0L)
            check(afterSync.getDouble("usedMinutes") == 30.0)
            check(!afterSync.getBoolean("canExit"))

            engine.remote("credit", 5, "test")
            day = rollbackDay
            val adjusted = engine.status()
            check(adjusted.getLong("remainingSeconds") == 300L)
            check(adjusted.getDouble("todayLimitMinutes") == 125.0)
            check(store.get("credit_ms") == "300000" && store.get("cap_adjust_ms") == "300000")
            day = today
            engine.consume(5000)
            val recorded = usageEvents()
            check(recorded == listOf(5000L)) { "不足 30 秒的尾段应仍在内存中" }

            day = tomorrow
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                val activeDay = engine.status()
                check(activeDay.getLong("remainingSeconds") == 295L)
                check(store.get("usage_day") == today.toString())
                check(usageEvents() == recorded) { "事务中查询状态不能写出尾段用量" }
            } finally {
                db.endTransaction()
            }

            val nextDay = engine.status()
            check(nextDay.getLong("freeQuotaSeconds") == 1800L)
            check(nextDay.getDouble("usedMinutes") == 0.0)
            check(nextDay.getBoolean("canExit") && engine.exitSnapshot.canExit)
            check(engine.start(fromGate = true).getBoolean("watching")) { "免费额度到账后，仍打开的拦截页不能重新出题" }
            check(store.get("credit_ms") == "0" && store.get("cap_adjust_ms") == "0")
            engine.consume(5000)
            check(engine.status().getLong("freeQuotaSeconds") == 1795L)
            check(usageEvents() == recorded + 5000L) { "日切时尾段用量应写入且只写入一次" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun videoSettlementAcrossMidnight(context: Context) {
        val name = "midnight-video-settlement.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            val today = store.startOfToday()
            val tomorrow = today + TimeUnit.DAYS.toMillis(1)
            var day = today
            var advanceAfterSample = false
            val engine = LearningEngine(store) {
                val sampled = day
                if (advanceAfterSample) {
                    day = tomorrow
                    advanceAfterSample = false
                }
                sampled
            }
            engine.saveConfig(JSONObject().put("learningContent", "video").put("videoMinutes", 1)
                .put("sessionMinutes", 10).put("educationalVideos", JSONArray().put("https://example.test/video.mp4")))
            val lessonId = engine.start().getJSONObject("videoLesson").getString("id")
            val lesson = JSONObject(store.get("video_lesson"))
            store.set("video_lesson", lesson.put("watchedMs", lesson.getLong("requiredMs")).toString())

            advanceAfterSample = true
            val settled = engine.completeVideo(lessonId)
            check(settled.getDouble("rewardMinutes") == 10.0)
            check(settled.getJSONObject("status").getLong("remainingSeconds") == 600L) { "结算响应沿用事务开始时的记账日" }
            check(store.roundsDoneToday(today) == 1)
            check(engine.status().getLong("remainingSeconds") == 0L)
            check(store.get("usage_day") == tomorrow.toString())
            check(runCatching { engine.completeVideo(lessonId) }.exceptionOrNull() is IllegalArgumentException)
            check(store.roundsDoneToday(today) == 1) { "跨午夜结算只能发放一次" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun expire(store: LocalStore, key: String) {
        store.set(key, JSONObject().put("boot", -1).put("atElapsed", 0).put("untilElapsed", SystemClock.elapsedRealtime() - 1).put("until", 0).toString())
    }
}
