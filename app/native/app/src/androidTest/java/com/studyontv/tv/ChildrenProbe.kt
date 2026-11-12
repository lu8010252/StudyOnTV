package com.studyontv.tv

import android.content.Context
import org.json.JSONObject

/**
 * 多孩子锁屏冒烟（只在测试设备上跑）：
 * `adb shell am instrument -w -e children 1 <runner>`
 *
 * 建一个第二个孩子档案，并把当日额度清零，让学习页停在「时间到了」的锁屏上。
 * 之后手动 `am start` 主界面截图，确认锁屏按孩子列出「开始学习 · 名字」。
 * 只修改测试设备自己的数据。
 */
object ChildrenProbe {
    fun run(context: Context): String = StringBuilder().also { out ->
        DeviceRuntime.ensure(context)
        val store = DeviceRuntime.store
        val engine = DeviceRuntime.learning
        val names = (0 until store.children().length()).map { store.children().getJSONObject(it).getString("name") }
        if ("小红" !in names) store.createChild("小红")
        if ("小明" !in names) store.createChild("小明")
        engine.saveConfig(JSONObject().put("allDay", true).put("scheduleMode", "allow")
            .put("dailyLimitEnabled", true).put("dailyMinutes", 40).put("roundsLimitEnabled", true))
        store.set("remote_override", "")
        store.set("active_round", "")
        store.set("credit_ms", "0")
        store.set("free_quota_ms", "0")
        store.set("boot_quota_ms", "0")
        store.set("used_ms", "0")
        val status = engine.status()
        out.append("reason=${status.optString("reason")} active=${store.activeChild()}\n")
        out.append("children=").append(store.children().toString()).append('\n')
    }.toString()
}
