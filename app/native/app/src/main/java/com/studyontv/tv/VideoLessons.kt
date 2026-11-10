package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** 只累计播放器实际报告播放期间的单调时间；跳转播放位置不能直接兑换奖励。 */
class VideoLessons(private val store: LocalStore) {
    companion object {
        fun open(context: android.content.Context) {
            try {
                val lesson = DeviceRuntime.learning.start().optJSONObject("videoLesson") ?: return
                context.startActivity(android.content.Intent(context, EducationalVideoActivity::class.java)
                    .putExtra("lessonId", lesson.getString("id")).putExtra("url", lesson.getString("url"))
                    .apply { if (context !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e: Exception) { android.widget.Toast.makeText(context, e.message ?: "无法打开视频", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    fun wanted(config: JSONObject): Boolean = config.optString("learningContent", "quiz") == "video" ||
        (config.optString("learningContent") == "alternate" && store.roundsDoneToday() % 2 == 0)
    fun start(config: JSONObject): JSONObject {
        val existing = store.get("video_lesson")
        if (existing.isNotBlank()) {
            val lesson = JSONObject(existing).put("playing", false).put("tick", SystemClock.elapsedRealtime())
            store.set("video_lesson", lesson.toString())
            return public(lesson)
        }
        val urls = config.getJSONArray("educationalVideos")
        require(urls.length() > 0) { "请先由家长添加公益视频地址" }
        val index = store.get("video_sequence", "0").toIntOrNull() ?: 0
        val lesson = JSONObject().put("id", UUID.randomUUID().toString()).put("url", urls.getString(index % urls.length()))
            .put("requiredMs", config.optLong("videoMinutes", 1) * 60000).put("watchedMs", 0)
            .put("playing", false).put("tick", SystemClock.elapsedRealtime()).put("day", store.startOfToday())
        store.set("video_lesson", lesson.toString())
        return public(lesson)
    }
    fun progress(id: String, playing: Boolean): JSONObject {
        val raw = store.get("video_lesson")
        require(raw.isNotBlank()) { "视频任务已结束，请重新开始" }
        val lesson = JSONObject(raw)
        require(lesson.optString("id") == id && lesson.optLong("day") == store.startOfToday()) { "视频任务已更新" }
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lesson.optLong("tick")).coerceIn(0, 2500)
        if (lesson.optBoolean("playing")) lesson.put("watchedMs", minOf(lesson.optLong("requiredMs"), lesson.optLong("watchedMs") + elapsed))
        lesson.put("playing", playing).put("tick", now)
        store.set("video_lesson", lesson.toString())
        return public(lesson)
    }
    fun complete(id: String) {
        val result = progress(id, false)
        require(result.optLong("remainingSeconds") == 0L) { "请先完成视频学习时长" }
        store.set("video_lesson", "")
        store.set("video_sequence", ((store.get("video_sequence", "0").toIntOrNull() ?: 0) + 1).toString())
    }
    private fun public(lesson: JSONObject): JSONObject = JSONObject().put("id", lesson.getString("id")).put("url", lesson.getString("url"))
        .put("remainingSeconds", (lesson.optLong("requiredMs") - lesson.optLong("watchedMs") + 999) / 1000)
}
