package com.studyontv.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

/** Pause cooperative players first; keep a reversible music-stream mute for players that ignore pause. */
object MediaPause {
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    // 主线程之外（无障碍服务、家长面板）也可能 release，用线程安全的集合避免持有者被漏掉/重复添加。
    private val owners = java.util.concurrent.CopyOnWriteArraySet<Context>()
    private var holding = false
    private var muted = false
    private var attempts = 0
    private var lastKey = 0L
    private var originalVolume = 0
    private var originalMuted = false
    private var lastRestoreWarnAt = 0L
    // 只有读到的媒体流「有音量且未静音」时才允许改全局静音。
    // HDMI 固定音量 / 部分 Amlogic 盒子会读到 volume=0（甚至 isStreamMute=true）但实际有声音，
    // 在这种设备上 mute 可能把整机声音锁死，解锁后也不再恢复（issue #116 天猫魔盒 M16S）。
    private var canMute = false
    val active: Boolean get() = owners.isNotEmpty()
    /** 静音持有者数量；诊断用（>1 时说明有多个 Context 各自持有） */
    val ownerCount: Int get() = owners.size
    /** 音乐流是否已经被我们静音（enforce 落库后的状态） */
    val levelMuted: Boolean get() = muted
    /** 最近一次开始静音的时间（elapsedRealtime），用于诊断「静音了多久」 */
    @Volatile var lastPauseAt: Long = 0L
        private set
    @Volatile var lastReleaseAt: Long = 0L
        private set

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private fun prefs(context: Context) = context.getSharedPreferences("sotv", Context.MODE_PRIVATE)
    private fun sendPause(audio: AudioManager) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        lastKey = SystemClock.elapsedRealtime()
    }

    @Suppress("DEPRECATION")
    private fun setMuted(audio: AudioManager, value: Boolean) {
        if (Build.VERSION.SDK_INT >= 23) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (value) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        else audio.setStreamMute(AudioManager.STREAM_MUSIC, value)
    }

    @Suppress("DEPRECATION")
    fun pause(context: Context) {
        if (owners.contains(context)) return
        val audio = audio(context) ?: return
        if (owners.isNotEmpty()) { owners.add(context); Diag.info("Audio", "add owner (${owners.size}), already active"); return }
        recover(context)
        try {
            originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            originalMuted = if (Build.VERSION.SDK_INT >= 23) audio.isStreamMute(AudioManager.STREAM_MUSIC) else originalVolume == 0
            canMute = originalVolume > 0 && !originalMuted
            attempts = 0
            lastKey = SystemClock.elapsedRealtime()
            lastPauseAt = lastKey
            owners.add(context)
            Diag.info("Audio", "pause start volume=$originalVolume muted=$originalMuted canMute=$canMute api=${Build.VERSION.SDK_INT} music=${audio.isMusicActive} owners=${owners.size}")
            if (audio.isMusicActive) { sendPause(audio); attempts = 1 }
            holding = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Pause media failed", e) }
    }

    fun enforce(context: Context) {
        if (!active) return
        val audio = audio(context) ?: return
        try {
            if (!audio.isMusicActive) return
            if (muted) {
                // A volume key can undo stream mute while the quiz is still covering the player.
                val audible = if (Build.VERSION.SDK_INT >= 23) !audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
                if (audible) setMuted(audio, true)
                return
            }
            if (SystemClock.elapsedRealtime() - lastKey < 3000) return
            if (!canMute) {
                // 音量读数为 0：可能本来就没声音，也可能是固定音量设备。继续尝试暂停，
                // 但绝不写全局静音，避免把整机音频锁死。
                if (attempts < 2) { sendPause(audio); attempts++ }
                return
            }
            if (attempts < 2) { sendPause(audio); attempts++; return }
            // Commit the recovery record before changing global audio state.
            if (!prefs(context).edit().putBoolean("media_muted", true)
                    .putInt("media_original_volume", originalVolume)
                    .putBoolean("media_original_muted", originalMuted).commit()) return
            muted = true
            setMuted(audio, true)
            Diag.warn("Audio", "enforce: player ignored pause, muted music stream (original volume=$originalVolume, originally muted=$originalMuted)")
        } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Enforce pause failed", e) }
    }

    @Suppress("DEPRECATION")
    private fun restore(context: Context): Boolean {
        val state = prefs(context)
        if (!state.getBoolean("media_muted", false)) return true
        val audio = audio(context) ?: return false
        return try {
            val wasMuted = state.getBoolean("media_original_muted", false)
            // 电视 / HDMI 设备可能使用固定音量，系统读到的媒体音量是 0 但实际有声音。
            // 这种情况下不要把音量写回 0，只解除我们自己的静音即可，否则解锁后会一直无声。
            val savedVolume = state.getInt("media_original_volume", 0)
            // 原始音量为 0 + 原始状态静音，才视为「用户本来就静音」；读到正音量却标记静音
            // 通常是设备误报，必须恢复，否则一次弹题后整机再无声音。
            val originallyMuted = wasMuted && savedVolume <= 0
            if (!originallyMuted && state.contains("media_original_volume") && savedVolume > 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }
            if (!originallyMuted) {
                // 只解除我们自己的静音；本来静音的流不动，避免覆盖用户设置。
                setMuted(audio, false)
                // 部分电视（小米 / Amlogic 等）对 ADJUST_UNMUTE 的响应不稳定，
                // 额外调用一次 setStreamMute(false) 兜底。
                if (Build.VERSION.SDK_INT >= 23) try { audio.setStreamMute(AudioManager.STREAM_MUSIC, false) } catch (_: Exception) { }
                // 新版 Android 会限制非前台应用改音量；如果这次没生效，保留记录，
                // 等 Activity 回到前台或下一次 tick 再试，不能把恢复机会一起清掉。
                val restoredVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (savedVolume > 0 && restoredVolume <= 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRestoreWarnAt > 30000) {
                        lastRestoreWarnAt = now
                        Diag.warn("Audio", "restore deferred: volume still 0 (saved=$savedVolume)")
                    }
                    return false
                }
            }
            state.edit().remove("media_muted").remove("media_original_volume").remove("media_original_muted").commit()
            Diag.info("Audio", "restored volume=$savedVolume wasMuted=$wasMuted originallyMuted=$originallyMuted holding=$holding")
            true
        } catch (e: Exception) {
            android.util.Log.w("StudyOnTV", "Restore media volume failed", e)
            Diag.error("Audio", "restore failed", e)
            false
        }
    }

    /** Also used on startup after process death; never undo another currently visible gate's mute. */
    fun recover(context: Context) { if (!active) restore(context) }

    fun releaseAll(context: Context) {
        owners.clear()
        release(context)
    }

    @Suppress("DEPRECATION")
    fun release(context: Context) {
        val removed = owners.remove(context)
        if (active) return
        lastReleaseAt = SystemClock.elapsedRealtime()
        if (removed) Diag.info("Audio", "release owner, active=false muted=$muted")
        if (restore(context)) muted = false
        try { if (holding) audio(context)?.abandonAudioFocus(listener) }
        catch (e: Exception) { android.util.Log.w("StudyOnTV", "Release audio focus failed", e) }
        holding = false
        attempts = 0
    }
}
