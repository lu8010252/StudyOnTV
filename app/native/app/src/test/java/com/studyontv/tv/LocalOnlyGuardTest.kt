package com.studyontv.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyGuardTest {

    @Test
    fun allowsLoopbackAndPrivateLanUrls() {
        assertTrue(LocalOnlyGuard.isLocalUrl("http://127.0.0.1:8099/api/state"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://localhost:11434/v1/chat/completions"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://192.168.1.50:11434/v1"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://10.0.0.8:8080/v1"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://172.16.5.2:8080/v1"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://172.31.255.1:8080/v1"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://ollama.local:11434/v1"))
        assertTrue(LocalOnlyGuard.isLocalUrl("http://nas.lan/banks.json"))
        assertTrue(LocalOnlyGuard.isLocalUrl("about:blank"))
        assertTrue(LocalOnlyGuard.isLocalUrl("data:image/png;base64,AAAA"))
    }

    @Test
    fun blocksPublicInternetUrls() {
        assertFalse(LocalOnlyGuard.isLocalUrl("https://api.deepseek.com/v1/chat/completions"))
        assertFalse(LocalOnlyGuard.isLocalUrl("https://www.pgyer.com/apiv1/update/check"))
        assertFalse(LocalOnlyGuard.isLocalUrl("https://sotv-logs-bj.oss-cn-beijing.aliyuncs.com/logs/a.zip"))
        assertFalse(LocalOnlyGuard.isLocalUrl("http://172.32.0.1/v1"))
        assertFalse(LocalOnlyGuard.isLocalUrl("http://8.8.8.8/test"))
    }
}
