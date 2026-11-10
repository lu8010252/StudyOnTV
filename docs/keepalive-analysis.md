# 保活与强停可行性分析（#38/#43/#48/#52/#59/#67/#70/#73/#76/#97/#100/#101/#111）

本轮只读代码与既有文档，未编译、未安装、未连接任何设备或模拟器。凡涉及厂商固件行为的结论都标注**未验证**。

## 结论速览

| 问题 | 能否在代码内解决 | 建议手段 | 需要用户做什么 |
| --- | --- | --- | --- |
| A 厂商 force-stop（#67/#70/#76/#48） | **不能**解除，只能缩短空窗与提高可观测性 | 心跳落盘 + 启动原因配对 + 进程内自愈（已有），剩余靠外部 ADB | 不按"强行停止"；开无障碍/自启动/后台运行白名单 |
| A 厂商"清理后台"（#73/#52，只杀进程） | **能缓解** | 现有 JobScheduler + AlarmManager + 无障碍绑定已覆盖，建议加内存回收处理 | 关电池优化、开无障碍 |
| B 外部 ADB 拉起 | **能**，但前提是 ADB 仍在 | `scripts/device-watchdog.py` + `deploy/watchdog/**` | 常开主机 + 局域网 ADB 常开 + 授权密钥保留 |
| B 电视重启后 ADB 被关 | **做不到** | 无合法入口（见 B2） | 只能重新在电视上手动开调试 |
| C 重启后抢先卸载（#97） | **不是"重启窗口"bug**；普通管理员保护不受进程存活影响 | Device Owner + `setUninstallBlocked` | 首次配置时用 ADB 建立 DO（多数已激活电视不支持） |
| C 外部 ADB 防卸载 | **不能阻止卸载**，只能检测 + 重装 | 监控 `pm list packages`，被卸载后 `adb install -r` | ADB 常开、主机保留 APK、会丢数据 |
| D 按 Home 退出（#59-1） | **部分已成立**：时间用尽时拦截页仍盖住桌面；余额未用尽时桌面是自由的 | 可选"桌面也算受控"+ 无障碍回桌面即拦截；kiosk/DO 才能真拦住 HOME 键 | 需家长授权（DO）或接受"桌面也被接管" |
| E TCL 权限开不了（#59-3） | **只能改进引导与取证** | AppOps 交叉校验、跳转兜底（已有）、诊断导出 | 提供 `appops get` / 权限页截图 / 系统版本 |
| F 小米周期清理（#73） | **不能阻止**，只能降低被杀概率 | 降常驻、`onTrimMemory`、状态持久化 | 手动加白名单；不建议改固件开关 |
| G SyncAdapter 自愈（#111） | 技术上可行，**但仍不能对抗 force-stop** | 先验证再决定 | 无（不建议现在做） |

---

## A. 厂商 force-stop

### A1 逐项：能对抗什么 / 不能对抗什么

| 实现 | 位置 | 能对抗 | 不能对抗 |
| --- | --- | --- | --- |
| 前台服务 `startForeground(1001, specialUse)` | `GateService.kt:412-413`、`AndroidManifest.xml:79-83` | 低内存回收、后台服务限制、Doze 下的服务冻结 | 厂商 `force-stop`/`killAllThirdApp`、用户在设置里强停 |
| `START_STICKY` | `GateService.kt:425` | 进程被系统因内存回收杀掉后的重建 | 包处于 **stopped** 状态时系统不会重建（这是设计） |
| `onTaskRemoved` → `scheduleRestart(2000)` | `GateService.kt:429-431` | 手机/平板从最近任务划掉、部分电视"退出即杀服务" | 强停时 `onTaskRemoved` 不会回调，且排的闹钟会被强停清掉 |
| `onDestroy` → `scheduleRestart(5000)` + `flushUsage()` | `GateService.kt:434-441` | 有序停止后的自恢复、用量不丢 | 强停/崩溃不走 `onDestroy` |
| JobScheduler `setPeriodic(15min).setPersisted(true)` | `Watchdog.kt:30-35` | 进程死亡后约 15 分钟内拉回；`setPersisted` 让作业跨重启保留 | 强停会取消该包的作业，且 stopped 状态下不执行；部分固件整体限制 JobScheduler |
| AlarmManager `setInexactRepeating(ELAPSED_REALTIME_WAKEUP, 5min/15min)` | `Watchdog.kt:42-52` | 固件限制 JobScheduler 但放行闹钟的机型 | 强停清空闹钟；`ELAPSED_REALTIME_*` 不跨重启（重启后靠 `BootReceiver` 重新 `scheduleAlarm`）；不精确闹钟在 Doze 会被合并延迟 |
| 无障碍绑定 → 每个 `TYPE_WINDOW_STATE_CHANGED` 里 `ensureRunning` | `WatchAccessibilityService.kt:22,33` | **最有效的进程内自愈**：系统常驻服务被系统回收后，下一次窗口切换即拉起 | 强停会解绑；多数厂商 ROM 会把服务标记 crashed，即使"已开启"也不再连（#77 当贝即此现象） |
| 监听 `SCREEN_ON`/`USER_PRESENT`/`CONNECTIVITY_CHANGE` | `BootReceiver.kt:25-30`、`AndroidManifest.xml:130-132` | 进程被杀后的快速拉起（这三个都是不受隐式广播限制的例外） | stopped 状态的包收不到隐式广播 |
| `MY_PACKAGE_REPLACED` | `BootReceiver.kt:12,20` | 自更新后重排程、恢复状态 | 只在"我们自己被安装/更新"时发生；不是通用复活通道 |
| 后台启动前台服务的豁免 | `GateService.kt:461,465-469` | Android 12+ 后台起 FGS 限制下仍能起来（`SYSTEM_ALERT_WINDOW` / 无障碍在身时属豁免） | 前提是这些权限还在 |

### A2 被 force-stop 之后哪些入口失效，为什么

`force-stop` 在 `ActivityManagerService.forceStopPackageLocked` 里做三件事：杀掉全部进程、取消该包所有闹钟/Job/挂起 Intent、把包置为 **stopped** 状态（`PackageManager` 的 `FLAG_STOPPED`）。stopped 语义是：该包不能再被隐式广播、其他应用隐式 Intent 拉起，只有用户**显式**启动（点图标、`adb shell am start`）或安装/更新（安装会清除 stopped 标志）才会恢复。

因此全部失效：`START_STICKY` 不重建、`scheduleRestart` 的闹钟被清空、JobScheduler 作业被取消、`setInexactRepeating` 被清空、`SCREEN_ON`/`CONNECTIVITY_CHANGE` 收不到、无障碍服务被解绑（部分 ROM 标记 crashed）、前台服务被杀且不能自起。**代码内没有任何手段能清除 stopped 标志**——它需要系统权限。

必须区分两种"杀"，否则会误判问题性质（本轮**未在真机验证**厂商实现）：

- **只杀进程**（多数"清理后台/一键加速"、`killAllThirdApp`）：不设 stopped，闹钟与作业仍在，无障碍绑定仍在 → 我们能自愈，空窗 ≤ 一个闹钟/作业周期。
- **系统强停**（设置→强行停止、`am force-stop`）：设 stopped → 应用内全灭，只有外部 ADB 的 `am start` 或安装更新能救。

### A3 代码内还能改进的点

- 已实现：Diag 落盘并轮转（`Diag.kt:19-46`）、导出 zip 含 `device.json`（`Diag.kt:59-86`，里面有 `monitor`/`autostart`）、开机自启判定（`Watchdog.kt:68-93`）、每次启动写 `Service started via=$startVia`（`GateService.kt:418`）、弹窗失败原因人话化并进通知（`GateService.kt:227-247`）。
- **可做 1（高价值，零风险）**：`Prefs.serviceRunning` 是**死值**——只有 `GateService.kt:404/441` 两处写入，全仓库没有读取点。强停时不会走 `onDestroy`，于是它永久停在 `true`，等于"没有证据"。建议改成心跳：tick 里每 30–60s 写 `wallClock + bootCount + via`，家长面板据此显示"上次心跳距今 X 分钟，可能是被强停/断电"。
- **可做 2**：`lastActivePackage` 只在内存（`GateService.kt:454-456`）→ 进程被杀后 `resumePrevious()`/`exitToLauncher()` 失去还原目标（#68/#74 行为退化）。建议持久化到 `Prefs`。
- **可做 3**：把"启动原因 + 上次退出是否有序"配对落盘（现在日志里只有孤立的 start/destroyed 行，家长读不出来"这次为什么没起来"）。建议启动时检查"上一条日志是不是 destroyed"，直接写一行结论。
- **可做 4**：`checkAccessibilityLost`（`GateService.kt:290-320`）只处理"设置里被关掉"，没处理"设置仍开着但 `connected=false`"（crashed）。建议区分两种状态并给不同指引。
- 做不到：清除 stopped 状态、阻止用户在设置里强停、阻止系统应用杀进程。

---

## B. 外部设备拉起 ADB 是否可行

### B1 各平台前提（据 `deploy/watchdog/README.md`）

| 主机 | 依赖前提 |
| --- | --- |
| Windows `windows/install-task.ps1` | 已装 Python 3.10+ 与 platform-tools；注册"当前用户登录时"启动的计划任务 → 需要该用户保持登录 |
| macOS `macos/install-launchd.sh` | 同上，LaunchAgent 以当前用户运行，需用户已登录 |
| 树莓派 / Linux systemd | `android-tools-adb` + Python 3.10+；`/etc/studyontv-watchdog.env` 配 `SOTV_SERIAL`；以服务用户身份**授权过一次** ADB |
| NAS docker | 必须把 `/root/.android`（ADB 私钥）持久化到卷，否则容器重建后变 `unauthorized` |
| OpenWrt | 固件自带 `adb` 与 `timeout`（多数官方固件没有，需自编译）；procd + UCI |
| ESP32/ESP8266 | MicroPython + 自带最小 ADB 客户端；**仅传统 TCP ADB**，不支持 Android 11+ 无线调试配对码/TLS；需 `provision.py` 生成独立密钥 |

共同前提：电视已完成首配、与主机在同一可信局域网、**局域网 ADB 已开启并已授权该主机密钥**。工具只做 `dumpsys` 只读检查 + `am start -n <包>/com.studyontv.tv.MainActivity`，不启用 ADB、不重启、不改厂商设置（`scripts/device-watchdog.py:150-175`）。

### B2 重启后网络 ADB 被关闭时，外部设备有合法手段恢复吗

**没有。** 理由：

1. ADB 关闭 = shell 通道关闭，外部唯一的"显式启动"能力（`am start`）依赖它；应用自身处于 stopped 状态时无法自举。
2. USB ADB 需要物理接触 + 电视上手动打开"USB 调试"，与"远程恢复"目标矛盾。
3. 厂商接口（小米/海信 JUII/创维酷开/TCL）没有对第三方开放的远程启动 API；能远程控制的只有厂商自己的云服务和智能家居协议，本应用无法接入。
4. 企业方案（EMM/Android Management API）能远程下发命令，但前提是设备已经是 **Device Owner 并由企业服务器托管**——同样需要在设备本地完成一次授权，且多数已激活的电视无法再设为 DO。

结论：ADB 一旦被固件关掉，唯一恢复路径是家长回到电视上手动打开。所谓"零接触恢复"不成立。（各厂商重启后是否保留 ADB 开关属固件行为，**未验证**。）

### B3 场景 × 手段矩阵

✅=有效 ／ ⚠️=通常有效但依赖固件，未验证 ／ ❌=无效

| 场景 | 前台服务 | 无障碍绑定 | JobScheduler | AlarmManager | 外部看门狗（ADB 在） | 外部看门狗（ADB 关） |
| --- | --- | --- | --- | --- | --- | --- |
| 普通进程回收（低内存） | ✅ | ✅ | ✅ | ✅ | —（不需要） | — |
| 待机唤醒（屏灭→亮） | ✅ 服务存活则继续 | ✅ | ✅ | ✅ `SCREEN_ON` | ⚠️ 需先判断已亮屏（脚本已实现） | ❌ |
| 遥控器重启（待机态重启） | ⚠️ 依赖开机广播 | ⚠️ 依赖系统重新绑定 | ⚠️ `setPersisted` 重排程 | ❌ 闹钟不跨重启，靠广播重排 | ⚠️ 若 ADB 仍开则有效 | ❌ |
| 断电冷启动 | ⚠️ 同 `BOOT_COMPLETED`（被自启动管理拦截则失效） | ⚠️ 同上 | ⚠️ 同上 | ❌ | ⚠️ 同上 | ❌ |
| 厂商 `killAllThirdApp`（只杀进程） | ⚠️ 取决于是否重启服务 | ✅ 下一次窗口切换即拉起 | ⚠️ 15 分钟 | ⚠️ 5–20 分钟 | ✅（轮询周期） | ❌ |
| 系统强停 / `am force-stop`（stopped） | ❌ | ❌ | ❌ | ❌ | ✅ `am start` 可清除 stopped | ❌ |
| 用户手动卸载 | ❌ | ❌ | ❌ | ❌ | ⚠️ 只能发现后重装（丢数据） | ❌ |

### B4 恢复动作的副作用与现有规避

现有规避（读代码确认）：`ensure()` 先 `dumpsys power` 判断唤醒态，`awake is not True` 直接返回 `sleeping`/`unknown` 不动作（`scripts/device-watchdog.py:150-155`）→ 不会半夜点亮电视；只在"进程缺失 **或** 前台服务缺失"时才 `am start`；不杀别的应用、不改全局设置、不重启设备与共享 ADB server（`connect()` 注释）；恢复后不宣称成功，要求下一轮复核。

仍存在的副作用：

1. `am start MainActivity` 会把学习页顶到前台。若进程还活着、只是前台服务掉了，会**打断正在播放的视频**。更精准的做法是尝试 `am start-foreground-service`（shell 有 `START_FOREGROUND_SERVICE` 权限，是否被厂商放行**未验证**）。
2. 缺少"时间窗"判断：现在任何唤醒时刻都会拉起，深夜/自由时段也会。建议只在"应该管控"的时间窗内恢复。
3. `--repair-accessibility` 会写 `secure` 设置，影响整机辅助功能行为（默认关闭是合理的）。
4. 没有给家长的"昨夜恢复了 N 次 / 失败 M 次"汇总，家长只能自己看 JSON。

---

## 对 #97 与外部 ADB 的明确回答

**问：重启后抢先卸载是不是我们代码的 bug？——不是（对普通设备管理员这一档而言）。**

依据：设备管理员状态由 `system_server` 在开机时从 `/data/system/device_policies.xml` 恢复，"是否为活动管理员"的卸载校验发生在 `PackageManagerService.deletePackage`，与我们进程是否已启动、`restoreOwnerPolicy` 有没有跑过**无关**。`UninstallGuard.restoreOwnerPolicy`（`UninstallGuard.kt:26-33`）恢复的是 **Device Owner** 的 `setUninstallBlocked` 策略，不是管理员身份本身；`GateService.kt:401`、`BootReceiver.kt:18` 调用它只是尽快把 DO 策略重新写一遍。而且 `res/xml/device_admin.xml` 只声明 `<uses-policies />`，不申请任何策略，普通管理员能提供的保护就是系统那条"活动管理员不可卸载"。

所以"重启后立刻卸载成功"的现实原因只可能是：厂商自带"我的应用/应用商店"卸载入口不检查 DeviceAdmin；家长已手动关掉管理员；或该机型把限制只做在 Settings 里。**具体是哪一种需要真机复现，未验证。** 另一个代码侧问题是 `UninstallGuard.active()`（`UninstallGuard.kt:44-48`）在非 DO 时返回 `isAdminActive`，家长面板会显示"已保护"，但实际保护强度很弱（`DevicePanel.tsx:62` 的文案已说明这点）。

**问：外部 ADB 能不能避免抢先卸载？——不能阻止，只能检测与重装。**

- `pm uninstall` 是 shell 权限，ADB 只是通道；**普通 ADB 不是设备管理员**，无法调用 `setUninstallBlocked`。想靠 ADB 获得"阻止"能力，唯一路径是 `adb shell dpm set-device-owner` 把应用变成 DO——那已经属于 C2 方案，且需要设备未激活/单用户/无账号，多数在用电视做不到。
- ADB 能做的：①`pm list packages <包名>` / `dumpsys package` 监控是否被卸；②提前把 APK 放在主机，发现被卸就 `adb install -r`（需要 `REQUEST_INSTALL_PACKAGES` 之外的 shell 权限，shell 有）；③被卸后重建无障碍与设置；④`dumpsys` 取证。
- 代价与限制：ADB 必须常开（重启后是否仍开**未验证**）；主机必须保住 ADB 私钥（docker 卷 / `~/.android`）；重装后 `/data/data` 为空——题库、规则、家长 token 全丢，需重新配对；`adb backup` 在 Android 9+ 基本不可用；侧载会触发系统"未知来源"提示；**ADB 常开本身等于把 shell 权限放在局域网上**，孩子或客人拿到同一网络就能强停/卸载/改设置。

---

## C. 覆盖 #97 窗口的系统机制与方案排序

### C2 真正能覆盖该窗口的机制

`DevicePolicyManager.setUninstallBlocked()`（API 21+，**仅 Device Owner 可调用**）。它在 `PackageManagerService` 的卸载路径上统一生效，`am force-stop`/重启/进程未启动都不影响，连 `adb shell pm uninstall` 也会被拒（`DELETE_FAILED_DEVICE_POLICY_MANAGER`；shell 需先 `dpm remove-active-admin` 才能绕过——AOSP 行为，本项目**未真机验证**）。

我们的实现：`UninstallGuard.restoreOwnerPolicy`（`:26-33`，只在 `owner(context)` 为真时写策略）、`setEnabled`（`:35-41`）、`active`（`:44-48`）、面板开关 `/api/device/uninstall-guard`（`LanServer.kt:332-344`）。生效条件：设备**已经是**本应用的 Device Owner（我们不做自动配置、也不引导），且家长开关 `uninstall_guard_enabled` 为真（`:30`）。

### C3 建议方案排序（最可靠 → 最不可靠）

| 序 | 方案 | 可靠性 | 需要的授权/权限 | 代价与风险 |
| --- | --- | --- | --- | --- |
| 1 | Device Owner + `setUninstallBlocked`（+ 可选 `setLockTaskPackages`） | 最高，进程未启动也生效，shell 也挡 | 一次性 `adb shell dpm set-device-owner`；固件不阻止 | 多数在用电视无法再设 DO；DO 会把设备变成"企业/学习机"，恢复需 `dpm remove-active-admin` 或恢复出厂 |
| 2 | 普通设备管理员（现状） | 中，挡 Settings 卸载入口 | 家长在设备上点授权 | 厂商"我的应用"入口可能绕过；管理员可被随时关掉（这是我们有意保留的逃生门） |
| 3 | 外部 ADB：检测 + 被卸后重装 | 低—中，只减空白期 | ADB 常开 + 主机常开 + APK 常备 | 丢数据、需重新配对、侧载提示、ADB 常开的安全风险 |
| 4 | 外部 ADB：只监控 + 告警 | 低，不恢复 | 同上 | 只让家长知道，不阻止 |
| 5 | 应用内提示/引导文案 | 极低 | 无 | 对已知情者无效 |

---

## D. 按 Home 键退出（#59 第 1 条）

### D1 代码里 Home 键走什么路径

- `MainActivity.dispatchKeyEvent`（`MainActivity.kt:112-118`）把按键交给 `RemoteKeys.handle`，只映射 DPAD 四向/OK/ENTER/BACK（`RemoteKeys.kt:20-26`）。**HOME 不在映射里**，落到 `super.dispatchKeyEvent` → 由系统处理，我们拿不到（无 `filterKeyEvents`、无 `HOME_STATE` 权限代码，全仓库 grep 无命中）。
- `DeviceBridge.exitToLauncher`（`DeviceBridge.kt:70-81`）是**网页/悬浮页主动调用**的"退出"，不是 Home 键：先校验"不在管控原因里且还有余额/自由时段"，再 `moveTaskToBack(true)`（把任务压到后台，不跳桌面，避免顶掉正在播放的视频 #54/#63）。
- `GateOverlay.exitToLauncher`（`GateOverlay.kt:253-260`）同样带余额校验，`goHome` 回调 = `WatchAccessibilityService` 的 `performGlobalAction(GLOBAL_ACTION_HOME)`（`WatchAccessibilityService.kt:29-32`），即由无障碍主动回桌面。
- 小米 `HOME_STATE` 权限被拒后的 `moveTaskToBack`：代码里没有这条分支，"退到后台而不跳桌面"就是 `DeviceBridge.kt:79` 的做法。

**关键事实：按 Home 并不等于放弃管控。** `checkForeground` 里 `protecting = awake && monitoring && (pkg == null ? canOverlay : !exempt(pkg, false))`（`GateService.kt:94`），而 `exempt(pkg, includeLauncher=false)` 对桌面包返回 `false`（`GateService.kt:389-396`）→ 桌面临前台时 `protecting` 仍为真；`GateService.kt:108` 的 `hideAll()` 条件也排除了桌面包。所以**余额用尽时，按 Home 回到桌面，拦截页仍会被弹出来盖住桌面**（悬浮层是全屏可聚焦窗口，`GateOverlay.kt:135-137` + `view.requestFocus()`）。真正没有管控的是"余额未用尽/自由时段"，那是设计选择。

### D2 让"回桌面"不脱离管控的合法办法

| 办法 | 可行性 | 代价 |
| --- | --- | --- |
| 现状：桌面也算受控前台，时间到即以悬浮/无障碍覆盖层盖住（`GateService.kt:94,108`） | 已具备，最轻 | 覆盖层不是"独占"，无焦点抢占能力；桌面可见一瞬 |
| 无障碍在桌面前台时立刻 `showGate`（已有能力，只需策略开关） | 可行，无需新权限 | 孩子完全无法使用桌面；某些电视桌面即影视入口（海信 JUUI，见 `GateService.kt:373-382`），会误伤正在看片 |
| 把拦截页注册为 Home（`CATEGORY_HOME`），让用户选为默认桌面 | 可行，需新增 Activity 与引导 | 要替代整个桌面 UI；多数电视不允许更换桌面；家长自己也回不到原桌面 |
| `Activity.startLockTask()`（屏幕固定，API 21+，无需特殊权限，用户确认一次） | 可行 | 孩子按提示可自行退出；家长用电视时也被挡住 |
| DO：`setLockTaskPackages` + `setLockTaskFeatures` + `setKeyguardDisabled` | 最可靠 | 必须 DO；电视变纯学习机 |
| DO：`addPersistentPreferredActivity`（把 HOME intent 固定到我们） | 可行 | 必须 DO；无法在设备上临时切换 |
| 直接拦截 HOME 键事件 | **做不到**：HOME 是系统键，第三方应用（含普通无障碍）无法过滤 | — |

### D3 现有具备 / 还差什么

具备：前台服务 tick + 无障碍窗口识别 + 三种拦截呈现（悬浮 WebView / `TYPE_ACCESSIBILITY_OVERLAY` / 全屏 `GateActivity`）+ 失败退避重试（`GateService.kt:154-215`）+ 兜底通知。
差：①"桌面也算受控"当前是隐式结果而非可关的策略项，家长无法选择"桌面可自由使用"；②没有 Home 替代、没有 `startLockTask`、没有 DO kiosk；③按 Home 反复切换时没有"立即重弹"（受 `VISIBLE_RETRY_MS` 1.8s 防抖影响，`GateService.kt:566`）；④没有"按 Home 时给一次可读提示"的体验兜底。

---

## E. TCL 权限无法设置（#59 第 3 条）

### E1 代码里能做

- 已有：权限引导页逐行"去开启"并显示状态（`OnboardingActivity.kt:83-100`）；跳转四级兜底——`package:` 专用 Intent → 通用 action → 应用详情 → 系统设置总入口（`OnboardingActivity.kt:157-168`）；`Settings.canDrawOverlays`（`GateOverlay.kt:263-267`）；从设置返回后每秒重读状态（`OnboardingActivity.kt:28-31,199-208`）；品牌专属文案（TCL/海信/创维/小米/长虹，`OnboardingActivity.kt:119-129`）；运行期撤销检测 + 通知（`GateService.kt:252-288`、`:290-320`）。
- 可做（未做）：①悬浮窗的 **AppOps 交叉校验**——现在 AppOps 只用于使用情况访问（`Foreground.kt:126-133`），建议同时读 `OPSTR_SYSTEM_ALERT_WINDOW`，识别"设置页显示已开但 `canDrawOverlays` 为 false"的固件缓存问题；②权限页里加"复制诊断/导出日志"入口，让家长一条命令取证；③"无障碍已开启但 `connected=false`（crashed）"与"已关闭"分开提示；④TCL 部分固件在应用进程重启后才刷新 AppOps，可在返回时提示"重启应用生效"并提供一键重启入口（`AlarmManager` + `scheduleRestart` 已有）。
- 做不到：绕过厂商对悬浮窗/无障碍的授予限制。

### E2 需要用户提供的证据

`adb shell appops get com.studyontv.tv SYSTEM_ALERT_WINDOW`；`adb shell appops get com.studyontv.tv GET_USAGE_STATS`；`adb shell settings get secure enabled_accessibility_services` 与 `accessibility_enabled`；`adb shell dumpsys window | grep -i "2038\|OVERLAY"`；权限页截图；`Build.VERSION.RELEASE` / `SDK_INT` / `ro.product.model` / `ro.build.version.incremental`；`adb logcat -s StudyOnTV-Gate` 里 `addView` 的异常类型（`GateOverlay.kt:154-158` 已把 `window=… className: message` 写进 `lastError`）；面板导出的 zip（`Diag.export` 已含 `device.json` + `monitor`）。

---

## F. 小米周期清理（#73）

### F1 应用内能否阻止 `PeriodicCleaner`/`killAllThirdApp`

**不能。** 它们属于厂商系统应用/framework（省电与安全中心），白名单与调度策略由系统持有；第三方应用没有任何 API 能修改别的系统组件的行为，也无法拒绝系统对自己进程的 `kill`。唯一的"应用内"出路是把进程变得"不值得被杀"（前台服务 + 低内存 + 无障碍绑定），或者拿到 DO/root/系统签名。

### F2 应用内能做的缓解

已做：常驻窗口极简（1×1 透明 anchor，可关闭，`GateService.kt:239-258`）；无悬浮窗权限时立即 `overlay.hide()`（`:60`）；弹窗失败用 `hide()` 而非 `destroy()`，但 WebView 只保留 60 秒后自动 `destroy()`（`GateOverlay.kt:169-190`，注释解释了"复用 vs 内存"权衡）；输入页期间 `suspend()` 并释放（`GateService.kt:72-78`）；静音与弹窗同源，弹不出来立刻解除静音（`:110`、`:189-194`）；TV 上学习页 `onStop` 立即销毁 WebView（`MainActivity.kt:88-96`）；`onDestroy` 落盘用量（`:435`）。

缺：①**完全没有 `onTrimMemory`/`onLowMemory`**（全仓库 grep 无命中）——建议在 `TRIM_MEMORY_RUNNING_LOW` 时 `overlay.destroy()` 并释放 anchor，`CRITICAL` 时主动降级为"下次只用 GateActivity"；②没有"低内存降级开关"，也没有把降级状态写进诊断；③没有把 `WebView` 换成更轻的静态拦截页的选项（部分老电视黑屏时其实只需要一页文字）；④进程被杀后 `lastActivePackage` 丢失（见 A3-2）。

### F3 对 `cloud_periodic_enable` 这类固件开关的立场

**同意"不自动改"，只在诊断页提示并给家长可复制的 ADB 命令。** 理由：属整机设置，会影响其他应用与系统行为；需要 root/shell 才能改；跨固件名称与默认值都不同；应用在家长不知情时改整机策略属于越权，也会让"出问题后无法归因"。建议呈现为：诊断页一行说明 + 可复制命令 + 风险提示 + 恢复命令（值改回），并明确标注"该命令由社区/用户提供，本项目**未在真机验证**"。

---

## G. SyncAdapter 自愈（#111）可行性

### G1 原理与前提

SyncAdapter 不是"自启动"，而是"由系统主动 bind"：`ContentResolver.addPeriodicSync` 让 `system_server` 的 sync 管理逻辑在维护窗口到点时绑定 `<包>/<SyncService>` 并调用 `onPerformSync`。它的好处是这条调度由系统账户/同步服务持有，往往比第三方 JobScheduler 更少被厂商省电策略掐掉。

前提：`AccountManager` 账户类型 + `authenticator.xml` + `syncadapter.xml`；`SyncService` 声明 `BIND_SYNC_ADAPTER` 权限；`WRITE_SYNC_SETTINGS`（或用户手动开同步）；一个可用账户（可用本地 dummy account，不依赖 Google 登录）。

**关键限制：它同样受 stopped 状态约束**——`force-stop` 的包不会被系统调起同步。所以 SyncAdapter 只能改善"只杀进程"类清理下的存活率，**不解决 #67/#70/#76/#48 这类强停**。

### G2 成本与风险

成本：新增 3 个 xml、1 个 Service、1 个 Authenticator、周期同步注册与生命周期处理；约数百行。风险：①本地账户会出现在系统"账户"列表，家长/孩子都能看到，容易被当成可疑行为，电视上该入口常被裁剪导致添加失败；②小米等 ROM 对"后台同步"有独立开关（`getMasterSyncAutomatically`），关闭后等于没有；③Google 服务缺失不影响本地账户，但部分固件的 sync 管理器直接坏掉；④调试困难——同步调起时机不可控。上述均**未在本项目验证**。

### G3 建议

**先验证，暂不实现。** 优先级排序：A3 的心跳与诊断（零风险、直接解决"为什么这次没起来"的可见性）> 现有的 JobScheduler + AlarmManager + 无障碍三通道（已实现，缺的是诊断）> SyncAdapter。若要新增一条"抗省电"通道，同等成本下 `NotificationListenerService`（同样由系统 bind、需用户在"通知使用权"里授权）比 SyncAdapter 更简单、副作用更小，且不污染账户列表。SyncAdapter 只在"目标机型上实测同步确实会被调起"之后再考虑，并且做成默认关闭的实验开关。

---

## 给家长的操作建议（按可靠性排序）

| 序 | 做法 | 覆盖什么 | 风险 / 代价 |
| --- | --- | --- | --- |
| 1 | 打开**悬浮窗** + **无障碍** + 关闭电池优化（应用内权限页一次配好） | 普通进程回收、后台限制、大部分"清理后台" | 无；无障碍是敏感权限，请只在本机开启 |
| 2 | 在系统"自启动管理"里放行本应用（TCL/海信/创维/小米路径见权限页） | 断电冷启动、遥控器重启 | 无；被固件拦截时只能靠外部看门狗 |
| 3 | 开启**防卸载（设备管理员）** | 应用内卸载入口 | 孩子可在设置里关掉；厂商"我的应用"入口可能绕过（**未验证**） |
| 4 | 常开小主机（树莓派/NAS/旧电脑）+ `device-watchdog.py watch`，保持**局域网 ADB 常开** | 系统强停、厂商杀进程 | ADB = shell 权限，只放可信局域网；重启后 ADB 可能被关；恢复时会拉到前台，可能打断观看 |
| 5 | **Device Owner**（首次用 `adb shell dpm set-device-owner`）+ 防卸载 + （可选）kiosk | 卸载、Home 逃逸、开机后立即卸载窗口 | 多数在用电视做不到；会显著改变设备行为；退出需恢复出厂或 `dpm remove-active-admin` |
| 6 | 外部 ADB 被卸载后自动重装 | 卸载后的恢复 | **会丢数据**（题库/规则/配对全部清空）、需重新授权、侧载提示；不建议默认开启 |
| — | 不建议：修改 `cloud_periodic_enable` 等固件开关 | — | 影响整机与其他应用、无法归因、需 shell；仅在诊断页给命令让家长自行决定 |

## 本轮边界

- 全文未编译、未安装、未连接设备或模拟器；无任何实测数据，所有厂商相关结论标为**未验证**。
- 需要真机复测的关键点：厂商"一键加速"是否设置 stopped 标志；重启后网络 ADB 是否保留；普通管理员能否被厂商卸载入口绕过；`am start-foreground-service` 是否被放行；TCL 悬浮窗 AppOps 状态；`onTrimMemory` 触发阈值。
- 本文件只新增分析，未修改任何产品代码与其他文档。
