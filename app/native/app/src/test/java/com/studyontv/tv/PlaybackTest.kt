package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {

    @Test
    fun doesNotFalseTriggerOnColdStartBeforeHysteresisWindow() {
        // 冷启动前 3 秒内无媒体播放且从未记录播放（lastActiveAt = -1L），必须返回 false
        assertFalse(Playback.isActiveWithHysteresis(playing = false, now = 1_500L, lastActiveAt = -1L))
        assertFalse(Playback.isActiveWithHysteresis(playing = false, now = 0L, lastActiveAt = -1L))
    }

    @Test
    fun respectsHysteresisWindowAfterPlaybackStops() {
        val playedAt = 10_000L
        assertTrue(Playback.isActiveWithHysteresis(playing = true, now = playedAt, lastActiveAt = playedAt))
        // 停止后 3 秒内保持滞回 true
        assertTrue(Playback.isActiveWithHysteresis(playing = false, now = playedAt + Playback.HYSTERESIS_MS - 1L, lastActiveAt = playedAt))
        // 达到/超过 3 秒后恢复 false
        assertFalse(Playback.isActiveWithHysteresis(playing = false, now = playedAt + Playback.HYSTERESIS_MS, lastActiveAt = playedAt))
    }
}
