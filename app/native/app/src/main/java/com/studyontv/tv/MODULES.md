# 模块职责

所有 Kotlin 源码都在 `com.studyontv.tv` 同一个包下，用文件名区分职责。新增文件时请归到下面某一类，并在本文件补一行。

## 核心控制流

| 文件 | 职责 |
|---|---|
| `GateService.kt` | 前台服务，每秒 tick 检查管控状态（计时、弹窗、静音、诊断） |
| `GateTickState.kt` | 单次 tick 的事实快照（纯数据），弹窗 / 静音 / 角标读同一份判断 |
| `GateOverlay.kt` / `CountdownOverlay.kt` / `GateActivity.kt` | 悬浮窗管控层、右上角剩余时间角标、全屏学习页 |
| `LearningEngine.kt` | 答题业务：出题、计时、奖励、错题与覆盖优先 |
| `LearningNavigation.kt` | 学习退出策略快照、遥控器导航按键过滤与不可用提示 |
| `TimePolicy.kt` / `DeviceClock.kt` | 可用时段判断、「今天」的界线（支持家长指定时区） |
| `WatchGap.kt` | 进程被固件清理期间漏掉的观看时长补算（心跳 + 开机时钟，不含深睡） |
| `WatchWhitelist.kt` | 不计时应用白名单（屏保 / 网课）的校验与解析 |
| `LocalStore.kt` | SQLite 数据访问层（题库、统计、事件、配置、多孩子、学情与整机备份） |
| `MediaStore.kt` | 题目配图与手写作答的本地文件存储、SHA-256 去重与孤儿清理 |
| `QuestionOptions.kt` | 选项与选项配图同步乱序及答案映射 |

## 网络与鉴权

| 文件 | 职责 |
|---|---|
| `LanServer.kt` | 局域网 HTTP 服务器（手写，零依赖）：解析请求、统一鉴权、静态文件 |
| `Router.kt` | 路由表：方法 + 路径 → 处理器，精确路径优先，支持前缀路由 |
| `AdminAuth.kt` | 家长管理口令、登录会话与临时验证 |
| `IntegrationAuth.kt` | Home Assistant 等外部集成的独立令牌与权限 |
| `DeviceBridge.kt` | 设备与家长面板之间的桥接（打开系统设置等） |
| `BankMarket.kt` / `BankAutomation.kt` | 在线题库市场、题库自动同步 |

## 音频与拦截

| 文件 | 职责 |
|---|---|
| `MediaPause.kt` | 暂停 / 静音其他应用的播放 |
| `AudioController.kt` | 静音与弹窗同源：弹窗没真正显示就解除静音（#110） |
| `SystemSettingsGuard.kt` | 识别系统设置应用、家长验证后的临时放行窗口 |
| `WatchAccessibilityService.kt` | 无障碍覆盖层与前台应用感知 |
| `Foreground.kt` | 当前前台包名 / Activity 判定（避开本应用自身） |

## UI 与入口

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` / `WebShell.kt` | 主界面与 WebView 容器 |
| `ParentActivity.kt` / `OnboardingActivity.kt` | 家长设置、首次引导 |
| `EducationalVideoActivity.kt` / `VideoLessons.kt` | 公益视频学习 |
| `TvUi.kt` / `FreshUi.kt` | 遥控器焦点逻辑、界面刷新 |
| `DesktopMode.kt` / `LauncherIcon.kt` | 设为电视桌面、隐藏桌面图标 |
| `UpdateActivity.kt` / `UpdateInstallReceiver.kt` / `Updater.kt` / `ApkInstaller.kt` / `ApkProvider.kt` | 应用自更新与安装 |
| `Pgyer.kt` | 蒲公英发布源 |

## 功能模块

| 文件 | 职责 |
|---|---|
| `SpeechReader.kt` / `EdgeTts.kt` / `VoiceCapture.kt` | TTS 朗读（含内置微软 Edge 在线朗读）、语音作答 |
| `TtsProbe.kt` | 本地语音引擎探测：有没有引擎、缺不缺语言包，供面板自检（#128） |
| `GradingService.kt` | AI 批改与 AI 出题 |
| `HolidayService.kt` | 节假日日历同步 |
| `KeepAlive.kt` / `Watchdog.kt` / `RevivalListener.kt` / `ProcessHealth.kt` / `BootReceiver.kt` / `GuardSync.kt` | 保活、开机自启、被系统清理后的复活 |
| `UninstallGuard.kt` | 防卸载（设备管理员） |
| `Playback.kt` / `PlaybackTraffic.kt` | 播放状态与流量辅助判断 |
| `Prefs.kt` / `DeviceRuntime.kt` / `SeedAssets.kt` | 偏好设置、运行时单例、内置题库 |
| `Diag.kt` / `CrashDetector.kt` / `BuglyInit.kt` | 诊断日志、崩溃检测与上报 |
| `RemoteKeys.kt` / `AnswerInput.kt` | 遥控器按键与答题输入 |

> 若以后要真正迁移到子包（`data/`、`service/`、`ui/`），需要同步修改 `AndroidManifest.xml` 里的 `android:name`，建议单独一个版本做。
