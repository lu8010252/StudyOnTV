package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * 题目配图与主观题手写图的本地文件存储。
 *
 * 以前配图与手写 PNG 以几百 KB 的 `data:image/...;base64,...` 直接塞进 SQLite 的 `questions.details`
 * 与 `reviews.payload`：既容易撑爆 Android SQLite 2 MB 的 `CursorWindow`，也会让列表查询和内存暴涨。
 * 现在按内容 SHA-256 落盘到应用私有目录，数据库里只存短路径 `/api/media/<sha256>.<ext>`，相同图片天然去重；
 * 仅在导出备份或发给外部多模态大模型时按需转回 `data:image/...`。
 */
object MediaStore {
    const val PREFIX = "/api/media/"

    private val NAME_PATTERN = Regex("^[0-9a-f]{64}\\.(webp|jpg|png|gif)$")

    /**
     * 扫描 JSON 文本里的媒体引用。Android 自带的 `org.json.JSONObject.toString()` 会把 `/` 转义成 `\/`，
     * 所以必须同时识别 `/api/media/...` 与 `\/api\/media\/...`，否则孤儿清理会误删仍在使用的图片。
     */
    private val JSON_REF_PATTERN = Regex("""(?:/|\\/)api(?:/|\\/)media(?:/|\\/)([0-9a-f]{64}\.(?:webp|jpg|png|gif))""")

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    fun isMediaPath(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith(PREFIX) && NAME_PATTERN.matches(trimmed.substring(PREFIX.length))
    }

    /**
     * 兼容 Android API 21+ 与纯 JVM 单元测试的 Base64 解码：
     * `unitTests.isReturnDefaultValues = true` 下 `android.jar` 的空壳 `Base64.decode` 会返回 `null`，
     * 此时回退到 JVM 标准库 `java.util.Base64`。
     */
    @Suppress("NewApi")
    internal fun decodeBase64(raw: String): ByteArray {
        val clean = raw.trim()
        require(clean.isNotEmpty()) { "图片数据为空" }
        try {
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            if (decoded != null) return decoded
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Throwable) {
            // 纯 JVM 单测环境回退到 java.util.Base64
        }
        val compact = buildString(clean.length) {
            for (ch in clean) if (!ch.isWhitespace()) append(ch)
        }
        return java.util.Base64.getDecoder().decode(compact)
    }

    @Suppress("NewApi")
    internal fun encodeBase64(bytes: ByteArray): String {
        try {
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            if (encoded != null) return encoded
        } catch (_: Throwable) {
            // 纯 JVM 单测环境回退到 java.util.Base64
        }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 解析 `data:image/(webp|jpeg|jpg|png|gif);base64,<payload>` 并按 SHA-256 原子落盘，
     * 返回 `/api/media/<sha256>.<ext>`。不用正则匹配整段大文本，避免百 KB 级字符串触发正则栈溢出。
     */
    fun saveDataUrl(dir: File, dataUrl: String, maxDecodedBytes: Int = 2_000_000): String {
        val trimmed = dataUrl.trim()
        val prefix = "data:image/"
        val marker = ";base64,"
        val markerIndex = trimmed.indexOf(marker, ignoreCase = true)
        require(trimmed.startsWith(prefix, ignoreCase = true) && markerIndex > prefix.length) { "图片格式不受支持" }
        val subtype = trimmed.substring(prefix.length, markerIndex).lowercase(Locale.US)
        val ext = when (subtype) {
            "jpeg", "jpg" -> "jpg"
            "webp" -> "webp"
            "png" -> "png"
            "gif" -> "gif"
            else -> throw IllegalArgumentException("不支持的图片格式：$subtype")
        }
        val payload = trimmed.substring(markerIndex + marker.length)
        val bytes = decodeBase64(payload)
        require(bytes.isNotEmpty() && bytes.size <= maxDecodedBytes) { "图片大小超出限制" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it) }
        val name = "$sha256.$ext"
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("无法创建媒体目录")
        }
        val target = File(dir, name)
        if (!target.exists() || target.length() != bytes.size.toLong()) {
            val temp = File(dir, "$name.${UUID.randomUUID()}.tmp")
            try {
                temp.outputStream().use { out ->
                    out.write(bytes)
                    try { out.fd.sync() } catch (_: Exception) { }
                }
                if (!temp.renameTo(target)) {
                    // Windows 文件系统在目标已存在时 renameTo 可能返回 false
                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                        target.delete()
                        if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                    }
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        return "$PREFIX$name"
    }

    /**
     * 归一化题目配图引用：本地媒体路径和合法外链原样保留，`data:image/` 自动转存成本地文件。
     */
    fun normalizeImageRef(
        dir: File,
        raw: String,
        maxDataUrlLen: Int = 900_000,
        maxDecodedBytes: Int = 650_000,
        label: String = "配图"
    ): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (isMediaPath(value)) return value
        if ((value.startsWith("https://") || value.startsWith("http://")) && value.length <= 2048) return value
        if (value.startsWith("data:image/", ignoreCase = true) && value.length <= maxDataUrlLen) {
            return try {
                saveDataUrl(dir, value, maxDecodedBytes)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("${label}地址无效")
            }
        }
        throw IllegalArgumentException("${label}地址无效")
    }

    /** 严格按文件名白名单读取媒体字节与 MIME，彻底阻断目录穿越。 */
    fun readMedia(dir: File, name: String): Pair<ByteArray, String>? {
        if (!NAME_PATTERN.matches(name)) return null
        val file = File(dir, name)
        if (!file.isFile) return null
        val canonicalDir = try { dir.canonicalFile } catch (_: Exception) { return null }
        val canonicalFile = try { file.canonicalFile } catch (_: Exception) { return null }
        if (canonicalFile.parentFile != canonicalDir) return null
        val mime = when (name.substringAfterLast('.')) {
            "webp" -> "image/webp"
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> return null
        }
        val bytes = try { canonicalFile.readBytes() } catch (_: Exception) { return null }
        return bytes to mime
    }

    /**
     * 把 `/api/media/...` 还原成 `data:<mime>;base64,...`（用于整机备份导出、题库导出、送外部视觉模型批改）。
     * 非媒体路径原样返回；本地文件已丢失时返回空串。
     */
    fun toDataUrl(dir: File, ref: String): String {
        val trimmed = ref.trim()
        if (!isMediaPath(trimmed)) return ref
        val name = trimmed.substring(PREFIX.length)
        val (bytes, mime) = readMedia(dir, name) ?: return ""
        return "data:$mime;base64,${encodeBase64(bytes)}"
    }

    /** 导出题库 / 整机备份时把题目的本地媒体引用内联回 Data URL，使导出的 JSON 在其他设备上可独立导入。 */
    fun inlineQuestionMedia(dir: File, item: JSONObject): JSONObject {
        val copy = JSONObject(item.toString())
        val image = copy.optString("image")
        if (image.isNotEmpty()) {
            copy.put("image", toDataUrl(dir, image))
        }
        val images = copy.optJSONArray("images")
        if (images != null) {
            val inlinedImages = JSONArray()
            for (i in 0 until images.length()) {
                val raw = images.optString(i)
                if (raw.isEmpty()) continue
                val converted = toDataUrl(dir, raw)
                if (converted.isNotEmpty()) inlinedImages.put(converted)
            }
            copy.put("images", inlinedImages)
            if (copy.optString("image").isEmpty() && inlinedImages.length() > 0) {
                copy.put("image", inlinedImages.getString(0))
            }
        }
        val optionImages = copy.optJSONArray("optionImages")
        if (optionImages != null) {
            // 选项配图必须与选项下标一一对齐，空位或缺失文件保留为空字符串，不能压缩下标
            val inlinedOptions = JSONArray()
            for (i in 0 until optionImages.length()) {
                val raw = optionImages.optString(i)
                inlinedOptions.put(if (raw.isEmpty()) "" else toDataUrl(dir, raw))
            }
            copy.put("optionImages", inlinedOptions)
        }
        return copy
    }

    fun collectMediaNamesFromJson(raw: String, out: MutableSet<String>) {
        if (raw.isEmpty() || !raw.contains("media")) return
        for (match in JSON_REF_PATTERN.findAll(raw)) {
            out.add(match.groupValues[1])
        }
    }

    /** 清理不再被题库、批改记录或进行中任务引用的孤儿媒体文件，返回删除数量。 */
    fun pruneUnreferenced(dir: File, referenced: Set<String>): Int {
        if (!dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        var removed = 0
        val now = System.currentTimeMillis()
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (NAME_PATTERN.matches(name) && name !in referenced) {
                if (file.delete()) removed++
            } else if (name.endsWith(".tmp") && now - file.lastModified() > 300_000L) {
                file.delete()
            }
        }
        return removed
    }
}
