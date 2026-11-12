package com.studyontv.tv

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 公益视频播放冒烟（只在测试设备上跑）：
 * `adb shell am instrument -w -e video 1 -e url http://10.0.2.2:8765/test.mp4 <runner>`
 *
 * 播放页用的是设备自己的 LearningEngine，所以这里必须用 DeviceRuntime 的真实存储，
 * 跑完把改动前的配置写回去。验证三件事：播放器有没有真的渲染出画面、播放期间学习时长有没有涨、
 * 播放页有没有因为任务过期/播放失败直接退出。会临时清掉远程锁定和进行中的一轮，只在测试设备上跑。
 */
object VideoProbe {
    fun run(context: Context, url: String): String = StringBuilder().also { out ->
        DeviceRuntime.ensure(context)
        val store = DeviceRuntime.store
        val engine = DeviceRuntime.learning
        val original = JSONObject(store.config().toString())
        try {
            engine.saveConfig(JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
                .put("questionsPerRound", 3).put("extraEnabled", false).put("allDay", true).put("roundsPerDay", 100)
                .put("roundsLimitEnabled", false).put("passRatio", 0.6).put("wrongReviewSeconds", 0)
                .put("restEnabled", false).put("dailyLimitEnabled", true).put("playMode", "round")
                .put("scheduleMode", "allow").put("childSelectLevel", false)
                .put("learningContent", "video").put("videoMinutes", 1)
                .put("educationalVideos", JSONArray().put(url)))
            store.set("video_lesson", "")
            store.set("active_round", "")
            store.set("remote_override", "")
            // 屏幕灭着时引擎会把状态判成 locked，播放页会拒绝开始学习
            engine.awake(true)
            val started = engine.start()
            val lesson = started.optJSONObject("videoLesson")
            if (lesson == null) {
                out.append("FAIL 没有生成视频任务: ${started.toString().take(300)}\n")
                return@also
            }
            val id = lesson.getString("id")
            out.append("lesson id=$id remaining=${lesson.getLong("remainingSeconds")}\n")
            context.startActivity(Intent(context, EducationalVideoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("lessonId", id).putExtra("url", url))
            Thread.sleep(12_000)
            val after = engine.videoProgress(id, false)
            out.append(if (after.optBoolean("expired")) "FAIL 任务意外过期\n"
            else "watched: remaining=${after.getLong("remainingSeconds")}（应明显小于 60）\n")
            val array = JSONArray(Diag.read(context, 80).toString())
            val video = (0 until array.length()).map { array.getString(it) }.filter { it.contains("/Video") }
            if (video.isEmpty()) out.append("FAIL 日志里没有 Video 记录（没有渲染，也没有报错）\n")
            else video.forEach { out.append(it.trim()).append('\n') }
        } finally {
            store.set("video_lesson", "")
            try { engine.saveConfig(original) } catch (e: Exception) { out.append("恢复配置失败: ${e.message}\n") }
        }
    }.toString()
}
