package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BankAutomationAndMarketTest {

    @Test
    fun bankMarketAreAllPrivateRejectsEmptyAndMixedPublicRecords() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val lan = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        val pub = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

        assertFalse(BankMarket.areAllPrivate(emptyArray()))
        assertTrue(BankMarket.areAllPrivate(arrayOf(loopback)))
        assertTrue(BankMarket.areAllPrivate(arrayOf(loopback, lan)))
        assertFalse(BankMarket.areAllPrivate(arrayOf(loopback, pub)))
        assertFalse(BankMarket.areAllPrivate(arrayOf(pub)))
    }

    @Test
    fun updaterEffectiveCachedInfoSuppressesStaleCacheInLocalOnlyMode() {
        val stale = Updater.Info(
            latest = "9.9.9",
            current = "0.5.3",
            force = false,
            notes = "notes",
            url = "https://www.pgyer.com/app/installUpdate/abc",
            source = "蒲公英"
        )
        assertNull(Updater.effectiveCachedInfo(stale, localOnly = true))
        assertEquals(stale, Updater.effectiveCachedInfo(stale, localOnly = false))
    }

    @Test
    fun bankAutomationFormatFailureMessagePreservesRootCauseDetail() {
        val withDetail = BankAutomation.formatFailureMessage(
            "同步失败",
            "本地题库已保留",
            IllegalArgumentException("远程题库存在重复 ID 或题干")
        )
        assertTrue(withDetail.contains("远程题库存在重复 ID 或题干"))
        assertTrue(withDetail.contains("本地题库已保留"))

        val withoutDetail = BankAutomation.formatFailureMessage(
            "自动补题失败",
            "已有题目保留",
            RuntimeException()
        )
        assertEquals("自动补题失败，已有题目保留", withoutDetail)
    }
}
