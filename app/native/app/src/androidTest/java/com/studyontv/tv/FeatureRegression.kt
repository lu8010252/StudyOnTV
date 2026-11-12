package com.studyontv.tv

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

object FeatureRegression {
    fun run(context: Context) {
        migration(context)
        childMigration(context)
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
            store.upsertQuestion(JSONObject("""{"id":"canto","q":"粵語題目","a":["A","B"],"k":0,"language":"zh-HK"}"""))
            store.deleteQuestions(listOf("canto"))
            children(store, engine)
            try { BankAutomation.save(store, JSONObject().put("sources", JSONArray().put(JSONObject().put("name", "invalid").put("url", "file:///tmp/bank.json"))));error("invalid source accepted") } catch (_: IllegalArgumentException) { }
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
    private fun answer(round: JSONObject, selection: Int) = JSONObject().put("roundId", round.getString("id"))
        .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(selection))
    private fun expire(store: LocalStore, key: String) {
        store.set(key, JSONObject().put("boot", -1).put("atElapsed", 0).put("untilElapsed", SystemClock.elapsedRealtime() - 1).put("until", 0).toString())
    }
}
