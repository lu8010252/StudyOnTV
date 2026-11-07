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
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val data = try {
            JSONObject(intent.getStringExtra("payload") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val latest = data.optString("latest", "")
        val current = data.optString("current", Updater.currentVersion(this))
        val notes = data.optString("notes", "")
            .lineSequence()
            .filter { !it.contains("[force]", true) && it.isNotBlank() }
            .joinToString("\n")
        val url = data.optString("url", "")
        force = data.optBoolean("force", false)
        sha256 = data.optString("sha256", "")

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
        go = TvUi.button(this, if (ApkInstaller.alreadyDownloaded(this, 0)) "开始安装" else "立即更新", true) {
            startUpdate(url, go, progress)
        }
        go.textSize = if (tv) 21f else 18f
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
        if (url.isEmpty()) go.isEnabled = false
        go.requestFocus()
        if (intent.getBooleanExtra("auto", false) && url.isNotEmpty()) {
            go.post { startUpdate(url, go, progress) }
        }
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
        if (url.isEmpty()) { button.text = "没有下载地址"; return }

        val ready = ApkInstaller.apkFile(this)
        if (ApkInstaller.alreadyDownloaded(this, 0)) {
            Prefs.setUpdateInProgress(this, true)
            if (ApkInstaller.install(this, ready)) return
            button.text = "请允许安装未知应用"
            return
        }

        Prefs.setUpdateInProgress(this, true)
        button.isEnabled = false
        button.text = "下载中 0%"
        progress?.visibility = View.VISIBLE
        Thread {
            val file = ApkInstaller.download(this, url, sha256, { source -> runOnUiThread { status.text = "下载线路：$source" } }) { percent ->
                runOnUiThread {
                    button.text = if (percent < 0) "下载中…" else if (percent < 100) "下载中 $percent%" else "正在安装…"
                    if (percent >= 0) progress?.progress = percent.coerceIn(0, 100)
                }
            }
            runOnUiThread {
                if (file != null && ApkInstaller.install(this, file)) {
                    button.text = "正在安装…"
                } else {
                    button.isEnabled = true
                    button.text = "下载失败，重试"
                    status.text = (ApkInstaller.lastError.ifEmpty { "下载失败，请检查网络" }) + "。也可以点「手动下载」到 GitHub Release 页面安装。"
                    progress?.visibility = View.GONE
                }
                button.requestFocus()
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
}
