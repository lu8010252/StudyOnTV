package com.studyontv.tv

import android.content.Context
import android.media.AudioManager
import android.net.TrafficStats
import android.os.SystemClock

/** 只在前台识别不明确时辅助判断；整机下载流量不能单独触发计时。 */
object PlaybackTraffic {
    private var sampledAt = -1L
    private var received = 0L
    private var recentVideoTraffic = false
    @Synchronized fun playing(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sampledAt < 0 || now - sampledAt >= 60000) {
            val bytes = TrafficStats.getTotalRxBytes()
            recentVideoTraffic = sampledAt >= 0 && bytes >= received && bytes - received >= 512 * 1024
            received = bytes.coerceAtLeast(0); sampledAt = now
        }
        val audio = try { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive } catch (_: Exception) { false }
        return recentVideoTraffic && audio
    }
}
