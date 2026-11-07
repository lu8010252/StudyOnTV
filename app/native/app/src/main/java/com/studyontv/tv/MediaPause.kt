package com.studyontv.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

/** Pause cooperative players first; keep a reversible music-stream mute for players that ignore pause. */
object MediaPause {
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private val owners = mutableSetOf<Context>()
    private var holding = false
    private var muted = false
    private var attempts = 0
    private var lastKey = 0L
    private var originalVolume = 0
    private var originalMuted = false
    val active: Boolean get() = owners.isNotEmpty()

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
            attempts = 0
            lastKey = SystemClock.elapsedRealtime()
            owners.add(context)
            Diag.info("Audio", "pause start volume=$originalVolume muted=$originalMuted api=${Build.VERSION.SDK_INT} music=${audio.isMusicActive}")
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
            if ((!wasMuted || Build.VERSION.SDK_INT < 23) && state.contains("media_original_volume") && savedVolume > 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }
            // Before API 23 mute calls are counted per client: release our request once.
            // A previously quiet stream was read as volume zero, which remains quiet after release.
            if (Build.VERSION.SDK_INT < 23) setMuted(audio, false)
            else {
                setMuted(audio, wasMuted)
                // 部分电视（小米等）对 ADJUST_UNMUTE 的响应不稳定，额外调用一次 setStreamMute(false) 兜底。
                if (!wasMuted) try { audio.setStreamMute(AudioManager.STREAM_MUSIC, false) } catch (_: Exception) { }
            }
            state.edit().remove("media_muted").remove("media_original_volume").remove("media_original_muted").commit()
            Diag.info("Audio", "restored volume=$savedVolume wasMuted=$wasMuted holding=$holding")
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
        if (removed) Diag.info("Audio", "release owner, active=false muted=$muted")
        if (restore(context)) muted = false
        try { if (holding) audio(context)?.abandonAudioFocus(listener) }
        catch (e: Exception) { android.util.Log.w("StudyOnTV", "Release audio focus failed", e) }
        holding = false
        attempts = 0
    }
}
