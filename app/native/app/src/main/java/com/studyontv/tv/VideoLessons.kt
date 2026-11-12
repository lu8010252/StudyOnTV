package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONArray
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
        val requiredMs = config.optLong("videoMinutes", 1).coerceIn(1, 30) * 60000L
        val urls = config.optJSONArray("educationalVideos") ?: JSONArray()
        require(urls.length() > 0) { "请先由家长添加公益视频地址" }
        val existing = store.get("video_lesson")
        if (existing.isNotBlank()) {
            val lesson = try { JSONObject(existing) } catch (_: Exception) { null }
            // 只有「同一天 + 地址还在家长填写的列表里」才复用旧任务。
            // 家长改「每次视频学习分钟」必须立刻生效：以前这里直接返回旧任务，改时长等于没改；
            // 已经看过的部分按新时长截断后保留，不让改时长把进度清零、也不让人白看。
            if (lesson != null && lesson.optLong("day") == store.startOfToday() && inList(lesson.optString("url"), urls)) {
                lesson.put("requiredMs", requiredMs).put("watchedMs", lesson.optLong("watchedMs").coerceIn(0, requiredMs))
                    .put("playing", false).put("tick", SystemClock.elapsedRealtime())
                store.set("video_lesson", lesson.toString())
                return public(lesson)
            }
            store.set("video_lesson", "")
        }
        val index = store.get("video_sequence", "0").toIntOrNull() ?: 0
        val lesson = JSONObject().put("id", UUID.randomUUID().toString()).put("url", urls.getString(index % urls.length()))
            .put("requiredMs", requiredMs).put("watchedMs", 0)
            .put("playing", false).put("tick", SystemClock.elapsedRealtime()).put("day", store.startOfToday())
        store.set("video_lesson", lesson.toString())
        return public(lesson)
    }
    private fun inList(url: String, urls: JSONArray): Boolean = (0 until urls.length()).any { urls.optString(it) == url }
    fun progress(id: String, playing: Boolean): JSONObject {
        val raw = store.get("video_lesson")
        val lesson = if (raw.isBlank()) null else try { JSONObject(raw) } catch (_: Exception) { null }
        // 任务被清掉 / 跨天 / 已经换成新任务：返回过期标记让播放页自己退出，
        // 不要每秒抛一次异常——那会把孩子卡在「还需观看 N 秒」的黑屏播放页上出不来。
        if (lesson == null || lesson.optString("id") != id || lesson.optLong("day") != store.startOfToday()) return expired()
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lesson.optLong("tick")).coerceIn(0, 2500)
        if (lesson.optBoolean("playing")) lesson.put("watchedMs", minOf(lesson.optLong("requiredMs"), lesson.optLong("watchedMs") + elapsed))
        lesson.put("playing", playing).put("tick", now)
        store.set("video_lesson", lesson.toString())
        return public(lesson)
    }
    fun complete(id: String) {
        val result = progress(id, false)
        require(!result.optBoolean("expired")) { "视频任务已更新，请重新开始" }
        require(result.optLong("remainingSeconds") == 0L) { "请先完成视频学习时长" }
        store.set("video_lesson", "")
        store.set("video_sequence", ((store.get("video_sequence", "0").toIntOrNull() ?: 0) + 1).toString())
    }
    private fun public(lesson: JSONObject): JSONObject = JSONObject().put("id", lesson.getString("id")).put("url", lesson.getString("url"))
        .put("requiredSeconds", (lesson.optLong("requiredMs") + 999) / 1000)
        .put("remainingSeconds", (lesson.optLong("requiredMs") - lesson.optLong("watchedMs") + 999) / 1000)
    /** 过期任务：不带 remainingSeconds，避免被当成「已完成」而发放奖励 */
    private fun expired(): JSONObject = JSONObject().put("expired", true)
}
