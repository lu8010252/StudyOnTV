package com.studyontv.tv

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一键授权：应用通过本机的 ADB（127.0.0.1:5555）给自己开权限，等于替你在电脑上敲 adb 命令。
 * 前提是电视的 ADB 调试（TCP 5555）已开启。第一次连接时电视上会弹“允许 USB 调试吗”，勾选“始终允许”后以后不再弹。
 * 授权内容：使用情况访问、悬浮窗、无障碍服务（保留原有的服务）、后台运行（省电白名单）。
 */
object SelfGrant {
    private val running = AtomicBoolean(false)
    @Volatile private var last: JSONObject? = null

    fun status(context: Context): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val idle = Build.VERSION.SDK_INT >= 23 && pm.isIgnoringBatteryOptimizations(context.packageName)
        val o = JSONObject().put("overlay", GateOverlay.canDraw(context)).put("usage", Foreground.hasUsageAccess(context))
            .put("accessibility", WatchAccessibilityService.connected).put("background", idle)
            .put("running", running.get()).put("monitor", GateService.monitorMode)
        last?.let { o.put("last", it) }
        return o
    }

    fun runAsync(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        last = null
        GateService.openingSystemPage() // 暂时放行 2 分钟，免得锁屏盖住电视上的“允许 USB 调试”弹窗
        val worker = Thread {
            val result = try { grant(app) } catch (e: Throwable) { JSONObject().put("ok", false).put("message", friendly(e)) }
            if (running.get()) last = result
        }.apply { isDaemon = true; name = "self-grant" }
        Thread {
            worker.start()
            worker.join(90_000)
            if (worker.isAlive) {
                last = JSONObject().put("ok", false).put("message", "等了 90 秒没有收到电视的确认。请看电视屏幕，有没有弹出“允许 USB 调试吗？”，勾选“始终允许”并点确定后再试一次。")
                worker.interrupt()
            }
            running.set(false)
        }.apply { isDaemon = true; name = "self-grant-watch" }.start()
        return true
    }

    private fun friendly(e: Throwable): String = when {
        e is ConnectException || e.cause is ConnectException -> "连不上本机 ADB（5555 端口）。请确认电视的 ADB 调试已经开启，开启后再点一次。"
        else -> "授权失败：" + (e.message ?: e.javaClass.simpleName)
    }

    private fun grant(ctx: Context): JSONObject {
        val dir = File(ctx.filesDir, "adbkey").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val keys = AdbKeyPair.read(priv, pub)
        val pkg = ctx.packageName
        val service = ComponentName(ctx, WatchAccessibilityService::class.java).flattenToString()
        val steps = JSONArray()
        var allOk = true
        Dadb.create("127.0.0.1", 5555, keys).use { adb ->
            fun sh(label: String, cmd: String): String {
                val r = adb.shell(cmd)
                val ok = r.exitCode == 0 && !r.allOutput.contains("Exception")
                if (!ok) allOk = false
                steps.put(JSONObject().put("step", label).put("ok", ok).put("out", r.allOutput.trim().take(160)))
                return r.output.trim()
            }
            sh("使用情况访问", "appops set $pkg GET_USAGE_STATS allow")
            sh("悬浮窗", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
            val current = sh("读取已有无障碍服务", "settings get secure enabled_accessibility_services")
            val list = if (current.isEmpty() || current == "null") emptyList() else current.split(':')
            if (list.none { it.startsWith("$pkg/") }) {
                sh("开启无障碍服务", "settings put secure enabled_accessibility_services '" + (list + service).joinToString(":") + "'")
            }
            sh("无障碍总开关", "settings put secure accessibility_enabled 1")
            sh("后台运行（省电白名单）", "dumpsys deviceidle whitelist +$pkg")
        }
        return JSONObject().put("ok", allOk).put("steps", steps)
            .put("message", if (allOk) "授权完成。几秒后下面的状态会刷新，如果无障碍还没变绿，强行停止应用再打开一次。" else "有几步没有成功，详情见下方。")
    }
}
