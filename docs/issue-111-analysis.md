# #111 SyncAdapter 自愈：技术分析与决策建议

**结论：本版不做。** 若后续要用一条"系统主动 bind"的通道，优先评估 **通知监听心跳 + 诊断**，而不是 SyncAdapter；且必须默认关闭、需家长显式授权、不读取任何通知内容。

本文只做分析与决策建议，不含实现代码。信息来源：`gh issue view 111 --repo chadyi/StudyOnTV --comments`（@cx5311 的方案与参考补丁）、`docs/keepalive-analysis.md` 的 A/B/G 节、当前工作区的 `GateService.kt` / `Watchdog.kt` / `BootReceiver.kt` / `AndroidManifest.xml`。**本轮未编译、未安装、未连接任何设备**；凡涉及固件行为与 AOSP 内部校验点的结论都标为**未验证**，与 `keepalive-analysis.md` 的标注口径一致。

---

## 1. #111 方案摘要

### 1.1 结构（据补丁）

| 文件 | 作用 |
| --- | --- |
| `GuardSync.kt`（新增） | `GuardSync.ensure()` 注册本地账户 + 打开 `settings` authority 的可同步/自动同步；`GuardSync.heal()` 用 `WRITE_SECURE_SETTINGS` 把无障碍服务串补回、并拉起 `GateService`；`GuardSyncService`（`AbstractThreadedSyncAdapter`）在 `onPerformSync` 里调 `ensure + heal`；`GuardAccountService`（`AbstractAccountAuthenticator`，全部方法返回 `null`） |
| `res/xml/guard_account.xml` | `accountType=com.studyontv.tv`，账户名 `StudyOnTV` |
| `res/xml/guard_sync.xml` | `contentAuthority="settings"`、`isAlwaysSyncable`、`userVisible=false`、不支持的 `supportsUploading=false` |
| `AndroidManifest.xml` | 声明 `WRITE_SECURE_SETTINGS`（`tools:ignore="ProtectedPermissions"`）+ 注册两个服务 |
| `BootReceiver.kt` | 开机 / `MY_PACKAGE_REPLACED` / `SCREEN_ON` / `CONNECTIVITY_CHANGE` / `REVIVE_ACTION` 路径插入 `ensure + heal` |
| `DeviceRuntime.kt` | App 每次启动 `ensure + heal` |

核心假设（补丁注释原话）：同步框架的同步任务"属于系统进程，force-stop 不会清掉它们，且系统仍会拉起该应用来执行同步"；且"`SettingsProvider` 每次带 `syncToNetwork` 的设置写入（包括固件摘除无障碍的那次写入本身）都会在约 30 秒内触发一次本地同步"。

许可：结构改写自 Aerial TV（MIT, Copyright (c) 2026 pengchujin），补丁保留了版权头。若真要合入，仍需在 `THIRD_PARTY_NOTICES.md` 补一条（本仓库现有惯例）。

### 1.2 它声称能解决 / 不能解决

| 场景 | 补丁的态度 | 我们的判断 |
| --- | --- | --- |
| 固件把无障碍服务从 `enabled_accessibility_services` 摘掉（#77 当贝类、#73 小米清理后的常见后果） | **能**（有 `WRITE_SECURE_SETTINGS` 时自行补回） | 补回无障碍这件事本身成立（前提是 adb 授权过），但它只解决"授权还在、串被摘掉"这一种；不解决"服务已 crashed/固件不再 bind" |
| 只杀进程（`killAllThirdApp`、一键加速） | 能缩短空窗 | 与现有 JobScheduler(15min) + AlarmManager(5–20min) + 无障碍绑定（下一次窗口切换）**同量级**，边际收益主要是"同步由系统触发，不占我们的作业配额" |
| 系统强停（设置里"强行停止"、`am force-stop`、厂商固件定向 force-stop，#48/#67/#70/#76） | 隐含声称能（注释写"force-stop 也会杀掉普通的前台服务和 JobScheduler，但同步任务不会被清掉"） | **不成立或至少未被证明**：stopped 包不会被系统调起，见第 2 节。补丁作者的验证记录里也没有 `am force-stop` 前后对照实验 |
| 卸载后重装、重启后 ADB 关闭、Home 键逃逸 | 不涉及 | 同 `keepalive-analysis.md`：只有外部 ADB / Device Owner 能覆盖 |

### 1.3 证据强度核查（重要）

评论里的"真机验证结果（2026-11-09，MiTV-MFFU0 / Android 14）"实际记录的是：

- `dumpsys account` 里账户注册成功 → **只证明 `ensure()` 生效**；
- 覆盖安装后无障碍串被补回 → **只证明 `heal()` 在被调用时生效**（而覆盖安装本身会清 stopped 标志，这一条不能说明抗强停）；
- "进程被强停后，settings 写入触发的同步能把进程拉回" → **没有给出实验步骤与时间戳**：没写用 `am force-stop` 还是厂商清理、没写等待多久、没有 `dumpsys`/`logcat` 前后对照；
- 全程未重启电视，未在第二台机型验证。

结论：这条补丁**证明了"账户与同步适配器能注册成功、heal 逻辑能跑通"，没有证明"stopped 状态能被同步拉回"**。而这正是它唯一相对现有三通道的增量价值所在。按本仓库的验证口径（`docs/keepalive-analysis.md`、`docs/issue-triage.md` 都区分"已实现"与"已真机验证"），它目前只能算"未验证的实验特性"。

---

## 2. force-stop 与 stopped 包的真实语义

### 2.1 `force-stop` 到底做了什么

`ActivityManagerService.forceStopPackageLocked` 依次做四件事（AOSP 长期行为，**本轮未在设备上复核**）：

1. 杀掉该包所有进程（不走 `onDestroy`）；
2. 取消该包所有 `AlarmManager` 闹钟、`JobScheduler` 作业、挂起的 `PendingIntent`；
3. 解绑该包的无障碍服务、通知监听、输入法等系统托管绑定；
4. 给包打上 `PackageManager.FLAG_STOPPED`（stopped）。

**只有两条路能清掉 stopped 标志**：用户**显式**启动（点图标、`am start`）、或该包被安装/更新（安装器会清 `FLAG_STOPPED`）。应用自己在进程里没有任何 API 能清它——这就是 `GateService.ensureRunning`（`GateService.kt:529-533`）在强停后必然失手的原因。

### 2.2 哪些组件"还能被系统 bind"，哪些不能

| 组件 / 通道 | 进程被清理（未设 stopped） | stopped 状态 | 关键校验点（未验证） |
| --- | --- | --- | --- |
| 前台服务 + `START_STICKY` | ✅ 系统重建 | ❌ 不重建 | `ActiveServices` 启动校验 |
| `AlarmManager` `setInexactRepeating` | ✅ 到点广播 | ❌ 闹钟已被清空 | `AlarmManagerService` 强停时清该包闹钟 |
| `JobScheduler`（`setPersisted`） | ✅ 15 分钟内 | ❌ 作业被取消、stopped 包不执行 | `JobSchedulerService` 强停处理 |
| 隐式广播（`SCREEN_ON`/`CONNECTIVITY_CHANGE`） | ✅ 显式注册的接收者仍能收 | ❌ stopped 包被过滤 | `BroadcastQueue` 的 `FLAG_STOPPED` 过滤 |
| 无障碍服务被系统 bind | ✅（下一次窗口变化或重连） | ❌ 被 unbind，且不再重连 | `AccessibilityManagerService` 的 package-stopped 判定 |
| **SyncAdapter 被系统调起** | ✅ 通常有效（系统 sync 管理器/作业到点 bind） | ⚠️ **争议点**：Android 8+ 同步由 `SyncJobService`/JobScheduler 承载，强停会连带取消；AOSP 是否存在"同步跳过 stopped 包"的显式判定，本轮**无法确证** | `SyncManager` / `SyncStorageEngine`；补丁未做验证 |
| `NotificationListenerService` 被系统 rebind | ✅ 常驻绑定，重连时机由 `NotificationManagerService`/`ManagedServices` 决定 | ❌ 同样被 unbind，且 stopped 包不会被 rebind | 同上 |
| `AccountAuthenticator` 被 bind | ✅ 只有"有人请求该账户类型的操作"时 | ❌ 同上 | 账户管理器无主动轮询能力 |
| 外部 `adb shell am start` | ✅ | ✅ **唯一确定有效** | shell 权限 |

需要特别区分两种"杀"（这是所有讨论的前提，也是 `keepalive-analysis.md` A2 的核心）：

- **只杀进程**：不设 stopped，闹钟/作业/无障碍绑定都还在 → 现有三通道 + SyncAdapter 都能自愈，差别只是空窗长度；
- **系统强停**：设 stopped → 应用内全灭，代码侧无解，只有外部 ADB（`docs/background-recovery.md` 的 `scripts/device-watchdog.py`）或安装更新能救。

#111 想要的是第二种，而第二种恰恰是 SyncAdapter 大概率拿不到的那种。

### 2.3 补丁自身的技术疑点（除"能不能抗强停"之外）

1. **`GuardSyncService` 缺少官方要求的声明**：Google 的同步适配器样例是 `android:exported="true"` + `android:permission="android.permission.BIND_SYNC_ADAPTER"`，补丁写成 `exported="false"` 且无权限。`system_server` 因 UID=1000 通常仍能 bind，但这属于依赖未文档化行为，跨固件风险高。
2. **`heal()` 只写 `ENABLED_ACCESSIBILITY_SERVICES`，没写 `ACCESSIBILITY_ENABLED=1`**。若固件把开关本身置 0，补回服务串是无效的（读侧要求两者同时成立）。
3. **读-改-写竞态**：`heal()` 先 `getString` 再 `putString` 整串覆盖。若同一时刻另一个守护应用（或系统本身）也在写这一行，会互相覆盖——补丁注释里的"只追加自己的段"只在"没有并发写者"时成立。
4. **同步触发条件依赖厂商实现**：settings 写入是否以 `syncToNetwork=true` 通知变更、`SettingsProvider` 是否参与该路径，是"约 30 秒内触发"成立的前提；这条前提没有文档保证。
5. **账户列表污染**：`Account("StudyOnTV", ...)` 会出现在系统"账户"里（多数电视该入口被裁剪，反而更难排查）；家长看到"题了个题"账户容易当成可疑行为。
6. **`WRITE_SECURE_SETTINGS` 是整机级权限**：能改的是 `Settings.Secure` 全局表，`heal()` 每次启动都读写它。这与 `keepalive-analysis.md` F3 的立场（"不自动改固件整机开关，只在诊断页给命令让家长自行决定"）直接冲突，需要在产品层面先统一口径。

---

## 3. 方案对比表

图例：✅ 有效 ／ ⚠️ 通常有效但依赖固件，未验证 ／ ❌ 无效

| 方案 | 覆盖：只杀进程 | 覆盖：系统强停（stopped） | 覆盖：无障碍串被摘 | 权限成本 | 隐私 / 上架风险 | 厂商兼容性 | 可观测性 | 实现量 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **现有三通道**（前台服务 + JobScheduler 15min + AlarmManager 15min + 无障碍绑定）见 `GateService.kt:485-487`、`Watchdog.kt:26-52`、`WatchAccessibilityService` | ✅ 空窗 ≤15–20min，无障碍在身时秒级 | ❌ | ❌（系统 unbind 后不再连） | 无额外（已有） | 低 | 小米/海信/TCL 上已被用户报告"有时不生效" | 中（`Watchdog.report`、`Diag`） | 已实现 |
| **SyncAdapter（#111）** | ⚠️ 与上者同量级，边际收益小 | ❌ 或 ⚠️ **未验证**；Android 8+ 同步走 JobScheduler，强停大概率连带取消 | ✅ 若持有 `WRITE_SECURE_SETTINGS` 且只被摘串 | 中：adb 一次性 `pm grant WRITE_SECURE_SETTINGS`；账户 + 同步 + 2 个服务 + 3 个 xml | **中高**：整机账户列表可见；自动改 `Settings.Secure`；"保活/自启"是国内商店审核敏感项；引入 MIT 代码需补 `THIRD_PARTY_NOTICES.md` | ⚠️ 依赖 `SyncManager` 未被固件裁剪、系统"自动同步"总开关未被关（电视上常被关） | 差：同步调起时机不可控，`dumpsys account`/`sync` 只能看状态 | 中（约 150–300 行 + 清单/xml + 面板文案） |
| **NotificationListenerService 心跳** | ✅ 系统常驻绑定，rebind 由 `NotificationManagerService` 决定 | ❌ 同样被 unbind（**不是强停解药**） | ❌（但可发现"被摘"并提示） | 中：用户在"通知使用权"里授权（比 adb 友好，电视上入口可能缺失） | **中**：可读全部通知 = 敏感；必须承诺只写时间戳、不读内容、不上传；商店审核同样关注 | ⚠️ 电视 ROM 常裁剪"通知使用权"入口；部分固件 NLS 不常驻 | **好**：`onListenerConnected/onListenerDisconnected` 天然是心跳信号，`dumpsys notification` 可查 | 中低（约 100–200 行） |
| **无障碍（已用 + 加强：`connected` 与 `enabled` 分离判定）** | ✅ 最强（下一次窗口切换即拉起） | ❌ | — | 已付 | 高敏感权限，但产品语义正当（拦截页） | ⚠️ 小米/当贝会标记 crashed 后不再连 | 好（`WatchAccessibilityService.connected`） | 小（`GateService.kt:290-320` 已有雏形，A3-4 建议项） |
| **JobScheduler + AlarmManager 加强**（`setExactAndAllowWhileIdle` 需 `SCHEDULE_EXACT_ALARM`、前台服务化作业） | ✅ 可把空窗压到 <5min | ❌ | ❌ | 低-中（精确闹钟在 Android 12+ 需用户授权） | 低 | ⚠️ 部分固件整体掐 JobScheduler | 中 | 小 |
| **无障碍 + Device Owner（kiosk）** | ✅ | ⚠️ DO 不能被普通设置强停，但仍可能被固件清理 | — | 高（adb `dpm set-device-owner`，多数在用电视做不到） | 高（显著改变设备行为，退出需恢复出厂） | ⚠️ 视固件 | 好 | 中（`UninstallGuard` 已有部分） |
| **外部 ADB 看门狗**（`deploy/watchdog/**`、`scripts/device-watchdog.py`） | ✅ | ✅ **唯一确定能清 stopped 的手段**（`am start`） | ✅（`--repair-accessibility`） | 高（常开主机 + 局域网 ADB 常开 + 保留 ADB 私钥） | 高（ADB = shell 权限暴露在局域网） | ⚠️ 重启后 ADB 可能被关（未验证） | 好（JSON 汇总） | 已实现 |

一句话：**在这张表里，SyncAdapter 的列全是"⚠️/❌ + 差的可观测性 + 最高的合规摩擦"，它不占据任何一格独占优势。** 能做到的事，现有三通道和无障碍已经覆盖；做不到的事（stopped），它和它们一样做不到。

---

## 4. 对 StudyOnTV 的建议

### 4.1 这一版不做（已定）

理由（按权重）：

1. **不解决目标问题**：它想救的 #48/#67/#70/#76 是 stopped 场景，而 stopped 包不会被系统调起；补丁也没有提供能反驳这一点的实验证据（1.3 节）。
2. **边际收益低**：对"只杀进程"场景，`Watchdog` 的 15min 作业 + 15min 闹钟 + 无障碍绑定已经在做同一件事，SyncAdapter 只是"再来一条同量级通道"。
3. **合规与心智成本高**：账户列表可见、"自动改 `Settings.Secure`"、"后台自启/保活"叙事，与一个家长管控类产品的信任模型相冲突；还牵出 MIT 代码归属与 `THIRD_PARTY_NOTICES.md` 维护。
4. **可验证性差**：调起时机不可控，出问题后家长和我们都无法归因，等于把"看不清"的问题再加一层。
5. **当前更高价值的活是"看得见"而不是"再多一条通道"**：`docs/keepalive-analysis.md` A3 的四条（心跳落盘、`lastActivePackage` 持久化、启动原因与上次退出配对、"enabled 但 connected=false" 区分）都是零权限、零合规风险，直接回答"这次为什么没起来"。

对应动作：issue 保持 open，标注"本版不做，分析见 `docs/issue-111-analysis.md`"；**不合并参考补丁**；不在清单里加 `WRITE_SECURE_SETTINGS`、账户或同步服务。

### 4.2 若后续要做，建议顺序

1. **先做 A3 的诊断类改动**（零风险），它们会让后续任何一条新通道的效果可测量；
2. **再评估"通知监听心跳 + 诊断"**，而不是 SyncAdapter。产品约束（如果真做，必须写进文档与 UI）：
   - **默认关闭**，作为独立实验特性，出现在权限页而不是自动开启；
   - **需要家长在系统"通知使用权"页显式授权**，应用内先说明用途，再跳转；
   - **不读取任何通知内容**：只实现 `onListenerConnected` / `onListenerDisconnected` / `onNotificationPosted` 的**时间戳**，不调用 `getActiveNotifications()`、不取 `extras`、不存标题正文、不落盘、不上传；
   - 心跳只写本地（`Diag` + `Prefs`），面板展示"上次系统绑定距今 X 分钟"，与 `Watchdog.report` 合并到同一块诊断卡片；
   - 明确的退出路径：家长关掉开关后 `requestUnbind` 或直接提示去系统页撤销。
   为什么是它而不是 SyncAdapter：同属"系统 bind"，但它**天然有连接/断开回调**（可观测）、不污染账户列表、不需要 `WRITE_SECURE_SETTINGS`、不写整机安全设置，代码量约一半，符合"默认关闭 + 可解释"的产品口径。（它在强停场景同样无效，所以在 UI 文案里**不得**承诺"强停后自动恢复"。）
3. **明确不建议**：用 `WRITE_SECURE_SETTINGS` 在后台静默改写 `ENABLED_ACCESSIBILITY_SERVICES`。这与 F3 的立场冲突。若确有需求，只做"诊断页提示 + 一键跳转设置页 + 可复制的 `settings put` 命令"，由家长自己执行。
4. 强停场景的正解仍是外部 ADB 看门狗（`docs/background-recovery.md`），维持"可选、需自备主机、ADB 常开有风险"的定位。

---

## 5. 需要真机验证的清单（若将来启动该方向）

| # | 验证项 | 命令 / 观察点 | 判据 | 需要的设备 |
| --- | --- | --- | --- | --- |
| V1 | stopped 包是否还会被同步调起（**决定性**） | `adb shell am force-stop com.studyontv.tv` → `adb shell settings put system test_probe 1` → `adb shell dumpsys account \| grep -i studyontv`、`adb logcat -s StudyOnTV-Guard SyncManager SyncJobService` | force-stop 后 5 分钟内是否出现过 `onPerformSync` | 小米 / 海信 / TCL / 创维 各一台 |
| V2 | settings 写入是否以 `syncToNetwork` 触发同步 | `logcat` 抓 `SyncManager` 的调度日志，对比"写 `Settings.System`"与"写自己 authority" | 是否约 30s 内出现调度 | 同上（至少 1 台 Android 12+） |
| V3 | 系统"自动同步"总开关状态 | `adb shell dumpsys content \| grep -i "master sync"` / `settings get content_master_sync_automatically` | 关闭时 SyncAdapter 是否完全失效 | 电视默认状态即需记录 |
| V4 | 固件摘无障碍时到底写了什么 | 触发厂商清理/休眠前后 `settings get secure enabled_accessibility_services` + `accessibility_enabled` | 是否真的只改前者（决定 `heal()` 是否要补 `ACCESSIBILITY_ENABLED`） | 小米（#73）、当贝（#77） |
| V5 | 服务声明最小可用集合 | 改成 `exported="true"` + `BIND_SYNC_ADAPTER` 与补丁写法各测一次 | 哪种 `dumpsys` 里 `SyncAdapterBindService` 真的被 bind | 1 台即可 |
| V6 | 账户条目对家长/孩子的观感 | 设置 → 账户 / `dumpsys account` | 是否出现"题了个题"账户、能否被误删 | 小屏电视 UI |
| V7 | NLS 对照实验（建议同轮做） | `adb shell cmd notification allow_listener com.studyontv.tv/.XxxListener` → `dumpsys notification \| grep -i listener` → 杀进程 / 强停 | "杀进程"后是否 rebind、"强停"后是否 rebind | 同上；重点看电视是否裁剪"通知使用权"入口 |
| V8 | 功耗与唤醒副作用 | 8 小时 `dumpsys batterystats` 前后对比 | 新增唤醒次数、是否影响待机 | 1 台 |

**工作量（粗估，不含真机排队时间）**

| 事项 | 人日 |
| --- | --- |
| V1–V5 的实验脚本与记录（含 3–4 台机型） | 2–4 |
| 诊断类改动（A3 四条） | 1.5–2.5 |
| NLS 心跳 + 授权页 + 诊断卡 + 文案（默认关闭） | 2–3 |
| SyncAdapter 版本（仅在 V1 明确通过的前提下） | 2–3 + 合规文案/`THIRD_PARTY_NOTICES` 0.5 |
| 回归（拦截/计时/权限页/旧配置兼容） | 1–2 |

现实判断：**V1 通过的概率不高**（Android 8+ 同步由 JobScheduler 承载，强停会取消作业），而这决定了 V1 之后的 5 人日是否值得花。这也是"本版不做"的成本依据。

---

## 6. 与其他文档的关系

- `docs/keepalive-analysis.md`：A 节（force-stop 语义与现有通道能力）、B 节（外部 ADB）、G 节（本 issue 的初版判断）。本文是其 G 节在拿到参考补丁与真机自测声明之后的**细化与决策落地**，结论一致：本版不做，若要新增通道优先 NLS。
- `docs/background-recovery.md`：强停场景的正解（外部 ADB 看门狗）与其边界。
- `docs/issue-triage.md`：#111 的本地状态记录（未实现、补丁未合入）。

## 本轮边界

- 未编译、未安装、未连接设备；未修改任何产品代码、清单、资源或版本号。
- 只新增本文件，并在 `docs/issue-triage.md` 的 #111 条目补了指向本文的一句；其余文件未动。
- 文中所有厂商/AOSP 内部行为的结论均为**未验证**，须按第 5 节清单复核后才能作为实现依据。
