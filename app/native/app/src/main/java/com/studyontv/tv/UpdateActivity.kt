package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject





class UpdateActivity : Activity() {
    private var force = false
    private var sha256 = ""
    private var targetVersion = ""
    private var preDownloadedApkPath = ""
    private lateinit var status: TextView
    private var goButton: TextView? = null
    @Volatile private var installHandedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LocalOnlyGuard.enabled) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val data = try {
            JSONObject(intent.getStringExtra("payload") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val latest = data.optString("latest", "")
        targetVersion = latest
        val current = data.optString("current", Updater.currentVersion(this))
        val notes = data.optString("notes", "")
            .lineSequence()
            .filter { !it.contains("[force]", true) && it.isNotBlank() }
            .joinToString("\n")
        val url = data.optString("url", "")
        force = data.optBoolean("force", false)
        sha256 = data.optString("sha256", "")
        preDownloadedApkPath = intent.getStringExtra("apkPath").orEmpty().trim()

        val palette = TvUi.palette(this)
        val tv = DeviceBridge.isTv(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (tv) 32 else 20), dp(if (tv) 44 else 28), dp(if (tv) 32 else 20), dp(30))
        }
        content.addView(TvUi.text(this, if (tv) "题了个题 · TV" else "题了个题", 14f, palette.text3))
        content.addView(TvUi.text(this, "发现新版本", 15f, palette.text2).apply {
            setPadding(0, dp(18), 0, 0)
        })
        content.addView(TvUi.text(this, "v$latest", if (tv) 46f else 38f, palette.text, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        content.addView(TvUi.text(this, "当前 v$current" + if (force) " · 需要更新后才能继续" else "", 14f, palette.text3).apply {
            setPadding(0, dp(6), 0, dp(22))
        })

        if (notes.isNotEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(18))
                background = solid(palette.surface, 20)
                addView(TvUi.text(context, "更新内容", 12f, palette.text3))
                val note = TvUi.text(context, notes, if (tv) 17f else 15f, palette.text).apply {
                    setLineSpacing(dp(7).toFloat(), 1.12f)
                    setPadding(0, dp(8), 0, 0)
                }
                val scroll = ScrollView(context).apply { addView(note) }
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (tv) 200 else 150)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); bottomMargin = dp(24)
            })
        }

        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.text)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.surface2)
            visibility = View.GONE
        }
        content.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { bottomMargin = dp(14) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var go: android.widget.Button
        go = TvUi.button(this, "立即更新", true) {
            startUpdate(url, go, progress)
        }
        go.textSize = if (tv) 21f else 18f
        goButton = go
        refreshPrimaryButtonLabel(go)
        buttons.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (tv) 62 else 54)))

        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!force) {
            secondary.addView(TvUi.button(this, "以后再说") { finish() },
                LinearLayout.LayoutParams(0, dp(if (tv) 52 else 48), 1f))
        }
        secondary.addView(TvUi.button(this, "手动下载") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + Updater.OWNER_REPO + "/releases/latest")))
            } catch (e: Exception) {
                status.text = "这台设备没有可用浏览器，请用手机或电脑访问 https://github.com/" + Updater.OWNER_REPO + "/releases/latest"
                Diag.error("Update", "cannot open manual download page", e)
            }
        }, LinearLayout.LayoutParams(0, dp(if (tv) 52 else 48), 1f).apply {
            leftMargin = if (force) 0 else dp(12)
        })
        buttons.addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        content.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        status = TvUi.text(this, "下载后覆盖安装，题库和设置不会丢", 12f, palette.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(status)

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(palette.bg) }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(
            if (tv) dp(720) else android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(scroll)
        if (url.isEmpty() && preDownloadedApkPath.isEmpty()) go.isEnabled = false
        go.requestFocus()
        if (intent.getBooleanExtra("auto", false) && (url.isNotEmpty() || preDownloadedApkPath.isNotEmpty())) {
            go.post { startUpdate(url, go, progress) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        val error = Prefs.updateInstallError(this)
        if (error.isNotEmpty()) {
            installHandedOff = false
            Prefs.setUpdateInProgress(this, false)
            Updater.updateInstallState("error", 100, error, targetVersion)
            status.text = "$error。可以点「立即更新」再试一次，或用「手动下载」装。"
            goButton?.isEnabled = true
            goButton?.text = "重试安装"
        } else if (!ApkInstaller.canInstall(this)) {
            goButton?.text = "去允许安装应用"
            status.text = "这套电视系统默认禁止应用自己安装更新，需要先允许「题了个题」安装应用"
        } else if (goButton?.text?.toString() == "去允许安装应用") {
            goButton?.let { refreshPrimaryButtonLabel(it) }
            status.text = "下载后覆盖安装，题库和设置不会丢"
        }
    }

    override fun onDestroy() {
        if (!installHandedOff) {
            Prefs.setUpdateInProgress(this, false)
            if (Updater.currentInstallSnapshot().state == "installing") {
                Updater.resetInstallState()
            }
        }
        super.onDestroy()
    }

    @Synchronized
    private fun resolvePreDownloadedApk(): java.io.File? {
        val path = preDownloadedApkPath
        if (path.isEmpty()) return null
        val file = java.io.File(path)
        return if (file.exists() && file.isFile && isCachedApkMatching(file, sha256, deleteOnMismatch = false)) file else null
    }

    @Synchronized
    private fun hasValidCachedApk(): Boolean {
        if (resolvePreDownloadedApk() != null) return true
        val ready = ApkInstaller.apkFile(this)
        if (!ApkInstaller.alreadyDownloaded(this, 0)) return false
        return isCachedApkMatching(ready, sha256, deleteOnMismatch = true)
    }

    private fun refreshPrimaryButtonLabel(button: TextView) {
        Thread {
            val downloaded = try { hasValidCachedApk() } catch (_: Exception) { false }
            runOnUiThread {
                if (!isFinishing && button.isEnabled && (button.text == "立即更新" || button.text == "去允许安装应用")) {
                    button.text = if (downloaded) "开始安装" else "立即更新"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun label(text: String, size: Float, color: Int, bottomMarginDp: Int,
                      gravity: Int = Gravity.CENTER_HORIZONTAL): TextView {
        val v = TextView(this)
        v.text = text
        v.setTextColor(color)
        v.textSize = size
        v.gravity = gravity
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(bottomMarginDp)
        v.layoutParams = lp
        return v
    }

    
    private fun startUpdate(url: String, button: TextView, progress: android.widget.ProgressBar? = null) {
        if (!button.isEnabled) return
        val preDownloaded = resolvePreDownloadedApk()
        if (url.isEmpty() && preDownloaded == null) { button.text = "没有下载地址"; return }

        if (!ApkInstaller.canInstall(this)) {
            // Android 8+ 是按应用授权的：没开之前调起安装器只会失败，先去设置里打开
            Updater.updateInstallState("error", 100, "系统尚未允许「题了个题」安装未知应用，请在设备上授权后重试", targetVersion)
            status.text = "先允许「题了个题」安装应用，允许后返回这里再点一次"
            try {
                GateService.openingSystemPage(parentAuthorized = true)
                startActivity(ApkInstaller.permissionIntent(this))
            } catch (e: Exception) {
                status.text = "这台电视找不到「安装未知应用」设置页，请到 设置 → 应用 里允许「题了个题」安装应用"
                Diag.warn("Install", "cannot open unknown-sources settings: ${e.javaClass.simpleName}")
            }
            return
        }

        installHandedOff = false
        Prefs.setUpdateInProgress(this, true)
        button.isEnabled = false
        button.text = "准备安装…"
        Thread {
            try {
                val ready = resolvePreDownloadedApk() ?: if (hasValidCachedApk()) ApkInstaller.apkFile(this) else null
                if (ready != null) {
                    val installed = ApkInstaller.install(this, ready)
                    if (installed) {
                        installHandedOff = true
                    } else {
                        Prefs.setUpdateInProgress(this, false)
                        Updater.updateInstallState("error", 100, ApkInstaller.lastError.ifEmpty { "系统没有接受这次安装" }, targetVersion)
                    }
                    runOnUiThread {
                        if (installed) {
                            button.text = "正在安装…"
                        } else {
                            button.isEnabled = true
                            button.text = "安装失败，重试"
                            status.text = ApkInstaller.lastError.ifEmpty { "系统没有接受这次安装" }
                            button.requestFocus()
                        }
                    }
                    return@Thread
                }

                runOnUiThread {
                    button.text = "下载中 0%"
                    progress?.visibility = View.VISIBLE
                }
                val file = ApkInstaller.download(this, url, sha256, { source -> runOnUiThread { status.text = "下载线路：$source" } }) { percent ->
                    runOnUiThread {
                        button.text = if (percent < 0) "下载中…" else if (percent < 100) "下载中 $percent%" else "正在准备安装…"
                        if (percent >= 0) progress?.progress = percent.coerceIn(0, 100)
                    }
                }
                val installed = if (file != null) ApkInstaller.install(this, file) else false
                if (installed) {
                    installHandedOff = true
                } else {
                    Prefs.setUpdateInProgress(this, false)
                }
                runOnUiThread {
                    if (installed) {
                        button.text = "正在安装…"
                    } else {
                        button.isEnabled = true
                        button.text = if (file == null) "下载失败，重试" else "安装失败，重试"
                        status.text = (ApkInstaller.lastError.ifEmpty { if (file == null) "下载失败，请检查网络" else "系统没有接受这次安装" }) +
                            "。也可以点「手动下载」到 GitHub Release 页面安装。"
                        progress?.visibility = View.GONE
                    }
                    button.requestFocus()
                }
            } catch (e: Exception) {
                Prefs.setUpdateInProgress(this, false)
                Updater.updateInstallState("error", 0, "更新准备失败：${e.message ?: e.javaClass.simpleName}", targetVersion)
                Diag.error("Update", "update task failed", e)
                runOnUiThread {
                    button.isEnabled = true
                    button.text = "安装失败，重试"
                    status.text = "更新准备失败：${e.message ?: e.javaClass.simpleName}"
                    progress?.visibility = View.GONE
                    button.requestFocus()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!force) finish() }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun solid(color: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun gradient(from: Int, to: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(from, to)
        ).apply { cornerRadius = dp(radiusDp).toFloat() }

    companion object {
        /**
         * 校验缓存 APK 文件是否与目标版本 SHA-256 摘要一致。
         * - expectedSha256 为空时不强制比对；
         * - 非空时计算文件 SHA-256 忽略大小写比对，不匹配或不可读时清理过期缓存并返回 false。
         */
        fun isCachedApkMatching(file: java.io.File, expectedSha256: String, deleteOnMismatch: Boolean = true): Boolean {
            if (!file.exists() || !file.isFile) return false
            val expected = expectedSha256.trim()
            if (expected.isEmpty()) return true
            val actual = try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
            val matched = actual.isNotEmpty() && actual.equals(expected, ignoreCase = true)
            if (!matched && deleteOnMismatch) {
                try { file.delete() } catch (_: Exception) { }
            }
            return matched
        }
    }
}
