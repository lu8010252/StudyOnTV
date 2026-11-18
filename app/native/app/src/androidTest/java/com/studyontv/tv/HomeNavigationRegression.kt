package com.studyontv.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

/** Isolated database; no household configuration, questions or passwords are changed. */
object HomeNavigationRegression {
    fun run(context: Context) {
        val service = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .installedAccessibilityServiceList.first { it.resolveInfo.serviceInfo.packageName == context.packageName }
        check(service.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
        // #162：按键过滤不再写死在 XML 里，改由 LearningNavigation 的需要在运行时开关，
        // 避免常驻过滤影响厂商音量面板。这里只断言服务保留了申请能力。
        check(!LearningNavigation.allows(null, 1))
        check(!LearningNavigation.allows(LearningExitSnapshot(false, 100), 101))
        check(LearningNavigation.allows(LearningExitSnapshot(true, 100), 101))
        check(!LearningNavigation.allows(LearningExitSnapshot(true, 100), 2101))

        var blocked = true
        var decisions = 0
        val keys = NavigationKeyFilter { decisions++; blocked }
        fun key(action: Int, time: Long, repeat: Int = 0, device: Int = 1, code: Int = KeyEvent.KEYCODE_HOME) =
            KeyEvent(time, time + repeat + 1, action, code, repeat, 0, device, 0)
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 1)))
        blocked = false
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 1, 1)))
        check(keys.handle(key(KeyEvent.ACTION_UP, 1)))
        check(decisions == 1)
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 2)))
        blocked = true
        check(!keys.handle(key(KeyEvent.ACTION_UP, 2)))
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 3)))
        blocked = false
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 4, device = 2)))
        check(keys.handle(key(KeyEvent.ACTION_UP, 3)))
        check(!keys.handle(key(KeyEvent.ACTION_UP, 4, device = 2)))
        blocked = true
        check(!keys.handle(key(KeyEvent.ACTION_UP, 5)))
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 6, 1)))
        check(!keys.handle(key(KeyEvent.ACTION_UP, 6)))
        val before = decisions
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER)) {
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 7, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 7, code = code)))
        }
        check(decisions == before)
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 8)))
        keys.reset()
        check(!keys.handle(key(KeyEvent.ACTION_UP, 8)))

        for (code in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)) {
            blocked = true
            check(keys.handle(key(KeyEvent.ACTION_DOWN, 9, code = code)))
            blocked = false
            check(keys.handle(key(KeyEvent.ACTION_DOWN, 9, 1, code = code)))
            check(keys.handle(key(KeyEvent.ACTION_UP, 9, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 10, code = code)))
            blocked = true
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 10, 1, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 10, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 11, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 12, 1, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 12, code = code)))
            check(keys.handle(key(KeyEvent.ACTION_DOWN, 13, code = code)))
            keys.reset()
            check(!keys.handle(key(KeyEvent.ACTION_UP, 13, code = code)))

            // Same device and downTime: each key must retain its own policy until released.
            val otherCode = if (code == KeyEvent.KEYCODE_HOME) KeyEvent.KEYCODE_APP_SWITCH else KeyEvent.KEYCODE_HOME
            val beforeOverlap = decisions
            check(keys.handle(key(KeyEvent.ACTION_DOWN, 14, code = code)))
            blocked = false
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 14, code = otherCode)))
            check(decisions == beforeOverlap + 2)
            check(keys.handle(key(KeyEvent.ACTION_DOWN, 14, 1, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 14, 1, code = otherCode)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 14, code = otherCode)))
            check(keys.handle(key(KeyEvent.ACTION_UP, 14, code = code)))
        }

        fun chord(action: Int, code: Int, downTime: Long, eventTime: Long, repeat: Int = 0) =
            KeyEvent(downTime, eventTime, action, code, repeat, 0, 1, 0)
        val navigationCodes = listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)
        for (code in navigationCodes) {
            for (other in navigationCodes + listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP)) {
                if (other == code) continue
                for (initialBlock in listOf(true, false)) for (releaseFirst in listOf(true, false)) {
                    keys.reset()
                    blocked = initialBlock
                    check(keys.handle(chord(KeyEvent.ACTION_DOWN, code, 100, 100)) == initialBlock)
                    blocked = !initialBlock
                    val otherBlock = other in navigationCodes && blocked
                    check(keys.handle(chord(KeyEvent.ACTION_DOWN, other, 200, 200)) == otherBlock)
                    val beforeRepeat = decisions
                    check(keys.handle(chord(KeyEvent.ACTION_DOWN, code, 200, 250, 1)) == initialBlock)
                    check(decisions == beforeRepeat) { "A repeat with changed downTime must retain its decision" }
                    if (releaseFirst) {
                        check(keys.handle(chord(KeyEvent.ACTION_UP, code, 200, 300)) == initialBlock)
                        check(keys.handle(chord(KeyEvent.ACTION_UP, other, 200, 350)) == otherBlock)
                    } else {
                        check(keys.handle(chord(KeyEvent.ACTION_UP, other, 200, 300)) == otherBlock)
                        check(keys.handle(chord(KeyEvent.ACTION_UP, code, 200, 350)) == initialBlock)
                    }
                    check(!keys.handle(chord(KeyEvent.ACTION_UP, code, 200, 400)))
                }
            }
            // If a device loses UP, a fresh DOWN must replace the stale decision.
            blocked = true
            check(keys.handle(chord(KeyEvent.ACTION_DOWN, code, 500, 500)))
            blocked = false
            check(!keys.handle(chord(KeyEvent.ACTION_DOWN, code, 600, 600)))
            check(!keys.handle(chord(KeyEvent.ACTION_UP, code, 600, 650)))
        }

        val database = "home-navigation-regression.db"
        context.deleteDatabase(database)
        val store = LocalStore(context, database)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("allDay", true).put("dailyFreeMinutes", 0)
                .put("bootFreeMinutes", 0).put("questionsPerRound", 1).put("extraEnabled", false)
                .put("wrongReviewSeconds", 0).put("roundsLimitEnabled", false))
            check(!engine.exitSnapshot.canExit)
            engine.remote("unlock", 1, "test")
            check(engine.exitSnapshot.canExit)
            engine.remote("cancel", 0, "test")
            check(!engine.exitSnapshot.canExit)
            engine.remote("credit", 1, "test")
            check(engine.exitSnapshot.canExit)
            engine.saveConfig(JSONObject().put("allDay", false).put("weekSlots", JSONArray(List(7) { "0".repeat(48) })))
            check(!engine.exitSnapshot.canExit)
            engine.saveConfig(JSONObject().put("allDay", true))
            check(engine.exitSnapshot.canExit)
            val originalChild = store.activeChild()
            val second = store.createChild("测试孩子").getString("id")
            engine.switchChild(second)
            check(!engine.exitSnapshot.canExit)
            engine.switchChild(originalChild)
            check(engine.exitSnapshot.canExit)
            repeat(12) { engine.consume(5000) }
            check(!engine.exitSnapshot.canExit)
            store.upsertQuestion(JSONObject().put("id", "home-q").put("q", "1+1=?")
                .put("a", JSONArray(listOf("2", "3"))).put("k", 0))
            val round = engine.start()
            val answer = engine.answer(JSONObject().put("roundId", round.getString("id"))
                .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(0)))
            check(answer.getBoolean("finished"))
            check(answer.getJSONObject("status").getBoolean("canExit") && engine.exitSnapshot.canExit)
            engine.remote("unlock", 1, "test")
            val override = JSONObject(store.get("remote_override"))
                .put("until", System.currentTimeMillis() + 1000)
                .put("untilElapsed", SystemClock.elapsedRealtime() + 1000)
            store.set("remote_override", override.toString())
            engine.remote("credit", -480, "test")
            check(engine.exitSnapshot.canExit)
            Thread.sleep(1100)
            check(!engine.status().getBoolean("canExit") && !engine.exitSnapshot.canExit)
        } finally {
            store.close()
            context.deleteDatabase(database)
        }
    }
}
