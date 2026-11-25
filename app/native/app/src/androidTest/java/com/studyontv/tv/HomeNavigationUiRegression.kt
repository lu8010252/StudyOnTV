package com.studyontv.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicitly destructive test setup, restricted to a disposable debug emulator. */
object HomeNavigationUiRegression {
    fun freeQuotaGate(instrumentation: Instrumentation): String {
        check(BuildConfig.DEBUG && Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val activities = mutableListOf<Activity>()
        fun openGate(reason: String): Activity {
            val activity = instrumentation.startActivitySync(Intent(context, GateActivity::class.java)
                .putExtra("reason", reason).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activities.add(activity)
            waitUntil(instrumentation) { Foreground.resumedActivity() === activity }
            return activity
        }
        fun exhaustQuota() {
            DeviceRuntime.store.set("free_quota_ms", "0")
            DeviceRuntime.store.set("free_used_ms", "1800000")
            DeviceRuntime.store.set("used_ms", "12000000")
            check(DeviceRuntime.learning.status().getString("reason") == "quota")
        }
        try {
            onMain(instrumentation) {
                DeviceRuntime.ensure(context)
                AdminAuth.setPassword(context, UUID.randomUUID().toString())
                Prefs.setOnboarded(context, true)
                DeviceRuntime.learning.remote("cancel", 0, "test")
                DeviceRuntime.learning.saveConfig(JSONObject().put("allDay", true).put("dailyFreeMinutes", 30)
                    .put("bootFreeMinutes", 0).put("dailyMinutes", 200).put("dailyLimitEnabled", true))
                exhaustQuota()
            }
            val expiredGate = openGate("quota")
            onMain(instrumentation) {
                check(LearningNavigation.shouldBlockHome() && !expiredGate.isFinishing)
                // Simulate clock synchronization moving the stored usage day into the past.
                DeviceRuntime.store.set("usage_day", "0")
                GateService.refreshNow()
            }
            waitUntil(instrumentation) { Foreground.resumedActivity() !== expiredGate }
            onMain(instrumentation) {
                check(expiredGate.isFinishing)
                val status = DeviceRuntime.learning.status()
                check(status.getLong("freeQuotaSeconds") == 1800L && status.getBoolean("canExit"))
            }
            val settingsGate = openGate("settings")
            onMain(instrumentation) { GateService.refreshNow() }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                check(!settingsGate.isFinishing) { "免费额度不能收起设置保护页" }
                settingsGate.finish()
                exhaustQuota()
            }
            val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activities.add(main)
            lateinit var learningWeb: WebView
            onMain(instrumentation) {
                learningWeb = descendants(main.window.decorView).filterIsInstance<WebView>().first()
            }
            waitForJavascript(instrumentation, learningWeb,
                "!!(window.TV && window.TV.state.screen === 'lock' && window.TV.state.lockReason === 'quota')",
                "Learning page did not show the expired quota")
            onMain(instrumentation) { DeviceRuntime.store.set("usage_day", "0") }
            waitForJavascript(instrumentation, learningWeb,
                "!!(window.TV && window.TV.state.learning.canExit && document.getElementById('lockDesc').textContent === '还可以使用 30 分钟')",
                "Recovering the daily quota should show watch time without displaying a new question")
            return "PASS: Daily free quota releases the fullscreen limit gate, restores navigation and avoids automatic quiz prompts.\n"
        } finally {
            onMain(instrumentation) {
                Prefs.setOnboarded(context, false)
                activities.asReversed().forEach { it.finish() }
                context.stopService(Intent(context, GateService::class.java))
            }
        }
    }

    fun run(instrumentation: Instrumentation): String {
        check(BuildConfig.DEBUG && Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val activities = mutableListOf<Activity>()
        val monitor = Instrumentation.ActivityMonitor(null as IntentFilter?, null, false)
        var web: WebView? = null
        var inputId = ""
        val passphrase = UUID.randomUUID().toString()
        var callbackMs = 0L
        instrumentation.addMonitor(monitor)
        try {
            onMain(instrumentation) {
                DeviceRuntime.ensure(context)
                AdminAuth.setPassword(context, passphrase)
                Prefs.setOnboarded(context, true)
                DeviceRuntime.learning.saveConfig(JSONObject().put("allDay", true).put("dailyFreeMinutes", 0)
                    .put("bootFreeMinutes", 0).put("forceFullscreenGate", true))
                DeviceRuntime.learning.remote("lock", 0, "test")
            }
            val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activities.add(main)
            waitUntil(instrumentation) { Foreground.resumedActivity() is MainActivity }
            onMain(instrumentation) { check(LearningNavigation.shouldBlockHome()) }

            // Invoke the actual callback while another thread owns both engine monitor and SQLite transaction.
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                synchronized(DeviceRuntime.learning) {
                    val db = DeviceRuntime.store.writableDatabase
                    db.beginTransaction()
                    try { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    finally { db.endTransaction() }
                }
            }
            try {
                onMain(instrumentation) {
                    // Queue latency from unrelated main-thread work is a separate remaining risk;
                    // this runnable specifically proves the callback itself does not take either lock.
                    holder.start()
                    try {
                        check(held.await(2, TimeUnit.SECONDS))
                        val service = WatchAccessibilityService()
                        val time = SystemClock.uptimeMillis()
                        val start = SystemClock.elapsedRealtime()
                        val homeBefore = WatchAccessibilityService.blockedHomePresses
                        val appSwitchBefore = WatchAccessibilityService.blockedAppSwitchPresses
                        for (code in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)) {
                            check(service.onKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0)))
                            check(service.onKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0)))
                        }
                        check(WatchAccessibilityService.blockedHomePresses == homeBefore + 1)
                        check(WatchAccessibilityService.blockedAppSwitchPresses == appSwitchBefore + 1)
                        callbackMs = SystemClock.elapsedRealtime() - start
                        check(callbackMs < 100) { "Home callback waited for engine/database lock: ${callbackMs}ms" }
                    } finally { release.countDown() }
                }
            } finally { release.countDown(); holder.join(3000) }

            lateinit var learningWeb: WebView
            onMain(instrumentation) {
                learningWeb = descendants(main.window.decorView).filterIsInstance<WebView>().first()
            }
            fun waitForPage(view: WebView) = waitForJavascript(instrumentation, view,
                "!!(window.TV && window.TV.state.learning.canExit === false)", "Learning page did not load its navigation policy")
            waitForPage(learningWeb)
            val noticeText = JSONObject.quote(context.getString(R.string.navigation_unavailable))
            fun expectNotice(view: WebView, action: () -> Unit) {
                javascript(instrumentation, view, "(function(){var n=document.getElementById('toast');if(n)n.className='toast';return true;})()")
                action()
                waitForJavascript(instrumentation, view,
                    "(function(){var n=document.getElementById('toast');if(!n)return false;var r=n.getBoundingClientRect();" +
                        "return n.textContent===$noticeText&&n.className==='toast show'&&r.width>0&&r.height>0&&" +
                        "r.bottom>0&&r.top<innerHeight&&parseFloat(getComputedStyle(n).opacity)>0.9;})()",
                    "Navigation feedback was not visible in the owning WebView")
            }
            val noticeService = WatchAccessibilityService()
            fun checkPageNotices(view: WebView) {
                expectNotice(view) { javascript(instrumentation, view, "window.TV.back(); true") }
                for (code in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)) {
                    expectNotice(view) {
                        onMain(instrumentation) {
                            val time = SystemClock.uptimeMillis()
                            check(noticeService.onKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0)))
                            check(noticeService.onKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0)))
                        }
                    }
                }
            }
            checkPageNotices(learningWeb)

            // Cover the actual full-screen application overlay, where a system Toast would be hidden.
            fun overlayPermission(mode: String) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                OverlayAccess.invalidate()
            }
            overlayPermission("allow")
            try {
                onMain(instrumentation) {
                    DeviceRuntime.learning.saveConfig(JSONObject().put("forceFullscreenGate", false))
                }
                // Settings may force-hide application overlays; use the launcher as the real background.
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitUntil(instrumentation) { !Foreground.hasResumedActivity() && GateService.presentedOverlay() }
                lateinit var overlayWeb: WebView
                onMain(instrumentation) {
                    val serviceField = GateService::class.java.getDeclaredField("instance").apply { isAccessible = true }
                    val service = serviceField.get(null) ?: error("Gate service missing")
                    val overlayField = GateService::class.java.getDeclaredField("overlay").apply { isAccessible = true }
                    val overlay = overlayField.get(service) as GateOverlay
                    val webField = GateOverlay::class.java.getDeclaredField("web").apply { isAccessible = true }
                    overlayWeb = webField.get(overlay) as WebView
                    check(overlay.presenting() && overlayWeb.isShown)
                }
                waitForPage(overlayWeb)
                checkPageNotices(overlayWeb)
                Thread.sleep(250) // Let the compositor present the verified WebView frame.
                val capture = instrumentation.uiAutomation.takeScreenshot() ?: error("Overlay screenshot failed")
                try {
                    val file = java.io.File(context.getExternalFilesDir(null), "navigation-overlay-feedback.png")
                    file.outputStream().use { check(capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { capture.recycle() }
            } finally {
                onMain(instrumentation) {
                    main.startActivity(Intent(main, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                waitUntil(instrumentation) { Foreground.resumedActivity() === main }
                onMain(instrumentation) {
                    GateService.hideGates()
                    DeviceRuntime.learning.saveConfig(JSONObject().put("forceFullscreenGate", true))
                }
                overlayPermission("default")
            }

            onMain(instrumentation) {
                web = WebView(context)
                InputSupport.openText(main, web!!, "草稿", "Home 范围验证")
            }
            val input = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                ?.takeUnless { it === main } ?: waitForInput(instrumentation)
            activities.add(input)
            waitUntil(instrumentation) { AnswerInput.owns(Foreground.resumedActivity() ?: return@waitUntil false) }
            onMain(instrumentation) {
                inputId = input.intent.getStringExtra("requestId") ?: error("Missing input request")
                check(LearningNavigation.shouldBlockHome())
            }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil(instrumentation) { !Foreground.hasResumedActivity() }
            onMain(instrumentation) {
                check(AnswerInput.active)
                check(!LearningNavigation.shouldBlockHome())
            }
            val parent = instrumentation.startActivitySync(Intent(context, ParentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ParentActivity
            activities.add(parent)
            onMain(instrumentation) {
                check(parent.requiresParentVerification && LearningNavigation.shouldBlockHome())
                descendants(parent.window.decorView).filterIsInstance<EditText>().first().setText(passphrase)
                descendants(parent.window.decorView).filterIsInstance<Button>()
                    .first { it.text.toString() == "验证并打开连接页" }.performClick()
            }
            waitUntil(instrumentation) { !parent.requiresParentVerification }
            onMain(instrumentation) { check(!LearningNavigation.shouldBlockHome()) }

            val stopped = ParentActivity::class.java.getDeclaredField("stoppedAt").apply { isAccessible = true }
            var previousStop = 0L
            var expiredStop = 0L
            onMain(instrumentation) { previousStop = stopped.getLong(parent) }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // onPause clears Foreground before onStop records stoppedAt. Wait for that write,
            // otherwise the real onStop can overwrite the simulated elapsed minute below.
            waitUntil(instrumentation) {
                !Foreground.hasResumedActivity() && stopped.getLong(parent) > previousStop
            }
            onMain(instrumentation) {
                expiredStop = (SystemClock.elapsedRealtime() - 61000).also { check(it > 0) }
                stopped.setLong(parent, expiredStop)
                check(stopped.getLong(parent) == expiredStop)
                parent.startActivity(Intent(parent, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitUntil(instrumentation) { Foreground.resumedActivity() === parent }
            onMain(instrumentation) {
                check(stopped.getLong(parent) == expiredStop) { "Parent stop timestamp was overwritten after simulation" }
                check(parent.requiresParentVerification && LearningNavigation.shouldBlockHome())
            }
            onMain(instrumentation) {
                input.startActivity(Intent(input, input.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitUntil(instrumentation) { Foreground.resumedActivity() === input }
            onMain(instrumentation) {
                check(AnswerInput.owns(input) && LearningNavigation.shouldBlockHome())
                DeviceRuntime.learning.remote("unlock", 1, "test")
                check(!LearningNavigation.shouldBlockHome()) { "Unlock must publish before the API returns" }
            }
            return "PASS: actual callback under engine/SQLite lock (${callbackMs}ms); shared Back/Home/App Switch notices in activity and full-screen overlay; input foreground/background; parent verification and timeout; immediate unlock\n"
        } finally {
            instrumentation.removeMonitor(monitor)
            onMain(instrumentation) {
                if (inputId.isNotEmpty()) AnswerInput.complete(inputId, null)
                Prefs.setOnboarded(context, false)
                activities.asReversed().forEach { it.finish() }
                web?.destroy()
                context.stopService(Intent(context, GateService::class.java))
            }
        }
    }

    private fun waitForJavascript(instrumentation: Instrumentation, web: WebView, script: String, failure: String) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (javascript(instrumentation, web, script) != "true") {
            check(SystemClock.elapsedRealtime() < deadline) { failure }
            Thread.sleep(50)
        }
    }

    private fun javascript(instrumentation: Instrumentation, web: WebView, script: String): String? {
        val complete = CountDownLatch(1)
        var result: String? = null
        onMain(instrumentation) { web.evaluateJavascript(script) { result = it; complete.countDown() } }
        check(complete.await(2, TimeUnit.SECONDS)) { "Learning page JavaScript timed out" }
        return result
    }

    private fun waitForInput(instrumentation: Instrumentation): Activity {
        waitUntil(instrumentation) { Foreground.resumedActivity()?.let { AnswerInput.owns(it) } == true }
        return Foreground.resumedActivity() ?: error("Input disappeared")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /** runOnMainSync does not marshal assertion failures; keep them on the runner thread. */
    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
    }

    private fun waitUntil(instrumentation: Instrumentation, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain(instrumentation) { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        error("Navigation lifecycle timed out")
    }
}
