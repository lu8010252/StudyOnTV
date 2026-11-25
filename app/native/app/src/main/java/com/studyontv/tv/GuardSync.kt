/*
 * GuardSync / GuardSyncService / GuardAccountService
 *
 * 同步框架自愈：适配器结构参考并改写自 Aerial TV (aerial-tv) 的 RevivalSync.kt / GuardSetup.kt，
 * 原项目：https://github.com/pengchujin/aerial-tv ，按 MIT 许可使用。
 *
 * MIT License
 *
 * Copyright (c) 2026 pengchujin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.studyontv.tv

import android.Manifest
import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * 同步框架自愈（结构参考 Aerial TV 的 RevivalSync，MIT，出处见文件头）。
 *
 * 小米等电视固件在待机 / 冷启动时对第三方应用执行 force-stop，系统会随之把该应用的无障碍服务
 * 从 enabled_accessibility_services 里摘掉；force-stop 同时会拦掉广播、闹钟和 JobScheduler，
 * 但**拦不住同步框架**——同步任务由 system_server 里的 SyncManager 调度，adapter 的注册信息
 * 在系统侧缓存，到点时系统会显式 bind 拉起进程执行 onPerformSync。
 *
 * 把 adapter 挂在系统的 "settings" authority 上有两个触发源：
 * 1. 事件同步：SettingsProvider 每次写入都带 SYNC_EXTRAS_UPLOAD 触发 LOCAL 同步。固件强停时
 *    「把自己从无障碍列表里摘掉」本身就是一次 settings 写入，等于系统杀应用的瞬间顺手触发了复活。
 * 2. 周期同步：addPeriodicSync 兜底（系统最小 15 分钟）。包处于 stopped=true 时也照样能拉起进程，
 *    而任何一次「系统主动拉起」都会自动清掉 stopped 标记，之后 BOOT_COMPLETED 才能正常送达。
 *
 * 注意：guard_sync.xml 里 supportsUploading 必须为 true，否则 SyncManager 会直接丢弃
 * 带 SYNC_EXTRAS_UPLOAD 的 LOCAL 同步，事件通道会完全失效（只剩周期同步）。
 *
 * 同步不做任何数据交换，只做自愈：修复被摘掉的无障碍服务（需要 WRITE_SECURE_SETTINGS，
 * adb 一次性授权）、并把 GateService 拉起来。未授权时 heal() 静默跳过，不影响任何功能。
 */
object GuardSync {
    const val TAG = "StudyOnTV-Guard"
    private const val AUTHORITY = "settings"

    /** 必须与 res/xml/guard_account.xml、res/xml/guard_sync.xml 里的 accountType 一致 */
    private const val ACCOUNT_TYPE = "com.studyontv.tv"

    /** 周期同步（秒）。请求 300s，系统会钳到最小值 900s。 */
    private const val PERIODIC_SYNC_SECONDS = 300L

    /** 「串在但服务没绑上」时的重绑冷却，防止写 settings → 触发同步 → 再重绑的风暴 */
    internal const val REBIND_COOLDOWN_MS = 10 * 60 * 1000L

    @Volatile
    private var lastRebindAt = -1L

    /** 首次冷启动（lastRebindAt < 0）允许立即重绑，之后严格受冷却间隔保护（ST-105） */
    internal fun canRebind(lastRebindAt: Long, now: Long, cooldownMs: Long = REBIND_COOLDOWN_MS): Boolean =
        lastRebindAt < 0L || now - lastRebindAt > cooldownMs

    /** 注册（或确认已注册）同步账户与周期同步。进程每次启动都调用，幂等、开销可忽略。 */
    fun ensure(context: Context) {
        val app = context.applicationContext
        // 未授权时什么都不注册：不在系统账户列表里留下用不到的条目（本特性默认关闭）
        if (!hasWriteSecureSettings(app)) return
        runCatching {
            val am = AccountManager.get(app)
            var created = false
            val account = am.getAccountsByType(ACCOUNT_TYPE).firstOrNull()
                ?: Account("StudyOnTV", ACCOUNT_TYPE).also {
                    if (!am.addAccountExplicitly(it, null, null)) {
                        log(app, "sync account registration refused")
                        return
                    }
                    created = true
                }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            }
            // 周期同步兜底：冷启动后进程处于 stopped、广播全被拦，也只有它能保证被拉起
            if (ContentResolver.getPeriodicSyncs(account, AUTHORITY).isEmpty()) {
                ContentResolver.addPeriodicSync(account, AUTHORITY, Bundle.EMPTY, PERIODIC_SYNC_SECONDS)
            }
            if (!ContentResolver.getMasterSyncAutomatically()) {
                log(app, "system master sync is off; sync revival unavailable")
            }
            if (created) {
                // 首次注册立刻走一轮，省掉最多 15 分钟的等待
                ContentResolver.requestSync(account, AUTHORITY, Bundle.EMPTY)
                log(app, "sync account registered; sync framework can now revive the app")
            }
        }.onFailure { log(context, "guard sync setup failed: ${it.message}") }
    }

    /**
     * 修复权限 / 拉起服务（对应 Aerial 的 ensureEnabled + rebindIfStalled）：
     * - 持有 WRITE_SECURE_SETTINGS 时，把本应用的无障碍服务补回 enabled 列表（只追加自己的段，
     *   保留其他应用/厂商桌面守护的段），并确保无障碍总开关开启；
     * - 串还在但服务没绑上时，摘掉再补回，逼系统重新绑定（10 分钟最多一次）；
     * - GateService 不在运行时拉起它。
     * 未授权时静默返回，不影响任何功能。
     */
    fun heal(context: Context) {
        runCatching {
            val app = context.applicationContext
            if (hasWriteSecureSettings(app)) {
                val resolver = app.contentResolver
                val me = ComponentName(app, WatchAccessibilityService::class.java).flattenToString()
                val current = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val entries = current.split(':').filter { it.isNotEmpty() }
                val present = entries.any { it.equals(me, true) }
                when {
                    !present -> {
                        Settings.Secure.putString(
                            resolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            (entries + me).joinToString(":")
                        )
                        log(app, "accessibility healed: re-added to enabled services")
                    }
                    !WatchAccessibilityService.connected &&
                        canRebind(lastRebindAt, SystemClock.elapsedRealtime()) -> {
                        // 与 Aerial 的 rebindIfStalled 相同：两次连续写入会被系统合并，
                        // 先摘掉，1.5 秒后再补回（补回时重新读列表，不覆盖别人的段）。
                        lastRebindAt = SystemClock.elapsedRealtime()
                        Settings.Secure.putString(
                            resolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            entries.filterNot { it.equals(me, true) }.joinToString(":")
                        )
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val now = Settings.Secure.getString(
                                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                                ).orEmpty().split(':').filter { it.isNotEmpty() }
                                if (now.none { it.equals(me, true) }) {
                                    Settings.Secure.putString(
                                        resolver,
                                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                        (now + me).joinToString(":")
                                    )
                                }
                                log(app, "accessibility rebound (enabled but not bound)")
                            }
                        }, 1_500)
                    }
                }
                // 无障碍总开关兜底：有条件写，避免每次同步都产生新写入
                if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            }
            // 进程已被同步框架 bind 拉起，把门卫服务一并带上
            GateService.ensureRunning(app, "guard-sync")
        }.onFailure { log(context, "heal failed: ${it.message}") }
    }

    /** 诊断用：adb 是否已授予自愈所需的权限、同步账户是否已就绪 */
    fun status(context: Context): String {
        val granted = hasWriteSecureSettings(context)
        val account = runCatching { AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull() }.getOrNull()
        val syncable = account?.let { ContentResolver.getIsSyncable(it, AUTHORITY) > 0 } ?: false
        val periodic = account?.let { ContentResolver.getPeriodicSyncs(it, AUTHORITY).isNotEmpty() } ?: false
        return "wss=$granted account=${account != null} syncable=$syncable periodic=$periodic"
    }

    /** 用 PackageManager 判断而不是 Context.checkSelfPermission：后者是 API 23+，本项目 minSdk 21 */
    private fun hasWriteSecureSettings(context: Context): Boolean =
        context.packageManager.checkPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS, context.packageName
        ) == PackageManager.PERMISSION_GRANTED

    private fun log(context: Context, message: String) {
        // 复活往往发生在 Diag 初始化之前，logcat 也要留一条
        android.util.Log.i(TAG, message)
        runCatching { Diag.info("Guard", message) }
    }
}

/** 同步适配器宿主服务：每次 settings 写入 / 周期到期后的本地同步都从这里进来。 */
class GuardSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(
            account: Account, extras: Bundle, authority: String,
            provider: ContentProviderClient, result: SyncResult
        ) {
            // 同步时机本身就是「系统还记得我们」的信号，趁机自检自愈
            GuardSync.ensure(context)
            GuardSync.heal(context)
        }
    }
}

/** 账户类型托管服务；不提供任何真实的登录能力，只为让 SyncManager 有一个可调度的账户。 */
class GuardAccountService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?) = null
        override fun addAccount(
            response: AccountAuthenticatorResponse?, accountType: String?,
            authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?
        ) = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?) = null
        override fun getAuthToken(
            response: AccountAuthenticatorResponse?, account: Account?,
            authTokenType: String?, options: Bundle?
        ) = null
        override fun getAuthTokenLabel(authTokenType: String?) = null
        override fun updateCredentials(
            response: AccountAuthenticatorResponse?, account: Account?,
            authTokenType: String?, options: Bundle?
        ) = null
        override fun hasFeatures(
            response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?
        ) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
