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
    /** 最近一次暂停时是否真的有音频在播：#122 用来判断“画面上是视频”，据此优先走全屏学习页 */
    @Volatile var lastPauseHadAudio: Boolean = false
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
            lastPauseHadAudio = audio.isMusicActive
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
                if (audible) {
                    // 遥控器音量键会先解除静音再调音量：记下用户此刻的音量，
                    // 解锁时以它为准，不能再用静音前的旧值覆盖（音量卡在 17，用户反馈）。
                    val p = prefs(context)
                    val nowVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (!p.getBoolean("media_user_touched", false) || p.getInt("media_user_volume", -1) != nowVolume) {
                        p.edit().putBoolean("media_user_touched", true).putInt("media_user_volume", nowVolume).apply()
                    }
                    setMuted(audio, true)
                }
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
                    .putBoolean("media_original_muted", originalMuted)
                    .putBoolean("media_user_touched", false)
                    .putInt("media_user_volume", 0)
                    .putInt("media_restore_attempts", 0).commit()) return
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
            val touched = state.getBoolean("media_user_touched", false)
            val userVolume = state.getInt("media_user_volume", 0)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            // 静音只是把流静掉，音量档位一般不动；只有读回 0（少数电视静音时把档位写成 0）
            // 才需要把原值写回去。用户自己动过音量时一律不写，否则会把孩子/家长刚调低的
            // 音量顶回静音前的旧值（用户反馈：音量卡在 17，调低又自动恢复）。
            val writeVolume = !originallyMuted && !touched && savedVolume > 0 && current <= 0
            if (writeVolume) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }
            if (!originallyMuted) {
                // 只解除我们自己的静音；本来静音的流不动，避免覆盖用户设置。
                setMuted(audio, false)
                // 部分电视（小米 / Amlogic 等）对 ADJUST_UNMUTE 的响应不稳定，
                // 额外调用一次 setStreamMute(false) 兜底。
                if (Build.VERSION.SDK_INT >= 23) try { audio.setStreamMute(AudioManager.STREAM_MUSIC, false) } catch (_: Exception) { }
                // 有的电视解除静音时会把档位弹回静音前的值（甚至归 0）：用户在静音期间
                // 调过音量时，只在「读数没回来」或「正好弹回旧值」这两种情况下放回用户的音量，
                // 不去覆盖静音结束后用户自己的调整。
                val nowVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (touched && userVolume > 0 && nowVolume != userVolume &&
                    (nowVolume <= 0 || nowVolume == savedVolume)) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        userVolume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                }
                // 新版 Android 会限制非前台应用改音量；如果这次没生效，保留记录，
                // 等 Activity 回到前台或下一次 tick 再试，不能把恢复机会一起清掉。
                // 但重试必须有上限：每秒重写一次会把用户刚调低的音量一直顶回原值。
                val restoredVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                // 有的电视解除静音要连按几次才生效：只要流还是静音状态，就继续重试，
                // 不能只看音量读数（读数正常但流仍静音时，解锁后会整机没声音）。
                val stillMuted = if (Build.VERSION.SDK_INT >= 23) try { audio.isStreamMute(AudioManager.STREAM_MUSIC) } catch (_: Exception) { false } else false
                if ((writeVolume && restoredVolume <= 0) || stillMuted) {
                    val tries = state.getInt("media_restore_attempts", 0) + 1
                    if (tries < 10) {
                        state.edit().putInt("media_restore_attempts", tries).apply()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRestoreWarnAt > 30000) {
                            lastRestoreWarnAt = now
                            Diag.warn("Audio", "restore deferred: volume=$restoredVolume stillMuted=$stillMuted (saved=$savedVolume try=$tries)")
                        }
                        return false
                    }
                    Diag.warn("Audio", "restore gave up after $tries tries (saved=$savedVolume volume=$restoredVolume stillMuted=$stillMuted)")
                }
            }
            state.edit().remove("media_muted").remove("media_original_volume").remove("media_original_muted")
                .remove("media_user_touched").remove("media_user_volume").remove("media_restore_attempts").commit()
            Diag.info("Audio", "restored volume=$savedVolume wasMuted=$wasMuted originallyMuted=$originallyMuted touched=$touched userVolume=$userVolume holding=$holding")
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
