package com.studyontv.tv

import android.app.Activity
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

class EducationalVideoActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var complete: Button
    private var resumed = false
    private val id get() = intent.getStringExtra("lessonId").orEmpty()
    private val tick = object : Runnable {
        override fun run() {
            try {
                val progress = DeviceRuntime.learning.videoProgress(id, video.isPlaying)
                val seconds = progress.optLong("remainingSeconds")
                status.text = if (seconds > 0) "公益视频学习 · 还需观看 $seconds 秒" else "观看完成，可以领取使用时间"
                complete.isEnabled = seconds == 0L
            } catch (e: Exception) { status.text = e.message; video.pause(); complete.isEnabled = false }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceRuntime.ensure(this)
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
            setOnPreparedListener { if (resumed) start() }
            setOnCompletionListener { if (resumed && !complete.isEnabled) start() }
            setOnErrorListener { _, _, _ -> status.text = "视频无法播放，请检查视频直链与网络，或请家长切回答题模式";true }
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
    override fun onResume() { super.onResume();resumed = true;handler.post(tick) }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        if (::video.isInitialized) video.pause()
        try { DeviceRuntime.learning.videoProgress(id, false) } catch (_: Exception) { }
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacks(tick);if (::video.isInitialized) video.stopPlayback();super.onDestroy() }
}
