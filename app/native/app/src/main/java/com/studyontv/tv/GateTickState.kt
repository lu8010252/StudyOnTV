package com.studyontv.tv

/**
 * 一次 tick 的事实快照（纯数据，无副作用）。
 *
 * 拆出来是为了让「计时、静音、弹窗、角标」读同一份判断，不再各算各的
 * （#110 静音与弹窗同源、#17 能力缺失、#93 前台识别失败都在这里汇总）。
 */
data class GateTickState(
    val at: Long,
    val free: Boolean,
    val awake: Boolean,
    val screenOn: Boolean,
    val monitoring: Boolean,
    val canOverlay: Boolean,
    val ownForeground: Boolean,
    val foregroundPackage: String?,
    /** 这一 tick 是否应该有一个 Gate（静音与弹窗共用） */
    val gateExpected: Boolean,
    val gateReason: String,
    val settingsBlocked: Boolean,
    /** 是否计入使用时长 */
    val active: Boolean,
    /** 是否有需要拦截的场景（前台应用不可豁免） */
    val protecting: Boolean
)
