package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 可选的电视桌面（[DesktopMode] 打开后才会出现在系统的桌面列表里）。
 *
 * 只做最必要的事：一屏应用图标 + 当前剩余时间，点开就是系统里装的应用。
 * 时间用完时不做自己的锁定界面，直接拉起 [GateActivity]——锁屏 / 答题流程都在那边，
 * 这样桌面和 App 里看到的是同一套逻辑，不会出现两套判断不一致。
 *
 * 不用 WebView：低端电视上 WebView 首屏要 1–3 秒，桌面必须秒开。
 * 这里全部是系统控件，没有 androidx 依赖（整个 App 都没有）。
 */
class HomeActivity : Activity() {
    private companion object {
        const val KIND_LEARN = 0
        const val KIND_APP = 1
        /** 连不上就自动退回系统桌面（避免「电视没有桌面」） */
        const val MAX_FAILURES = 2
        var failures = 0
        var lastFailureAt = 0L
    }

    private class Item(val kind: Int, val pkg: String?, val label: String, val icon: Drawable?)

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var items: List<Item> = emptyList()
    private var cells: List<View> = emptyList()
    private var grid: GridView? = null
    private var statusText: TextView? = null
    private var clockText: TextView? = null
    private var lastGateAt = 0L

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // 桌面可能是开机后第一个起来的界面，运行时和守护服务要在这里保证起来
            DeviceRuntime.ensure(this)
            if (!AdminAuth.configured(this)) {
                startActivity(Intent(this, ParentActivity::class.java)); finish(); return
            }
            if (!Prefs.onboarded(this)) {
                startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
            }
            GateService.start(this)
            setContentView(buildLayout())
            loadApps()
        } catch (e: Throwable) {
            // 桌面起不来就是「电视没有桌面」，这时候必须把自己交还给系统
            failAndExit(e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 30_000)
        openGateIfLocked()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0F1218"), Color.parseColor("#1A2130")))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(12))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isAllCaps = false
        }
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#A8B3C4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        clockText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val parent = TextView(this).apply {
            text = "家长"
            setTextColor(Color.parseColor("#8FB6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(8), dp(8))
            isFocusable = true
            setOnClickListener {
                try { startActivity(Intent(this@HomeActivity, ParentActivity::class.java)) }
                catch (e: Exception) { Diag.warn("Desktop", "open parent failed: ${e.javaClass.simpleName}") }
            }
        }
        header.addView(title)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        header.addView(clockText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) })
        header.addView(parent)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(132)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(dp(36), dp(8), dp(36), dp(36))
            clipToPadding = false
            adapter = CellAdapter()
            setOnItemClickListener { _, _, position, _ -> open(items.getOrNull(position)) }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun renderStatus() {
        clockText?.text = clockFormat.format(Date())
        val status = try { DeviceRuntime.learning.status() } catch (_: Exception) { null }
        val free = status?.optBoolean("free") == true
        val reason = status?.optString("reason").orEmpty()
        val seconds = status?.optLong("remainingSeconds", 0L) ?: 0L
        val minutes = ((seconds + 59) / 60).toInt()
        statusText?.text = when {
            status == null -> ""
            free -> "今天不限时"
            reason.isNotEmpty() -> when (reason) {
                "rest" -> "休息中，休息完才能继续看"
                "schedule" -> "现在不在可以看的时段"
                "locked" -> "电视已被家长锁定"
                "feedback" -> "等家长批改作业"
                "rounds" -> "今天的组数用完了"
                else -> "今天的时间用完了，答对题目可以继续看"
            }
            else -> "今天还可以看 $minutes 分钟"
        }
    }

    /** 时间用完 / 被锁定时，桌面直接给锁屏，孩子按 Home 也绕不过去 */
    private fun openGateIfLocked() {
        val status = try { DeviceRuntime.learning.status() } catch (_: Exception) { return }
        if (status.optBoolean("free")) return
        val reason = status.optString("reason")
        if (reason.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastGateAt < 5_000) return
        lastGateAt = now
        try {
            startActivity(Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("reason", reason))
        } catch (e: Exception) {
            Diag.warn("Desktop", "open gate failed: ${e.javaClass.simpleName}")
        }
    }

    private fun loadApps() {
        Thread {
            val list = try { scanApps() } catch (e: Exception) {
                Diag.warn("Desktop", "scan apps failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            handler.post {
                if (isFinishing) return@post
                items = list
                cells = list.map { itemCell(it) }
                (grid?.adapter as? BaseAdapter)?.notifyDataSetChanged()
                if (list.isNotEmpty()) grid?.requestFocus()
            }
        }.start()
    }

    private fun scanApps(): List<Item> {
        val pm = packageManager
        val out = ArrayList<Item>()
        val own = applicationInfo.loadIcon(pm)
        out.add(Item(KIND_LEARN, null, "学习", own))
        val seen = HashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || pkg in EXCLUDED || !seen.add(pkg)) continue
                out.add(Item(KIND_APP, pkg, info.loadLabel(pm).toString(), info.loadIcon(pm)))
            }
        }
        val sorted = out.drop(1).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return listOf(out.first()) + sorted
    }

    private fun tileBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#243044"))
            setStroke(dp(2), Color.parseColor("#7EB6FF"))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#00000000"))
        })
    }

    private fun itemCell(item: Item): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(148))
            background = tileBackground()
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(8), dp(14), dp(8), dp(10))
        }
        cell.addView(ImageView(this).apply {
            setImageDrawable(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        cell.addView(TextView(this).apply {
            text = item.label
            setTextColor(Color.parseColor("#E6ECF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        cell.setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
        }
        cell.setOnClickListener { open(item) }
        return cell
    }

    private fun open(item: Item?) {
        if (item == null) return
        if (item.kind == KIND_LEARN) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }
        val pkg = item.pkg ?: return
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "打不开 ${item.label}", Toast.LENGTH_SHORT).show()
            return
        }
        try { startActivity(intent) } catch (e: Exception) {
            Diag.warn("Desktop", "launch $pkg failed: ${e.javaClass.simpleName}")
            Toast.makeText(this, "打不开 ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun failAndExit(error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureAt > 10 * 60_000) failures = 0
        lastFailureAt = now
        failures += 1
        Diag.error("Desktop", "home activity failed ($failures)", error)
        if (failures >= MAX_FAILURES) {
            DesktopMode.failToSystemHome(this, error.javaClass.simpleName)
            Toast.makeText(this, "电视桌面启动失败，已切回系统桌面", Toast.LENGTH_LONG).show()
        } else {
            DesktopMode.openChooser(this)
        }
        finish()
    }

    private inner class CellAdapter : BaseAdapter() {
        override fun getCount(): Int = cells.size
        override fun getItem(position: Int): Any = cells[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = cells[position]
    }
}

/** 桌面上不列出来的包：系统设置（我们本来就要拦）和电视自带的启动器 */
private val EXCLUDED = setOf(
    "com.android.settings", "com.android.tv.settings", "com.android.providers.settings"
)
