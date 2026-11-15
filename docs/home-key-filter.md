# 学习期间过滤遥控器 Home 和多任务键

需要家长启用「屏幕时间守护」无障碍服务。服务申请 `canRequestFilterKeyEvents` 和 `flagRequestFilterKeyEvents`，使用 `onKeyEvent` 过滤硬件 Home（`KEYCODE_HOME`）和多任务键（`KEYCODE_APP_SWITCH`），两者沿用同一退出策略；普通 Activity 不接收系统 Home。厂商是否分发这些按键仍需真机验证，系统导航手势及 ADB 注入事件不属于硬件按键过滤的保证范围。

## 判定与生命周期

`LearningEngine.status()` 新增 `canExit`，统一沿用原有 Back 退出规则：没有管控原因，且有可用奖励秒数或处于自由使用状态。原生退出桥和页面 Back 使用同一个结果。

业务操作结束后发布不可变的 `LearningExitSnapshot`；事务中的计算结果不发布，提交或回滚结束后再刷新。配置、远程放行、奖励结算、扣时和孩子切换均发布；运行时另有后台一秒刷新以覆盖跨天、时段和期限到达。可放行快照超过两秒未刷新时，学习页面保守拦截，避免卡住的刷新任务无限沿用过期放行。

导航键回调只读快照和界面归属，按设备与键码分别维护按下、重复、松开的整次决定，Home 与多任务键交错按下时互不覆盖；不使用 `downTime` 匹配重复或松开，因为组合返回、方向、音量等按键也可能改变该时间戳。首次 DOWN 保存决定，重复事件与 UP 沿用；新的首次 DOWN 可以覆盖丢失 UP 后的旧状态。两类按键分别增加内存拦截计数，不查询数据库、不等待学习引擎锁、不写文件，也不补发按键。BACK 的网页桥接与 Home/多任务键拦截共用 `LearningNavigation.notifyBlocked()`，将“现在暂不可用”提示排入主线程后返回，再调用当前 Activity 或悬浮窗网页共用的 `SOTV_NAVIGATION_BLOCKED` 显示，避免系统 Toast 被不透明覆盖层遮住；原生输入页/密码页没有学习 WebView 时回退原生 Toast。连续排队的请求合并，解锁或离开受限界面后不显示旧提示。纯网页没有原生桥时保留网页提示。

- 学习 Activity 和正在呈现的学习覆盖层：按快照决定。
- 原生输入 Activity：类型和请求 ID 均与当前输入请求匹配才属于学习；后台等待请求不会锁住其他应用。
- 未通过本机验证的家长页：仍受退出规则控制；已验证管理页、首次设置和权限引导允许 Home。离开家长页超过原有一分钟期限后恢复密码验证和限制。
- 外部应用（包括外部语音识别 Activity）：不根据悬而未决的输入请求拦截 Home，原有后台管控继续独立工作。
- 服务解绑/销毁：清除按键序列。按住期间发生解锁时，本次仍保持首次按下的决定，下一次 Home 使用新策略。

诊断 `/api/device` 的 `monitor` 增加 `homeBlockedPresses`（Home 拦截次数）、`appSwitchBlockedPresses`（多任务键拦截次数）、`homePolicyExitAllowed`、`homePolicyFresh`、`homeBlocking` 和 `homeFilterRequested`。

## 回归入口

```text
adb -s <测试设备> shell am instrument -w -e home 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
adb -s <独立调试模拟器> shell am instrument -w -e homeUi 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```

`home` 使用独立数据库，覆盖能力声明、Home/多任务键完整序列、与返回/方向/音量键组合导致的时间戳变化、丢失 UP 后恢复、快照时效、配置/余额/孩子/答题/期限发布。

`homeUi` **只允许独立 debug 模拟器**：会用随机且不输出的密码初始化调试应用、修改测试规则。验证实际无障碍回调在另一线程持有引擎锁和 SQLite 事务时仍快速返回，并验证 BACK 网页桥接与 Home/多任务键回调在 Activity 和实际全屏悬浮窗中显示同一提示（检查 DOM 可见性并保存截图）、原生输入页前后台、家长验证与验证过期、立即远程放行。测试后应清理该独立模拟器的调试应用数据。

宿主端还应发送真实硬件输入路径的 Home，用拦截计数和前台窗口共同验证；`adb shell input keyevent HOME` 在部分系统会绕过无障碍过滤，不能据此判断物理键能力。

## 剩余边界

回调本身不读库，并不能保证系统的等待时限永远满足。无障碍回调仍使用应用主线程，现有其他主线程任务（例如管控服务的检查）若长时间阻塞，可能使按键在进入回调前已超时。此次没有把整个管控服务重构为异步状态机；需要在目标电视和压力负载下继续验证，不宣称能阻止厂商关闭无障碍或强停应用。
