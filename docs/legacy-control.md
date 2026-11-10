# Android 4.0+ 整机管控 PoC（legacy）

本文说明独立包 `com.studyontv.legacy` 中的整机管控 PoC：它尝试在 Android 4.0+ 上实现“前台服务 + 悬浮拦截 + 每日用量上限 + 开机自启”。这是一个可用于评估的技术验证，不是绕过系统安全模型的成品管控方案。

- 模块目录：`app/native/legacy`
- 包名：`com.studyontv.legacy`（与现代版数据完全分开）
- minSdk 14、targetSdk 34、compileSdk 36
- 验证环境：Android 4.4.2（API 19，AVD `StudyOnTV_API19`）、Android 16（API 36）模拟器
- 截图：[拦截层](screenshots/legacy-gate.png)、[家长设置](screenshots/legacy-settings.png)

## 架构

| 组件 | 作用 |
| --- | --- |
| `LegacyActivity` | 离线练习 WebView；按 MENU / SETTINGS 进入家长设置；通过 `LegacyApp` 记录本应用是否可见。 |
| `LegacyService` | 常驻前台服务。每 3 秒轮询一次：识别前台应用、累计亮屏使用时长、按规则显示/隐藏拦截层。 |
| `LegacyGate` | 全屏悬浮拦截层。显示剩余时间与两个按钮（去答题 +5 分钟、家长设置）；拦截层自身也响应 MENU / SETTINGS 键。 |
| `LegacySettingsActivity` | 家长设置：4 位 PIN（默认 `1234`）、每日分钟数、可用时段、全天可用、重置今日用量、临时解锁 30 分钟。 |
| `LegacyPrefs` | SharedPreferences：配置、按日期存放的今日用量、临时解锁截止时间。 |
| `BootReceiver` | 收到 `BOOT_COMPLETED` 后拉起管控服务。 |
| `LegacyWatchdogReceiver` | 由 `AlarmManager` 每 60 秒触发的看门狗；服务进程被杀后重新拉起服务。 |
| `LegacyServiceStarter` | 统一启动入口：API 26+ 使用 `startForegroundService`，老系统使用 `startService`。 |
| `LegacyApp` | 进程内静态可见性计数，避免把“孩子正在答题/家长设置”误判为其他应用。 |

工作流程：

1. 启动练习页或开机广播后，`LegacyService` 以前台服务运行并注册 60 秒看门狗闹钟。
2. 服务每 3 秒检查屏幕状态、前台包名和本应用可见性。
3. 不在本应用内、屏幕亮着且未触发拦截时，按 3 秒粒度累计今日用量。
4. 超出每日上限或不在可用时段时，显示全屏悬浮拦截层；回到本应用后自动收起。
5. 看门狗闹钟周期性启动服务，弥补 `START_STICKY` 在老模拟器/部分 ROM 上不生效的问题。

## 权限

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `SYSTEM_ALERT_WINDOW` | 显示全屏拦截层 | Android 6.0 以下安装即授予；6.0+ 需要用户在系统设置中手动授权。 |
| `GET_TASKS` | `getRunningTasks(1)` 识别前台应用 | Android 5.0+ 起该接口只返回本应用任务，无法再识别其他前台应用。 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 用户“强行停止”应用后，系统不会再投递该广播。 |
| `FOREGROUND_SERVICE` | 前台服务 | Android 9+ 使用；老系统忽略。 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | `specialUse` 前台服务类型 | Android 14+ 使用；老系统忽略。 |
| `INTERNET` | WebView 访问网络资源 | 离线练习页本身不依赖网络。 |

安装时权限在 Android 4.x 上直接授予，因此 PoC 在老系统上不需要额外的权限引导流程。Android 6.0+ 的悬浮窗授权没有做成引导页，属于已知限制。

## 能力边界（按系统版本）

### Android 4.0–4.3（API 14–18）

- 具备完整 PoC 设计所需的条件：安装时授予悬浮窗、`getRunningTasks` 可拿到真实顶层 Activity、无后台服务限制、开机广播可拉起服务。
- 本轮只在 Android 4.4.2（API 19）上实际运行；4.0–4.3 仅通过 `minSdk 14` 编译验证，没有对应模拟器/真机回归，不能视为已验证。
- 这些版本的旧 WebView 对新 TLS 证书、部分 JavaScript 特性支持不完整；离线练习页可用，但依赖外网资源的功能不保证。

### Android 4.4（API 19–20）

- 本轮唯一完成端到端验证的版本。
- `getRunningTasks(1)` 仍可返回真实前台包名，因此拦截判断准确。
- 拦截层使用 `TYPE_SYSTEM_ALERT`，无需运行时授权。
- 服务由 `startService` 拉起，开机广播可直接启动。
- 实测：安装、练习页、PIN 设置、0 分钟立即拦截、5 分钟用量累计、重置用量、开机自启、进程被杀后看门狗恢复均通过。

### Android 5.0+（API 21+）

- `getRunningTasks` 退化：只能看到本应用自己的任务，无法知道孩子正在使用哪个第三方应用。
- 前台识别退化为“本应用是否可见”：本应用不可见时一律视为使用中，无法区分桌面、系统设置和具体应用，也无法按应用配置规则。
- 悬浮拦截、用量累计、PIN 设置、开机自启设计上仍可用，拦截层在 API 26+ 改用 `TYPE_APPLICATION_OVERLAY`；本轮未在 API 21–35 上逐版本验证。
- 没有接入 `UsageStatsManager` / 无障碍服务，因此当前精度不足以做真正的按应用管控。

### Android 6.0+（API 23+）

- `SYSTEM_ALERT_WINDOW` 变为运行时授权。未授权时 `addView` 抛出 `WindowManager.BadTokenException`，被服务捕获，拦截层不显示，但服务继续运行、不崩溃。
- 本轮在 Android 16（API 36）模拟器做了冒烟验证：安装、启动、前台服务、无悬浮窗权限时的降级均正常，未验证手动授权后的拦截显示。
- Android 8.0+ 的后台执行限制要求使用前台服务；PoC 已在 API 26+ 使用 `startForegroundService` 和通知渠道。
- Android 12+ 对后台启动前台服务有限制，看门狗闹钟在极端情况下可能被延迟或拒绝；代码捕获异常，不会崩溃，但恢复不保证。
- Android 14+ 声明 `specialUse` 前台服务类型；若要上架应用商店，还需要按 Play 政策补充声明/用途说明。

## 测试步骤

以下命令以仓库根目录为工作目录，Android SDK / Gradle / JDK 按本机环境替换。

```sh
# 构建
gradle -p app/native :legacy:assembleDebug
gradle -p app/native :legacy:assembleRelease

# 安装并启动
adb -s emulator-5560 install -r app/native/legacy/build/outputs/apk/debug/legacy-debug.apk
adb -s emulator-5560 shell am start -n com.studyontv.legacy/.LegacyActivity

# 查看日志
adb -s emulator-5560 logcat -s LegacyService:* LegacyGate:* LegacyBoot:* LegacyWatchdog:*
```

1. 启动练习页，确认 `LegacyService: service created`。
2. 按 MENU（`adb shell input keyevent 82`），输入 PIN `1234`，进入家长设置。
3. 把“每日可用分钟”改为 0 并保存，按 HOME 回桌面，等待约 3 秒，确认日志出现 `gate shown`、`gated=true`，并截图拦截层。
4. 把上限改为 5 分钟，在系统设置等外部应用停留约 20 秒，确认日志 `used=` 增长；回到家长设置点“重置今日用量”，确认归零。
5. 重启设备（`adb reboot`），等待启动完成，确认 `LegacyBoot: service requested after boot` 和 `LegacyService: service created`。
6. 在 Android 16 模拟器安装同一 APK，确认启动不崩溃；不授予悬浮窗权限时允许拦截层不显示，但服务必须存活。
7. 结束测试前把上限恢复为 20 分钟并重置用量，避免设备被锁死。
8. 可选：`kill -9` 杀掉应用进程，等待看门狗闹钟（最多约 60 秒），确认服务被重新拉起。

## 验证结果

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Debug / Release 构建 | 通过 | `:legacy:assembleDebug`、`:legacy:assembleRelease` 均 BUILD SUCCESSFUL |
| Android 4.4.2 安装与启动 | 通过 | 服务日志 `service created` |
| MENU → PIN → 设置表单 | 通过 | 默认 PIN `1234`，表单正常显示 |
| 0 分钟立即拦截 + 截图 | 通过 | `gate shown`，[legacy-gate.png](screenshots/legacy-gate.png) |
| 外部应用用量累计 | 通过 | 系统设置停留 15 秒 / 30 秒时 `used=15s` / `used=30s` |
| 重置今日用量 | 通过 | 状态恢复 `今日已用 0 分钟` |
| 开机自启 | 通过 | `LegacyBoot: service requested after boot` → `service created` |
| 进程被杀后恢复 | 通过（看门狗） | `kill -9` 后约 50 秒出现 `LegacyWatchdog: watchdog restarted service` |
| 拦截层按钮 / MENU 键 | 通过 | 点击“家长设置”或按 MENU 均能收起拦截层并打开 PIN 弹窗 |
| Android 16 冒烟 | 通过 | 前台服务 `isForeground=true types=0x40000000`；无悬浮窗权限时捕获 `BadTokenException`，进程存活、无 FATAL |
| Android 4.0–4.3 实机/模拟器 | 未验证 | 仅编译验证（minSdk 14） |
| Android 6.0+ 悬浮窗授权后的拦截 | 未验证 | 仅验证未授权降级路径 |

## 已知限制

- **无法阻止卸载或强行停止**：孩子/其他人可以在系统设置中卸载应用、强行停止应用或撤销悬浮窗权限。强行停止后系统会取消闹钟和开机广播，直到用户再次打开应用。
- **5.0+ 前台识别退化**：`getRunningTasks` 不再返回其他应用，当前只能按“本应用是否可见”粗略计时，无法区分具体应用，也无法做按应用规则。
- **6.0+ 悬浮窗需用户授权**：PoC 未实现授权引导；未授权时拦截层静默不显示（服务不崩溃）。需要引导用户到“显示在其他应用上层”设置。
- **看门狗成本与限制**：60 秒 `RTC_WAKEUP` 闹钟会带来持续唤醒开销；Doze、厂商省电策略、Android 12+ 后台前台服务限制都可能延迟或阻止恢复。
- **旧 WebView TLS**：Android 4.x 的系统证书库较旧，访问现代 HTTPS 站点可能失败；内置离线练习页不受影响。
- **计时精度**：3 秒轮询、Handler 延迟、休眠/后台限制都会导致用量偏差；只统计亮屏时间，不统计具体应用。
- **PIN 与配置安全性**：PIN 默认 `1234`，以明文保存在应用私有 SharedPreferences；有 root/adb 调试权限的人可以读取或修改。PoC 不提供防篡改能力。
- **无设备管理员/设备所有者能力**：不能锁定系统设置、不能阻止安装其他应用、不能做 kiosk。需要真正的设备所有者方案才能阻止卸载和修改权限。
- **通知与前台服务**：Android 13+ 未请求通知权限，前台服务通知可能不可见，但服务仍可运行；Android 14+ 的 `specialUse` 类型需要满足商店政策。
- **多用户/多分区**：未适配工作资料、多用户、分身应用等场景。
- **未验证矩阵**：Android 4.0–4.3、5.0–15、各类电视/盒子厂商 ROM、真机遥控器操作均未系统验证。

## 后续路线

1. **现代系统能力**：Android 5.0+ 接入 `UsageStatsManager`（需“使用情况访问”授权）或无障碍服务，恢复按应用识别；Android 7.0+ 评估设备所有者 / `DevicePolicyManager` 以阻止卸载和限制设置。
2. **权限引导**：为 Android 6.0+ 增加悬浮窗授权引导；为 5.0+ 增加使用情况访问引导；为厂商 ROM 增加自启动/后台白名单说明。
3. **可靠性**：用 `JobScheduler` / `WorkManager` 替代或补充老式闹钟；对 Android 12+ 使用合规的前台服务启动路径；增加服务存活的本地诊断日志。
4. **规则能力**：按应用白名单/黑名单、分时段规则、答题解锁、家长端远程配置与同步。
5. **安全加固**：PIN 加盐哈希存储、配置签名校验、调试开关与发布构建分离。
6. **验证矩阵**：补充 Android 4.0–4.3、5.0/6.0/8.0/12/14 模拟器和目标真机的回归；覆盖遥控器 D-pad 操作、休眠唤醒、重启、强杀、卸载重装等场景。

## 相关文件

- `app/native/legacy/src/main/AndroidManifest.xml`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyService.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyGate.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyPrefs.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacySettingsActivity.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyActivity.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/BootReceiver.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyWatchdogReceiver.java`
- `app/native/legacy/src/main/java/com/studyontv/legacy/LegacyServiceStarter.java`
- `docs/platform-support.md`
