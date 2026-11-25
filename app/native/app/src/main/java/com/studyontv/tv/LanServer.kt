package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class LanServer(
    private val context: Context,
    private val store: LocalStore,
    private val assets: AssetManager,
    private val port: Int = 8099
) {
    companion object {
        private const val MAX_UNAUTH_BODY = 4_096
        private const val MAX_INTEGRATION_BODY = 65_536
        private const val MAX_DEVICE_BODY = 3_000_000
        private const val MAX_PARENT_BODY = 24_000_000

        /** 按 RFC 3986 路径段规则解码 UTF-8 百分号编码（保留字面 `+`，拒绝非法 `%` 序列）。 */
        internal fun decodePathSegment(raw: String): String {
            if ('%' !in raw) return raw
            val out = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch == '%') {
                    require(i + 2 < raw.length) { "路径参数编码无效" }
                    val hi = Character.digit(raw[i + 1], 16)
                    val lo = Character.digit(raw[i + 2], 16)
                    require(hi in 0..15 && lo in 0..15) { "路径参数编码无效" }
                    out.write((hi shl 4) or lo)
                    i += 3
                } else {
                    if (ch.code < 0x80) out.write(ch.code)
                    else out.write(ch.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /** 严格按 Content-Length 读取完整请求体；若客户端中途断连导致 EOF 则抛 EOFException 中止请求。 */
        internal fun readExactBody(input: InputStream, length: Int): String {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) throw EOFException("请求体未接收完整（已读 $read / $length 字节）")
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }

        /**
         * 仅允许本机、沙盒页面或局域网私有地址来源的跨域请求，防止公网恶意网页利用浏览器 Private Network Access 探测电视接口。
         * 只做纯语法校验，绝不对未知域名发起 DNS 解析（防止 DNS Rebinding 与阻塞）。
         */
        internal fun allowedCorsOrigin(origin: String?): String? {
            if (origin == null || origin.isBlank()) return "*"
            val trimmed = origin.trim()
            if (trimmed.equals("null", ignoreCase = true)) return null
            val uri = try { URI(trimmed) } catch (_: Exception) { return null }
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.rawUserInfo != null || (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || uri.rawQuery != null || uri.rawFragment != null) {
                return null
            }
            val host = uri.host?.lowercase(Locale.US)?.removeSurrounding("[", "]") ?: return null
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan")) {
                return trimmed
            }
            if (isPrivateIpv4Literal(host)) return trimmed
            return null
        }

        private fun isPrivateIpv4Literal(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = IntArray(4)
            for (i in 0..3) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return false
                val v = p.toIntOrNull() ?: return false
                if (v !in 0..255) return false
                octets[i] = v
            }
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
        }

        /** 设备令牌（学习页 WebView）仅允许调用学习状态、切换孩子与答题相关接口，禁止改动规则、题库或密码。 */
        internal fun isDeviceRouteAllowed(method: String, path: String): Boolean = when {
            method == "GET" && path == "/api/state" -> true
            method == "POST" && path in setOf(
                "/api/children/switch",
                "/api/quiz/start",
                "/api/quiz/answer",
                "/api/quiz/continue",
                "/api/quiz/review",
                "/api/quiz/skip",
                "/api/quiz/memorized",
                "/api/quiz/rate",
                "/api/event"
            ) -> true
            else -> false
        }

        internal data class ChildSwitchDecision(
            val allowed: Boolean,
            val errorCode: String? = null,
            val message: String? = null
        )

        /** 解析可选 pin 字段：未传或 null 返回 null（不改动）；传 "" 表示清空；非空返回字符串。 */
        internal fun extractOptionalPin(input: JSONObject): String? =
            if (input.has("pin") && !input.isNull("pin")) input.optString("pin", "") else null

        /**
         * 评估孩子切换权限（ST-610）：
         * - 家长端已鉴权会话直接放行；
         * - 切换到当前已激活孩子或未设密码的孩子直接放行；
         * - 电视/平板学习页切换到已设密码的其他孩子时，必须携带该孩子的切换密码（或家长主密码兜底）。
         */
        internal fun evaluateChildSwitch(
            targetId: String,
            activeId: String,
            isParent: Boolean,
            targetHasPin: Boolean,
            pin: String?,
            verifyChildPin: (String) -> Boolean,
            verifyParentPassword: (String) -> Boolean
        ): ChildSwitchDecision {
            if (isParent || targetId == activeId || !targetHasPin) {
                return ChildSwitchDecision(allowed = true)
            }
            val candidate = pin?.trim().orEmpty()
            if (candidate.isEmpty()) {
                return ChildSwitchDecision(
                    allowed = false,
                    errorCode = "child_pin_required",
                    message = "请输入该孩子的切换密码"
                )
            }
            if (verifyChildPin(candidate) || verifyParentPassword(candidate)) {
                return ChildSwitchDecision(allowed = true)
            }
            return ChildSwitchDecision(
                allowed = false,
                errorCode = "child_pin_invalid",
                message = "切换密码错误，请重试"
            )
        }

        /**
         * 统一 `/api/` 接口鉴权裁决（纯函数，便于单测直接验证家长/设备/集成令牌隔离）。
         * 返回 `null` 表示放行；否则返回 `(httpStatus, message)`。
         */
        internal fun checkApiAccess(
            method: String,
            path: String,
            deviceAccess: Boolean,
            parent: Boolean,
            integration: IntegrationAuth.Scope?
        ): Pair<Int, String>? {
            val isPublicApi = path == "/api/hello" || (method == "GET" && path.startsWith(MediaStore.PREFIX))
            if (!path.startsWith("/api/") || isPublicApi) return null
            if (!deviceAccess && !parent && integration == null) return 401 to "unauthorized"
            if (integration != null && !deviceAccess && !parent) {
                val scope = when {
                    path == "/api/state" && method == "GET" -> "read"
                    path == "/api/device" && method == "GET" -> "read"
                    path == "/api/coverage" && method == "GET" -> "read"
                    path == "/api/analytics" && method == "GET" -> "read"
                    path == "/api/remote" && method == "POST" -> "control"
                    else -> null
                } ?: return 403 to "这个接口需要家长登录"
                if ((scope == "read" && !integration.read) || (scope == "control" && !integration.control)) {
                    return 403 to "集成令牌没有这个权限"
                }
            }
            if (deviceAccess && !parent && integration == null && !isDeviceRouteAllowed(method, path)) {
                return 403 to "forbidden"
            }
            return null
        }

        internal fun formatSyncSuccessMessage(
            scopes: Set<String>,
            mode: String,
            syncedQuestions: Int,
            totalQuestions: Int
        ): String {
            val parts = mutableListOf<String>()
            if ("config" in scopes) parts.add("学习计划")
            if ("questions" in scopes) {
                val modeLabel = if (mode == "replace") "覆盖" else "合并"
                val count = if (mode == "replace") totalQuestions else syncedQuestions
                parts.add("题库（$modeLabel ${count} 题）")
            }
            if ("speech" in scopes) parts.add("朗读设置")
            if ("grading" in scopes) parts.add("AI 模型配置")
            return if (parts.isEmpty()) "同步完成" else "已同步" + parts.joinToString("、")
        }
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(if (BuildConfig.FLAVOR == "tv") 4 else 6)
    private val router = Router()
    @Volatile private var running = false
    var boundPort: Int = port
        private set

    init { registerRoutes() }

    fun start() {
        if (running) return
        running = true
        if (!open()) Diag.error("Server", "无法监听 $port / 8090，稍后自动重试")
        PeerDiscovery.start(context, store, portProvider = { boundPort }, ipProvider = { localIp() })
        // 守护线程：端口被旧进程占着时抢回默认端口；监听挂死（进程被冻结、网络切换后）时重新监听
        Thread {
            var bad = 0
            while (running) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                try {
                    val s = socket
                    var needOpen = s == null || s.isClosed
                    if (!needOpen && boundPort != port) {
                        // 现在在备用端口上：默认端口能绑了就换回去（家长收藏的地址是默认端口）
                        val tmp = try { ServerSocket(port) } catch (e: Exception) { null }
                        if (tmp != null) { tmp.close(); needOpen = true }
                    }
                    if (!needOpen) {
                        bad = if (probe(boundPort)) 0 else bad + 1
                        if (bad >= 2) needOpen = true
                    }
                    if (needOpen) { bad = 0; if (open()) Diag.info("Server", "重新监听 端口=$boundPort") }
                } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; name = "lan-watch" }.start()
    }

    /** 关掉旧的、重新绑端口并起接收线程。 */
    @Synchronized
    private fun open(): Boolean {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        val p = tryBind(port) ?: tryBind(8090) ?: return false
        boundPort = p
        try {
            Prefs.setLocalPort(context, p)
            WebShell.PrefsHolder.baseUrl = Prefs.localBase(context)
        } catch (e: Exception) { }
        val s = socket!!
        Thread {
            while (running && s === socket && !s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (s.isClosed || s !== socket) break
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
        }.apply { isDaemon = true; name = "lan-accept" }.start()
        return true
    }

    private fun probe(p: Int): Boolean = try {
        Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", p), 1500) }
        true
    } catch (e: Exception) { false }

    fun stop() {
        running = false
        PeerDiscovery.stop()
        try { socket?.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }

    private fun tryBind(p: Int): Int? = try {
        socket = ServerSocket(p)
        p
    } catch (e: Exception) {
        null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 8000
            try {
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (headers.size >= 100) return reply(c.getOutputStream(), 400, "text/plain", "Too many headers".toByteArray())
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
                }

                val out = c.getOutputStream()
                val origin = headers["origin"]
                if (method == "OPTIONS") return reply(out, 204, null, null, origin = origin)

                val contentLengthRaw = headers["content-length"]
                val contentLength = if (contentLengthRaw == null) 0L else contentLengthRaw.toLongOrNull() ?: -1L
                if (contentLength < 0L) return json(out, 400, err("bad_request").put("message", "Content-Length 格式错误"), origin)
                if (contentLength > MAX_PARENT_BODY) return json(out, 413, err("payload_too_large").put("message", "请求内容过大"), origin)

                val local = c.inetAddress?.isLoopbackAddress ?: false
                val deviceAccess = local && headers["x-device-token"] == AdminAuth.deviceToken
                val token = headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-admin-token"]

                val isPublicApi = path == "/api/hello" ||
                    (path == "/api/auth" && method == "POST") ||
                    (method == "GET" && path.startsWith(MediaStore.PREFIX))

                var parent = false
                var integration: IntegrationAuth.Scope? = null
                if (path.startsWith("/api/") && !isPublicApi) {
                    val adminKey = headers["x-admin-key"]
                    parent = AdminAuth.valid(token) || (adminKey != null && AdminAuth.verify(context, adminKey))
                    val integrationToken = token?.takeIf { it.startsWith("sotv_") }
                    integration = IntegrationAuth.verify(context, integrationToken)
                    if (integration != null && !deviceAccess && !parent && !IntegrationAuth.takeBudget()) {
                        return json(out, 429, err("too_many_requests").put("message", "请求太频繁，请稍后再试"), origin)
                    }
                    if (!deviceAccess && !parent && integration == null) {
                        return json(out, 401, err("unauthorized"), origin)
                    }
                }

                // 在真正分配缓冲区读请求体之前先按身份卡死体积上限，防止未登录连接用 24MB 请求打爆内存
                val maxBody = when {
                    !path.startsWith("/api/") || isPublicApi -> MAX_UNAUTH_BODY
                    parent -> MAX_PARENT_BODY
                    deviceAccess -> MAX_DEVICE_BODY
                    else -> MAX_INTEGRATION_BODY
                }
                if (contentLength > maxBody) {
                    return json(out, 413, err("payload_too_large").put("message", "请求内容过大"), origin)
                }

                val body = if (contentLength > 0L) readBody(input, contentLength.toInt()) else ""
                route(method, path, query, headers, body, out, local, deviceAccess, parent, token, integration)
            } catch (_: Exception) {
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: String,
        out: OutputStream,
        local: Boolean,
        deviceAccess: Boolean,
        parent: Boolean,
        token: String?,
        integration: IntegrationAuth.Scope?
    ) {
        val origin = headers["origin"]
        val q = try {
            if (query.isEmpty()) emptyMap() else query.split("&").associate {
                val kv = it.split("=", limit = 2)
                java.net.URLDecoder.decode(kv[0], "UTF-8") to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            return json(out, 400, err("bad_request").put("message", "请求参数格式错误"), origin)
        }

        if (path == "/api/auth" && method == "POST") {
            val input = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val remember = input.optBoolean("remember")
            val session = AdminAuth.login(context, input.optString("passphrase"), remember)
            return if (session == null) json(out, 401, err("管理密码错误，连续错误 5 次后需等待一分钟"), origin)
            else json(out, 200, JSONObject().put("token", session).put("expiresIn", if (remember) 30 * 86400 else 43200), origin)
        }

        checkApiAccess(method, path, deviceAccess, parent, integration)?.let { (code, message) ->
            val payload = if (code == 401) err("unauthorized")
            else if (message == "forbidden") err("forbidden")
            else err("forbidden").put("message", message)
            return json(out, code, payload, origin)
        }

        if (method != "GET") Diag.info("HTTP", "$method $path")
        val ctx = RouteContext(method, path, q, headers, body, out, local, deviceAccess, parent, token, integration)
        try {
            if (router.dispatch(ctx)) return
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (e is java.net.SocketException &&
                (msg.contains("Broken pipe", ignoreCase = true) || msg.contains("Connection reset", ignoreCase = true))
            ) {
                return
            }
            Diag.error("HTTP", "$method $path failed", e)
            return json(out, 400, JSONObject().put("error", "bad_request").put("message", e.message ?: "请求格式错误"), origin)
        }

        if (method != "GET") return json(out, 405, err("method_not_allowed"), origin)
        serveStatic(path, out, headers["accept-encoding"]?.contains("gzip") == true, headers["if-none-match"], local, origin)
    }

    /** 所有 /api 路由集中在这里登记；路径匹配顺序由 Router 保证（精确路径优先） */
    private fun registerRoutes() {
        router.get("/api/hello") { ctx ->
            json(ctx, 200, JSONObject()
                .put("app", "StudyOnTV")
                .put("id", Prefs.deviceId(context))
                .put("version", BuildConfig.VERSION_NAME)
                .put("name", Prefs.deviceName(context))
                .put("ip", localIp() ?: "")
                .put("port", boundPort)
                .put("isTv", BuildConfig.FLAVOR == "tv")
                .put("questions", store.countQuestions())
                .put("needsToken", true)
                .put("passwordRequired", true))
        }
        // 本地媒体文件：文件名本身是 64 位 SHA-256，内容不可变，无需鉴权即可给 <img src="/api/media/..."> 直接加载并长期缓存
        router.prefixGet(MediaStore.PREFIX) { ctx ->
            val name = ctx.path.removePrefix(MediaStore.PREFIX)
            val mediaEtag = "\"$name\""
            if (MediaStore.isValidName(name) && ctx.headers["if-none-match"] == mediaEtag && File(store.mediaDir, name).isFile) {
                return@prefixGet reply(ctx.out, 304, null, null, mapOf("ETag" to mediaEtag, "Cache-Control" to "public, max-age=31536000, immutable"), ctx.headers["origin"])
            }
            val (bytes, mime) = MediaStore.readMedia(store.mediaDir, name)
                ?: return@prefixGet reply(ctx.out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = ctx.headers["origin"])
            reply(
                ctx.out,
                200,
                mime,
                bytes,
                mapOf("Cache-Control" to "public, max-age=31536000, immutable", "ETag" to mediaEtag),
                ctx.headers["origin"]
            )
        }
        router.get("/api/apps") { ctx ->
            val pm = context.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val items = pm.queryIntentActivities(main, 0).asSequence()
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
                    pkg to info.loadLabel(pm).toString().trim().take(60)
                }
                .sortedBy { it.second }
                .take(500)
                .map { (pkg, label) -> JSONObject().put("package", pkg).put("name", label.ifEmpty { pkg }) }
                .toList()
            json(ctx, 200, JSONObject().put("items", JSONArray(items)))
        }
        router.get("/api/state") { ctx ->
            DeviceRuntime.learning.flushUsage()
            json(ctx, 200, JSONObject()
                .put("config", store.config())
                .put("today", store.todayStats())
                .put("questions", store.countQuestions())
                .put("roundsDoneToday", store.roundsDoneToday())
                .put("repetition", store.repetitionStats())
                .put("study", store.studySummary())
                .put("learning", DeviceRuntime.learning.status())
                .put("children", store.children()).put("activeChild", store.activeChild())
                .put("speech", JSONObject()
                    .put("autoRead", SpeechReader.settings(store).optBoolean("autoRead"))
                    .put("readScope", SpeechReader.settings(store).optString("readScope", "question"))))
        }
        router.get("/api/analytics") { ctx ->
            DeviceRuntime.learning.flushUsage()
            val days = ctx.query["days"]?.toIntOrNull() ?: 14
            json(ctx, 200, store.learningAnalytics(days))
        }
        router.get("/api/backup") { ctx ->
            DeviceRuntime.learning.flushUsage()
            val includeQuestions = ctx.query["includeQuestions"] != "0"
            json(ctx, 200, store.exportBackup(includeQuestions))
        }
        router.post("/api/backup/restore") { ctx ->
            DeviceRuntime.learning.flushUsage()
            val input = JSONObject(ctx.body)
            val backup = input.optJSONObject("backup") ?: input
            val mode = input.optString("mode", "replace")
            val scopesArr = input.optJSONArray("scopes")
            val scopes: Set<String>? = if (scopesArr != null) {
                val set = LinkedHashSet<String>()
                for (i in 0 until scopesArr.length()) {
                    val s = scopesArr.optString(i).trim()
                    if (s.isNotEmpty()) set.add(s)
                }
                set
            } else null
            val restored = store.restoreBackup(backup, mode, scopes)
            DeviceRuntime.learning.status()
            GateService.refreshNow()
            json(ctx, 200, restored)
        }
        router.get("/api/peers") { ctx ->
            val doScan = ctx.query["scan"] == "1" || ctx.query["scan"].equals("true", ignoreCase = true)
            val peerList = if (doScan) PeerDiscovery.scan(700L) else PeerDiscovery.listPeers()
            val selfIp = localIp() ?: ""
            val selfHost = if (selfIp.isNotEmpty()) "$selfIp:$boundPort" else ""
            val selfJson = JSONObject()
                .put("id", Prefs.deviceId(context))
                .put("name", Prefs.deviceName(context))
                .put("ip", selfIp)
                .put("port", boundPort)
                .put("host", selfHost)
                .put("version", BuildConfig.VERSION_NAME)
                .put("isTv", BuildConfig.FLAVOR == "tv")
                .put("questions", store.countQuestions())
            val peersArr = JSONArray()
            val nowMs = System.currentTimeMillis()
            for (p in peerList) peersArr.put(p.toJson(nowMs))
            json(ctx, 200, JSONObject().put("self", selfJson).put("peers", peersArr))
        }
        router.post("/api/peers/probe") { ctx ->
            val input = JSONObject(ctx.body)
            val rawHost = input.optString("host").trim()
            val parsed = PeerDiscovery.parsePrivateLanHost(rawHost)
                ?: return@post json(ctx, 400, err("invalid_host").put("message", "仅支持局域网私有 IPv4 地址（10.x / 172.16-31.x / 192.168.x）及合法端口（1024–65535）"))
            val probed = probePeerHello(parsed)
            if (probed == null) {
                return@post json(ctx, 200, JSONObject()
                    .put("ok", false)
                    .put("online", false)
                    .put("host", parsed.host)
                    .put("message", "无法连接该设备，请确认设备已开机且在同一局域网"))
            }
            PeerDiscovery.recordProbedPeer(probed)
            val peerJson = probed.toJson()
            json(ctx, 200, JSONObject()
                .put("ok", true)
                .put("online", true)
                .put("host", probed.host)
                .put("id", probed.id)
                .put("name", probed.name)
                .put("ip", probed.ip)
                .put("port", probed.port)
                .put("version", probed.version)
                .put("isTv", probed.isTv)
                .put("questions", probed.questions)
                .put("peer", peerJson))
        }
        router.post("/api/peers/sync") { ctx ->
            val input = JSONObject(ctx.body)
            val targets = input.optJSONArray("targets")
            require(targets != null && targets.length() in 1..20) { "请选择 1–20 台目标设备" }
            val scopesRaw = mutableListOf<String>()
            input.optJSONArray("scopes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) scopesRaw.add(s)
                }
            }
            val scopes = LocalStore.normalizeSyncScopes(scopesRaw)
            require(scopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
            val mode = input.optString("mode", "merge").trim().lowercase(Locale.US)
            require(mode in listOf("merge", "replace")) { "同步模式仅支持 merge 或 replace" }
            val defaultPassword = input.optString("defaultPassword", "")

            DeviceRuntime.learning.flushUsage()
            val scopedPayload = store.exportScopedSync(scopes)
            val selfIp = localIp()
            val results = JSONArray()
            var allOk = true

            for (i in 0 until targets.length()) {
                val t = targets.optJSONObject(i) ?: continue
                val rawHost = t.optString("host").trim()
                val hintedName = t.optString("name").trim()
                val parsed = PeerDiscovery.parsePrivateLanHost(rawHost)
                if (parsed == null) {
                    allOk = false
                    results.put(JSONObject()
                        .put("host", rawHost)
                        .put("name", hintedName)
                        .put("ok", false)
                        .put("message", "仅支持局域网私有 IPv4 地址及合法端口（1024–65535）"))
                    continue
                }
                if (selfIp != null && parsed.ip == selfIp && parsed.port == boundPort) {
                    allOk = false
                    results.put(JSONObject()
                        .put("host", parsed.host)
                        .put("name", Prefs.deviceName(context))
                        .put("ok", false)
                        .put("message", "无需同步到当前本机设备"))
                    continue
                }
                val knownPeer = PeerDiscovery.listPeers().firstOrNull { it.ip == parsed.ip && it.port == parsed.port }
                val targetName = hintedName.ifEmpty { knownPeer?.name ?: parsed.host }
                val password = t.optString("password", "").ifEmpty { defaultPassword }
                if (password.isEmpty()) {
                    allOk = false
                    results.put(JSONObject()
                        .put("host", parsed.host)
                        .put("name", targetName)
                        .put("ok", false)
                        .put("message", "需要输入该台设备的管理密码"))
                    continue
                }
                val syncResult = syncToPeer(parsed, targetName, password, scopedPayload, scopes, mode)
                if (!syncResult.optBoolean("ok")) allOk = false
                results.put(syncResult)
            }
            json(ctx, 200, JSONObject().put("ok", allOk).put("results", results))
        }
        router.get("/api/children") { ctx ->
            json(ctx, 200, JSONObject().put("items", store.children()).put("active", store.activeChild()))
        }
        router.post("/api/children") { ctx ->
            val input = JSONObject(ctx.body)
            val child = store.createChild(input.getString("name"), extractOptionalPin(input))
            json(ctx, 200, JSONObject().put("ok", true).put("child", child).put("items", store.children()))
        }
        router.put("/api/children") { ctx ->
            val input = JSONObject(ctx.body)
            store.updateChild(input.getString("id"), input.getString("name"), extractOptionalPin(input))
            json(ctx, 200, JSONObject().put("ok", true).put("items", store.children()))
        }
        router.post("/api/children/switch") { ctx ->
            val input = JSONObject(ctx.body)
            val targetId = input.optString("id")
            val decision = evaluateChildSwitch(
                targetId = targetId,
                activeId = store.activeChild(),
                isParent = ctx.parent,
                targetHasPin = store.childHasPin(targetId),
                pin = extractOptionalPin(input),
                verifyChildPin = { store.verifyChildPin(targetId, it) },
                verifyParentPassword = { AdminAuth.verify(context, it) }
            )
            if (!decision.allowed) {
                return@post json(
                    ctx,
                    403,
                    err(decision.errorCode ?: "child_pin_invalid")
                        .put("message", decision.message ?: "切换密码错误，请重试")
                )
            }
            DeviceRuntime.learning.switchChild(targetId)
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("ok", true).put("items", store.children())
                .put("active", store.activeChild()))
        }
        router.post("/api/children/delete") { ctx ->
            DeviceRuntime.learning.deleteChild(JSONObject(ctx.body).optString("id"))
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("ok", true).put("items", store.children())
                .put("active", store.activeChild()))
        }
        router.get("/api/holidays") { ctx ->
            json(ctx, 200, HolidayService.info(context))
        }
        router.post("/api/holidays/sync") { ctx ->
            val year = ctx.query["year"]?.toIntOrNull() ?: DeviceClock.calendar(store).get(java.util.Calendar.YEAR)
            try {
                json(ctx, 200, HolidayService.fetch(context, year))
            } catch (e: Exception) {
                json(ctx, 200, err("holiday_failed").put("message", e.message ?: "节假日同步失败"))
            }
        }
        router.put("/api/config") { ctx ->
            val input = try { JSONObject(ctx.body) } catch (_: Exception) { return@put json(ctx, 200, err("bad_json")) }
            val config = DeviceRuntime.learning.saveConfig(input)
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("config", config))
        }
        router.post("/api/questions/generate") { ctx ->
            val input = try { JSONObject(ctx.body.ifEmpty { "{}" }) } catch (_: Exception) { return@post json(ctx, 200, err("bad_json")) }
            try {
                json(ctx, 200, GradingService.generateQuestions(store, input))
            } catch (e: Exception) {
                json(ctx, 200, err("generate_failed").put("message", e.message ?: "生成失败，请稍后重试"))
            }
        }
        router.get("/api/questions") { ctx ->
            val q = ctx.query
            val all = q["all"] == "1" || q["enabledOnly"] == "false"
            json(ctx, 200, store.questionPage(
                subject = q["subject"], level = q["level"], bank = q["bank"], search = q["search"],
                type = q["type"], difficulty = q["difficulty"]?.toIntOrNull(),
                wrongOnly = q["wrong"] == "1", enabledOnly = !all,
                sortBy = q["sort"] ?: "random",
                limit = q["limit"]?.toIntOrNull() ?: 500,
                offset = q["offset"]?.toIntOrNull() ?: 0,
                inlineMedia = q["inlineMedia"] == "1"
            ))
        }
        router.get("/api/questions/meta") { ctx ->
            json(ctx, 200, store.questionMeta())
        }
        router.get("/api/coverage") { ctx ->
            val gradeSubjects = LocalStore.parseGradeSubjects(store.config().optJSONObject("gradeSubjects"))
            json(ctx, 200, store.coverage(ctx.query["sample"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20, gradeSubjects = gradeSubjects))
        }
        router.get("/api/wrong-questions") { ctx ->
            val q = ctx.query
            json(ctx, 200, store.listWrongQuestions(
                subject = q["subject"],
                level = q["level"],
                bank = q["bank"],
                q = q["q"]
            ))
        }
        router.post("/api/wrong-questions/master") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            val all = input.optBoolean("all", false)
            val idsArr = input.optJSONArray("ids")
            val ids = if (idsArr != null) (0 until idsArr.length()).map { idsArr.optString(it) }.filter { it.isNotBlank() } else emptyList()
            val updated = store.markWrongMastered(ids = ids, all = all)
            json(ctx, 200, JSONObject().put("ok", true).put("updated", updated).put("study", store.studySummary()))
        }
        router.post("/api/wrong-questions/extra-pool") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            val idsArr = input.optJSONArray("ids") ?: JSONArray()
            val ids = (0 until idsArr.length()).map { idsArr.optString(it) }.filter { it.isNotBlank() }
            val result = DeviceRuntime.learning.addWrongToExtraPool(ids)
            GateService.refreshNow()
            json(ctx, 200, result)
        }
        router.post("/api/extra-pool/clear") { ctx ->
            val result = DeviceRuntime.learning.clearExtraPool()
            GateService.refreshNow()
            json(ctx, 200, result)
        }
        router.post("/api/questions/pause") { ctx ->
            val input = JSONObject(ctx.body)
            val ids = input.getJSONArray("ids")
            val enabled = input.optBoolean("enabled", false)
            require(ids.length() in 1..50000) { "请选择题目" }
            val updated = DeviceRuntime.learning.setQuestionsEnabled((0 until ids.length()).map { ids.getString(it) }, enabled)
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("ok", true).put("updated", updated))
        }
        router.post("/api/questions") { ctx ->
            val payload = try { JSONObject(ctx.body) } catch (_: Exception) { return@post json(ctx, 200, err("bad_json")) }
            if (payload.has("items")) {
                val result = store.importQuestions(payload.getJSONArray("items"), payload.optBoolean("skipDuplicates"))
                return@post json(ctx, 200, JSONObject().put("ok", true)
                    .put("added", result.getInt("added")).put("skipped", result.getInt("skipped")))
            }
            try {
                val id = store.upsertQuestion(payload)
                store.logEvent("question_added", JSONObject().put("id", id))
                json(ctx, 200, JSONObject().put("ok", true).put("id", id))
            } catch (e: IllegalArgumentException) {
                json(ctx, 200, JSONObject().put("error", "bad_question").put("message", e.message ?: ""))
            }
        }
        router.post("/api/system-settings") { ctx ->
            json(ctx, 200, JSONObject().put("ok", DeviceBridge.openSystemSettings(context)))
        }
        // (二改) 一键授权 / 星星屋积分同步 / 保活调参
        router.get("/api/grant") { ctx -> json(ctx, 200, SelfGrant.status(context)) }
        router.post("/api/grant/run") { ctx -> SelfGrant.runAsync(context); json(ctx, 200, SelfGrant.status(context)) }
        router.get("/api/points") { ctx -> json(ctx, 200, PointsSync.summary(store)) }
        router.put("/api/points/settings") { ctx ->
            try { json(ctx, 200, PointsSync.save(store, JSONObject(ctx.body))) }
            catch (e: IllegalArgumentException) { json(ctx, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
            catch (e: java.io.IOException) { json(ctx, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
        }
        router.post("/api/points/children") { ctx ->
            try { json(ctx, 200, PointsSync.listChildren(store, JSONObject(ctx.body))) }
            catch (e: IllegalArgumentException) { json(ctx, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
            catch (e: java.io.IOException) { json(ctx, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
        }
        router.post("/api/points/sync") { ctx -> PointsSync.syncNow(store); json(ctx, 200, PointsSync.summary(store)) }
        router.get("/api/tune") { ctx -> json(ctx, 200, Tune.summary(store)) }
        router.put("/api/tune/settings") { ctx -> json(ctx, 200, Tune.save(store, JSONObject(ctx.body))) }
        router.get("/api/speech") { ctx -> json(ctx, 200, SpeechReader.settings(store)) }
        router.put("/api/speech") { ctx -> json(ctx, 200, SpeechReader.save(store, JSONObject(ctx.body))) }
        // #128 本地语音诊断：引擎列表只查 PackageManager；探测会阻塞最多 3 秒（路由本来就跑在 worker 线程）
        router.get("/api/speech/engines") { ctx ->
            json(ctx, 200, JSONObject().put("engines", TtsProbe.installedEngines(context))
                .put("defaultEngine", TtsProbe.defaultEngine(context)))
        }
        router.get("/api/speech/probe") { ctx ->
            json(ctx, 200, TtsProbe.probe(context, ctx.query["language"].orEmpty().ifBlank { "zh-CN" }))
        }
        router.post("/api/speech/install-voice") { ctx ->
            json(ctx, 200, JSONObject().put("ok", TtsProbe.openInstallVoiceData(context)))
        }
        router.post("/api/speech/test") { ctx ->
            val text = try { JSONObject(ctx.body.ifEmpty { "{}" }).optString("text").trim() } catch (_: Exception) { "" }
            SpeechReader.read(context, text.ifBlank { "今天天气不错" }.take(200))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 800 && SpeechReader.status().optString("reason") == "idle") Thread.sleep(50)
            json(ctx, 200, SpeechReader.status())
        }
        router.get("/api/bank-automation") { ctx -> json(ctx, 200, BankAutomation.settings(store)) }
        router.put("/api/bank-automation") { ctx -> json(ctx, 200, BankAutomation.save(store, JSONObject(ctx.body))) }
        router.get("/api/banks/market") { ctx -> json(ctx, 200, BankMarket.market(store)) }
        router.post("/api/banks/sources") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx, 200, BankMarket.addSource(store, payload.optString("name"), payload.optString("url")))
        }
        router.delete("/api/banks/sources") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx, 200, BankMarket.removeSource(store, payload.optString("url")))
        }
        router.post("/api/banks/fetch") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx, 200, JSONObject().put("ok", true).put("data", BankMarket.json(payload.optString("url"))))
        }
        router.post("/api/bank-automation/sync") { ctx ->
            BankAutomation.start(store)
            json(ctx, 200, BankAutomation.settings(store))
        }
        router.delete("/api/questions/bank") { ctx ->
            val input = JSONObject(ctx.body)
            require(input.optBoolean("confirm")) { "请确认删除题库" }
            val deleted = DeviceRuntime.learning.clearQuestions(input.getString("bank"))
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("deleted", deleted))
        }
        router.post("/api/questions/clear") { ctx ->
            require(JSONObject(ctx.body).optBoolean("confirm")) { "请确认清空全部题库" }
            val deleted = DeviceRuntime.learning.clearQuestions()
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("deleted", deleted))
        }
        router.post("/api/questions/delete") { ctx ->
            val ids = JSONObject(ctx.body).getJSONArray("ids")
            require(ids.length() in 1..50000) { "请选择要删除的题目" }
            val deleted = DeviceRuntime.learning.deleteQuestions((0 until ids.length()).map { ids.getString(it) })
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("ok", true).put("deleted", deleted))
        }
        router.prefixDelete("/api/questions/") { ctx ->
            val id = decodePathSegment(ctx.path.removePrefix("/api/questions/"))
            val deleted = DeviceRuntime.learning.deleteQuestions(listOf(id))
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("ok", deleted > 0))
        }
        router.get("/api/history") { ctx ->
            DeviceRuntime.learning.flushUsage()
            val days = ctx.query["days"]?.toIntOrNull() ?: 7
            json(ctx, 200, JSONObject().put("items", store.historyDays(days)))
        }
        router.get("/api/events") { ctx ->
            json(ctx, 200, JSONObject().put("items", store.recentEvents(ctx.query["limit"]?.toIntOrNull() ?: 50)))
        }
        router.get("/api/grading") { ctx -> json(ctx, 200, GradingService.settings(store).put("providers", GradingService.providers())) }
        router.post("/api/grading/test") { ctx -> json(ctx, 200, GradingService.test(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
        router.post("/api/grading/models") { ctx -> json(ctx, 200, GradingService.models(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
        router.get("/api/update") { ctx -> json(ctx, 200, Updater.status(context)) }
        router.put("/api/update") { ctx -> json(ctx, 200, Updater.saveSettings(context, JSONObject(ctx.body))) }
        router.post("/api/update/check") { ctx ->
            Updater.startCheck(context)
            json(ctx, 200, Updater.status(context))
        }
        router.post("/api/update/install") { ctx ->
            val (code, payload) = Updater.startRemoteInstall(context)
            json(ctx, code, payload)
        }
        router.post("/api/password") { ctx ->
            val input = JSONObject(ctx.body)
            if (!AdminAuth.verify(context, input.optString("current"))) {
                return@post json(ctx, 403, err("wrong_password").put("message", "当前密码不正确，连续错误 5 次后需等待一分钟"))
            }
            AdminAuth.setPassword(context, input.optString("password"))
            val newToken = AdminAuth.issueSession(remember = false, context = context)
            json(ctx, 200, JSONObject().put("ok", true).put("token", newToken))
        }
        router.post("/api/logout") { ctx ->
            AdminAuth.forget(context, ctx.token)
            json(ctx, 200, JSONObject().put("ok", true))
        }
        router.post("/api/remote") { ctx ->
            val parent = ctx.parent
            val control = ctx.integration?.control == true
            if (!parent && !control) return@post json(ctx, 403, err("forbidden").put("message", "请先用管理密码登录"))
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            val action = input.optString("action")
            if (action == "clear_extra") {
                val res = DeviceRuntime.learning.clearExtraPool()
                store.logEvent("remote", JSONObject().put("action", action).put("cleared", res.optLong("cleared", 0L)).put("source", if (parent) "panel" else "integration"))
                GateService.refreshNow()
                return@post json(ctx, 200, JSONObject().put("learning", res.getJSONObject("learning")).put("cleared", res.getLong("cleared")))
            }
            if (action == "credit") {
                val value = input.getDouble("minutes")
                require(value.isFinite() && value == value.toInt().toDouble()) { "调整时长需要整数分钟" }
            }
            val status = DeviceRuntime.learning.remote(action, input.optInt("minutes"), if (parent) "panel" else "integration")
            GateService.refreshNow()
            json(ctx, 200, JSONObject().put("learning", status))
        }
        router.get("/api/integration") { ctx -> json(ctx, 200, IntegrationAuth.state(context)) }
        router.post("/api/integration") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            val read = input.optBoolean("read", true)
            val control = input.optBoolean("control", false)
            if (!read && !control) return@post json(ctx, 200, err("bad_scope").put("message", "至少选择只读或控制中的一种权限"))
            val token = IntegrationAuth.issue(context, read, control)
            json(ctx, 200, JSONObject().put("token", token).put("integration", IntegrationAuth.state(context)))
        }
        router.put("/api/integration") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            IntegrationAuth.update(context,
                if (input.has("enabled")) input.optBoolean("enabled") else null,
                if (input.has("read")) input.optBoolean("read") else null,
                if (input.has("control")) input.optBoolean("control") else null)
            json(ctx, 200, IntegrationAuth.state(context))
        }
        router.delete("/api/integration") { ctx ->
            IntegrationAuth.revoke(context)
            json(ctx, 200, IntegrationAuth.state(context))
        }
        router.get("/api/logs") { ctx ->
            json(ctx, 200, JSONObject().put("lines", Diag.read(context, ctx.query["limit"]?.toIntOrNull()?.coerceIn(50, 3000) ?: 800, ctx.query["q"] ?: "")))
        }
        router.delete("/api/logs") { ctx ->
            Diag.clear(context)
            json(ctx, 200, JSONObject().put("ok", true))
        }
        router.get("/api/logs/export") { ctx ->
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
            reply(ctx.out, 200, "application/zip", Diag.export(context), mapOf("Content-Disposition" to "attachment; filename=\"studyontv-logs-$stamp.zip\""), ctx.headers["origin"])
        }
        router.get("/api/device") { ctx ->
            val clock = store.clockReading()
            json(ctx, 200, JSONObject().put("name", Prefs.deviceName(context))
                .put("version", BuildConfig.VERSION_NAME).put("flavor", BuildConfig.FLAVOR).put("localOnly", LocalOnlyGuard.enabled).put("ip", localIp() ?: "").put("port", boundPort)
                .put("uninstallProtected", UninstallGuard.active(context)).put("deviceOwner", UninstallGuard.owner(context))
                .put("launcherHidden", LauncherIcon.hidden(context)).put("desktop", DesktopMode.state(context)).put("autostart", Watchdog.report(context)).put("monitor", GateService.diagnostics(context))
                // 家长在别的时区时，面板要能看出「设备现在几点」到底按哪个时区算
                .put("timezone", DeviceClock.describe(store, clock.millis))
                .put("deviceTime", DeviceClock.calendar(store, clock.millis).time.toString())
                .put("clockSource", clock.source.id)
                .put("keepalive", KeepAlive.report(context)).put("buglyConsent", !LocalOnlyGuard.enabled && Prefs.buglyConsent(context))
                .put("crashUpload", crashUploadState(context)))
        }
        router.put("/api/device/crash") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            if (input.has("consent")) Prefs.setCrashUploadConsent(context, input.optBoolean("consent"))
            if (input.has("endpoint")) {
                val endpoint = input.optString("endpoint").trim()
                require(endpoint.isEmpty() || endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "上传地址需要以 http:// 或 https:// 开头" }
                Prefs.setCrashEndpoint(context, endpoint)
            }
            if (input.has("token")) Prefs.setCrashToken(context, input.optString("token").trim())
            json(ctx, 200, JSONObject().put("ok", true).put("crashUpload", crashUploadState(context)))
        }
        router.put("/api/device/bugly") { ctx ->
            if (LocalOnlyGuard.enabled) {
                BuglyInit.revoke(context)
                json(ctx, 200, JSONObject().put("ok", true).put("buglyConsent", false).put("localOnly", true))
            } else {
                val enabled = JSONObject(ctx.body).optBoolean("enabled")
                if (enabled) BuglyInit.initWithConsent(context) else BuglyInit.revoke(context)
                json(ctx, 200, JSONObject().put("ok", true).put("buglyConsent", Prefs.buglyConsent(context)))
            }
        }
        router.delete("/api/device/uninstall-guard") { ctx ->
            try {
                UninstallGuard.setEnabled(context, false)
                json(ctx, 200, JSONObject().put("ok", true).put("uninstallProtected", false))
            } catch (e: Exception) {
                json(ctx, 500, err(e.message ?: "failed"))
            }
        }
        router.put("/api/device/uninstall-guard") { ctx ->
            UninstallGuard.setEnabled(context, true)
            json(ctx, 200, JSONObject().put("ok", true).put("uninstallProtected", UninstallGuard.active(context)))
        }
        router.put("/api/device/desktop") { ctx ->
            val enabled = JSONObject(ctx.body).optBoolean("enabled")
            try {
                json(ctx, 200, DesktopMode.setEnabled(context, enabled))
            } catch (e: Exception) {
                json(ctx, 500, err(e.message ?: "切换电视桌面失败"))
            }
        }
        router.post("/api/device/desktop/choose") { ctx ->
            DesktopMode.openChooser(context)
            json(ctx, 200, DesktopMode.state(context))
        }
        router.put("/api/device/launcher") { ctx ->
            val hidden = LauncherIcon.setHidden(context, JSONObject(ctx.body).optBoolean("hidden"))
            json(ctx, 200, JSONObject().put("ok", true).put("launcherHidden", hidden))
        }
        router.put("/api/device") { ctx ->
            val name = JSONObject(ctx.body).optString("name").trim()
            require(name.length in 1..32) { "设备名称需要 1–32 个字" }
            Prefs.setDeviceName(context, name)
            json(ctx, 200, JSONObject().put("ok", true).put("name", name))
        }
        router.put("/api/grading") { ctx -> json(ctx, 200, GradingService.save(store, JSONObject(ctx.body))) }
        router.get("/api/reviews") { ctx ->
            val items = store.reviews(ctx.query["state"] ?: "pending", ctx.query["child"]?.takeIf { it.isNotEmpty() })
            // 手写图已经以 /api/media/<sha256>.png 短路径落盘，summary 视图直接标上 hasDrawing 并去掉路径字段
            if (ctx.query["view"] == "summary") for (i in 0 until items.length()) {
                val input = items.getJSONObject(i).optJSONObject("input") ?: continue
                if (input.optString("drawing").isNotEmpty()) input.put("hasDrawing", true)
                input.remove("drawing")
            }
            json(ctx, 200, JSONObject().put("items", items))
        }
        router.prefixGet("/api/reviews/") { ctx ->
            val id = decodePathSegment(ctx.path.removePrefix("/api/reviews/").substringBefore('/'))
            val review = store.review(id) ?: return@prefixGet json(ctx, 404, err("not_found"))
            json(ctx, 200, review)
        }
        router.prefixPost("/api/reviews/") { ctx ->
            val id = decodePathSegment(ctx.path.removePrefix("/api/reviews/").substringBefore('/'))
            if (ctx.path.endsWith("/retry")) {
                require(store.review(id) != null) { "题目不存在" }
                GradingService.enqueue(store, DeviceRuntime.learning, id)
                return@prefixPost json(ctx, 200, JSONObject().put("ok", true))
            }
            val decision = JSONObject(ctx.body)
            json(ctx, 200, DeviceRuntime.learning.grade(id, decision.getDouble("score"), decision.optString("feedback"), "parent_or_agent"))
        }
        router.post("/api/quiz/start") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            json(ctx, 200, DeviceRuntime.learning.start(JSONObject(ctx.body.ifBlank { "{}" })))
        }
        router.post("/api/quiz/memorized") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            val payload = JSONObject(ctx.body.ifBlank { "{}" })
            json(ctx, 200, DeviceRuntime.learning.memorized(payload.optString("roundId")))
        }
        router.post("/api/quiz/rate") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            val payload = JSONObject(ctx.body)
            json(ctx, 200, DeviceRuntime.learning.rateFlashcard(
                payload.optString("roundId"),
                payload.getString("qid"),
                payload.optInt("quality", 4)
            ))
        }
        router.post("/api/quiz/continue") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            json(ctx, 200, DeviceRuntime.learning.continueFeedback())
        }
        router.post("/api/quiz/review") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            json(ctx, 200, DeviceRuntime.learning.acknowledgeReview(JSONObject(ctx.body).getString("reviewId")))
        }
        router.post("/api/quiz/skip") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            json(ctx, 200, DeviceRuntime.learning.skipPending())
        }
        router.post("/api/quiz/answer") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
            val result = DeviceRuntime.learning.answer(JSONObject(ctx.body))
            Diag.info("Quiz", "answer correct=${result.optBoolean("correct")} finished=${result.optBoolean("finished")} pending=${result.optBoolean("pending")} reward=${result.optDouble("rewardMinutes", 0.0)}")
            if (result.optBoolean("pending")) GradingService.enqueue(store, DeviceRuntime.learning, result.getString("reviewId"))
            json(ctx, 200, result)
        }
        router.post("/api/event") { ctx ->
            val payload = try { JSONObject(ctx.body) } catch (_: Exception) { JSONObject() }
            val type = payload.optString("type")
            if (type != "study") return@post json(ctx, 403, err("event_managed_by_device"))
            store.logEvent(type, payload)
            json(ctx, 200, JSONObject().put("ok", true))
        }
    }

    private val gzipCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val etag = "\"" + BuildConfig.VERSION_CODE + "-" + (try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }) + "\""

    private fun serveStatic(path: String, out: OutputStream, gzip: Boolean, ifNoneMatch: String?, local: Boolean, origin: String?) {
        val (assetPath, contentType) = when {
            path == "/practice" || path == "/practice.html" -> "web/practice.html" to "text/html; charset=utf-8"
            path == "/practice-sw.js" -> "web/practice-sw.js" to "application/javascript; charset=utf-8"
            path == "/practice.appcache" -> "web/practice.appcache" to "text/cache-manifest; charset=utf-8"
            path == "/" || path == "/index.html" -> if (local) "web/index.html" to "text/html; charset=utf-8"
            else "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/admin/assets/") || path.startsWith("/admin/icons/") ||
                path == "/admin/manifest.webmanifest" || path == "/admin/sw.js" -> "admin" + path.removePrefix("/admin") to mime(path)
            path == "/admin" -> return reply(out, 301, null, null, mapOf("Location" to "/admin/"), origin)
            path.startsWith("/admin") -> "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/assets/") -> "web" + path to mime(path)
            else -> if (local) "web/index.html" to "text/html; charset=utf-8" else "admin/index.html" to "text/html; charset=utf-8"
        }
        if (assetPath.contains("..")) return reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = origin)
        val hashed = Regex("-[A-Za-z0-9_-]{8}\\.(js|css)$").containsMatchIn(assetPath) || assetPath.contains("/vendor/")
        val cache = if (hashed) "public, max-age=31536000, immutable" else "no-cache"
        if (ifNoneMatch == etag) return reply(out, 304, null, null, mapOf("ETag" to etag, "Cache-Control" to cache), origin)
        try {
            val compressible = contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json") || contentType.contains("svg")
            val extra = mutableMapOf("ETag" to etag, "Cache-Control" to cache)
            val bytes = if (gzip && compressible) {
                extra["Content-Encoding"] = "gzip"; extra["Vary"] = "Accept-Encoding"
                gzipCache.getOrPut(assetPath) {
                    val raw = assetBytes(assetPath)
                    ByteArrayOutputStream().also { buf -> java.util.zip.GZIPOutputStream(buf).use { it.write(raw) } }.toByteArray()
                }
            } else assetBytes(assetPath)
            reply(out, 200, contentType, bytes, extra, origin)
        } catch (_: Exception) {
            reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = origin)
        }
    }

    private fun assetBytes(assetPath: String): ByteArray {
        val raw = assets.open(assetPath).use { it.readBytes() }
        if (assetPath != "admin/index.html") return raw
        val text = String(raw, Charsets.UTF_8)
        if (text.contains("<base ")) return raw
        return text.replaceFirst("<head>", "<head><base href=\"/admin/\">").toByteArray(Charsets.UTF_8)
    }

    private fun mime(path: String): String = when {
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".webmanifest") -> "application/manifest+json; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun crashUploadState(context: Context) = JSONObject()
        .put("consent", Prefs.crashUploadConsent(context))
        .put("endpoint", Prefs.crashEndpointStored(context))
        .put("builtInEndpoint", BuildConfig.CRASH_ENDPOINT)
        .put("hasToken", Prefs.crashToken(context).isNotBlank())
        .put("builtInToken", BuildConfig.CRASH_TOKEN.isNotBlank())
        .put("pending", CrashDetector.hasPending(context))

    private fun json(ctx: RouteContext, code: Int, obj: JSONObject) =
        json(ctx.out, code, obj, ctx.headers["origin"])

    private fun json(out: OutputStream, code: Int, obj: JSONObject, origin: String? = null) =
        reply(out, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8), origin = origin)

    private fun err(code: String) = JSONObject().put("error", code)

    private fun reply(
        out: OutputStream,
        code: Int,
        contentType: String?,
        bytes: ByteArray?,
        extra: Map<String, String> = emptyMap(),
        origin: String? = null
    ) {
        val body = bytes ?: ByteArray(0)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (!extra.containsKey("Cache-Control")) head.append("Cache-Control: no-store\r\n")
        val corsOrigin = allowedCorsOrigin(origin)
        extra.forEach { (k, v) ->
            if (k.equals("Vary", ignoreCase = true) && corsOrigin != null && corsOrigin != "*") {
                head.append("Vary: ").append(v).append(", Origin\r\n")
            } else {
                head.append(k).append(": ").append(v).append("\r\n")
            }
        }
        if (corsOrigin != null) {
            head.append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n")
            if (corsOrigin != "*" && extra.keys.none { it.equals("Vary", ignoreCase = true) }) {
                head.append("Vary: Origin\r\n")
            }
            head.append("Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\n")
            head.append("Access-Control-Allow-Headers: Content-Type,X-Admin-Token,Authorization,X-Admin-Key,X-Device-Token\r\n")
            head.append("Access-Control-Allow-Private-Network: true\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size, Charsets.UTF_8)
            }
            if (buf.size() >= 8192) throw IllegalArgumentException("Header line too long")
            buf.write(b)
        }
    }

    private fun readBody(input: BufferedInputStream, length: Int): String = readExactBody(input, length)

    private fun probePeerHello(target: PeerDiscovery.ParsedLanHost): PeerDiscovery.PeerInfo? {
        val (code, body) = lanHttpRequest(
            target = target,
            method = "GET",
            path = "/api/hello",
            connectTimeoutMs = 2500,
            readTimeoutMs = 3000
        ) ?: return null
        if (code != 200) return null
        val obj = try { JSONObject(body) } catch (_: Exception) { return null }
        if (obj.optString("app") != "StudyOnTV") return null
        val id = obj.optString("id").trim().take(32).ifEmpty { target.host }
        val name = obj.optString("name").trim().take(32).ifEmpty { target.host }
        val version = obj.optString("version").trim().take(24)
        val isTv = obj.optBoolean("isTv", true)
        val questions = obj.optInt("questions", 0).coerceAtLeast(0)
        return PeerDiscovery.PeerInfo(
            id = id,
            name = name,
            ip = target.ip,
            port = target.port,
            version = version,
            isTv = isTv,
            questions = questions,
            lastSeenAt = System.currentTimeMillis()
        )
    }

    private fun syncToPeer(
        target: PeerDiscovery.ParsedLanHost,
        initialName: String,
        password: String,
        scopedPayload: JSONObject,
        scopes: Set<String>,
        mode: String
    ): JSONObject {
        val resolvedName = try {
            probePeerHello(target)?.name?.takeIf { it.isNotBlank() } ?: initialName
        } catch (_: Exception) {
            initialName
        }

        val authBody = JSONObject()
            .put("passphrase", password)
            .put("remember", false)
            .toString()
        val authResp = lanHttpRequest(
            target = target,
            method = "POST",
            path = "/api/auth",
            body = authBody,
            connectTimeoutMs = 3000,
            readTimeoutMs = 5000
        ) ?: return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", "无法连接目标设备，请确认设备已开机且在同一局域网")

        if (authResp.first == 401) {
            return JSONObject()
                .put("host", target.host)
                .put("name", resolvedName)
                .put("ok", false)
                .put("message", "管理密码错误，需要输入该台设备的管理密码")
        }
        if (authResp.first !in 200..299) {
            val remoteMsg = try { JSONObject(authResp.second).optString("message") } catch (_: Exception) { "" }
            return JSONObject()
                .put("host", target.host)
                .put("name", resolvedName)
                .put("ok", false)
                .put("message", remoteMsg.ifBlank { "目标设备鉴权失败（HTTP ${authResp.first}）" })
        }
        val remoteToken = try { JSONObject(authResp.second).optString("token") } catch (_: Exception) { "" }
        if (remoteToken.isBlank()) {
            return JSONObject()
                .put("host", target.host)
                .put("name", resolvedName)
                .put("ok", false)
                .put("message", "目标设备未返回有效会话令牌")
        }

        val restoreBody = JSONObject()
            .put("backup", scopedPayload)
            .put("mode", mode)
            .put("scopes", JSONArray(scopes.sorted()))
            .toString()
        val restoreResp = lanHttpRequest(
            target = target,
            method = "POST",
            path = "/api/backup/restore",
            body = restoreBody,
            bearerToken = remoteToken,
            connectTimeoutMs = 4000,
            readTimeoutMs = 20000
        ) ?: return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", "同步传输中断，请稍后重试")

        if (restoreResp.first !in 200..299) {
            val remoteMsg = try { JSONObject(restoreResp.second).optString("message") } catch (_: Exception) { "" }
            return JSONObject()
                .put("host", target.host)
                .put("name", resolvedName)
                .put("ok", false)
                .put("message", remoteMsg.ifBlank { "目标设备同步失败（HTTP ${restoreResp.first}）" })
        }
        val restoredJson = try { JSONObject(restoreResp.second) } catch (_: Exception) { JSONObject() }
        val syncedQ = restoredJson.optInt("syncedQuestions", restoredJson.optInt("questions", 0))
        val totalQ = restoredJson.optInt("questions", syncedQ)
        val summary = formatSyncSuccessMessage(scopes, mode, syncedQ, totalQ)
        return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", true)
            .put("message", summary)
    }

    private fun lanHttpRequest(
        target: PeerDiscovery.ParsedLanHost,
        method: String,
        path: String,
        body: String? = null,
        bearerToken: String? = null,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): Pair<Int, String>? {
        if (!PeerDiscovery.isPrivateLanIp(target.ip) || target.port !in 1024..65535) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http", target.ip, target.port, path)
            conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = if (stream != null) {
                stream.use { ins ->
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    var total = 0
                    while (total < 65_536) {
                        val n = ins.read(chunk)
                        if (n <= 0) break
                        val take = minOf(n, 65_536 - total)
                        buf.write(chunk, 0, take)
                        total += take
                    }
                    buf.toString("UTF-8")
                }
            } else ""
            code to text
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    fun localIp(): String? = try {
        val lanIp = try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            connectivity.allNetworks.asSequence().mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ||
                    !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) null
                else connectivity.getLinkProperties(network)?.linkAddresses?.asSequence()
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            }.firstOrNull()
        } catch (_: Exception) { null }
        lanIp ?: NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint &&
                !it.name.startsWith("tun") && !it.name.startsWith("tap") && !it.name.startsWith("wg") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
