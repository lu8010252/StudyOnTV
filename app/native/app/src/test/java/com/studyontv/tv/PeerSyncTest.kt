package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeerSyncTest {

    @Test
    fun isPrivateLanHostAllowsOnlyRfc1918Ipv4AndValidPorts() {
        val valid = listOf(
            "10.0.0.1",
            "10.255.255.254:8099",
            "172.16.0.1:1024",
            "172.31.255.254:65535",
            "192.168.0.1",
            "192.168.1.20:8099",
            " 192.168.50.8:8090 "
        )
        for (host in valid) {
            assertTrue("Expected valid LAN host: $host", PeerDiscovery.isPrivateLanHost(host))
        }

        val parsed = PeerDiscovery.parsePrivateLanHost("192.168.1.20")
        assertNotNull(parsed)
        assertEquals("192.168.1.20", parsed!!.ip)
        assertEquals(8099, parsed.port)
        assertEquals("192.168.1.20:8099", parsed.host)

        val rejected = listOf(
            "",
            "   ",
            // Public IPs
            "8.8.8.8",
            "8.8.8.8:8099",
            "1.1.1.1:8099",
            "11.0.0.1:8099",
            "172.15.255.255:8099",
            "172.32.0.1:8099",
            "192.169.1.1:8099",
            // Loopback & link-local / cloud metadata
            "127.0.0.1",
            "127.0.0.1:8099",
            "127.1.2.3:8099",
            "169.254.169.254",
            "169.254.169.254:8099",
            "0.0.0.0:8099",
            // Privileged / out-of-range / malformed ports
            "192.168.1.20:0",
            "192.168.1.20:80",
            "192.168.1.20:443",
            "192.168.1.20:1023",
            "192.168.1.20:65536",
            "192.168.1.20:08099",
            "192.168.1.20:-1",
            "192.168.1.20:abc",
            "192.168.1.20:",
            // Leading-zero octal tricks & invalid octets
            "010.0.0.1:8099",
            "192.168.01.20:8099",
            "192.168.1.256:8099",
            // Hostnames, IPv6, schemes, paths, userinfo, query/fragment injection
            "localhost:8099",
            "tv.local:8099",
            "example.com:8099",
            "[::1]:8099",
            "[fd00::1]:8099",
            "http://192.168.1.20:8099",
            "192.168.1.20:8099/api/hello",
            "192.168.1.20:8099?x=1",
            "192.168.1.20:8099#frag",
            "user@192.168.1.20:8099",
            "192.168.1.20:8099:8099"
        )
        for (bad in rejected) {
            assertFalse("Expected SSRF/invalid host rejected: $bad", PeerDiscovery.isPrivateLanHost(bad))
        }
    }

    @Test
    fun udpPacketCodecAndRegistryDedupSelfFilterAndExpiry() {
        val discover = PeerDiscovery.encodeDiscoverPacket()
        assertTrue(discover.size <= PeerDiscovery.MAX_PACKET_BYTES)
        assertTrue(PeerDiscovery.isDiscoverPacket(discover))
        assertFalse(PeerDiscovery.isDiscoverPacket("{\"magic\":\"OTHER\"}".toByteArray()))

        val encoded = PeerDiscovery.encodePeerPacket(
            id = "dev-living-1",
            name = "客厅电视",
            port = 8099,
            version = "0.5.6",
            isTv = true,
            questions = 128
        )
        assertTrue(encoded.size <= PeerDiscovery.MAX_PACKET_BYTES)
        assertFalse(PeerDiscovery.isDiscoverPacket(encoded))

        // Public or loopback sender IPs must be rejected even if packet payload is valid
        assertNull(PeerDiscovery.parsePeerPacket(encoded, encoded.size, "8.8.8.8", nowMs = 1_000L))
        assertNull(PeerDiscovery.parsePeerPacket(encoded, encoded.size, "127.0.0.1", nowMs = 1_000L))
        assertNull(PeerDiscovery.parsePeerPacket(encoded, encoded.size, "169.254.1.2", nowMs = 1_000L))

        val parsed = PeerDiscovery.parsePeerPacket(encoded, encoded.size, "192.168.1.20", nowMs = 1_000L)
        assertNotNull(parsed)
        assertEquals("dev-living-1", parsed!!.id)
        assertEquals("客厅电视", parsed.name)
        assertEquals("192.168.1.20:8099", parsed.host)
        assertEquals("0.5.6", parsed.version)
        assertTrue(parsed.isTv)
        assertEquals(128, parsed.questions)

        // Registry: filter self by id or ip:port, dedup by id, expire after TTL
        val registry = PeerDiscovery.PeerRegistry(ttlMs = 10_000L)
        assertFalse(registry.record(parsed, selfId = "dev-living-1", selfIp = "192.168.1.10", selfPort = 8099, nowMs = 1_000L))
        assertFalse(registry.record(parsed, selfId = "other-self", selfIp = "192.168.1.20", selfPort = 8099, nowMs = 1_000L))
        assertTrue(registry.record(parsed, selfId = "other-self", selfIp = "192.168.1.10", selfPort = 8099, nowMs = 1_000L))

        // Updating same id refreshes instead of duplicating
        val updated = parsed.copy(name = "客厅大电视", questions = 200, lastSeenAt = 5_000L)
        assertTrue(registry.record(updated, selfId = "other-self", selfIp = "192.168.1.10", selfPort = 8099, nowMs = 5_000L))
        val listAt6s = registry.list(nowMs = 6_000L)
        assertEquals(1, listAt6s.size)
        assertEquals("客厅大电视", listAt6s[0].name)
        assertEquals(200, listAt6s[0].questions)

        // Expired after 5_000 + 10_000 ms
        assertTrue(registry.list(nowMs = 16_000L).isEmpty())
    }

    @Test
    fun scopedSyncPayloadTrimsToSelectedScopesAndExcludesRuntimeState() {
        val fullBackup = JSONObject()
            .put("format", "studyontv-backup")
            .put("version", 1)
            .put("exportedAt", 1700000000000L)
            .put("appVersion", "0.5.6")
            .put("deviceName", "主控客厅电视")
            .put("activeChild", "kid2")
            .put("children", JSONArray().put(JSONObject().put("id", "").put("name", "大宝")).put(JSONObject().put("id", "kid2").put("name", "二宝")))
            .put(
                "kv",
                JSONObject()
                    .put("config", "{\"watchMinutes\":20}")
                    .put("child:kid2:config", "{\"watchMinutes\":15}")
                    // Runtime / ephemeral / secret keys that MUST NEVER be synced
                    .put("used_ms", "900000")
                    .put("free_used_ms", "300000")
                    .put("credit_ms", "600000")
                    .put("usage_day", "2026-11-24")
                    .put("child:kid2:used_ms", "450000")
                    .put("child:kid2:credit_ms", "120000")
                    .put("active_round", "{\"id\":\"r1\"}")
                    .put("child:kid2:active_round", "{\"id\":\"r2\"}")
                    .put("admin_token", "secret-token")
                    .put("parent_access", "secret-hash")
                    .put("extra_pool", "5")
                    .put("rest_deadline", "999999")
            )
            .put("questions", JSONArray().put(JSONObject().put("id", "q1").put("prompt", "1+1=?")))
            .put("questionStats", JSONArray().put(JSONObject().put("qid", "q1").put("presented", 3)))
            .put("questionProgress", JSONArray().put(JSONObject().put("qid", "q1").put("repetitions", 2)))
            .put("events", JSONArray().put(JSONObject().put("type", "usage")))
            .put("reviews", JSONArray().put(JSONObject().put("id", "rev1")))
            .put("speech", JSONObject().put("mode", "system").put("autoRead", true))
            .put("grading", JSONObject().put("mode", "ai").put("provider", "deepseek"))

        // 1) Only config + speech
        val configAndSpeech = LocalStore.buildScopedSyncPayload(fullBackup, setOf("config", "speech", "unknown_scope"))
        assertEquals("studyontv-backup", configAndSpeech.getString("format"))
        assertFalse(configAndSpeech.getBoolean("includeQuestions"))
        assertFalse(configAndSpeech.has("deviceName"))
        assertFalse(configAndSpeech.has("activeChild"))
        assertFalse(configAndSpeech.has("questions"))
        assertFalse(configAndSpeech.has("grading"))
        assertFalse(configAndSpeech.has("events"))
        assertFalse(configAndSpeech.has("reviews"))
        assertFalse(configAndSpeech.has("questionStats"))
        assertFalse(configAndSpeech.has("questionProgress"))
        assertTrue(configAndSpeech.has("speech"))
        assertTrue(configAndSpeech.has("children"))

        val kv = configAndSpeech.getJSONObject("kv")
        assertEquals("{\"watchMinutes\":20}", kv.getString("config"))
        assertEquals("{\"watchMinutes\":15}", kv.getString("child:kid2:config"))
        assertEquals(2, kv.length())
        for (forbidden in listOf("used_ms", "free_used_ms", "credit_ms", "usage_day", "child:kid2:used_ms", "child:kid2:credit_ms", "active_round", "child:kid2:active_round", "admin_token", "parent_access", "extra_pool", "rest_deadline")) {
            assertFalse("Runtime/secret key must be stripped: $forbidden", kv.has(forbidden))
        }

        // 2) Only questions + grading
        val qAndGrading = LocalStore.buildScopedSyncPayload(fullBackup, setOf("questions", "grading"))
        assertTrue(qAndGrading.getBoolean("includeQuestions"))
        assertEquals(1, qAndGrading.getJSONArray("questions").length())
        assertTrue(qAndGrading.has("grading"))
        assertFalse(qAndGrading.has("kv"))
        assertFalse(qAndGrading.has("children"))
        assertFalse(qAndGrading.has("speech"))
        assertFalse(qAndGrading.has("deviceName"))

        // 3) Empty/invalid scopes must throw
        try {
            LocalStore.buildScopedSyncPayload(fullBackup, setOf("invalid"))
            fail("Expected IllegalArgumentException for empty valid scopes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("同步"))
        }
    }

    @Test
    fun peersEndpointsRequireParentAuthOnly() {
        val routes = listOf(
            "GET" to "/api/peers",
            "POST" to "/api/peers/probe",
            "POST" to "/api/peers/sync"
        )
        val fullIntegration = IntegrationAuth.Scope(read = true, control = true)

        for ((method, path) in routes) {
            // Unauthenticated -> 401
            val unauth = LanServer.checkApiAccess(method, path, deviceAccess = false, parent = false, integration = null)
            assertNotNull("Expected 401 for unauth $method $path", unauth)
            assertEquals(401, unauth!!.first)

            // Device token -> 403
            val device = LanServer.checkApiAccess(method, path, deviceAccess = true, parent = false, integration = null)
            assertNotNull("Expected 403 for device token $method $path", device)
            assertEquals(403, device!!.first)

            // Integration token -> 403
            val integ = LanServer.checkApiAccess(method, path, deviceAccess = false, parent = false, integration = fullIntegration)
            assertNotNull("Expected 403 for integration token $method $path", integ)
            assertEquals(403, integ!!.first)

            // Parent session -> allowed (null)
            val parent = LanServer.checkApiAccess(method, path, deviceAccess = false, parent = true, integration = null)
            assertNull("Expected parent access allowed for $method $path", parent)
        }
    }

    @Test
    fun formatSyncSuccessMessageSummarizesScopesAndMode() {
        val mergeMsg = LanServer.formatSyncSuccessMessage(
            scopes = setOf("config", "questions", "speech", "grading"),
            mode = "merge",
            syncedQuestions = 12,
            totalQuestions = 50
        )
        assertEquals("已同步学习计划、题库（合并 12 题）、朗读设置、AI 模型配置", mergeMsg)

        val replaceMsg = LanServer.formatSyncSuccessMessage(
            scopes = setOf("questions"),
            mode = "replace",
            syncedQuestions = 50,
            totalQuestions = 50
        )
        assertEquals("已同步题库（覆盖 50 题）", replaceMsg)
    }
}
