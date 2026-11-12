package com.studyontv.tv

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/**
 * 公益视频学习页。
 *
 * 播放失败不能把人锁死：MP4 有声音没画面、编码不被电视支持、地址其实是个网页时，
 * `VideoView.isPlaying` 仍可能是 true，学习时长也永远不涨，界面就停在「还需观看 N 秒」
 * 上无限重播同一条视频（用户反馈）。所以这里额外看两件事：
 *  1. 播放器有没有真的把第一帧渲染出来（MEDIA_INFO_VIDEO_RENDERING_START）；
 *  2. 任务是不是还在（跨天 / 家长重设后旧任务会过期）。
 * 任何一条不成立都明确报错并停下，而不是继续循环。
 */
class EducationalVideoActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var complete: Button
    private var resumed = false
    /** 第一帧是否已经渲染出来（黑屏检测） */
    private var renderStarted = false
    private var preparedAt = 0L
    private var openedAt = 0L
    /** 出错后保留原因：tick 每秒会重写状态栏，不能让错误提示被覆盖掉 */
    private var failure = ""
    private val id get() = intent.getStringExtra("lessonId").orEmpty()
    private val tick = object : Runnable {
        override fun run() {
            if (tickOnce()) handler.postDelayed(this, 1000)
        }
    }
    private fun tickOnce(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!checkPlayback(now)) return false
        try {
            val progress = DeviceRuntime.learning.videoProgress(id, video.isPlaying)
            if (progress.optBoolean("expired")) {
                // 任务跨天或已被重新生成：退出播放页回到学习页，别让孩子卡在一个不会再涨的任务上
                status.text = "观看任务已更新，请重新开始"
                Diag.info("Video", "lesson expired, leaving player id=$id")
                finish()
                return false
            }
            val seconds = progress.optLong("remainingSeconds")
            status.text = if (seconds > 0) "公益视频学习 · 还需观看 $seconds 秒" else "观看完成，可以领取使用时间"
            complete.isEnabled = seconds == 0L
        } catch (e: Exception) { fail(e.message ?: "视频任务出错") ; return false }
        return true
    }
    /** true = 还在正常播；false = 已经报错并停住（不要继续循环） */
    private fun checkPlayback(now: Long): Boolean {
        if (failure.isNotEmpty()) return false
        val playing = ::video.isInitialized && video.isPlaying
        val message = when {
            preparedAt > 0 && !renderStarted && playing && now - preparedAt > RENDER_TIMEOUT_MS ->
                "视频有声音但没有画面：这台电视可能不支持这个编码，请换一个 H.264 编码的 MP4 直链，或请家长切换为答题模式"
            preparedAt > 0 && !renderStarted && now - preparedAt > LOAD_TIMEOUT_MS ->
                "视频一直没有开始播放，请确认地址是可以直接下载的 mp4 文件，且电视能访问它"
            preparedAt == 0L && now - openedAt > LOAD_TIMEOUT_MS ->
                "视频加载超时，请检查公益视频直链（需要 mp4 等媒体文件，不是网页地址）与网络"
            else -> ""
        }
        if (message.isEmpty()) return true
        fail(message)
        return false
    }
    private fun fail(message: String) {
        failure = message
        status.text = message
        complete.isEnabled = false
        try { if (::video.isInitialized) video.pause() } catch (_: Exception) { }
        Diag.warn("Video", "playback failed: $message url=${intent.getStringExtra("url")}")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceRuntime.ensure(this)
        openedAt = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setPadding(20,20,20,20);setBackgroundColor(TvUi.background(this@EducationalVideoActivity)) }
        status = TvUi.text(this, "加载公益视频…", 20f)
        root.addView(status)
        video = VideoView(this).apply {
            // 公益视频是普通媒体内容，用 USAGE_MEDIA 才能走电视的主音频输出；
            // 部分 Amlogic 盒子（如天猫魔盒）没有独立的无障碍音频路由，用
            // USAGE_ASSISTANCE_ACCESSIBILITY 会表现为完全无声（issue #116）。
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            setMediaController(MediaController(this@EducationalVideoActivity))
            setOnPreparedListener { preparedAt = SystemClock.elapsedRealtime();renderStarted = false;if (resumed) start() }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !renderStarted) {
                    renderStarted = true
                    Diag.info("Video", "render start duration=${video.duration}ms url=${intent.getStringExtra("url")}")
                }
                true
            }
            setOnCompletionListener {
                when {
                    // 画面正常、时长不够：继续播下一条，这是「看满 N 分钟」的应有行为
                    resumed && renderStarted && failure.isEmpty() && !complete.isEnabled -> start()
                    failure.isEmpty() && !renderStarted -> fail("视频没有画面，已停止重播；请换一个 H.264 编码的 MP4 直链，或请家长切换为答题模式")
                }
            }
            setOnErrorListener { _, what, extra ->
                fail("视频无法播放（错误码 $what/$extra）：请检查公益视频直链能否直接播放，或请家长切换为答题模式")
                true
            }
            setVideoPath(intent.getStringExtra("url"))
        }
        root.addView(video, LinearLayout.LayoutParams(-1,0,1f))
        val buttons = LinearLayout(this).apply { gravity=Gravity.CENTER }
        complete = TvUi.button(this, "完成学习，领取使用时间", true) {
            try { DeviceRuntime.learning.completeVideo(id);GateService.refreshNow();finish() }
            catch (e: Exception) { status.text = e.message }
        }.apply { isEnabled=false }
        buttons.addView(complete)
        buttons.addView(TvUi.button(this, "返回") { finish() })
        root.addView(buttons)
        setContentView(root)
    }
    override fun onResume() { super.onResume();resumed = true;handler.removeCallbacks(tick);handler.post(tick) }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        if (::video.isInitialized) video.pause()
        try { DeviceRuntime.learning.videoProgress(id, false) } catch (_: Exception) { }
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacks(tick);if (::video.isInitialized) video.stopPlayback();super.onDestroy() }

    private companion object {
        /**
         * 有声音没画面：再等也等不到第一帧。
         * 依赖 `MEDIA_INFO_VIDEO_RENDERING_START`（AOSP MediaPlayer 在第一帧上屏时发出）；
         * 万一某台电视不发这个回调，只会表现为「看到 20 秒就提示、不再自动重播」，不会锁死。
         */
        const val RENDER_TIMEOUT_MS = 20_000L
        /** 连 prepare 都没完成 / 一直没开始播 */
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
