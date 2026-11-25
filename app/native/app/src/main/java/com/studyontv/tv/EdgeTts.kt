package com.studyontv.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 免费内置微软 Edge 在线朗读（Read-Aloud WebSocket 协议，零第三方依赖）。
 *
 * 使用带连接超时的底层 Socket + TLS 包装 + RFC 6455 WebSocket 握手与分片重组，
 * 当电视机本地时钟失准导致 HTTP 403 时，自动读取响应头里的 `Date` 校准 `Sec-MS-GEC` 重试一次。
 */
object EdgeTts {
    private const val HOST = "speech.platform.bing.com"
    private const val PORT = 443
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_VERSION = "143.0.3650.75"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val MAX_AUDIO_BYTES = 4 * 1024 * 1024

    /** 记录设备时钟与微软服务端时钟的偏差，电视未完成 NTP 校时时后续请求直接复用校准值。 */
    @Volatile private var clockSkewMs = 0L

    val VOICES = listOf(
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-XiaoyiNeural",
        "zh-HK-HiuGaaiNeural",
        "zh-TW-HsiaoChenNeural",
        "en-US-JennyNeural",
        "en-GB-SoniaNeural",
    )

    fun defaultVoice(language: String): String {
        val tag = language.lowercase(Locale.US)
        return when {
            tag.startsWith("zh-hk") || tag.startsWith("yue") -> "zh-HK-HiuGaaiNeural"
            tag.startsWith("zh-tw") -> "zh-TW-HsiaoChenNeural"
            tag.startsWith("en-gb") -> "en-GB-SoniaNeural"
            tag.startsWith("en") -> "en-US-JennyNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        }
    }

    private class Handshake403Exception(message: String, val serverMillis: Long?) : IllegalStateException(message)

    internal fun requireOnlineAllowed(localOnly: Boolean = LocalOnlyGuard.enabled) {
        if (localOnly) throw IllegalStateException("仅本地隐私版（EU Local-Only）已禁用 Edge 在线语音")
    }

    internal fun shouldRetryAfter403(usedNowMs: Long, serverMs: Long?): Boolean {
        if (serverMs == null) return false
        return secMsGec(serverMs) != secMsGec(usedNowMs) || kotlin.math.abs(serverMs - usedNowMs) >= 60_000L
    }

    internal fun orderAddressesIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        val v4 = addresses.filterIsInstance<Inet4Address>()
        val other = addresses.filter { it !is Inet4Address }
        return v4 + other
    }

    fun synthesize(text: String, voice: String, language: String = "", rate: Double = 1.0): ByteArray {
        requireOnlineAllowed()
        val chosenVoice = voice.trim().ifEmpty { defaultVoice(language) }
        val pct = (((rate.coerceIn(0.7, 1.5)) - 1.0) * 100.0).toInt()
        val rateStr = if (pct >= 0) "+${pct}%" else "${pct}%"
        return synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = 0, allowTransientRetry = true)
    }

    private fun synthesizeWithRetry(
        text: String,
        chosenVoice: String,
        rateStr: String,
        addressOffset: Int,
        allowTransientRetry: Boolean
    ): ByteArray {
        val usedNowMs = System.currentTimeMillis() + clockSkewMs
        return try {
            synthesizeOnce(text, chosenVoice, rateStr, usedNowMs, addressOffset)
        } catch (e: Handshake403Exception) {
            val serverMs = e.serverMillis
            if (serverMs == null || !shouldRetryAfter403(usedNowMs, serverMs)) {
                throw IllegalStateException(e.message ?: "Edge 语音握手被拒绝（HTTP 403）")
            }
            clockSkewMs = serverMs - System.currentTimeMillis()
            try {
                synthesizeOnce(text, chosenVoice, rateStr, serverMs, addressOffset)
            } catch (retryErr: Handshake403Exception) {
                throw IllegalStateException(retryErr.message ?: "Edge 语音握手被拒绝（HTTP 403）")
            }
        } catch (e: SocketException) {
            if (!allowTransientRetry) throw e
            synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = addressOffset + 1, allowTransientRetry = false)
        } catch (e: EOFException) {
            if (!allowTransientRetry) throw e
            synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = addressOffset + 1, allowTransientRetry = false)
        }
    }

    private fun openTlsSocket(addressOffset: Int = 0): SSLSocket {
        val resolved = orderAddressesIpv4First(InetAddress.getAllByName(HOST).toList())
        val candidates = if (resolved.isEmpty()) {
            listOf<InetAddress?>(null)
        } else {
            val n = resolved.size
            val shift = ((addressOffset % n) + n) % n
            (0 until minOf(n, 4)).map { i -> resolved[(shift + i) % n] }
        }
        var lastError: Throwable? = null
        for (addr in candidates) {
            val raw = Socket()
            try {
                val target = if (addr != null) InetSocketAddress(addr, PORT) else InetSocketAddress(HOST, PORT)
                raw.connect(target, 4_500)
                raw.soTimeout = 15_000
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                return (sslFactory.createSocket(raw, HOST, PORT, true) as SSLSocket).apply {
                    soTimeout = 15_000
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        sslParameters = sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                    }
                    startHandshake()
                    if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, session)) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("Hostname $HOST not verified")
                    }
                }
            } catch (e: Throwable) {
                try { raw.close() } catch (_: Exception) { }
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法连接 Edge 语音服务")
    }

    private fun synthesizeOnce(
        text: String,
        chosenVoice: String,
        rateStr: String,
        nowMillis: Long,
        addressOffset: Int = 0
    ): ByteArray {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val muid = randomHex(16)
        val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${secMsGec(nowMillis)}" +
            "&Sec-MS-GEC-Version=2-$CHROMIUM_VERSION" +
            "&ConnectionId=$connId"

        val socket = openTlsSocket(addressOffset)
        try {
            val out = socket.outputStream
            val input = BufferedInputStream(socket.inputStream, 16 * 1024)
            handshake(input, out, path, muid)

            val ts = isoTimestamp(nowMillis)
            val configMsg = "X-Timestamp:$ts\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"
            sendTextFrame(out, configMsg)

            val reqId = UUID.randomUUID().toString().replace("-", "")
            val lang = chosenVoice.split("-").take(2).joinToString("-").ifEmpty { "zh-CN" }
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                "<voice name='$chosenVoice'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>${escapeXml(text)}</prosody></voice></speak>"
            val ssmlMsg = "X-RequestId:$reqId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:$ts\r\n" +
                "Path:ssml\r\n\r\n$ssml"
            sendTextFrame(out, ssmlMsg)

            val audio = ByteArrayOutputStream()
            var closeDetail = ""
            while (true) {
                val message = readMessage(input) { control ->
                    when (control.opcode) {
                        0x8 -> {
                            val (code, reason) = parseCloseInfo(control.payload)
                            closeDetail = listOfNotNull(code?.let { "code=$it" }, reason.takeIf { it.isNotEmpty() }).joinToString(" ")
                            false
                        }
                        0x9 -> {
                            sendPongFrame(out, control.payload)
                            true
                        }
                        else -> true
                    }
                } ?: break
                when (message.opcode) {
                    0x1 -> {
                        val msg = String(message.payload, Charsets.UTF_8)
                        if (msg.contains("Path:turn.end")) break
                    }
                    0x2 -> {
                        val data = message.payload
                        if (data.size >= 2) {
                            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val bodyStart = 2 + headerLen
                            if (bodyStart < data.size) {
                                require(audio.size() + (data.size - bodyStart) <= MAX_AUDIO_BYTES) { "Edge 语音返回数据过大" }
                                audio.write(data, bodyStart, data.size - bodyStart)
                            }
                        }
                    }
                }
            }
            sendCloseFrame(out)
            val result = audio.toByteArray()
            require(result.isNotEmpty()) {
                if (closeDetail.isNotEmpty()) "Edge 语音返回空音频（$closeDetail）" else "Edge 语音返回空音频"
            }
            return result
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /** 生成 v2 握手参数，时间戳按五分钟取整。 */
    internal fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        var ticks = (nowMillis / 1000L) + 11_644_473_600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val raw = "$ticks$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII)
        return EdgeGecV2.encode(raw)
    }

    /** 解析 HTTP 响应头中的 RFC 1123 时间（例如 `Wed, 18 Nov 2026 05:30:00 GMT`）。 */
    internal fun parseHttpDate(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in formats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                return fmt.parse(text)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private val HTTP_STATUS_LINE_RE = Regex("^HTTP/\\d+\\.\\d+\\s+(\\d{3})(?:\\s.*)?$")
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    internal fun parseHttpStatusCode(statusLine: String): Int? =
        HTTP_STATUS_LINE_RE.matchEntire(statusLine.trim())?.groupValues?.get(1)?.toIntOrNull()

    internal fun expectedAccept(wsKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((wsKey + WS_GUID).toByteArray(Charsets.US_ASCII))
        return MediaStore.encodeBase64(digest)
    }

    internal fun validateHandshakeResponse(statusLine: String, headers: Map<String, String>, wsKey: String) {
        val code = parseHttpStatusCode(statusLine)
        if (code == 403) {
            throw Handshake403Exception("Edge 语音握手失败：$statusLine", parseHttpDate(headers["date"]))
        }
        require(code == 101) { "Edge 语音握手失败：$statusLine" }
        val accept = headers["sec-websocket-accept"].orEmpty().trim()
        require(accept == expectedAccept(wsKey)) { "Edge 语音握手校验失败（Sec-WebSocket-Accept 不匹配）" }
    }

    private fun handshake(input: BufferedInputStream, out: OutputStream, path: String, muid: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = MediaStore.encodeBase64(keyBytes)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(HOST).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: ").append(ORIGIN).append("\r\n")
            append("User-Agent: ").append(USER_AGENT).append("\r\n")
            append("Pragma: no-cache\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Cookie: muid=").append(muid).append(";\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHttpLine(input)
        val headers = HashMap<String, String>()
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        validateHandshakeResponse(statusLine, headers, wsKey)
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("Edge 语音连接意外关闭")
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val len = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.UTF_8)
            }
            buf.write(b)
            require(buf.size() <= 8192) { "Edge 响应头过长" }
        }
    }

    internal class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    internal fun parseCloseInfo(payload: ByteArray): Pair<Int?, String> {
        if (payload.size < 2) return null to ""
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8).trim() else ""
        return code to reason
    }

    /**
     * 读取并重组一条完整的 WebSocket 数据消息（支持 `FIN=0` + `opcode=0x0` 连续分片，
     * 同时允许分片中途穿插 Ping / Close 等控制帧）。`onControl` 返回 `false` 表示收到关闭帧结束循环。
     */
    internal fun readMessage(input: InputStream, onControl: (Frame) -> Boolean = { it.opcode != 0x8 }): Frame? {
        var fragOpcode = -1
        val fragBuffer = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame(input) ?: return null
            if (frame.opcode >= 0x8) {
                if (!onControl(frame)) return null
                continue
            }
            if (frame.opcode != 0x0) {
                require(fragOpcode == -1) { "Edge WebSocket 分片未结束又收到新消息帧" }
                if (frame.fin) return frame
                fragOpcode = frame.opcode
                fragBuffer.reset()
                fragBuffer.write(frame.payload)
            } else {
                require(fragOpcode != -1) { "Edge WebSocket 收到孤立的连续分片帧" }
                require(fragBuffer.size() + frame.payload.size <= MAX_AUDIO_BYTES) { "Edge 帧过大" }
                fragBuffer.write(frame.payload)
                if (frame.fin) {
                    return Frame(true, fragOpcode, fragBuffer.toByteArray())
                }
            }
        }
    }

    internal fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var len = (second and 0x7F).toLong()
        if (len == 126L) {
            val b1 = input.read(); val b2 = input.read()
            if (b1 < 0 || b2 < 0) throw EOFException()
            len = (((b1 and 0xFF) shl 8) or (b2 and 0xFF)).toLong()
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) {
                val b = input.read(); if (b < 0) throw EOFException()
                v = (v shl 8) or (b.toLong() and 0xFFL)
            }
            len = v
        }
        require(len in 0..MAX_AUDIO_BYTES) { "Edge 帧过大" }
        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, len.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(out, off, size - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return out
    }

    private fun sendTextFrame(out: OutputStream, text: String) = sendFrame(out, 0x1, text.toByteArray(Charsets.UTF_8))
    private fun sendPongFrame(out: OutputStream, payload: ByteArray) = sendFrame(out, 0xA, payload)
    private fun sendCloseFrame(out: OutputStream) {
        try { sendFrame(out, 0x8, ByteArray(0)) } catch (_: Exception) { }
    }

    internal fun encodeFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write((if (fin) 0x80 else 0x00) or (opcode and 0x0F))
        val maskBit = if (masked) 0x80 else 0x00
        val len = payload.size
        when {
            len <= 125 -> out.write(maskBit or len)
            len <= 65535 -> {
                out.write(maskBit or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) out.write(((len.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        if (masked) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            out.write(mask)
            val maskedPayload = ByteArray(len) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            out.write(maskedPayload)
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val encoded = encodeFrame(opcode, payload, masked = true, fin = true)
        synchronized(out) {
            out.write(encoded)
            out.flush()
        }
    }

    private fun isoTimestamp(nowMillis: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMillis))
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(Locale.US, it) }

    /**
     * 按 XML 1.0 §2.2 净化并转义 SSML 文本：
     * - 剥离非法控制字符（保留 \t、\n、\r）与 0xFFFE/0xFFFF；
     * - 完整保留合法 UTF-16 代理对，丢弃孤立高/低代理字符；
     * - 转义 XML 特殊字符 & < > " '。
     */
    internal fun escapeXml(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    out.append(ch).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (Character.isLowSurrogate(ch)) {
                i++
                continue
            }
            val code = ch.code
            val xmlValid = ch == '\t' || ch == '\n' || ch == '\r' ||
                (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
            if (xmlValid) {
                when (ch) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    '\'' -> out.append("&apos;")
                    else -> out.append(ch)
                }
            }
            i++
        }
        return out.toString()
    }
}
