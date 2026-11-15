package com.studyontv.tv

import android.content.Context
import android.content.Intent
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
    // 路由表：鉴权仍在 route() 里统一做，这里只负责 方法+路径 → 处理器
    private val router = Router()
    @Volatile private var running = false
    var boundPort: Int = port
        private set

    init { registerRoutes() }

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
                // 题库单次可导入 5 万题，整份 JSON 可能十几 MB；文件本身仍建议控制在 20 MB 以内
                if (length !in 0..24_000_000) return reply(c.getOutputStream(), 413, "text/plain", "Request too large".toByteArray())
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
        // 鉴权在进入路由前统一完成，处理器只拿到结论（家长 / 本机设备 / 集成令牌权限）
        var parent = false
        var integration: IntegrationAuth.Scope? = null
        if (path.startsWith("/api/") && path != "/api/hello") {
            val adminKey = headers["x-admin-key"]
            val integrationToken = token?.takeIf { it.startsWith("sotv_") }
            if (integrationToken != null && !IntegrationAuth.takeBudget())
                return json(out, 429, err("too_many_requests").put("message", "请求太频繁，请稍后再试"))
            integration = IntegrationAuth.verify(context, integrationToken)
            parent = AdminAuth.valid(token) || (adminKey != null && AdminAuth.verify(context, adminKey))
            if (!deviceAccess && !parent && integration == null) return json(out, 401, err("unauthorized"))
            // 集成令牌只能访问明确列出的接口；不能改规则、题库、密码或权限本身。
            if (integration != null && !deviceAccess && !parent) {
                val scope = when {
                    path == "/api/state" && method == "GET" -> "read"
                    path == "/api/device" && method == "GET" -> "read"
                    path == "/api/coverage" && method == "GET" -> "read"
                    path == "/api/remote" && method == "POST" -> "control"
                    else -> null
                } ?: return json(out, 403, err("forbidden").put("message", "这个接口需要家长登录"))
                if ((scope == "read" && !integration.read) || (scope == "control" && !integration.control))
                    return json(out, 403, err("forbidden").put("message", "集成令牌没有这个权限"))
            }
        }

        if (method != "GET") Diag.info("HTTP", "$method $path")
        val ctx = RouteContext(method, path, q, headers, body, out, local, deviceAccess, parent, token, integration)
        try {
            if (router.dispatch(ctx)) return
        } catch (e: Exception) {
            Diag.error("HTTP", "$method $path failed", e)
            return json(out, 400, JSONObject().put("error", "bad_request").put("message", e.message ?: "请求格式错误"))
        }

        if (method != "GET") return json(out, 405, err("method_not_allowed"))
        serveStatic(path, out, headers["accept-encoding"]?.contains("gzip") == true, headers["if-none-match"], local)
    }

    /** 所有 /api 路由集中在这里登记；路径匹配顺序由 Router 保证（精确路径优先） */
    private fun registerRoutes() {
        router.get("/api/hello") { ctx ->
            json(ctx.out, 200, JSONObject()
                .put("app", "StudyOnTV")
                .put("version", BuildConfig.VERSION_NAME)
                .put("name", Prefs.deviceName(context))
                .put("ip", localIp() ?: "")
                .put("port", boundPort)
                .put("questions", store.countQuestions())
                .put("needsToken", true)
                .put("passwordRequired", true))
        }
        // 不计时白名单：列出设备上可启动的应用（名称 + 包名），家长面板用来挑选。
        // Android 11+ 需要 AndroidManifest 里的 <queries> 声明才能看到其他应用。
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
            json(ctx.out, 200, JSONObject().put("items", JSONArray(items)))
        }
        router.get("/api/state") { ctx ->
            DeviceRuntime.learning.flushUsage()
            json(ctx.out, 200, JSONObject()
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
        router.get("/api/children") { ctx ->
            json(ctx.out, 200, JSONObject().put("items", store.children()).put("active", store.activeChild()))
        }
        router.post("/api/children") { ctx ->
            val child = store.createChild(JSONObject(ctx.body).getString("name"))
            json(ctx.out, 200, JSONObject().put("ok", true).put("child", child).put("items", store.children()))
        }
        router.put("/api/children") { ctx ->
            val input = JSONObject(ctx.body)
            store.renameChild(input.getString("id"), input.getString("name"))
            json(ctx.out, 200, JSONObject().put("ok", true).put("items", store.children()))
        }
        router.post("/api/children/switch") { ctx ->
            // 切换前先把还没落库的观看时长记到原孩子名下，否则会算到下一个孩子头上
            DeviceRuntime.learning.switchChild(JSONObject(ctx.body).optString("id"))
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("ok", true).put("items", store.children())
                .put("active", store.activeChild()))
        }
        router.post("/api/children/delete") { ctx ->
            DeviceRuntime.learning.deleteChild(JSONObject(ctx.body).optString("id"))
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("ok", true).put("items", store.children())
                .put("active", store.activeChild()))
        }
        router.get("/api/holidays") { ctx ->
            json(ctx.out, 200, HolidayService.info(context))
        }
        router.post("/api/holidays/sync") { ctx ->
            val year = ctx.query["year"]?.toIntOrNull() ?: DeviceClock.calendar(store).get(java.util.Calendar.YEAR)
            try {
                json(ctx.out, 200, HolidayService.fetch(context, year))
            } catch (e: Exception) {
                json(ctx.out, 200, err("holiday_failed").put("message", e.message ?: "节假日同步失败"))
            }
        }
        router.put("/api/config") { ctx ->
            val input = try { JSONObject(ctx.body) } catch (e: Exception) { return@put json(ctx.out, 200, err("bad_json")) }
            val config = DeviceRuntime.learning.saveConfig(input)
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("config", config))
        }
        router.post("/api/questions/generate") { ctx ->
            val input = try { JSONObject(ctx.body.ifEmpty { "{}" }) } catch (e: Exception) { return@post json(ctx.out, 200, err("bad_json")) }
            try {
                json(ctx.out, 200, GradingService.generateQuestions(store, input))
            } catch (e: Exception) {
                json(ctx.out, 200, err("generate_failed").put("message", e.message ?: "生成失败，请稍后重试"))
            }
        }
        router.get("/api/questions") { ctx ->
            // all=1 与 enabledOnly=false 等价：都表示「包含已暂停的题目」。
            // 管理端用 limit/offset/search/bank/type/difficulty/wrong 做服务端筛选和分页，
            // 返回 {items,total,offset,limit}；不传参数时保持旧行为（500 题、随机顺序）。
            val q = ctx.query
            val all = q["all"] == "1" || q["enabledOnly"] == "false"
            json(ctx.out, 200, store.questionPage(
                subject = q["subject"], level = q["level"], bank = q["bank"], search = q["search"],
                type = q["type"], difficulty = q["difficulty"]?.toIntOrNull(),
                wrongOnly = q["wrong"] == "1", enabledOnly = !all,
                sortBy = q["sort"] ?: "random",
                limit = q["limit"]?.toIntOrNull() ?: 500,
                offset = q["offset"]?.toIntOrNull() ?: 0
            ))
        }
        // 题库页的筛选项（题库名 / 科目 / 年级）和计数，不返回题目内容
        router.get("/api/questions/meta") { ctx ->
            json(ctx.out, 200, store.questionMeta())
        }
        // 答题覆盖：家长面板的「答题覆盖」卡片；口径是「答对过一次」，与出题时的覆盖优先一致
        router.get("/api/coverage") { ctx ->
            json(ctx.out, 200, store.coverage(ctx.query["sample"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20))
        }
        router.post("/api/questions/pause") { ctx ->
            val ids = JSONObject(ctx.body).getJSONArray("ids")
            val enabled = JSONObject(ctx.body).optBoolean("enabled", false)
            require(ids.length() in 1..50000) { "请选择题目" }
            json(ctx.out, 200, JSONObject().put("ok", true).put("updated",
                store.setEnabled((0 until ids.length()).map { ids.getString(it) }, enabled)))
        }
        router.post("/api/questions") { ctx ->
            val payload = try { JSONObject(ctx.body) } catch (e: Exception) { return@post json(ctx.out, 200, err("bad_json")) }
            if (payload.has("items")) {
                // 题库市场一键添加会重复点，允许按题干去重；普通导入默认不去重（覆盖式导入）
                val result = store.importQuestions(payload.getJSONArray("items"), payload.optBoolean("skipDuplicates"))
                return@post json(ctx.out, 200, JSONObject().put("ok", true)
                    .put("added", result.getInt("added")).put("skipped", result.getInt("skipped")))
            }
            try {
                val id = store.upsertQuestion(payload)
                store.logEvent("question_added", JSONObject().put("id", id))
                json(ctx.out, 200, JSONObject().put("ok", true).put("id", id))
            } catch (e: IllegalArgumentException) {
                json(ctx.out, 200, JSONObject().put("error", "bad_question").put("message", e.message ?: ""))
            }
        }
        // #130：家长在手机上点一下，让电视 / 投影打开完整系统设置（很多固件藏了菜单）
        router.post("/api/system-settings") { ctx ->
            json(ctx.out, 200, JSONObject().put("ok", DeviceBridge.openSystemSettings(context)))
        }
        // (二改) 一键授权 / 星星屋积分同步 / 保活调参
        router.get("/api/grant") { ctx -> json(ctx.out, 200, SelfGrant.status(context)) }
        router.post("/api/grant/run") { ctx -> SelfGrant.runAsync(context); json(ctx.out, 200, SelfGrant.status(context)) }
        router.get("/api/points") { ctx -> json(ctx.out, 200, PointsSync.summary(store)) }
        router.put("/api/points/settings") { ctx ->
            try { json(ctx.out, 200, PointsSync.save(store, JSONObject(ctx.body))) }
            catch (e: IllegalArgumentException) { json(ctx.out, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
            catch (e: java.io.IOException) { json(ctx.out, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
        }
        router.post("/api/points/children") { ctx ->
            try { json(ctx.out, 200, PointsSync.listChildren(store, JSONObject(ctx.body))) }
            catch (e: IllegalArgumentException) { json(ctx.out, 200, JSONObject().put("error", "bad_settings").put("message", e.message ?: "")) }
            catch (e: java.io.IOException) { json(ctx.out, 200, JSONObject().put("error", "network").put("message", "连不上星星屋：" + (e.message ?: ""))) }
        }
        router.post("/api/points/sync") { ctx -> PointsSync.syncNow(store); json(ctx.out, 200, PointsSync.summary(store)) }
        router.get("/api/tune") { ctx -> json(ctx.out, 200, Tune.summary(store)) }
        router.put("/api/tune/settings") { ctx -> json(ctx.out, 200, Tune.save(store, JSONObject(ctx.body))) }
        router.get("/api/speech") { ctx -> json(ctx.out, 200, SpeechReader.settings(store)) }
        router.put("/api/speech") { ctx -> json(ctx.out, 200, SpeechReader.save(store, JSONObject(ctx.body))) }
        // #128 本地语音诊断：引擎列表只查 PackageManager；探测会阻塞最多 3 秒（路由本来就跑在 worker 线程）
        router.get("/api/speech/engines") { ctx ->
            json(ctx.out, 200, JSONObject().put("engines", TtsProbe.installedEngines(context))
                .put("defaultEngine", TtsProbe.defaultEngine(context)))
        }
        router.get("/api/speech/probe") { ctx ->
            json(ctx.out, 200, TtsProbe.probe(context, ctx.query["language"].orEmpty().ifBlank { "zh-CN" }))
        }
        router.post("/api/speech/install-voice") { ctx ->
            json(ctx.out, 200, JSONObject().put("ok", TtsProbe.openInstallVoiceData(context)))
        }
        // 面板「测试朗读」：在电视上读一句，短暂等一下首个结果再回报，避免面板只看到 idle
        router.post("/api/speech/test") { ctx ->
            val text = try { JSONObject(ctx.body.ifEmpty { "{}" }).optString("text").trim() } catch (_: Exception) { "" }
            SpeechReader.read(context, text.ifBlank { "今天天气不错" }.take(200))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 800 && SpeechReader.status().optString("reason") == "idle") Thread.sleep(50)
            json(ctx.out, 200, SpeechReader.status())
        }
        router.get("/api/bank-automation") { ctx -> json(ctx.out, 200, BankAutomation.settings(store)) }
        router.put("/api/bank-automation") { ctx -> json(ctx.out, 200, BankAutomation.save(store, JSONObject(ctx.body))) }
        // 题库市场：读共享题库的索引，家长点一下就加进本机题库
        router.get("/api/banks/market") { ctx -> json(ctx.out, 200, BankMarket.market(store)) }
        router.post("/api/banks/sources") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx.out, 200, BankMarket.addSource(store, payload.optString("name"), payload.optString("url")))
        }
        router.delete("/api/banks/sources") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx.out, 200, BankMarket.removeSource(store, payload.optString("url")))
        }
        router.post("/api/banks/fetch") { ctx ->
            val payload = JSONObject(ctx.body.ifEmpty { "{}" })
            json(ctx.out, 200, JSONObject().put("ok", true).put("data", BankMarket.json(payload.optString("url"))))
        }
        router.post("/api/bank-automation/sync") { ctx ->
            BankAutomation.start(store)
            json(ctx.out, 200, BankAutomation.settings(store))
        }
        router.delete("/api/questions/bank") { ctx ->
            val input = JSONObject(ctx.body)
            require(input.optBoolean("confirm")) { "请确认删除题库" }
            val deleted = DeviceRuntime.learning.clearQuestions(input.getString("bank"))
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("deleted", deleted))
        }
        router.post("/api/questions/clear") { ctx ->
            require(JSONObject(ctx.body).optBoolean("confirm")) { "请确认清空全部题库" }
            val deleted = DeviceRuntime.learning.clearQuestions()
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("deleted", deleted))
        }
        router.post("/api/questions/delete") { ctx ->
            val ids = JSONObject(ctx.body).getJSONArray("ids")
            require(ids.length() in 1..50000) { "请选择要删除的题目" }
            // 走 LearningEngine：删掉的正是孩子手上那题时要立刻换题并升高 quizGeneration
            val deleted = DeviceRuntime.learning.deleteQuestions((0 until ids.length()).map { ids.getString(it) })
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("ok", true).put("deleted", deleted))
        }
        router.prefixDelete("/api/questions/") { ctx ->
            val id = ctx.path.removePrefix("/api/questions/")
            val deleted = DeviceRuntime.learning.deleteQuestions(listOf(id))
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("ok", deleted > 0))
        }
        router.get("/api/history") { ctx ->
            DeviceRuntime.learning.flushUsage()
            val days = ctx.query["days"]?.toIntOrNull() ?: 7
            json(ctx.out, 200, JSONObject().put("items", store.historyDays(days)))
        }
        router.get("/api/events") { ctx ->
            json(ctx.out, 200, JSONObject().put("items", store.recentEvents(ctx.query["limit"]?.toIntOrNull() ?: 50)))
        }
        router.get("/api/grading") { ctx -> json(ctx.out, 200, GradingService.settings(store).put("providers", GradingService.providers())) }
        router.post("/api/grading/test") { ctx -> json(ctx.out, 200, GradingService.test(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
        router.post("/api/grading/models") { ctx -> json(ctx.out, 200, GradingService.models(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
        router.get("/api/update") { ctx -> json(ctx.out, 200, Updater.status(context)) }
        router.put("/api/update") { ctx -> json(ctx.out, 200, Updater.saveSettings(context, JSONObject(ctx.body))) }
        router.post("/api/update/check") { ctx ->
            // 检查可能要依次尝试多个来源（最长约 1 分钟），放到后台，面板轮询 GET /api/update
            Updater.startCheck(context)
            json(ctx.out, 200, Updater.status(context))
        }
        router.post("/api/password") { ctx ->
            val input = JSONObject(ctx.body)
            if (!AdminAuth.verify(context, input.optString("current"))) return@post json(ctx.out, 403, err("wrong_password").put("message", "当前密码不正确，连续错误 5 次后需等待一分钟"))
            AdminAuth.setPassword(context, input.optString("password"))
            json(ctx.out, 200, JSONObject().put("ok", true))
        }
        router.post("/api/logout") { ctx ->
            AdminAuth.forget(context, ctx.token)
            json(ctx.out, 200, JSONObject().put("ok", true))
        }
        router.post("/api/remote") { ctx ->
            // 家长会话，或带「控制」权限的集成令牌；学习页的设备令牌不能调用
            val parent = AdminAuth.valid(ctx.token)
            val control = IntegrationAuth.verify(context, ctx.token)?.control == true
            if (!parent && !control) return@post json(ctx.out, 403, err("forbidden").put("message", "请先用管理密码登录"))
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            if (input.optString("action") == "credit") {
                val value = input.getDouble("minutes")
                require(value.isFinite() && value == value.toInt().toDouble()) { "调整时长需要整数分钟" }
            }
            val status = DeviceRuntime.learning.remote(input.optString("action"), input.optInt("minutes"), if (parent) "panel" else "integration")
            GateService.refreshNow()
            json(ctx.out, 200, JSONObject().put("learning", status))
        }
        router.get("/api/integration") { ctx -> json(ctx.out, 200, IntegrationAuth.state(context)) }
        router.post("/api/integration") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            val read = input.optBoolean("read", true)
            val control = input.optBoolean("control", false)
            if (!read && !control) return@post json(ctx.out, 200, err("bad_scope").put("message", "至少选择只读或控制中的一种权限"))
            val token = IntegrationAuth.issue(context, read, control)
            json(ctx.out, 200, JSONObject().put("token", token).put("integration", IntegrationAuth.state(context)))
        }
        router.put("/api/integration") { ctx ->
            val input = JSONObject(ctx.body.ifEmpty { "{}" })
            IntegrationAuth.update(context,
                if (input.has("enabled")) input.optBoolean("enabled") else null,
                if (input.has("read")) input.optBoolean("read") else null,
                if (input.has("control")) input.optBoolean("control") else null)
            json(ctx.out, 200, IntegrationAuth.state(context))
        }
        router.delete("/api/integration") { ctx ->
            IntegrationAuth.revoke(context)
            json(ctx.out, 200, IntegrationAuth.state(context))
        }
        router.get("/api/logs") { ctx ->
            json(ctx.out, 200, JSONObject().put("lines", Diag.read(context, ctx.query["limit"]?.toIntOrNull()?.coerceIn(50, 3000) ?: 800, ctx.query["q"] ?: "")))
        }
        router.delete("/api/logs") { ctx ->
            Diag.clear(context)
            json(ctx.out, 200, JSONObject().put("ok", true))
        }
        router.get("/api/logs/export") { ctx ->
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            reply(ctx.out, 200, "application/zip", Diag.export(context), mapOf("Content-Disposition" to "attachment; filename=\"studyontv-logs-$stamp.zip\""))
        }
        router.get("/api/device") { ctx ->
            json(ctx.out, 200, JSONObject().put("name", Prefs.deviceName(context))
                .put("version", BuildConfig.VERSION_NAME).put("flavor", BuildConfig.FLAVOR).put("ip", localIp() ?: "").put("port", boundPort)
                .put("uninstallProtected", UninstallGuard.active(context)).put("deviceOwner", UninstallGuard.owner(context))
                .put("launcherHidden", LauncherIcon.hidden(context)).put("desktop", DesktopMode.state(context)).put("autostart", Watchdog.report(context)).put("monitor", GateService.diagnostics(context))
                // 家长在别的时区时，面板要能看出「设备现在几点」到底按哪个时区算
                .put("timezone", DeviceClock.describe(store)).put("deviceTime", DeviceClock.calendar(store).time.toString())
                .put("keepalive", KeepAlive.report(context)).put("buglyConsent", Prefs.buglyConsent(context))
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
            // token 只写不读：面板拿不到明文，留空表示不修改
            if (input.has("token")) Prefs.setCrashToken(context, input.optString("token").trim())
            json(ctx.out, 200, JSONObject().put("ok", true).put("crashUpload", crashUploadState(context)))
        }
        router.put("/api/device/bugly") { ctx ->
            val enabled = JSONObject(ctx.body).optBoolean("enabled")
            if (enabled) BuglyInit.initWithConsent(context) else BuglyInit.revoke(context)
            json(ctx.out, 200, JSONObject().put("ok", true).put("buglyConsent", Prefs.buglyConsent(context)))
        }
        router.delete("/api/device/uninstall-guard") { ctx ->
            // 家长在网页端可以关闭防卸载（安全：需要家长登录 token）
            try {
                UninstallGuard.setEnabled(context, false)
                json(ctx.out, 200, JSONObject().put("ok", true).put("uninstallProtected", false))
            } catch (e: Exception) {
                json(ctx.out, 500, err(e.message ?: "failed"))
            }
        }
        router.put("/api/device/uninstall-guard") { ctx ->
            UninstallGuard.setEnabled(context, true)
            json(ctx.out, 200, JSONObject().put("ok", true).put("uninstallProtected", UninstallGuard.active(context)))
        }
        router.put("/api/device/desktop") { ctx ->
            // 「把题了个题当电视桌面」是可选开关：只切一个 alias，进程不重启，关掉即回原桌面
            val enabled = JSONObject(ctx.body).optBoolean("enabled")
            try {
                json(ctx.out, 200, DesktopMode.setEnabled(context, enabled))
            } catch (e: Exception) {
                json(ctx.out, 500, err(e.message ?: "切换电视桌面失败"))
            }
        }
        router.post("/api/device/desktop/choose") { ctx ->
            DesktopMode.openChooser(context)
            json(ctx.out, 200, DesktopMode.state(context))
        }
        router.put("/api/device/launcher") { ctx ->
            // 只关桌面入口，不改应用本身的状态；家长面板可以随时恢复
            val hidden = LauncherIcon.setHidden(context, JSONObject(ctx.body).optBoolean("hidden"))
            json(ctx.out, 200, JSONObject().put("ok", true).put("launcherHidden", hidden))
        }
        router.put("/api/device") { ctx ->
            val name = JSONObject(ctx.body).optString("name").trim()
            require(name.length in 1..32) { "设备名称需要 1–32 个字" }
            Prefs.setDeviceName(context, name)
            json(ctx.out, 200, JSONObject().put("ok", true).put("name", name))
        }
        router.put("/api/grading") { ctx -> json(ctx.out, 200, GradingService.save(store, JSONObject(ctx.body))) }
        router.get("/api/reviews") { ctx ->
            val items = store.reviews(ctx.query["state"] ?: "pending")
            // 列表里可能有一条条整张手写图（PNG data URL），「已批改」这类历史列表用 summary 省流量：
            // 只留 hasDrawing 标记，展开某条时再用 GET /api/reviews/{id} 取原图
            if (ctx.query["view"] == "summary") for (i in 0 until items.length()) {
                val input = items.getJSONObject(i).optJSONObject("input") ?: continue
                if (input.optString("drawing").isNotEmpty()) input.put("hasDrawing", true)
                input.remove("drawing")
            }
            json(ctx.out, 200, JSONObject().put("items", items))
        }
        router.prefixGet("/api/reviews/") { ctx ->
            val id = ctx.path.removePrefix("/api/reviews/").substringBefore('/')
            val review = store.review(id) ?: return@prefixGet json(ctx.out, 404, err("not_found"))
            json(ctx.out, 200, review)
        }
        router.prefixPost("/api/reviews/") { ctx ->
            val id = ctx.path.removePrefix("/api/reviews/").substringBefore('/')
            if (ctx.path.endsWith("/retry")) {
                require(store.review(id) != null) { "题目不存在" }
                GradingService.enqueue(store, DeviceRuntime.learning, id)
                return@prefixPost json(ctx.out, 200, JSONObject().put("ok", true))
            }
            val decision = JSONObject(ctx.body)
            json(ctx.out, 200, DeviceRuntime.learning.grade(id, decision.getDouble("score"), decision.optString("feedback"), "parent_or_agent"))
        }
        router.post("/api/quiz/start") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx.out, 403, err("device_only"))
            json(ctx.out, 200, DeviceRuntime.learning.start(JSONObject(ctx.body.ifBlank { "{}" }).optString("level").takeIf { it.isNotBlank() }))
        }
        router.post("/api/quiz/continue") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx.out, 403, err("device_only"))
            json(ctx.out, 200, DeviceRuntime.learning.continueFeedback())
        }
        router.post("/api/quiz/review") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx.out, 403, err("device_only"))
            json(ctx.out, 200, DeviceRuntime.learning.acknowledgeReview(JSONObject(ctx.body).getString("reviewId")))
        }
        router.post("/api/quiz/skip") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx.out, 403, err("device_only"))
            json(ctx.out, 200, DeviceRuntime.learning.skipPending())
        }
        router.post("/api/quiz/answer") { ctx ->
            if (!ctx.deviceAccess) return@post json(ctx.out, 403, err("device_only"))
            val result = DeviceRuntime.learning.answer(JSONObject(ctx.body))
            Diag.info("Quiz", "answer correct=${result.optBoolean("correct")} finished=${result.optBoolean("finished")} pending=${result.optBoolean("pending")} reward=${result.optDouble("rewardMinutes", 0.0)}")
            if (result.optBoolean("pending")) GradingService.enqueue(store, DeviceRuntime.learning, result.getString("reviewId"))
            json(ctx.out, 200, result)
        }
        router.post("/api/event") { ctx ->
            val payload = try { JSONObject(ctx.body) } catch (e: Exception) { JSONObject() }
            val type = payload.optString("type")
            if (type != "study") return@post json(ctx.out, 403, err("event_managed_by_device"))
            store.logEvent(type, payload)
            json(ctx.out, 200, JSONObject().put("ok", true))
        }
    }



    
    /** gzip 后的静态文件缓存（资源随 APK 固定不变，压缩一次即可） */
    private val gzipCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    // 包含安装时间：同版本号的重新安装 / 调试构建也会让浏览器拿到新页面
    private val etag = "\"" + BuildConfig.VERSION_CODE + "-" + (try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }) + "\""

    private fun serveStatic(path: String, out: OutputStream, gzip: Boolean, ifNoneMatch: String?, local: Boolean) {
        val (assetPath, contentType) = when {
            path == "/practice" || path == "/practice.html" -> "web/practice.html" to "text/html; charset=utf-8"
            path == "/practice-sw.js" -> "web/practice-sw.js" to "application/javascript; charset=utf-8"
            path == "/practice.appcache" -> "web/practice.appcache" to "text/cache-manifest; charset=utf-8"
            // 电视自己的 WebView 走 127.0.0.1，需要的是学习页；家长在电脑 / 手机上访问
            // 「IP:端口」直接给管理面板（原来必须带 /admin），/admin、/admin/ 照旧可用
            path == "/" || path == "/index.html" -> if (local) "web/index.html" to "text/html; charset=utf-8"
                else "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/admin/assets/") || path.startsWith("/admin/icons/") ||
                path == "/admin/manifest.webmanifest" || path == "/admin/sw.js" -> "admin" + path.removePrefix("/admin") to mime(path)
            path == "/admin" -> return reply(out, 301, null, null, mapOf("Location" to "/admin/"))
            path.startsWith("/admin") -> "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/assets/") -> "web" + path to mime(path)
            // 面板是单页应用：/admin/records 之类没有对应文件的地址都回落到面板首页
            else -> if (local) "web/index.html" to "text/html; charset=utf-8" else "admin/index.html" to "text/html; charset=utf-8"
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
                    val raw = assetBytes(assetPath)
                    ByteArrayOutputStream().also { buf -> java.util.zip.GZIPOutputStream(buf).use { it.write(raw) } }.toByteArray()
                }
            } else assetBytes(assetPath)
            reply(out, 200, contentType, bytes, extra)
        } catch (e: Exception) {
            reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
        }
    }

    /**
     * 面板首页里的静态资源是相对路径（`./assets/...`）。面板现在也能从「IP:端口」根路径打开，
     * 相对路径会解析到 `/assets/...`（那是学习页的资源），所以在页面头部固定一个 `<base href="/admin/">`，
     * 不管是 `/`、`/admin/` 还是别的路径打开，资源都落在 `/admin/...`。
     */
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
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    
    /** 崩溃上报配置（不回传 token 明文，只告诉面板有没有存过） */
    private fun crashUploadState(context: android.content.Context) = JSONObject()
        .put("consent", Prefs.crashUploadConsent(context))
        .put("endpoint", Prefs.crashEndpointStored(context))
        // 构建时内置的默认地址：面板里留空就用它，填了会覆盖
        .put("builtInEndpoint", BuildConfig.CRASH_ENDPOINT)
        .put("hasToken", Prefs.crashToken(context).isNotBlank())
        .put("builtInToken", BuildConfig.CRASH_TOKEN.isNotBlank())
        .put("pending", CrashDetector.hasPending(context))

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
