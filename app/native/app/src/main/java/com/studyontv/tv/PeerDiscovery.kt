package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * 局域网多设备自动发现（ST-611 / GitHub #171）。
 *
 * 100% 零第三方依赖，仅使用 Java 标准库 `DatagramSocket` 在局域网 UDP 18099 端口做轻量广播探测，
 * 绝不访问外网，EU 纯本地隐私版（`-PlocalOnly=true`）同样正常工作。
 */
object PeerDiscovery {
    const val DISCOVERY_PORT = 18099
    const val MAGIC_DISCOVER = "SOTV_DISCOVER_V1"
    const val MAGIC_PEER = "SOTV_PEER_V1"
    const val MAX_PACKET_BYTES = 512
    const val DEFAULT_PEER_TTL_MS = 90_000L
    const val DEFAULT_HTTP_PORT = 8099

    data class ParsedLanHost(val ip: String, val port: Int) {
        val host: String get() = "$ip:$port"
    }

    data class PeerInfo(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int,
        val version: String,
        val isTv: Boolean,
        val questions: Int,
        val lastSeenAt: Long
    ) {
        val host: String get() = "$ip:$port"

        fun toJson(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_PEER_TTL_MS): JSONObject =
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("ip", ip)
                .put("port", port)
                .put("host", host)
                .put("version", version)
                .put("isTv", isTv)
                .put("questions", questions)
                .put("lastSeenAt", lastSeenAt)
                .put("online", nowMs - lastSeenAt <= ttlMs)
    }

    /**
     * 线程安全的局域网设备发现表：按设备 `id` 去重、自动过滤本机、按 TTL 清理超时离线项。
     */
    internal class PeerRegistry(
        private val ttlMs: Long = DEFAULT_PEER_TTL_MS
    ) {
        private val peers = LinkedHashMap<String, PeerInfo>()

        @Synchronized
        fun record(
            peer: PeerInfo,
            selfId: String?,
            selfIp: String?,
            selfPort: Int,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (!isPrivateLanIp(peer.ip) || peer.port !in 1024..65535) return false
            if (!selfId.isNullOrBlank() && peer.id == selfId) return false
            if (!selfIp.isNullOrBlank() && peer.ip == selfIp && peer.port == selfPort) return false
            peers.entries.removeAll { it.value.ip == peer.ip && it.value.port == peer.port && it.key != peer.id }
            peers[peer.id] = peer
            pruneLocked(nowMs)
            return true
        }

        @Synchronized
        fun list(nowMs: Long = System.currentTimeMillis()): List<PeerInfo> {
            pruneLocked(nowMs)
            return peers.values.sortedWith(
                compareBy<PeerInfo> { it.name.lowercase(Locale.ROOT) }.thenBy { it.host }
            )
        }

        @Synchronized
        fun prune(nowMs: Long = System.currentTimeMillis()) {
            pruneLocked(nowMs)
        }

        @Synchronized
        fun clear() {
            peers.clear()
        }

        private fun pruneLocked(nowMs: Long) {
            peers.entries.removeAll { nowMs - it.value.lastSeenAt > ttlMs }
        }
    }

    /**
     * 严格校验是否为 RFC1918 私有局域网 IPv4 字面量（零 DNS 解析，防 SSRF）。
     * 仅允许 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`；
     * 明确拒绝公网 IP、`127.x`（回环）、`169.254.x`（链路本地/云元数据）、前导零八进制伪装与非法字符。
     */
    fun isPrivateLanIp(ip: String): Boolean {
        if (ip.isEmpty() || ip.length > 15) return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return false
            if (p.length > 1 && p[0] == '0') return false
            if (p.any { it !in '0'..'9' }) return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

    /**
     * 解析并校验 `ip` 或 `ip:port` 为合法私有局域网目标地址。
     * 端口必须位于 `1024..65535`，拒绝协议头、路径、用户信息、空白或任意非法字符。
     */
    fun parsePrivateLanHost(hostOrIp: String, defaultPort: Int = DEFAULT_HTTP_PORT): ParsedLanHost? {
        if (defaultPort !in 1024..65535) return null
        val trimmed = hostOrIp.trim()
        if (trimmed.isEmpty() || trimmed.length > 21) return null
        for (ch in trimmed) {
            if (ch !in '0'..'9' && ch != '.' && ch != ':') return null
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx < 0) {
            return if (isPrivateLanIp(trimmed)) ParsedLanHost(trimmed, defaultPort) else null
        }
        if (trimmed.indexOf(':', colonIdx + 1) >= 0) return null
        val ipPart = trimmed.substring(0, colonIdx)
        val portPart = trimmed.substring(colonIdx + 1)
        if (!isPrivateLanIp(ipPart)) return null
        if (portPart.isEmpty() || portPart.length > 5 || portPart[0] == '0') return null
        val port = portPart.toIntOrNull() ?: return null
        if (port !in 1024..65535) return null
        return ParsedLanHost(ipPart, port)
    }

    /**
     * 纯函数 SSRF 防护入口：仅允许 RFC1918 私有局域网 IPv4 及 `1024..65535` 端口。
     */
    fun isPrivateLanHost(hostOrIp: String): Boolean = parsePrivateLanHost(hostOrIp) != null

    internal fun encodeDiscoverPacket(): ByteArray =
        JSONObject().put("magic", MAGIC_DISCOVER).toString().toByteArray(Charsets.UTF_8)

    internal fun encodePeerPacket(
        id: String,
        name: String,
        port: Int,
        version: String,
        isTv: Boolean,
        questions: Int
    ): ByteArray {
        val safeId = id.trim().take(32)
        val safeName = name.trim().ifEmpty { "StudyOnTV" }.take(32)
        val safeVersion = version.trim().take(24)
        val safePort = port.coerceIn(1024, 65535)
        val safeQuestions = questions.coerceAtLeast(0)
        var bytes = JSONObject()
            .put("magic", MAGIC_PEER)
            .put("id", safeId)
            .put("name", safeName)
            .put("port", safePort)
            .put("version", safeVersion)
            .put("isTv", isTv)
            .put("questions", safeQuestions)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PACKET_BYTES) {
            bytes = JSONObject()
                .put("magic", MAGIC_PEER)
                .put("id", safeId.take(16))
                .put("name", safeName.take(16))
                .put("port", safePort)
                .put("version", safeVersion.take(16))
                .put("isTv", isTv)
                .put("questions", safeQuestions)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }
        return bytes
    }

    internal fun isDiscoverPacket(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length !in 1..MAX_PACKET_BYTES || length > bytes.size) return false
        return try {
            JSONObject(String(bytes, 0, length, Charsets.UTF_8)).optString("magic") == MAGIC_DISCOVER
        } catch (_: Exception) {
            false
        }
    }

    internal fun parsePeerPacket(
        bytes: ByteArray,
        length: Int,
        senderIp: String,
        nowMs: Long = System.currentTimeMillis()
    ): PeerInfo? {
        if (length !in 1..MAX_PACKET_BYTES || length > bytes.size) return null
        if (!isPrivateLanIp(senderIp)) return null
        return try {
            val obj = JSONObject(String(bytes, 0, length, Charsets.UTF_8))
            if (obj.optString("magic") != MAGIC_PEER) return null
            val port = obj.optInt("port", -1)
            if (port !in 1024..65535) return null
            val id = obj.optString("id").trim().take(32).ifEmpty { "$senderIp:$port" }
            val name = obj.optString("name").trim().take(32).ifEmpty { senderIp }
            val version = obj.optString("version").trim().take(24)
            val isTv = obj.optBoolean("isTv", true)
            val questions = obj.optInt("questions", 0).coerceAtLeast(0)
            PeerInfo(
                id = id,
                name = name,
                ip = senderIp,
                port = port,
                version = version,
                isTv = isTv,
                questions = questions,
                lastSeenAt = nowMs
            )
        } catch (_: Exception) {
            null
        }
    }

    private val registry = PeerRegistry()
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var appContext: Context? = null
    @Volatile private var localStore: LocalStore? = null
    @Volatile private var portProvider: (() -> Int)? = null
    @Volatile private var ipProvider: (() -> String?)? = null
    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null

    fun start(context: Context, store: LocalStore) {
        start(context, store, portProvider = { Prefs.localPort(context.applicationContext) }, ipProvider = null)
    }

    fun start(
        context: Context,
        store: LocalStore,
        portProvider: (() -> Int)? = null,
        ipProvider: (() -> String?)? = null
    ) {
        synchronized(lock) {
            appContext = context.applicationContext
            localStore = store
            if (portProvider != null) this.portProvider = portProvider
            if (ipProvider != null) this.ipProvider = ipProvider
            if (running) return

            val sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            } catch (e: Exception) {
                Diag.warn("PeerDiscovery", "UDP $DISCOVERY_PORT bind failed, degraded mode: ${e.message}")
                null
            }

            socket = sock
            if (sock == null) return
            running = true
            listenerThread = Thread({ listenLoop(sock) }, "sotv-peer-discovery").apply {
                isDaemon = true
                start()
            }
            // 启动后异步广播一次自身存在并探测局域网设备
            Thread({
                try {
                    broadcastPacket(sock, buildSelfPacket())
                    broadcastPacket(sock, encodeDiscoverPacket())
                } catch (_: Exception) {
                }
            }, "sotv-peer-announce").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        val sock: DatagramSocket?
        synchronized(lock) {
            running = false
            sock = socket
            socket = null
            listenerThread = null
        }
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * 主动发送局域网 UDP 广播探测并等待回包（默认 700ms）。
     * 即使 18099 监听端口因权限/占用降级，也能通过临时 UDP 端口完成主动扫描。
     */
    fun scan(timeoutMs: Long = 700L): List<PeerInfo> {
        val waitMs = timeoutMs.coerceIn(100L, 3000L)
        val discoverBytes = encodeDiscoverPacket()
        val selfBytes = try { buildSelfPacket() } catch (_: Exception) { null }
        var tempSock: DatagramSocket? = null
        try {
            tempSock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 150
            }
            broadcastPacket(tempSock, discoverBytes)
            if (selfBytes != null) broadcastPacket(tempSock, selfBytes)
            val mainSock = socket
            if (mainSock != null && !mainSock.isClosed) {
                broadcastPacket(mainSock, discoverBytes)
            }

            val deadline = System.currentTimeMillis() + waitMs
            val buf = ByteArray(MAX_PACKET_BYTES)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    tempSock.receive(packet)
                    val senderIp = packet.address?.hostAddress.orEmpty()
                    val peer = parsePeerPacket(packet.data, packet.length, senderIp)
                    if (peer != null) {
                        recordPeer(peer)
                    }
                } catch (_: SocketTimeoutException) {
                    // continue until deadline
                } catch (_: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Diag.warn("PeerDiscovery", "scan failed: ${e.message}")
        } finally {
            try { tempSock?.close() } catch (_: Exception) { }
        }
        return listPeers()
    }

    fun listPeers(): List<PeerInfo> = registry.list()

    internal fun recordProbedPeer(peer: PeerInfo): Boolean = recordPeer(peer)

    private fun recordPeer(peer: PeerInfo): Boolean {
        val ctx = appContext
        val selfId = ctx?.let { Prefs.deviceId(it) }
        val selfPort = currentPort()
        val selfIp = ipProvider?.invoke()
        return registry.record(peer, selfId, selfIp, selfPort)
    }

    private fun currentPort(): Int {
        val provided = try { portProvider?.invoke() } catch (_: Exception) { null }
        if (provided != null && provided in 1024..65535) return provided
        val ctx = appContext
        return if (ctx != null) Prefs.localPort(ctx) else DEFAULT_HTTP_PORT
    }

    private fun buildSelfPacket(): ByteArray {
        val ctx = appContext
        val store = localStore
        val id = ctx?.let { Prefs.deviceId(it) } ?: ""
        val name = ctx?.let { Prefs.deviceName(it) } ?: "StudyOnTV"
        val port = currentPort()
        val questions = try { store?.countQuestions() ?: 0 } catch (_: Exception) { 0 }
        return encodePeerPacket(
            id = id,
            name = name,
            port = port,
            version = BuildConfig.VERSION_NAME,
            isTv = BuildConfig.FLAVOR == "tv",
            questions = questions
        )
    }

    private fun listenLoop(sock: DatagramSocket) {
        val buf = ByteArray(MAX_PACKET_BYTES)
        while (running && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val senderIp = packet.address?.hostAddress.orEmpty()
                if (!isPrivateLanIp(senderIp)) continue

                if (isDiscoverPacket(packet.data, packet.length)) {
                    val replyBytes = buildSelfPacket()
                    val reply = DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port)
                    try {
                        sock.send(reply)
                    } catch (_: Exception) {
                    }
                    continue
                }

                val peer = parsePeerPacket(packet.data, packet.length, senderIp)
                if (peer != null) {
                    recordPeer(peer)
                }
            } catch (_: SocketTimeoutException) {
                registry.prune()
            } catch (_: Exception) {
                if (!running || sock.isClosed) break
            }
        }
    }

    private fun broadcastPacket(sock: DatagramSocket, data: ByteArray) {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && isPrivateLanIp(addr.hostAddress.orEmpty())) {
                        ia.broadcast?.let { targets.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        for (target in targets) {
            try {
                sock.send(DatagramPacket(data, data.size, target, DISCOVERY_PORT))
            } catch (_: Exception) {
            }
        }
    }
}
