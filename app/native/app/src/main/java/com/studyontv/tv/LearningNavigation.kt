package com.studyontv.tv

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/** Only immutable, already-computed state is read on the accessibility key path. */
data class LearningExitSnapshot(val canExit: Boolean, val publishedAt: Long)

object LearningNavigation {
    private const val REFRESH_MS = 1000L
    private const val ALLOW_LEASE_MS = 2000L
    @Volatile private var engine: LearningEngine? = null
    private var worker: Handler? = null
    private val refresh = object : Runnable {
        override fun run() {
            try { engine?.status() } catch (e: Exception) {
                android.util.Log.w("StudyOnTV", "Navigation state refresh failed", e)
            } finally { worker?.postDelayed(this, REFRESH_MS) }
        }
    }

    private val noticeHandler = Handler(Looper.getMainLooper())
    private val noticePending = AtomicBoolean(false)
    private var notice: Toast? = null

    /** Shared by WebView Back and the accessibility key path; UI work never runs in onKeyEvent. */
    fun notifyBlocked(context: Context) {
        if (!noticePending.compareAndSet(false, true)) return
        val app = context.applicationContext
        noticeHandler.post {
            noticePending.set(false)
            // A queued notice must not appear after an unlock or on an unrelated screen.
            if (!shouldBlockHome()) return@post
            notice?.cancel()
            // System Toast windows can sit below our full-screen overlays. Render in the owning page.
            if (WatchAccessibilityService.showNavigationBlocked() || GateService.showNavigationBlocked() ||
                (Foreground.resumedActivity() as? MainActivity)?.showNavigationBlocked() == true) return@post
            notice = Toast.makeText(app, R.string.navigation_unavailable, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /** Main-thread rendering shared by activity and overlay WebViews; native input pages use Toast. */
    fun showBlockedNotice(web: WebView?): Boolean {
        if (web == null || !web.isAttachedToWindow || !web.isShown) return false
        val message = org.json.JSONObject.quote(web.context.getString(R.string.navigation_unavailable))
        return try {
            web.evaluateJavascript("window.SOTV_NAVIGATION_BLOCKED&&window.SOTV_NAVIGATION_BLOCKED($message)", null)
            true
        } catch (_: RuntimeException) { false }
    }

    /** Called once the runtime owns this engine; isolated test engines never publish globally. */
    fun start(value: LearningEngine) {
        engine = value
        if (worker == null) {
            val thread = HandlerThread("LearningNavigation").apply { start() }
            worker = Handler(thread.looper)
        }
        worker?.removeCallbacks(refresh)
        worker?.post(refresh)
    }

    val snapshot: LearningExitSnapshot? get() = engine?.exitSnapshot
    fun policyAllowsExit(now: Long = SystemClock.elapsedRealtime()): Boolean = allows(snapshot, now)

    internal fun allows(current: LearningExitSnapshot?, now: Long): Boolean {
        current ?: return false
        // A stalled refresher must not leave an expired temporary unlock open indefinitely.
        return current.canExit && now - current.publishedAt in 0..ALLOW_LEASE_MS
    }

    fun shouldBlockHome(): Boolean {
        val activity = Foreground.resumedActivity()
        val learningSurface = when (activity) {
            is MainActivity -> true
            is ParentActivity -> activity.requiresParentVerification
            null -> GateService.presentedOverlay() || WatchAccessibilityService.presentedGate()
            else -> AnswerInput.owns(activity)
        }
        return learningSurface && !policyAllowsExit()
    }
}

/** Keep the same decision for DOWN, repeats and UP, even if the policy changes mid-press. */
class NavigationKeyFilter(private val blocked: () -> Boolean) {
    private data class Key(val deviceId: Int, val keyCode: Int)
    private val presses = mutableMapOf<Key, Boolean>()

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME && event.keyCode != KeyEvent.KEYCODE_APP_SWITCH) return false
        val key = Key(event.deviceId, event.keyCode)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Chording any key can change downTime, including Back/volume keys we pass through.
                // Only a fresh DOWN starts a new decision; repeats retain it until this key's UP.
                if (event.repeatCount == 0) blocked().also { presses[key] = it }
                else presses[key] == true
            }
            KeyEvent.ACTION_UP -> presses.remove(key) == true
            else -> false
        }
    }

    fun reset() = presses.clear()
}
