package com.studyontv.tv

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

object FeatureRegression {
    fun run(context: Context) {
        migration(context)
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
            try { BankAutomation.save(store, JSONObject().put("sources", JSONArray().put(JSONObject().put("name", "invalid").put("url", "file:///tmp/bank.json"))));error("invalid source accepted") } catch (_: IllegalArgumentException) { }
        } finally { store.close();context.deleteDatabase(name) }
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
