# GitHub issue 排查记录

本轮读取 `chadyi/StudyOnTV` 的 57 条开放 issue 及评论，对照当前工作区代码排查。下表是本地修改状态，不代表上游已合并或 issue 已关闭。相同根因的报告合并处理；厂商行为需要对应真机验证。

## 本轮修改

- #77（填空部分）、#80、#84：原生输入页打开期间保留学习页 WebView，避免答案回传到已销毁实例。#77 的系统强停、无障碍崩溃停用部分仍未解决。
- #79、#96：过滤没有 resumed Activity 时由自身悬浮窗发出的前台事件；缓存为自身包名时继续尝试外部应用识别。
- #99、#101（计时部分）：前台信息增加包名与 Activity 类名配对，对 HOME 与影视共包的设备区分桌面 Activity 和内容 Activity，媒体播放状态仍作兜底。需要海信 JUUI 真机复测。
- #83：通过引擎同步保存规则，当天免费余额按修改差额立即更新；重复保存不重新发放。免费时间遵守每日上限与可用时段，实际消耗计入使用事件。
- #95：同一应用短暂失去播放状态时保留角标 12 秒，关屏、应用切换、锁定仍立即隐藏；新增默认开启的「显示剩余时间角标」。最后一分钟闪烁保留。
- #60、#59（批量题库操作部分）、#90（删除部分）：新增清空全部题库 API 与确认入口，包含已暂停题、清理复习进度；增加常显的「选择全部筛选结果」，支持跨页批量删除。清空题库不删除答题历史；对应进行中的任务和解析停留会失效，学习页通过任务代号更新，避免继续显示已删除题目。
- #98：新增默认关闭的「随机打乱选项」，仅改变出题快照，单选、多选的答案索引同步调整，判断题保持顺序。
- #86：每 60 秒检查无障碍授权是否撤销，保留丢失标记，恢复授权后清除提醒；家长设备页轮询并显示丢失/未连接提示。
- #102：二维码优先使用物理 Wi-Fi / Ethernet 地址，备用扫描排除常见隧道网卡，系统网络 API 失败时继续备用扫描。
- #106：AI 连接测试、模型列表请求超时从 10 秒增加至 60 秒；AI 请求超时提示与局域网连接超时区分。尚不能据此认定所有自定义服务均已兼容。

## 最新追加（#110–#112）

- #110：静音与弹窗改为同源判定。只有确实可见的 Gate（悬浮层 / 无障碍层 / 全屏学习页 / 刚拉起的兜底页）或输入页在前台时才允许持续静音；窗口被系统移除、addView 成功但不可见、弹窗失败时立即解除静音。弹窗失败按 2s→3s→5s→10s 退避重试，失败原因进入诊断和家长通知。已有 `GatePresentationRegression` 覆盖。
- #112：新增「网课时段」与「大人豁免」：精确到分钟、按真实星期、支持跨午夜；网课时段可选自由使用（不扣时长）或计入每日限额并使用该时段自己的上限（不改写已用时长，离开时段恢复原上限）；豁免优先于网课。家长端有增删改编辑器，`LocalStore` 做保存校验，`TimePointsRegression` 覆盖边界、跨午夜、优先级与旧配置兼容。待真机用真实网课时间验证。
- #111：SyncAdapter 方案未落地。分析结论是它同样受 `force-stop` 的 stopped 状态约束，救不了 #48/#67/#70/#76 这类强停，只会改善“只杀进程”的清理；曾试验的通知监听心跳通道未保留在最终工作区，当前没有对应实现或清单注册。需要的话应作为独立、默认关闭的实验特性重新设计。分析见 docs/issue-111-analysis.md。

## 全部开放 issue 对照

| Issue | 结论与后续 |
| --- | --- |
| [#112](https://github.com/chadyi/StudyOnTV/issues/112) 自定义时间点 | 已实现精确到分钟的网课时段（自由 / 计入限额 + 独立上限）、大人豁免、跨午夜与家长端编辑器，并有引擎回归；待真实网课时间和海信/JUUI 等固件复测。 |
| [#111](https://github.com/chadyi/StudyOnTV/issues/111) SyncAdapter 自愈 | 未实现，本版不做。参考补丁未合入；同源分析认为 SyncAdapter 仍受 force-stop/stopped 限制，不能解决强停类问题。当前工作区无通知监听心跳实现。分析见 docs/issue-111-analysis.md。 |
| [#110](https://github.com/chadyi/StudyOnTV/issues/110) 静音后不弹题 | 已修：静音必须由可见 Gate 或输入页背书；窗口被系统移除/不可见/失败时立即解除静音，弹窗失败退避重试并写诊断。需创维酷开真机复测。 |
| [#107](https://github.com/chadyi/StudyOnTV/issues/107) | 已补 MAXHUB Android / Windows OPS 安装范围与诊断说明；仍无对应真机验证。 |
| [#106](https://github.com/chadyi/StudyOnTV/issues/106) 自定义 AI | 已修前端过早超时；服务本身的协议/模型兼容性需实测。 |
| [#105](https://github.com/chadyi/StudyOnTV/issues/105) | 已实现多来源远程 JSON 周期拉取、手动同步、稳定 ID、批次校验和失败回滚。 |
| [#104](https://github.com/chadyi/StudyOnTV/issues/104) | 已评估外部看门狗方向；另阅读用户指定 tv-vigil，并实现跨平台外部 ADB 看门狗，尚无厂商真机恢复结论。 |
| [#102](https://github.com/chadyi/StudyOnTV/issues/102) VPN 二维码 | 已改地址选择，需多网卡真机验证。 |
| [#101](https://github.com/chadyi/StudyOnTV/issues/101) | 已改共包内容页计时，增加外部恢复工具和设备所有者防卸载策略；固件撤权/待机行为仍需真机验证。 |
| [#100](https://github.com/chadyi/StudyOnTV/issues/100) 飞利浦自启 | 需重启前后服务/权限日志，未解决。 |
| [#99](https://github.com/chadyi/StudyOnTV/issues/99) JUUI 共包不计时 | 已增加 Activity 识别，保留媒体兜底；待原机复测。 |
| [#98](https://github.com/chadyi/StudyOnTV/issues/98) 打乱选项 | 已实现可选开关与答案映射，已做回归测试。 |
| [#97](https://github.com/chadyi/StudyOnTV/issues/97) | 已有 Device Owner 时新增系统持久化卸载限制及家长开关；普通管理员的厂商卸载路径仍不能保证。 |
| [#96](https://github.com/chadyi/StudyOnTV/issues/96) 自身悬浮窗污染前台 | 已过滤并增加自身缓存兜底。 |
| [#95](https://github.com/chadyi/StudyOnTV/issues/95) 角标闪现 | 已加同应用宽限和显示开关，需原机缓冲场景验证。 |
| [#94](https://github.com/chadyi/StudyOnTV/issues/94) Docker | 日志来自 ChinaTextbookStudyFree / congcong-school，不是本仓库的构建链，未在此修改。 |
| [#93](https://github.com/chadyi/StudyOnTV/issues/93) Sony 拦截失效 | 已修「前台缓存长期停在自身包名/陈旧应用」并加入回归；Sony 20/21 的拦截层能否恢复仍需原机服务与窗口日志确认。 |
| [#92](https://github.com/chadyi/StudyOnTV/issues/92) 长虹悬浮权限 | 已实现三级判定 + 一次真实 `addView` 探针（设置 API → AppOps → 窗口探针），不再只信 `Settings.canDrawOverlays()`；探测结果缓存并限频。正向分支仍需长虹真机验证。 |
| [#91](https://github.com/chadyi/StudyOnTV/issues/91) | 已实现累计答对指定题数、错题重练，时长和组数上限可独立关闭。 |
| [#90](https://github.com/chadyi/StudyOnTV/issues/90) | 已实现命名题库、整库删除、DOCX/文字 PDF 导入预览；解析器按需加载。扫描 OCR、旧 .doc、任意版式不在当前能力内。 |
| [#89](https://github.com/chadyi/StudyOnTV/issues/89) | 已实现首页加减 1–480 分钟、同步调整今日额度、跨天重置，不修改每日规则。 |
| [#87](https://github.com/chadyi/StudyOnTV/issues/87) | 已实现共享朗读控制器、系统/Azure/阿里云/自动通路、签名换 Token、缓存、云端失败回落；真实云服务及电视音频路由仍需凭据和真机测试。 |
| [#86](https://github.com/chadyi/StudyOnTV/issues/86) 无障碍撤销检测 | 已增加运行期检查、诊断字段、家长提示。 |
| [#85](https://github.com/chadyi/StudyOnTV/issues/85) | 周时段已具备；新增多题库范围和可选孩子端每轮选择年级。 |
| [#84](https://github.com/chadyi/StudyOnTV/issues/84) 小米填空丢失 | 已修输入期间销毁 WebView，需原机复测。 |
| [#83](https://github.com/chadyi/StudyOnTV/issues/83) 免费时长不生效 | 已修当天差额调整，并测试上限、时段、重复保存、跨天。 |
| [#82](https://github.com/chadyi/StudyOnTV/issues/82) | 已实现出题/作答/正确统计，以及可选暂停已掌握题和 AI 自动补题。旧历史没有展示事件，出题统计从本版本开始。 |
| [#81](https://github.com/chadyi/StudyOnTV/issues/81) | 已新增按题语言的语音转写、编辑、重录、确认；无服务时保留文字输入。未做发音评分，小米遥控器路径需真机验证。 |
| [#80](https://github.com/chadyi/StudyOnTV/issues/80) 海信填空死锁 | 与 #84 同根因，已修输入期间销毁 WebView。 |
| [#79](https://github.com/chadyi/StudyOnTV/issues/79) 小米前台停留自身 | 已修自身事件/缓存路径，其他厂商事件缺失仍需真机验证。 |
| [#78](https://github.com/chadyi/StudyOnTV/issues/78) | 已实现学习页字号 75–200%，家长保存后更新。 |
| [#77](https://github.com/chadyi/StudyOnTV/issues/77) 当贝三缺陷 | 填空生命周期已修；系统强停、crashed 无障碍自动重申请未解决。 |
| [#76](https://github.com/chadyi/StudyOnTV/issues/76) 乐视退后台被杀 | 厂商行为，未解决。 |
| [#75](https://github.com/chadyi/StudyOnTV/issues/75) | 已实现开机/唤醒免费额度，服务重启不重复发放，保留每日免费时长。 |
| [#73](https://github.com/chadyi/StudyOnTV/issues/73) | 已补诊断文档与外部恢复工具；没有修改厂商全局清理配置。 |
| [#72](https://github.com/chadyi/StudyOnTV/issues/72) | 保留普通设备管理员，新增已有 Device Owner 时系统持久化卸载限制；不自动进行设备所有者配置。 |
| [#71](https://github.com/chadyi/StudyOnTV/issues/71) | 已实现可选整机流量采样，结合媒体音频状态，仅用于前台不明确场景；下载流量不会单独计时。 |
| [#70](https://github.com/chadyi/StudyOnTV/issues/70) 创维被杀 | 厂商行为，未解决。 |
| [#67](https://github.com/chadyi/StudyOnTV/issues/67) 坚果强停 | 外部系统服务强停；已有开机入口不能防止 force-stop，未解决。 |
| [#62](https://github.com/chadyi/StudyOnTV/issues/62) | 已实现设备端持久化解析停留，0–300 秒，倒计时期间禁止下一题和使用奖励时间；含刷新重开恢复。 |
| [#60](https://github.com/chadyi/StudyOnTV/issues/60) 清空题库 | 已实现并测试包含暂停题、清理进度、重复清空。 |
| [#59](https://github.com/chadyi/StudyOnTV/issues/59) 多项反馈 | 已改善题库跨页删除；Home 键、TCL 权限限制未解决。 |
| [#58](https://github.com/chadyi/StudyOnTV/issues/58) | 已实现可选孩子端每轮选择允许年级，进行中的任务保持原年级范围。 |
| [#57](https://github.com/chadyi/StudyOnTV/issues/57) | 已实现独立累计实际观看和强制休息，跨重启保存、保留额度/组数，关屏答题不累计。 |
| [#55](https://github.com/chadyi/StudyOnTV/issues/55) | 同 #58，已新增孩子端年级选择。 |
| [#52](https://github.com/chadyi/StudyOnTV/issues/52) | 已有前台服务；新增外部 ADB 恢复工具，尚不能证明所有小米固件已解决。 |
| [#48](https://github.com/chadyi/StudyOnTV/issues/48) | 已补厂商说明、可选后台锚点开关及外部 ADB 恢复工具；不宣称 APK 能自行解除强停。 |
| [#46](https://github.com/chadyi/StudyOnTV/issues/46) | 保留自启诊断，新增外部恢复工具；仍需冷启动和 ADB 保持条件验证。 |
| [#44](https://github.com/chadyi/StudyOnTV/issues/44) 当贝腾讯不拦截 | 缺前台、权限、服务日志，未认定已由本轮修复覆盖。 |
| [#43](https://github.com/chadyi/StudyOnTV/issues/43) | 已增加后台锚点开关及外部恢复工具；系统窗口查杀策略仍需原机验证。 |
| [#40](https://github.com/chadyi/StudyOnTV/issues/40) 商业授权 | 授权咨询，应由项目作者处理，不是代码缺陷。 |
| [#38](https://github.com/chadyi/StudyOnTV/issues/38) | 已增加运行期撤销提示和可选外部恢复工具；未保证被强停期间零管控空窗。 |
| [#35](https://github.com/chadyi/StudyOnTV/issues/35) | 已支持题目图片、音频和原生音频播放；题目与识别/朗读语言可配置。 |
| [#31](https://github.com/chadyi/StudyOnTV/issues/31) | 新增可安装在 Android 4.0+ 的独立离线练习 APK；不含现代版整机管控/后台 API，尚无 Android 4 真机验证。 |
| [#20](https://github.com/chadyi/StudyOnTV/issues/20) | 已实现公益视频、答题、交替三种内容模式，实际播放累计后领奖，暂停/拖动不能直接跳过；需家长提供合法媒体直链。 |
| [#19](https://github.com/chadyi/StudyOnTV/issues/19) | 新增 iPad Safari 本地练习端及离线缓存入口；未实现 iOS 原生整机管控，需 Apple 平台能力及签名。 |
| [#17](https://github.com/chadyi/StudyOnTV/issues/17) 手机不计时 | 现有使用情况/权限/共包计时相关；缺原机复现，未认定解决。 |

## 后续需求实现

当前 Android 工程已新增加减时、持久休息、开机免费、简单模式、年级选择、字号、网课时段/大人豁免、远程题库、统计/自动补题、文档导入、图片音频、云端朗读、按题语言语音输入和公益视频模式。操作与能力边界见 [新增需求说明](./feature-requests.md)。

用户指定参考的 `tv-vigil` 已完成源码分析，新增 Python 外部 ADB 看门狗，见 [后台恢复](./background-recovery.md)。iPad/旧 Android 提供独立浏览器/离线练习版本，不能等同现代 Android 整机管控，见 [平台支持](./platform-support.md)。

## 验证与复测

前端运行 `npm --prefix parent-ui test`、`npm --prefix parent-ui run build`。Android 编译 TV、mobile 两种 Debug 包。

原生回归使用独立的 `issue-regression.db`，不修改家庭题库：

```sh
gradle -p app/native :app:assembleTvDebug :app:assembleTvDebugAndroidTest
adb install -r app/native/app/build/outputs/apk/tv/debug/app-tv-debug.apk
adb install -r app/native/app/build/outputs/apk/androidTest/tv/debug/app-tv-debug-androidTest.apk
adb shell am instrument -w com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```

回归覆盖：免费额度即时差额调整、重复保存、无效配置、每日上限、可用时段、跨天；100 次单选/多选答案映射及不改变判断题；清空题库包含暂停题及复习进度。已在本地 Android TV 模拟器执行通过。模拟器不能替代小米/当贝/海信的厂商固件实测。

后续新增回归覆盖：数据库 v5→v6 保留题库/规则、当日加减时及跨天、服务重启不重复发放开机额度、强制休息跨引擎恢复、孩子选年级、简单模式、解析停留不能提前跳过、视频未完成不能领奖/不能重复领奖、远程同步失败原子回滚、清空题库使任务失效、朗读密钥不回显。已在 Android TV 模拟器执行通过。\n\nPython 外部看门狗的 9 项测试通过；浏览器验证了年级选择不被轮询覆盖、解析倒计时放行、DOCX/PDF 实际文件提取、独立练习记录保存及离线 worker 激活。TV、mobile、legacy 包编译通过，legacy lint 无错误。没有真实云朗读凭据、Android 4 或厂商电视实机，不能把这些模拟器/浏览器验证等同对应平台的实测。\n\n建议真机复测：填空输入确认和取消、切换应用后余额扣减、共包影视页与真正桌面的计时、角标缓冲宽限、撤销再恢复无障碍、VPN 开关前后的二维码地址、待机唤醒与冷启动、系统卸载入口。记录版本、型号、系统、权限及诊断日志。
