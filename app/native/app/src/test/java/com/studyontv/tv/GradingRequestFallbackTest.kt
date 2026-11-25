package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingRequestFallbackTest {
    @Test
    fun keepsConfiguredTemperatureAndRetriesOtherNamedParameters() {
        val body = JSONObject()
            .put("model", "gpt-6-luna")
            .put("temperature", 0)
            .put("max_tokens", 200)
            .put("response_format", JSONObject().put("type", "json_object"))

        assertNull(
            GradingService.RequestFallback.apply(
                body,
                "服务返回 HTTP 400：Unsupported value: 'temperature' does not support 0 with this model. Only the default (1) value is supported."
            )
        )
        assertEquals(0, body.getInt("temperature"))
        assertTrue(body.has("response_format"))
        assertTrue(body.has("max_tokens"))

        assertEquals(
            "max_tokens → max_completion_tokens",
            GradingService.RequestFallback.apply(
                body,
                "服务返回 HTTP 400：Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."
            )
        )
        assertFalse(body.has("max_tokens"))
        assertEquals(200, body.getInt("max_completion_tokens"))
    }

    @Test
    fun removesResponseFormatAndTopPOnlyWhenTheProviderReportsThoseFields() {
        val body = JSONObject()
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("top_p", 1)

        assertEquals(
            "without response_format",
            GradingService.RequestFallback.apply(body, "HTTP 422: response_format is not supported")
        )
        assertFalse(body.has("response_format"))
        assertTrue(body.has("top_p"))

        assertEquals(
            "without top_p",
            GradingService.RequestFallback.apply(body, "HTTP 400: top_p is not supported by this model")
        )
        assertFalse(body.has("top_p"))
    }

    @Test
    fun doesNotRetryUnrelatedErrorsOrRepeatAnAlreadyRemovedField() {
        val body = JSONObject().put("model", "missing-model").put("temperature", 0)

        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 400：模型不存在"))
        assertTrue(body.has("temperature"))
        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 400：temperature is invalid"))
        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 401：temperature is invalid"))
    }

    @Test
    fun validatesTemperatureInTheOpenAiCompatibleRange() {
        assertEquals(0.0, GradingService.validateTemperature(0.0), 0.0)
        assertEquals(1.0, GradingService.validateTemperature(1.0), 0.0)
        assertEquals(2.0, GradingService.validateTemperature(2.0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemperatureBelowZero() {
        GradingService.validateTemperature(-0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemperatureAboveTwo() {
        GradingService.validateTemperature(2.1)
    }

    @Test
    fun extractsErrorDetailAcrossOpenAiOllamaAndPlainTextShapes() {
        val ollamaDetail = GradingService.extractErrorDetail("""{"error":"model_not_found"}""")
        assertEquals("model_not_found", ollamaDetail)
        assertTrue(GradingService.localizeApiError(400, ollamaDetail).contains("模型名称不存在"))

        assertEquals("bad key", GradingService.extractErrorDetail("""{"message":"bad key"}"""))

        val nestedDetail = GradingService.extractErrorDetail("""{"error":{"message":"max_tokens unsupported"}}""")
        assertEquals("max_tokens unsupported", nestedDetail)
        val localized = GradingService.localizeApiError(400, nestedDetail)
        val body = JSONObject().put("max_tokens", 200)
        assertEquals("max_tokens → max_completion_tokens", GradingService.RequestFallback.apply(body, localized))

        assertEquals("400", GradingService.extractErrorDetail("""{"error":{"code":"400"}}"""))
        assertEquals(
            "bad_request: invalid_param",
            GradingService.extractErrorDetail("""{"error":{"code":"invalid_param","type":"bad_request"}}""")
        )
        assertEquals("502 Bad Gateway", GradingService.extractErrorDetail("  502 Bad Gateway  "))
        assertEquals("", GradingService.extractErrorDetail("   "))
    }

    @Test
    fun extractsJsonFromThinkBlocksMarkdownFencesAndSurroundingProse() {
        val withThink = "<think>思考含 {1} 公式 \\frac{1}{2}</think>\n{\"score\":85,\"feedback\":\"不错\"}"
        val parsedThink = GradingService.extractJson(withThink)
        assertEquals(85, parsedThink.getInt("score"))
        assertEquals("不错", parsedThink.getString("feedback"))

        val withFence = "说明 {x}：\n```json\n{\"score\":92,\"feedback\":\"含 \\\"}\\\" 与 {1}\"}\n```\n补充 {y}"
        val parsedFence = GradingService.extractJson(withFence)
        assertEquals(92, parsedFence.getInt("score"))
        assertEquals("含 \"}\" 与 {1}", parsedFence.getString("feedback"))

        val withProse = "前置集合 {1, 2} 结果如下：{\"score\":88,\"feedback\":\"正确\"} 尾注 {done}"
        val parsedProse = GradingService.extractJson(withProse)
        assertEquals(88, parsedProse.getInt("score"))
        assertEquals("正确", parsedProse.getString("feedback"))
    }

    @Test(expected = Exception::class)
    fun extractJsonRejectsTextWithoutValidJsonObject() {
        GradingService.extractJson("<think>{\"score\":100}</think> 只有普通文字 {not-json}")
    }

    @Test
    fun isPrivateAddressesRequiresAllResolvedIpsToBePrivate() {
        val loopback = java.net.InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        val lan = java.net.InetAddress.getByAddress("ollama.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        val lan10 = java.net.InetAddress.getByAddress("10.0.0.2", byteArrayOf(10, 0, 0, 2))
        val publicWithLocalSuffix = java.net.InetAddress.getByAddress("evil.local", byteArrayOf(8, 8, 8, 8))

        assertTrue(GradingService.isPrivateAddresses(arrayOf(loopback)))
        assertTrue(GradingService.isPrivateAddresses(arrayOf(lan, lan10)))
        // Mixed private + public DNS records must be rejected.
        assertFalse(GradingService.isPrivateAddresses(arrayOf(lan, publicWithLocalSuffix)))
        // .local suffix resolving to a public IP must be rejected.
        assertFalse(GradingService.isPrivateAddresses(arrayOf(publicWithLocalSuffix)))
        // Empty resolution must be rejected.
        assertFalse(GradingService.isPrivateAddresses(emptyArray()))
    }

    @Test
    fun checkEndpointBlocksPublicUrlInLocalOnlyModeAndAllowsLanOllama() {
        var blocked = false
        try {
            GradingService.checkEndpoint("https://api.deepseek.com/v1", localOnly = true)
        } catch (e: IllegalArgumentException) {
            blocked = e.message.orEmpty().contains("仅本地隐私版")
        }
        assertTrue(blocked)

        val lanUrl = GradingService.checkEndpoint("http://192.168.1.10:11434/v1", localOnly = true)
        assertEquals("192.168.1.10", lanUrl.host)
    }

    @Test
    fun applyApiKeyMutationClearsBeforeApplyingNewApiKey() {
        val next = JSONObject().put("apiKey", "sk-old")
        // clearKey=true + new apiKey: clears old key first, keeps newly submitted apiKey.
        GradingService.applyApiKeyMutation(next, JSONObject().put("clearKey", true).put("apiKey", "  sk-new  "))
        assertEquals("sk-new", next.optString("apiKey"))

        // clearKey=true alone: removes apiKey.
        GradingService.applyApiKeyMutation(next, JSONObject().put("clearKey", true))
        assertFalse(next.has("apiKey"))
    }
}
