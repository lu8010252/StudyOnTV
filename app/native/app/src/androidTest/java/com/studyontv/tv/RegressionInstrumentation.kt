package com.studyontv.tv

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** 无第三方测试依赖；只使用独立数据库，不修改家庭题库。 */
class RegressionInstrumentation : Instrumentation() {
    private var probe = false
    private var video = false
    private var children = false
    private var levels = false
    private var launcher = false
    private var videoUrl = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probe = arguments?.getString("probe") == "1"
        video = arguments?.getString("video") == "1"
        children = arguments?.getString("children") == "1"
        levels = arguments?.getString("levels") == "1"
        launcher = arguments?.getString("launcher") == "1"
        videoUrl = arguments?.getString("url") ?: ""
        start()
    }

    override fun onStart() {
        val results = Bundle()
        if (levels) {
            FeatureRegression.levelSelection(targetContext)
            results.putString("stream", "PASS: 年级选择（唯一年级自动开始、多年级选择、范围筛选、暂停题目、空题库、进行中任务保持年级）\n")
            finish(Activity.RESULT_OK, results)
            return
        }
        if (probe) {
            results.putString("stream", BugProbe.run(targetContext))
            finish(Activity.RESULT_OK, results)
            return
        }
        if (children) {
            results.putString("stream", ChildrenProbe.run(targetContext))
            finish(Activity.RESULT_OK, results)
            return
        }
        // 隐藏桌面图标单项：-e launcher 1
        if (launcher) {
            results.putString("stream", try {
                FeatureRegression.launcher(targetContext)
                "PASS: 隐藏桌面图标（alias 开关、桌面入口、MainActivity 不受影响）\n"
            } catch (e: Throwable) {
                android.util.Log.getStackTraceString(e)
            })
            finish(Activity.RESULT_OK, results)
            return
        }
        // 公益视频播放冒烟：-e video 1 -e url <可访问的 mp4 直链>
        if (video) {
            results.putString("stream", VideoProbe.run(targetContext, videoUrl))
            finish(Activity.RESULT_OK, results)
            return
        }
        val database = "issue-regression.db"
        targetContext.deleteDatabase(database)
        val store = LocalStore(targetContext, database)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 3))
            repeat(12) { engine.consume(5000) }
            check(engine.status().getLong("freeQuotaSeconds") == 120L)
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 15))
            check(engine.status().getLong("freeQuotaSeconds") == 840L) { "#83: 免费时长增加后应保留已用的 1 分钟" }
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 15))
            check(engine.status().getLong("freeQuotaSeconds") == 840L) { "重复保存不能补发免费时长" }
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 2))
            check(engine.status().getLong("freeQuotaSeconds") == 60L)
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 0))
            check(engine.status().getLong("freeQuotaSeconds") == 0L)
            // 调到 0 再调回来，不能把已经用掉的 1 分钟重新发一遍
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 15))
            check(engine.status().getLong("freeQuotaSeconds") == 840L) { "从 0 调回 15 不能补发已用的 1 分钟" }
            engine.saveConfig(JSONObject().put("dailyFreeMinutes", 0))
            try {
                engine.saveConfig(JSONObject().put("dailyFreeMinutes", 121))
                error("超范围的免费时长不应保存")
            } catch (_: IllegalArgumentException) { }
            check(store.config().getInt("dailyFreeMinutes") == 0)

            engine.saveConfig(JSONObject().put("dailyMinutes", 1).put("dailyFreeMinutes", 5))
            check(engine.status().getString("reason") == "quota") { "免费时长不能绕过每日上限" }
            val before = engine.status().getDouble("usedMinutes")
            engine.consume(5000)
            check(engine.status().getDouble("usedMinutes") == before)
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("allDay", false)
                .put("weekSlots", JSONArray(List(7) { "0".repeat(48) })))
            check(engine.status().getString("reason") == "schedule") { "免费时长不能绕过可用时段" }
            engine.consume(5000)
            check(engine.status().getDouble("usedMinutes") == before)
            store.set("usage_day", "0")
            engine.status()
            check(engine.status().getLong("freeQuotaSeconds") == 300L) { "跨天应恢复配置的免费额度" }

            val single = JSONObject("""{"id":"s","q":"test","type":"single","a":["A","B","C","D"],"k":2}""")
            val multiple = JSONObject("""{"id":"m","q":"test","type":"multiple","a":["A","B","C","D"],"correctIndices":[0,3]}""")
            repeat(100) {
                val s = QuestionOptions.shuffled(single)
                check(s.getJSONArray("a").getString(s.getInt("k")) == "C")
                val m = QuestionOptions.shuffled(multiple)
                val indices = m.getJSONArray("correctIndices")
                check((0 until indices.length()).map { m.getJSONArray("a").getString(indices.getInt(it)) }.toSet() == setOf("A", "D"))
                check(s.getJSONArray("a").length() == 4 && m.getJSONArray("a").length() == 4)
            }
            check(single.getInt("k") == 2 && single.getJSONArray("a").getString(0) == "A")
            val bool = JSONObject("""{"type":"boolean","a":["正确","错误"],"k":0}""")
            check(QuestionOptions.shuffled(bool).toString() == bool.toString())
            store.upsertQuestion(single)
            store.upsertQuestion(multiple.put("enabled", false))
            store.recordProgress("s", 4)
            check(store.clearQuestions() == 2) { "#60: 清空必须包含已暂停题目" }
            check(store.questions(enabledOnly = false).length() == 0)
            check(store.repetitionStats().getInt("studied") == 0)
            check(store.clearQuestions() == 0)

            Foreground.clearExternal()
            Foreground.update("com.jamdeo.tv.vod", "com.ju.main.launcher.LauncherActivity")
            check(Foreground.currentClass("com.jamdeo.tv.vod") == "com.ju.main.launcher.LauncherActivity")
            Foreground.update("com.jamdeo.tv.vod", "com.jamdeo.tv.vod.loader.a.ActivityN1NRNTSLS4")
            check(Foreground.currentClass("com.jamdeo.tv.vod") == "com.jamdeo.tv.vod.loader.a.ActivityN1NRNTSLS4")
            Foreground.update("com.ktcp.video", "com.ktcp.video.HomeActivity")
            check(Foreground.currentClass("com.jamdeo.tv.vod") == null) { "不能把其他应用的类名配给桌面包" }
            Foreground.clearExternal()
            check(Foreground.currentClass("com.ktcp.video") == null)

            AiTtsRegression.run(targetContext)
            BugFixRegression.run(targetContext)
            FeatureRegression.run(targetContext)
            GatePresentationRegression.run(targetContext)
            VendorFixRegression.run(targetContext)
            TimePointsRegression.run(targetContext)
            results.putString("stream", "PASS: 新需求回归（加减时、开机额度、持久休息、年级、简单模式、解析停留、统计、多题库、密钥设置）及既有回归；免费额度即时调整、重复保存、上下限、时段、跨天；100 次单选/多选答案映射、判断题；清空含暂停题及复习进度；前台包名/Activity 配对与清理；#110 静音与弹窗同源；#17 休息死区与面板扣时同口径；#93 前台不返回自身包名与陈旧缓存；#92 悬浮窗真实 addView 探针；#106/#87/#81 AI 密钥不回显、朗读语言回落与失败回报；#112 网课时段分钟边界/跨午夜/多天、free 不扣时、counted 换上限、豁免优先、旧配置不回归与保存校验；#136 错题记录、错题优先排序与加练从错题库出、答对后出库；隐藏桌面图标（alias 开关、主界面不受影响）\n")
            finish(Activity.RESULT_OK, results)
        } catch (e: Throwable) {
            results.putString("stream", android.util.Log.getStackTraceString(e))
            finish(Activity.RESULT_CANCELED, results)
        } finally {
            store.close()
            targetContext.deleteDatabase(database)
        }
    }
}
