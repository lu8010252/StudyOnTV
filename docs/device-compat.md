# 设备兼容性

不同电视厂商对后台进程、悬浮窗和无障碍的限制差别很大。这里汇总 Issue 里出现过的真实机型与实测结论，买电视、升级固件或改管控逻辑前可以先查一眼。

> 本表根据 Issue 标题和讨论整理，最后整理于 2026-11-15。状态以对应 Issue 的最新讨论为准，固件更新可能改变结论。

## 状态说明

| 标记 | 含义 |
|---|---|
| ✅ 可用 | 主要功能正常，或问题已在当前版本修复 |
| ⚠️ 部分可用 | 管控能用，但需要额外设置，或某个功能受固件限制 |
| ❌ 不可用 | 安装、启动或核心管控无法工作 |
| 🧪 待验证 | 只有记录或需求，还没有明确的复测结论 |

## 实测汇总

| 品牌 | 型号 | 系统 | 状态 | 说明 | Issue |
|---|---|---|---|---|---|
| 海信 / Vidda | 65V3H-X（65 吋） | VIDAA | ⚠️ 部分可用 | 悬浮窗可能被视频层压住，学习页不可见；可开「强制使用全屏学习页」 | [#101](https://github.com/chadyi/StudyOnTV/issues/101) [#122](https://github.com/chadyi/StudyOnTV/issues/122) |
| 海信 / Vidda | — | VIDAA / JUUI | ⚠️ 部分可用 | 桌面与聚好看同包名，前台识别不到；已用播放状态兜底计时 | [#66](https://github.com/chadyi/StudyOnTV/issues/66) [#99](https://github.com/chadyi/StudyOnTV/issues/99) |
| 海信 / Vidda | — | VIDAA | ⚠️ 部分可用 | 拔电冷启动后无障碍可能被解绑，到点不弹题 | [#129](https://github.com/chadyi/StudyOnTV/issues/129) |
| 创维 / 酷开 | 55S7E | 酷开 9.0 / Android 8.0 | 🧪 待验证 | 有真机兼容性验证记录，细节见 Issue | [#124](https://github.com/chadyi/StudyOnTV/issues/124) |
| 创维 / 酷开 | A5D Pro | 酷开 / Android 13 | ⚠️ 部分可用 | 开启「强制使用全屏学习页」后，遥控器关机键失效 | [#143](https://github.com/chadyi/StudyOnTV/issues/143) |
| 创维 / 酷开 | 7S77A20 | — | ⚠️ 部分可用 | 进程频繁被系统清理 | [#70](https://github.com/chadyi/StudyOnTV/issues/70) |
| 创维 / 酷开 | — | — | ⚠️ 部分可用 | 悬浮窗可能被固件回收，可用全屏学习页兜底 | [#14](https://github.com/chadyi/StudyOnTV/issues/14) [#23](https://github.com/chadyi/StudyOnTV/issues/23) [#49](https://github.com/chadyi/StudyOnTV/issues/49) |
| 小米 / Redmi | 85 吋 | HyperOS | ⚠️ 部分可用 | 题目排版看不全，配置界面调不出来 | [#141](https://github.com/chadyi/StudyOnTV/issues/141) |
| 小米 / Redmi | 电视 S 75 miniLED | — | ❌ 不可用 | 无法使用，详见 Issue | [#113](https://github.com/chadyi/StudyOnTV/issues/113) |
| 小米 / Redmi | HyperOS 3.0.106 机型 | HyperOS | ⚠️ 部分可用 | 退后台数秒被强杀；`SYSTEM_ALERT_WINDOW=allow` 可保活 | [#43](https://github.com/chadyi/StudyOnTV/issues/43) |
| 小米 / Redmi | MiTV-ASTR0 | — | ⚠️ 部分可用 | 周期清理强停，Issue 附固件开关与回滚方法 | [#73](https://github.com/chadyi/StudyOnTV/issues/73) |
| 小米 / Redmi | MiTV-ANSP0 | Android 9 | ⚠️ 部分可用 | 断电重启后不自启，管控静默失效 | [#46](https://github.com/chadyi/StudyOnTV/issues/46) |
| 小米 / Redmi | miTV（型号未注明） | — | ⚠️ 部分可用 | 按返回键回桌面后进程被杀，8099 管理页中断 | [#52](https://github.com/chadyi/StudyOnTV/issues/52) |
| 小米 / Redmi | — | MIUI for TV | ⚠️ 部分可用 | 无障碍被反复移除、进程被反复杀，使用时长不扣减 | [#38](https://github.com/chadyi/StudyOnTV/issues/38) |
| 小米 / Redmi | — | HyperOS | ❌ 不可用 | 无法使用，详见 Issue | [#131](https://github.com/chadyi/StudyOnTV/issues/131) |
| 小米 / Redmi | MiTV-NSOP0 | Android 9 | ✅ 已修复 | 填空题点确定后答案丢失（WebView 被 `onStop` 销毁） | [#84](https://github.com/chadyi/StudyOnTV/issues/84) |
| 小米 / Redmi | — | Android 14 | ✅ 已修复 | 返回桌面时因 `HOME_STATE` 权限崩溃 | [#11](https://github.com/chadyi/StudyOnTV/issues/11) |
| 红米 | A Pro 65 | — | ⚠️ 部分可用 | 无障碍无法启动，回桌面后自动关闭 | [#120](https://github.com/chadyi/StudyOnTV/issues/120) [#127](https://github.com/chadyi/StudyOnTV/issues/127) |
| TCL | T7L | — | ⚠️ 部分可用 | 没有声音 | [#128](https://github.com/chadyi/StudyOnTV/issues/128) |
| TCL | mt5879_cn | — | ✅ 已修复 | 输入答案后返回为空 | [#64](https://github.com/chadyi/StudyOnTV/issues/64) |
| OPPO | 电视（型号未注明） | — | ❌ 不可用 | 无法安装 v0.3.34 | [#146](https://github.com/chadyi/StudyOnTV/issues/146) |
| 飞利浦 | — | — | ⚠️ 部分可用 | 重启后 App 不自启，需要手动打开 | [#100](https://github.com/chadyi/StudyOnTV/issues/100) |
| 索尼 | 2020 / 2021 机型 | — | ⚠️ 部分可用 | 有兼容问题，细节见 Issue | [#93](https://github.com/chadyi/StudyOnTV/issues/93) |
| 索尼 | — | — | ✅ 已修复 | 答题结束后偶尔退出播放到桌面 | [#74](https://github.com/chadyi/StudyOnTV/issues/74) |
| 长虹 | — | — | 🧪 待复核 | 无法开启悬浮窗；Issue 已关闭，欢迎补充复测结果 | [#92](https://github.com/chadyi/StudyOnTV/issues/92) |
| 乐视 | 超 4 X5 | — | ⚠️ 部分可用 | 返回桌面后立刻被杀 | [#76](https://github.com/chadyi/StudyOnTV/issues/76) |
| 天猫魔盒 | MagicBox_M16S | — | ⚠️ 部分可用 | 安装后没有声音 | [#116](https://github.com/chadyi/StudyOnTV/issues/116) |
| 当贝 | H3（DBBOXH3） | 当贝 OS 4.0.0.2 / Android 11 | ⚠️ 部分可用 | 填空答案丢失、无障碍崩溃后不重申请、被杀不自恢复 | [#77](https://github.com/chadyi/StudyOnTV/issues/77) |
| 当贝 | 投影仪（型号未注明） | — | ⚠️ 部分可用 | 进入腾讯视频不生效 | [#44](https://github.com/chadyi/StudyOnTV/issues/44) |
| 坚果 | T912 | Android 11 | ⚠️ 部分可用 | 开机自启被系统 `com.jmgo.hippo` 清后台 | [#67](https://github.com/chadyi/StudyOnTV/issues/67) |
| 华为 | Mate 50 | — | ⚠️ 部分可用 | 后台限制到点不生效 | [#17](https://github.com/chadyi/StudyOnTV/issues/17) |
| MaxHub | 大屏（型号未注明） | — | 🧪 待验证 | 希望支持，尚无验证记录 | [#107](https://github.com/chadyi/StudyOnTV/issues/107) |

## 支持范围

- Android 5.0+（API 21）的手机、平板和电视；Android 4.x 不支持（[#31](https://github.com/chadyi/StudyOnTV/issues/31)）。
- 鸿蒙 NEXT、Tizen、webOS、iOS / iPadOS 暂无原生端（[#121](https://github.com/chadyi/StudyOnTV/issues/121)）。
- 受控设备与家长手机需要在同一局域网，或由家长自建隧道后访问。

## 怎么补充一条

欢迎直接提 Issue 或在 PR 里改这张表。为了结论可复现，请写清楚：

1. 品牌、具体型号（设置 → 关于里能找到，例如 `MiTV-ANSP0`）；
2. 系统版本与 Android 版本（例如「当贝 OS 4.0.0.2 / Android 11」）；
3. App 版本与安装方式（ADB / U 盘 / 应用商店）；
4. 已开启的权限：悬浮窗、使用情况访问、无障碍、自启动、电池优化；
5. 实际现象：到点是否弹题、时长是否扣减、重启后是否恢复；
6. 是否试过「强制使用全屏学习页」等兜底开关。

如果问题已经修好，请补充修复版本号，方便把状态改成 ✅。
