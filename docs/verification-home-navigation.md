# Home 过滤验证记录

验证日期：2026-11-13（Asia/Shanghai）。

## 分支与隔离

- 分支：`codex/block-home-during-quiz`，起点 `cacda54`（v0.3.31）。
- 实现由 subagent 完成。发现另一会话切换共享工作区分支后暂停写入，将当时 17 个实现及测试文件逐一核对并迁入独立工作树，再继续构建验证。
- 原工作区仅移出本次 Home 修改，既有 `admin` 文件经 SHA-256 校验保持不变。

实现与能力边界见 [Home 过滤说明](./home-key-filter.md)。

## 构建

以下 Gradle 任务均成功：

```text
:app:assembleTvDebug
:app:assembleTvDebugAndroidTest
:app:compileMobileDebugKotlin
```

测试源修正后再次成功构建 `:app:assembleTvDebugAndroidTest`，生产代码未改变。

## 模拟器与输入路径

- 独立 Android 13 / API 33 Google APIs x86_64 模拟器，序列号 `emulator-5586`，1080p 显示。
- 使用调试包 `com.studyontv.tv.debug`；未连接、安装或修改真实电视，未操作其他会话的 `emulator-5584`。
- APK 的 Bugly 原生库仅包含 ARM。为在 x86_64 模拟器运行，测试副本去掉 `lib/` 并重新使用调试证书签名，业务代码与无障碍元数据不变，原始构建 APK 未修改。
- 仅将测试模拟器的 adbd 切为 root，用于写入内核输入设备；应用仍运行于普通应用 UID，未配置 Device Owner 或 Lock Task。
- 先确认 `/dev/input/event1` 键盘的扫描码 172 经 `Generic.kl` 产生 `HOME(3)`，且无过滤时能从系统设置返回桌面。随后使用此路径验证应用过滤，不使用可能绕过过滤的 `adb shell input keyevent HOME`。

发送短按的测试命令如下，长按将 `sleep` 调整为 1.1 秒：

```text
sendevent /dev/input/event1 1 172 1
sendevent /dev/input/event1 0 0 0
sleep 0.08
sendevent /dev/input/event1 1 172 0
sendevent /dev/input/event1 0 0 0
```

## 结果

| 检查 | 结果 |
| --- | --- |
| `-e home 1`：完整按键序列、快照时效、配置/余额/孩子/答题/期限发布 | PASS |
| `-e homeUi 1`：输入页前台/后台、返回原输入页、家长验证与一分钟离开后重新验证、立即解锁 | PASS |
| 另一线程持有引擎 monitor 和 SQLite 事务时，直接调用实际 `onKeyEvent` 方法 | PASS，毫秒计时结果为 0 ms，断言上限 100 ms |
| 既有完整回归：计时、奖励、题库、孩子、时段、输入相关修复及隐藏桌面图标等 | PASS |
| 硬件 Home 短按与长按 | 每次首次按下的拦截计数增加一次，学习 Activity 保持前台 |
| 未验证的家长密码页 | 硬件 Home 被过滤，仍可正常输入密码 |
| 已验证的家长管理页 | 硬件 Home 放行，拦截计数不增加 |
| Home 按住期间远程解锁 | 该次松开仍被消费；下一次 Home 放行并返回桌面 |

专项入口：

```text
adb -s <独立测试模拟器> shell am instrument -w -e home 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
adb -s <独立测试模拟器> shell am instrument -w -e homeUi 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```

`homeUi` 会初始化仅供测试的随机密码及规则，只允许独立 debug 模拟器。宿主端验收在它结束后清理该调试应用，另行使用只保留于进程内的随机密码完成正常设置和管理 API 验证，未保存或输出密码、认证令牌。

## 验证范围

锁竞争测试证明回调方法本身不等待引擎锁或数据库事务；它不代表系统将事件送到回调前的排队时延。现有其他主线程工作、厂商撤销无障碍、固件是否分发 Home、系统导航手势等仍有边界。当前结果支持所测 API 33 硬件输入路径，不能替代目标电视的真机验证。
