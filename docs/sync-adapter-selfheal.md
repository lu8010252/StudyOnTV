# 同步框架自愈（SyncAdapter）——实现、授权与实测

`GuardSync.kt` 把一条 SyncAdapter 挂在系统的 `settings` authority 上，作为现有三条通道之外的**第四条拉起通道**。它不替代 `GateService` 的前台服务、`Watchdog` 的 JobScheduler/Alarm、`BootReceiver` 或 `RevivalListener`，只补一个它们覆盖不到的缺口：**固件 force-stop 之后，包处于 `stopped=true` 时，开机广播与自启全部失效**。

## 为什么这条通道能穿过 stopped

force-stop 会杀掉进程、取消闹钟与作业、解绑无障碍与通知监听，并给包打上 `FLAG_STOPPED`。但同步调度由 `system_server` 里的 `SyncManager` 持有，adapter 注册信息在系统侧缓存；到点时系统**显式 bind** 拉起 `GuardSyncService` 执行 `onPerformSync`。而 Android 对"系统主动拉起进程"会**自动清除 stopped 标记** —— 之后标准 `BOOT_COMPLETED` 才会重新进入派发名单。

两个触发源：

| 触发源 | 时机 | 作用 |
| --- | --- | --- |
| 事件同步（LOCAL） | SettingsProvider 每次写入都带 `SYNC_EXTRAS_UPLOAD`；固件摘除本应用无障碍的那次写入发生在 force-stop 过程中，通常不会立即调度，**下一次任意 settings 写入**（音量、亮度、电源状态等）才会把它拉起来 | 有写入时 3–4 秒 |
| 周期同步（PERIODIC） | `addPeriodicSync`，请求 300s，系统钳到最小 900s | 静置无写入时的兜底（最长 15 分钟），并负责清掉 stopped 标记 |

也就是说，force-stop 之后能否"秒级"回来，取决于电视在随后几分钟里有没有 settings 写入：开机、唤醒、调音量、切信号源都会写，所以开机/待机场景恢复很快；电视完全静置时最长要等 15 分钟周期作业。

## 关键配置（少一个就失效）

1. `res/xml/guard_sync.xml` 的 `android:supportsUploading` **必须为 `true`**。SyncManager 会直接丢弃发给 `false` 适配器的 upload 同步 —— 表现为 `dumpsys content` 里永远只有 `PERIODIC`、没有 `LOCAL`，事件通道完全失效。
2. Manifest 必须声明 `READ_SYNC_SETTINGS` / `WRITE_SYNC_SETTINGS`（安装时权限）。缺了会抛 `SecurityException`，同步适配器永远不启用。
3. `WRITE_SECURE_SETTINGS` 需 adb 一次性授权；未授权时 `GuardSync` 什么都不注册（见下）。
4. 服务声明用官方写法：`GuardSyncService` `exported="true"` + `android.permission.BIND_SYNC_ADAPTER`；`GuardAccountService` `exported="true"`。

## 默认关闭

`GuardSync.ensure()` 第一行就检查 `WRITE_SECURE_SETTINGS`：**未授权时不创建账户、不注册同步**。所以：

- 普通用户装完什么都不多（系统账户列表里不会出现"题了个题"条目）；
- 家长/维护者执行一次 adb 授权后，下次启动自动启用；
- 想关掉就 `pm revoke` 或删除该账户。

## 授权步骤

```bash
# 1. 一次性授权（必需）
adb shell pm grant com.studyontv.tv android.permission.WRITE_SECURE_SETTINGS

# 2. 通知监听复活通道（可选，RevivalListener 已有；直接写 settings 自批准无效）
adb shell cmd notification allow_listener com.studyontv.tv/com.studyontv.tv.RevivalListener
```

授权后重启应用一次即可（`DeviceRuntime.ensure` → `GuardSync.ensure + heal`）。

## 真机实测（MiTV-MFFU0 / Android 14，2026-11-10）

| 场景 | 结果 | 观察点 |
| --- | --- | --- |
| `am force-stop` 后**主动写一条 settings** | **3.5 秒复活** | `dumpsys content` 新增 `LOCAL ... android.uid.system:1000`，stopped 被清除 |
| `am force-stop` 后**电视静置、无任何 settings 写入** | 一次 +72 秒被周期作业拉起；一次 120 秒内未拉起（继续等 15 分钟周期） | 拉起时 `stopped=false`，无障碍自动补回 |
| 普通待机 → 开屏 | **恢复当秒拉起**，未走 15 分钟周期 | 监控捕获进程 UP + `enabled_accessibility_services` 含本应用段 |
| 拔电关机 → 上电开机 | **约 10 秒自动拉起** | `uptime=5min` 时进程存活、无障碍串完整（其他厂商段保留）、`stopped=false` |

三场景全程无外部看门狗、无常驻 adb、无人工打开 App。

### 对 `docs/issue-111-analysis.md` 待验证清单的回应

| 编号 | 结论 |
| --- | --- |
| V1（stopped 包是否被同步调起） | **已证**：force-stop 后写一条 settings，3.5 秒被拉起，`dumpsys content` 可见 `LOCAL ... android.uid.system:1000`；静置时由周期作业拉起（一次 +72s） |
| V2（settings 写入是否触发同步） | **已证**：任意 settings 写入后 LOCAL 记录 `initiator=android.uid.system:1000` |
| V3（自动同步总开关） | 关闭时同步不调度，`ensure()` 会写一条诊断日志提示 |
| V4（固件摘无障碍时写了什么） | 小米机型实测只改 `ENABLED_ACCESSIBILITY_SERVICES`；`heal()` 仍会顺带补 `ACCESSIBILITY_ENABLED=1` 兜底 |
| V5（服务声明） | 采用官方 `exported="true"` + `BIND_SYNC_ADAPTER`；早期 `false` 在本机也可用，但不依赖未文档化行为 |
| V6（账户观感） | 未授权不建账户；授权后账户名 `StudyOnTV`，可在家长面板诊断中展示 |
| V7（NLS 对照） | 上游 `RevivalListener` 已实现；stоpped 场景仍靠 SyncAdapter，二者互补 |
| V8（功耗） | 周期同步 15 分钟一次，未做 batterystats 对比，待补 |

## 边界

- 仅在上述机型/系统验证，其他品牌与 Android 版本未测，不做承诺。
- `low_ram` 机型、固件裁剪 `SyncManager`、系统"自动同步"总开关被关闭时，这条通道会失效；此时仍是现有三通道 + 外部 ADB 看门狗兜底。
- `heal()` 对 `ENABLED_ACCESSIBILITY_SERVICES` 是"读-改-写"，与其他守护应用并发写时存在覆盖窗口（只写自己的段，但整串会重写）。
- 账户条目会出现在系统账户列表（仅授权后），多数电视裁掉了该入口。
