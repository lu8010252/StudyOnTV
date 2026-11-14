package com.studyontv.tv

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

















class LanServer(
    private val context: Context,
    private val store: LocalStore,
    private val assets: AssetManager,
    private val port: Int = 8099
) {
    private var socket: ServerSocket? = null
    // 浏览器会并发 6 个连接加载面板，线程太少会排队；静态文件已缓存压缩，CPU 开销很小
    private val pool = Executors.newFixedThreadPool(if (BuildConfig.FLAVOR == "tv") 4 else 6)
    @Volatile private var running = false
    var boundPort: Int = port
        private set

    fun start() {
        if (running) return
        running = true
        if (!open()) Diag.error("Server", "无法监听 $port / 8090，稍后自动重试")
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
        try { socket?.close() } catch (e: Exception) { }
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
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..4000000) return reply(c.getOutputStream(), 413, "text/plain", "Request too large".toByteArray())
                val body = if (length > 0) readBody(input, length) else ""

                val out = c.getOutputStream()
                if (method == "OPTIONS") return reply(out, 204, null, null)
                val local = c.inetAddress?.isLoopbackAddress ?: false
                route(method, path, query, headers, body, out, local)
            } catch (e: Exception) {
                
            }
        }
    }

    private fun route(method: String, path: String, query: String, headers: Map<String, String>, body: String, out: OutputStream, local: Boolean) {
        val q = if (query.isEmpty()) emptyMap() else query.split("&").associate {
            val kv = it.split("=", limit = 2)
            kv[0] to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        val deviceAccess = local && headers["x-device-token"] == AdminAuth.deviceToken
        val token = headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-admin-token"]
        if (path == "/api/auth" && method == "POST") {
            val input = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val remember = input.optBoolean("remember")
            val session = AdminAuth.login(context, input.optString("passphrase"), remember)
            return if (session == null) json(out, 401, err("管理密码错误，连续错误 5 次后需等待一分钟"))
                else json(out, 200, JSONObject().put("token", session).put("expiresIn", if (remember) 30 * 86400 else 43200))
        }
        if (path.startsWith("/api/") && path != "/api/hello") {
            val adminKey = headers["x-admin-key"]
            val integrationToken = token?.takeIf { it.startsWith("sotv_") }
            if (integrationToken != null && !IntegrationAuth.takeBudget())
                return json(out, 429, err("too_many_requests").put("message", "请求太频繁，请稍后再试"))
            val integration = IntegrationAuth.verify(context, integrationToken)
            val parent = AdminAuth.valid(token) || (adminKey != null && AdminAuth.verify(context, adminKey))
            if (!deviceAccess && !parent && integration == null) return json(out, 401, err("unauthorized"))
            // 集成令牌只能访问明确列出的接口；不能改规则、题库、密码或权限本身。
            if (integration != null && !deviceAccess && !parent) {
                val scope = when {
                    path == "/api/state" && method == "GET" -> "read"
                    path == "/api/device" && method == "GET" -> "read"
                    path == "/api/remote" && method == "POST" -> "control"
                    else -> null
                } ?: return json(out, 403, err("forbidden").put("message", "这个接口需要家长登录"))
                if ((scope == "read" && !integration.read) || (scope == "control" && !integration.control))
                    return json(out, 403, err("forbidden").put("message", "集成令牌没有这个权限"))
            }
        }

        if (method != "GET") Diag.info("HTTP", "$method $path")
        try {
        when {
            path == "/api/hello" && method == "GET" -> {
                val info = JSONObject()
                    .put("app", "StudyOnTV")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("name", Prefs.deviceName(context))
                    .put("ip", localIp() ?: "")
                    .put("port", boundPort)
                    .put("questions", store.countQuestions())
                    .put("needsToken", true)
                    .put("passwordRequired", true)
                return json(out, 200, info)
            }
            path == "/api/state" && method == "GET" -> {
                DeviceRuntime.learning.flushUsage()
                return json(out, 200, JSONObject()
                    .put("config", store.config())
                    .put("today", store.todayStats())
                    .put("questions", store.countQuestions())
                        .put("roundsDoneToday", store.roundsDoneToday())
                        .put("repetition", store.repetitionStats())
                        .put("learning", DeviceRuntime.learning.status())
                    // 多孩子（#132）：设备端要知道「现在是谁在学」和有哪些可选档案
                    .put("children", store.children()).put("activeChild", store.activeChild())
                    .put("speech", JSONObject().put("autoRead", SpeechReader.settings(store).optBoolean("autoRead"))))
            }
            path == "/api/children" && method == "GET" -> return json(out, 200,
                JSONObject().put("items", store.children()).put("active", store.activeChild()))
            path == "/api/children" && method == "POST" -> {
                val child = store.createChild(JSONObject(body).getString("name"))
                return json(out, 200, JSONObject().put("ok", true).put("child", child).put("items", store.children()))
            }
            path == "/api/children" && method == "PUT" -> {
                val input = JSONObject(body)
                store.renameChild(input.getString("id"), input.getString("name"))
                return json(out, 200, JSONObject().put("ok", true).put("items", store.children()))
            }
            path == "/api/children/switch" && method == "POST" -> {
                // 切换前先把还没落库的观看时长记到原孩子名下，否则会算到下一个孩子头上
                DeviceRuntime.learning.flushUsage()
                store.switchChild(JSONObject(body).optString("id"))
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("ok", true).put("items", store.children())
                    .put("active", store.activeChild()))
            }
            path == "/api/children/delete" && method == "POST" -> {
                DeviceRuntime.learning.flushUsage()
                store.deleteChild(JSONObject(body).optString("id"))
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("ok", true).put("items", store.children())
                    .put("active", store.activeChild()))
            }
            path == "/api/holidays" && method == "GET" -> return json(out, 200, HolidayService.info(context))
            path == "/api/holidays/sync" && method == "POST" -> {
                val year = query.split("&").mapNotNull { part -> part.split("=").takeIf { it.size == 2 && it[0] == "year" }?.get(1)?.toIntOrNull() }.firstOrNull()
                    ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                return try {
                    json(out, 200, HolidayService.fetch(context, year))
                } catch (e: Exception) {
                    json(out, 200, err("holiday_failed").put("message", e.message ?: "节假日同步失败"))
                }
            }
            path == "/api/config" && method == "PUT" -> {
                val input = try { JSONObject(body) } catch (e: Exception) { return json(out, 200, err("bad_json")) }
                val config = DeviceRuntime.learning.saveConfig(input)
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("config", config))
            }
            path == "/api/questions/generate" && method == "POST" -> {
                val input = try { JSONObject(body.ifEmpty { "{}" }) } catch (e: Exception) { return json(out, 200, err("bad_json")) }
                return try {
                    json(out, 200, GradingService.generateQuestions(store, input))
                } catch (e: Exception) {
                    json(out, 200, err("generate_failed").put("message", e.message ?: "生成失败，请稍后重试"))
                }
            }
            path == "/api/questions" && method == "GET" -> {
                // all=1 与 enabledOnly=false 等价：都表示「包含已暂停的题目」。
                // 管理端用 enabledOnly=false 拿全量再在本地按「仅显示启用」过滤，
                // 否则暂停的题在页面上完全看不到（用户以为题目丢了）。
                val all = q["all"] == "1" || q["enabledOnly"] == "false"
                val items = store.questions(q["subject"], q["level"], q["limit"]?.toIntOrNull() ?: 500,
                    enabledOnly = !all, sortBy = q["sort"] ?: "random")
                return json(out, 200, JSONObject().put("items", items))
            }
            path == "/api/questions/pause" && method == "POST" -> {
                val ids = JSONObject(body).getJSONArray("ids")
                val enabled = JSONObject(body).optBoolean("enabled", false)
                require(ids.length() in 1..5000) { "请选择题目" }
                return json(out, 200, JSONObject().put("ok", true).put("updated",
                    store.setEnabled((0 until ids.length()).map { ids.getString(it) }, enabled)))
            }
            path == "/api/questions" && method == "POST" -> {
                val payload = try { JSONObject(body) } catch (e: Exception) { return json(out, 200, err("bad_json")) }
                if (payload.has("items")) {
                    // 题库市场一键添加会重复点，允许按题干去重；普通导入默认不去重（覆盖式导入）
                    val result = store.importQuestions(payload.getJSONArray("items"), payload.optBoolean("skipDuplicates"))
                    return json(out, 200, JSONObject().put("ok", true)
                        .put("added", result.getInt("added")).put("skipped", result.getInt("skipped")))
                }
                return try {
                    val id = store.upsertQuestion(payload)
                    store.logEvent("question_added", JSONObject().put("id", id))
                    json(out, 200, JSONObject().put("ok", true).put("id", id))
                } catch (e: IllegalArgumentException) {
                    json(out, 200, JSONObject().put("error", "bad_question").put("message", e.message ?: ""))
                }
            }
            // #130：家长在手机上点一下，让电视 / 投影打开完整系统设置（很多固件藏了菜单）
            path == "/api/system-settings" && method == "POST" -> return json(out, 200,
                JSONObject().put("ok", DeviceBridge.openSystemSettings(context)))
            path == "/api/speech" && method == "GET" -> return json(out, 200, SpeechReader.settings(store))
            path == "/api/speech" && method == "PUT" -> return json(out, 200, SpeechReader.save(store, JSONObject(body)))
            path == "/api/bank-automation" && method == "GET" -> return json(out, 200, BankAutomation.settings(store))
            path == "/api/bank-automation" && method == "PUT" -> return json(out, 200, BankAutomation.save(store, JSONObject(body)))
            // 题库市场：读共享题库的索引，家长点一下就加进本机题库
            path == "/api/banks/market" && method == "GET" -> return json(out, 200, BankMarket.market(store))
            path == "/api/banks/sources" && method == "POST" -> {
                val payload = JSONObject(body.ifEmpty { "{}" })
                return json(out, 200, BankMarket.addSource(store, payload.optString("name"), payload.optString("url")))
            }
            path == "/api/banks/sources" && method == "DELETE" -> {
                val payload = JSONObject(body.ifEmpty { "{}" })
                return json(out, 200, BankMarket.removeSource(store, payload.optString("url")))
            }
            path == "/api/banks/fetch" && method == "POST" -> {
                val payload = JSONObject(body.ifEmpty { "{}" })
                return json(out, 200, JSONObject().put("ok", true).put("data", BankMarket.json(payload.optString("url"))))
            }
            path == "/api/bank-automation/sync" && method == "POST" -> {
                BankAutomation.start(store)
                return json(out, 200, BankAutomation.settings(store))
            }
            path == "/api/questions/bank" && method == "DELETE" -> {
                val input = JSONObject(body)
                require(input.optBoolean("confirm")) { "请确认删除题库" }
                val deleted = DeviceRuntime.learning.clearQuestions(input.getString("bank"))
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("deleted", deleted))
            }
            path == "/api/questions/clear" && method == "POST" -> {
                require(JSONObject(body).optBoolean("confirm")) { "请确认清空全部题库" }
                val deleted = DeviceRuntime.learning.clearQuestions()
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("deleted", deleted))
            }
            path == "/api/questions/delete" && method == "POST" -> {
                val ids = JSONObject(body).getJSONArray("ids")
                require(ids.length() in 1..5000) { "请选择要删除的题目" }
                // 走 LearningEngine：删掉的正是孩子手上那题时要立刻换题并升高 quizGeneration
                val deleted = DeviceRuntime.learning.deleteQuestions((0 until ids.length()).map { ids.getString(it) })
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("ok", true).put("deleted", deleted))
            }
            path.startsWith("/api/questions/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/questions/")
                val deleted = DeviceRuntime.learning.deleteQuestions(listOf(id))
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("ok", deleted > 0))
            }
            path == "/api/history" && method == "GET" -> {
                DeviceRuntime.learning.flushUsage()
                val days = Regex("days=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 7
                return json(out, 200, JSONObject().put("items", store.historyDays(days)))
            }
            path == "/api/events" && method == "GET" -> {
                return json(out, 200, JSONObject().put("items", store.recentEvents(q["limit"]?.toIntOrNull() ?: 50)))
            }
            path == "/api/grant" && method == "GET" -> return json(out, 200, SelfGrant.status(context))
            path == "/api/grant/run" && method == "POST" -> { SelfGrant.runAsync(context); return json(out, 200, SelfGrant.status(context)) }
            path == "/api/points" && method == "GET" -> return json(out, 200, PointsSync.summary(store))
            path == "/api/points/settings" && method == "PUT" -> {
                return try { json(out, 200, PointsSync.save(store, JSONObject(body))) }
                catch (e: IllegalArgumentException) { json(out, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
                catch (e: java.io.IOException) { json(out, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
            }
            path == "/api/points/children" && method == "POST" -> {
                return try { json(out, 200, PointsSync.listChildren(store, JSONObject(body))) }
                catch (e: IllegalArgumentException) { json(out, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
                catch (e: java.io.IOException) { json(out, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
            }
            path == "/api/points/sync" && method == "POST" -> { PointsSync.syncNow(store); return json(out, 200, PointsSync.summary(store)) }
            path == "/api/tune" && method == "GET" -> return json(out, 200, Tune.summary(store))
            path == "/api/tune/settings" && method == "PUT" -> return json(out, 200, Tune.save(store, JSONObject(body)))
            path == "/api/grading" && method == "GET" -> return json(out, 200, GradingService.settings(store).put("providers", GradingService.providers()))
            path == "/api/grading/test" && method == "POST" -> return json(out, 200, GradingService.test(store, JSONObject(body.ifEmpty { "{}" })))
            path == "/api/grading/models" && method == "POST" -> return json(out, 200, GradingService.models(store, JSONObject(body.ifEmpty { "{}" })))
            path == "/api/update" && method == "GET" -> return json(out, 200, Updater.status(context))
            path == "/api/update" && method == "PUT" -> return json(out, 200, Updater.saveSettings(context, JSONObject(body)))
            path == "/api/update/check" && method == "POST" -> {
                // 检查可能要依次尝试多个来源（最长约 1 分钟），放到后台，面板轮询 GET /api/update
                Updater.startCheck(context)
                return json(out, 200, Updater.status(context))
            }
            path == "/api/password" && method == "POST" -> {
                val input = JSONObject(body)
                if (!AdminAuth.verify(context, input.optString("current"))) return json(out, 403, err("wrong_password").put("message", "当前密码不正确，连续错误 5 次后需等待一分钟"))
                AdminAuth.setPassword(context, input.optString("password"))
                return json(out, 200, JSONObject().put("ok", true))
            }
            path == "/api/logout" && method == "POST" -> { AdminAuth.forget(context, token); return json(out, 200, JSONObject().put("ok", true)) }
            path == "/api/remote" && method == "POST" -> {
                // 家长会话，或带「控制」权限的集成令牌；学习页的设备令牌不能调用
                val parent = AdminAuth.valid(token)
                val control = IntegrationAuth.verify(context, token)?.control == true
                if (!parent && !control) return json(out, 403, err("forbidden").put("message", "请先用管理密码登录"))
                val input = JSONObject(body.ifEmpty { "{}" })
                if (input.optString("action") == "credit") {
                    val value = input.getDouble("minutes")
                    require(value.isFinite() && value == value.toInt().toDouble()) { "调整时长需要整数分钟" }
                }
                val status = DeviceRuntime.learning.remote(input.optString("action"), input.optInt("minutes"), if (parent) "panel" else "integration")
                GateService.refreshNow()
                return json(out, 200, JSONObject().put("learning", status))
            }
            path == "/api/integration" && method == "GET" -> return json(out, 200, IntegrationAuth.state(context))
            path == "/api/integration" && method == "POST" -> {
                val input = JSONObject(body.ifEmpty { "{}" })
                val read = input.optBoolean("read", true)
                val control = input.optBoolean("control", false)
                if (!read && !control) return json(out, 200, err("bad_scope").put("message", "至少选择只读或控制中的一种权限"))
                val token = IntegrationAuth.issue(context, read, control)
                return json(out, 200, JSONObject().put("token", token).put("integration", IntegrationAuth.state(context)))
            }
            path == "/api/integration" && method == "PUT" -> {
                val input = JSONObject(body.ifEmpty { "{}" })
                IntegrationAuth.update(context,
                    if (input.has("enabled")) input.optBoolean("enabled") else null,
                    if (input.has("read")) input.optBoolean("read") else null,
                    if (input.has("control")) input.optBoolean("control") else null)
                return json(out, 200, IntegrationAuth.state(context))
            }
            path == "/api/integration" && method == "DELETE" -> {
                IntegrationAuth.revoke(context)
                return json(out, 200, IntegrationAuth.state(context))
            }
            path == "/api/logs" && method == "GET" -> return json(out, 200, JSONObject().put("lines", Diag.read(context, q["limit"]?.toIntOrNull()?.coerceIn(50, 3000) ?: 800, q["q"] ?: "")))
            path == "/api/logs" && method == "DELETE" -> { Diag.clear(context); return json(out, 200, JSONObject().put("ok", true)) }
            path == "/api/logs/export" && method == "GET" -> {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                return reply(out, 200, "application/zip", Diag.export(context), mapOf("Content-Disposition" to "attachment; filename=\"studyontv-logs-$stamp.zip\""))
            }
            path == "/api/device" && method == "GET" -> return json(out, 200, JSONObject().put("name", Prefs.deviceName(context))
                .put("version", BuildConfig.VERSION_NAME).put("flavor", BuildConfig.FLAVOR).put("ip", localIp() ?: "").put("port", boundPort)
                .put("uninstallProtected", UninstallGuard.active(context)).put("deviceOwner", UninstallGuard.owner(context))
                .put("launcherHidden", LauncherIcon.hidden(context)).put("desktop", DesktopMode.state(context)).put("autostart", Watchdog.report(context)).put("monitor", GateService.diagnostics(context))
                .put("keepalive", KeepAlive.report(context)).put("buglyConsent", Prefs.buglyConsent(context)))
            path == "/api/device/bugly" && method == "PUT" -> {
                val enabled = JSONObject(body).optBoolean("enabled")
                if (enabled) BuglyInit.initWithConsent(context) else BuglyInit.revoke(context)
                return json(out, 200, JSONObject().put("ok", true).put("buglyConsent", Prefs.buglyConsent(context)))
            }
            path == "/api/device/uninstall-guard" && method == "DELETE" -> {
                // 家长在网页端可以关闭防卸载（安全：需要家长登录 token）
                return try {
                    UninstallGuard.setEnabled(context, false)
                    json(out, 200, JSONObject().put("ok", true).put("uninstallProtected", false))
                } catch (e: Exception) {
                    json(out, 500, err(e.message ?: "failed"))
                }
            }
            path == "/api/device/uninstall-guard" && method == "PUT" -> {
                UninstallGuard.setEnabled(context, true)
                return json(out, 200, JSONObject().put("ok", true).put("uninstallProtected", UninstallGuard.active(context)))
            }
            path == "/api/device/desktop" && method == "PUT" -> {
                // 「把题了个题当电视桌面」是可选开关：只切一个 alias，进程不重启，关掉即回原桌面
                val enabled = JSONObject(body).optBoolean("enabled")
                return try {
                    json(out, 200, DesktopMode.setEnabled(context, enabled))
                } catch (e: Exception) {
                    json(out, 500, err(e.message ?: "切换电视桌面失败"))
                }
            }
            path == "/api/device/desktop/choose" && method == "POST" -> {
                DesktopMode.openChooser(context)
                return json(out, 200, DesktopMode.state(context))
            }
            path == "/api/device/launcher" && method == "PUT" -> {
                // 只关桌面入口，不改应用本身的状态；家长面板可以随时恢复
                val hidden = LauncherIcon.setHidden(context, JSONObject(body).optBoolean("hidden"))
                return json(out, 200, JSONObject().put("ok", true).put("launcherHidden", hidden))
            }
            path == "/api/device" && method == "PUT" -> {
                val name = JSONObject(body).optString("name").trim()
                require(name.length in 1..32) { "设备名称需要 1–32 个字" }
                Prefs.setDeviceName(context, name)
                return json(out, 200, JSONObject().put("ok", true).put("name", name))
            }
            path == "/api/grading" && method == "PUT" -> return json(out, 200, GradingService.save(store, JSONObject(body)))
            path == "/api/reviews" && method == "GET" -> {
                val items = store.reviews(q["state"] ?: "pending")
                // 列表里可能有一条条整张手写图（PNG data URL），「已批改」这类历史列表用 summary 省流量：
                // 只留 hasDrawing 标记，展开某条时再用 GET /api/reviews/{id} 取原图
                if (q["view"] == "summary") for (i in 0 until items.length()) {
                    val input = items.getJSONObject(i).optJSONObject("input") ?: continue
                    if (input.optString("drawing").isNotEmpty()) input.put("hasDrawing", true)
                    input.remove("drawing")
                }
                return json(out, 200, JSONObject().put("items", items))
            }
            path.startsWith("/api/reviews/") && method == "GET" -> {
                val id = path.removePrefix("/api/reviews/").substringBefore('/')
                val review = store.review(id) ?: return json(out, 404, err("not_found"))
                return json(out, 200, review)
            }
            path.startsWith("/api/reviews/") && method == "POST" -> {
                val id = path.removePrefix("/api/reviews/").substringBefore('/')
                if (path.endsWith("/retry")) {
                    require(store.review(id) != null) { "题目不存在" }
                    GradingService.enqueue(store, DeviceRuntime.learning, id)
                    return json(out, 200, JSONObject().put("ok", true))
                }
                val decision = JSONObject(body)
                return json(out, 200, DeviceRuntime.learning.grade(id, decision.getDouble("score"), decision.optString("feedback"), "parent_or_agent"))
            }
            path == "/api/quiz/start" && method == "POST" -> {
                if (!deviceAccess) return json(out, 403, err("device_only"))
                return json(out, 200, DeviceRuntime.learning.start(JSONObject(body.ifBlank { "{}" }).optString("level").takeIf { it.isNotBlank() }))
            }
            path == "/api/quiz/continue" && method == "POST" -> {
                if (!deviceAccess) return json(out, 403, err("device_only"))
                return json(out, 200, DeviceRuntime.learning.continueFeedback())
            }
            path == "/api/quiz/review" && method == "POST" -> {
                if (!deviceAccess) return json(out, 403, err("device_only"))
                return json(out, 200, DeviceRuntime.learning.acknowledgeReview(JSONObject(body).getString("reviewId")))
            }
            path == "/api/quiz/skip" && method == "POST" -> {
                if (!deviceAccess) return json(out, 403, err("device_only"))
                return json(out, 200, DeviceRuntime.learning.skipPending())
            }
            path == "/api/quiz/answer" && method == "POST" -> {
                if (!deviceAccess) return json(out, 403, err("device_only"))
                val result = DeviceRuntime.learning.answer(JSONObject(body))
                Diag.info("Quiz", "answer correct=${result.optBoolean("correct")} finished=${result.optBoolean("finished")} pending=${result.optBoolean("pending")} reward=${result.optDouble("rewardMinutes", 0.0)}")
                if (result.optBoolean("pending")) GradingService.enqueue(store, DeviceRuntime.learning, result.getString("reviewId"))
                return json(out, 200, result)
            }
            path == "/api/event" && method == "POST" -> {
                val payload = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val type = payload.optString("type")
                if (type != "study") return json(out, 403, err("event_managed_by_device"))
                store.logEvent(type, payload)
                return json(out, 200, JSONObject().put("ok", true))
            }
        }
        } catch (e: Exception) {
            Diag.error("HTTP", "$method $path failed", e)
            return json(out, 400, JSONObject().put("error", "bad_request").put("message", e.message ?: "请求格式错误"))
        }

        
        if (method != "GET") return json(out, 405, err("method_not_allowed"))
        serveStatic(path, out, headers["accept-encoding"]?.contains("gzip") == true, headers["if-none-match"])
    }



    
    /** gzip 后的静态文件缓存（资源随 APK 固定不变，压缩一次即可） */
    private val gzipCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    // 包含安装时间：同版本号的重新安装 / 调试构建也会让浏览器拿到新页面
    private val etag = "\"" + BuildConfig.VERSION_CODE + "-" + (try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }) + "\""

    private fun serveStatic(path: String, out: OutputStream, gzip: Boolean, ifNoneMatch: String?) {
        val (assetPath, contentType) = when {
            path == "/practice" || path == "/practice.html" -> "web/practice.html" to "text/html; charset=utf-8"
            path == "/practice-sw.js" -> "web/practice-sw.js" to "application/javascript; charset=utf-8"
            path == "/practice.appcache" -> "web/practice.appcache" to "text/cache-manifest; charset=utf-8"
            path == "/" || path == "/index.html" -> "web/index.html" to "text/html; charset=utf-8"
            path.startsWith("/admin/assets/") || path.startsWith("/admin/icons/") ||
                path == "/admin/manifest.webmanifest" || path == "/admin/sw.js" -> "admin" + path.removePrefix("/admin") to mime(path)
            path == "/admin" -> return reply(out, 301, null, null, mapOf("Location" to "/admin/"))
            path.startsWith("/admin") -> "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/assets/") -> "web" + path to mime(path)
            else -> "web/index.html" to "text/html; charset=utf-8"
        }
        if (assetPath.contains("..")) return reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
        // 带哈希的打包文件和字体可以长期缓存；页面本身每次校验（ETag = 版本号，升级后自动失效）
        val hashed = Regex("-[A-Za-z0-9_-]{8}\\.(js|css)$").containsMatchIn(assetPath) || assetPath.contains("/vendor/")
        val cache = if (hashed) "public, max-age=31536000, immutable" else "no-cache"
        if (ifNoneMatch == etag) return reply(out, 304, null, null, mapOf("ETag" to etag, "Cache-Control" to cache))
        try {
            val compressible = contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json") || contentType.contains("svg")
            val extra = mutableMapOf("ETag" to etag, "Cache-Control" to cache)
            val bytes = if (gzip && compressible) {
                extra["Content-Encoding"] = "gzip"; extra["Vary"] = "Accept-Encoding"
                gzipCache.getOrPut(assetPath) {
                    val raw = assets.open(assetPath).use { it.readBytes() }
                    ByteArrayOutputStream().also { buf -> java.util.zip.GZIPOutputStream(buf).use { it.write(raw) } }.toByteArray()
                }
            } else assets.open(assetPath).use { it.readBytes() }
            reply(out, 200, contentType, bytes, extra)
        } catch (e: Exception) {
            reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
        }
    }

    private fun mime(path: String): String = when {
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".webmanifest") -> "application/manifest+json; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    
    private fun json(out: OutputStream, code: Int, obj: JSONObject) =
        reply(out, code, "application/json; charset=utf-8", obj.toString().toByteArray())

    private fun err(code: String) = JSONObject().put("error", code)

    private fun reply(out: OutputStream, code: Int, contentType: String?, bytes: ByteArray?, extra: Map<String, String> = emptyMap()) {
        val body = bytes ?: ByteArray(0)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (!extra.containsKey("Cache-Control")) head.append("Cache-Control: no-store\r\n")
        extra.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\n")
        head.append("Access-Control-Allow-Headers: Content-Type,X-Admin-Token,Authorization,X-Admin-Key,X-Device-Token\r\n")
        head.append("Access-Control-Allow-Private-Network: true\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        301 -> "Moved Permanently"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "OK"
    }

    
    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size, Charsets.UTF_8)
            }
            buf.write(b)
            prev = b
        }
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    
    fun localIp(): String? = try {
        // VPNs can own the default route; advertise a physical LAN address instead.
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
    } catch (e: Exception) {
        null
    }
}
