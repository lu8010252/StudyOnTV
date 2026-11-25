package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoreBackupTest {

    @Test
    fun isEphemeralKvKeyMatchesDefaultAndChildPrefixedKeys() {
        assertTrue(LocalStore.isEphemeralKvKey("active_round"))
        assertTrue(LocalStore.isEphemeralKvKey("remote_override"))
        assertTrue(LocalStore.isEphemeralKvKey("rest_deadline"))
        assertTrue(LocalStore.isEphemeralKvKey("boot_quota_ms"))
        assertTrue(LocalStore.isEphemeralKvKey("child:kid2:active_round"))
        assertTrue(LocalStore.isEphemeralKvKey("child:kid2:feedback_deadline"))
        assertTrue(LocalStore.isEphemeralKvKey("child:kid2:video_lesson"))

        assertFalse(LocalStore.isEphemeralKvKey("config"))
        assertFalse(LocalStore.isEphemeralKvKey("child:kid2:config"))
        assertFalse(LocalStore.isEphemeralKvKey("bank_market_sources"))
        assertFalse(LocalStore.isEphemeralKvKey("extra_pool"))
    }

    @Test
    fun isValidChildIdRejectsColonAndOverlongIds() {
        assertTrue(LocalStore.isValidChildId(LocalStore.DEFAULT_CHILD))
        assertTrue(LocalStore.isValidChildId("kid_1-A"))
        assertFalse(LocalStore.isValidChildId("bad:id"))
        assertFalse(LocalStore.isValidChildId("has space"))
        assertFalse(LocalStore.isValidChildId("a".repeat(33)))
    }

    @Test
    fun localOnlyBackupSanitizationStripsPublicUrlsAndKeepsLocalUrls() {
        val cfgJson = JSONObject()
            .put("learningContent", "video")
            .put(
                "educationalVideos",
                JSONArray(listOf("https://public.example.com/a.mp4", "http://192.168.1.5:8080/local.mp4"))
            ).toString()
        val sanitizedCfg = JSONObject(LocalStore.sanitizeKvValueForLocalOnly("child:c1:config", cfgJson, localOnly = true))
        assertEquals(1, sanitizedCfg.getJSONArray("educationalVideos").length())
        assertEquals("http://192.168.1.5:8080/local.mp4", sanitizedCfg.getJSONArray("educationalVideos").getString(0))
        assertEquals("video", sanitizedCfg.getString("learningContent"))

        // If all videos were public, learningContent falls back to "quiz"
        val publicOnlyCfg = JSONObject()
            .put("learningContent", "video")
            .put("educationalVideos", JSONArray(listOf("https://cdn.example.com/v.mp4"))).toString()
        val fallbackCfg = JSONObject(LocalStore.sanitizeKvValueForLocalOnly("config", publicOnlyCfg, localOnly = true))
        assertEquals(0, fallbackCfg.getJSONArray("educationalVideos").length())
        assertEquals("quiz", fallbackCfg.getString("learningContent"))

        // bank_market_sources strips public sources
        val marketSources = JSONArray()
            .put(JSONObject().put("name", "pub").put("url", "https://cdn.jsdelivr.net/gh/x/y/index.json"))
            .put(JSONObject().put("name", "lan").put("url", "http://192.168.1.8:8000/index.json"))
            .toString()
        val sanitizedMarket = JSONArray(LocalStore.sanitizeKvValueForLocalOnly("bank_market_sources", marketSources, localOnly = true))
        assertEquals(1, sanitizedMarket.length())
        assertEquals("lan", sanitizedMarket.getJSONObject(0).getString("name"))

        // bankAutomation strips public sources and volatile keys
        val ba = JSONObject()
            .put("running", true)
            .put("status", JSONObject().put("ok", true))
            .put(
                "sources",
                JSONArray()
                    .put(JSONObject().put("name", "pub").put("url", "https://example.com/bank.json"))
                    .put(JSONObject().put("name", "local").put("url", "http://10.0.0.5:8090/bank.json"))
            )
        val sanitizedBa = LocalStore.sanitizeBankAutomationForLocalOnly(ba, localOnly = true)
        assertFalse(sanitizedBa.has("running"))
        assertFalse(sanitizedBa.has("status"))
        assertEquals(1, sanitizedBa.getJSONArray("sources").length())
        assertEquals("local", sanitizedBa.getJSONArray("sources").getJSONObject(0).getString("name"))

        // kv["bank_automation"] path (used during merge mode before top-level bankAutomation block) must also sanitize
        val sanitizedKvBa = JSONObject(LocalStore.sanitizeKvValueForLocalOnly("bank_automation", ba.toString(), localOnly = true))
        assertFalse(sanitizedKvBa.has("running"))
        assertFalse(sanitizedKvBa.has("status"))
        assertEquals(1, sanitizedKvBa.getJSONArray("sources").length())
        assertEquals("local", sanitizedKvBa.getJSONArray("sources").getJSONObject(0).getString("name"))
    }
}
