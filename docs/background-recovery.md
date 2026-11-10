# 后台恢复：tv-vigil 源码结论与外部看门狗

参考仓库：[Caldis/tv-vigil](https://github.com/Caldis/tv-vigil)，本轮检查提交 `c503b86c2ff009ce1032b252c3aee68294581511`。

## 它如何持续运行

它没有 APK，也没有通过前台服务、通知监听或 SyncAdapter 实现自身复活。

1. [`setup.sh`](https://github.com/Caldis/tv-vigil/blob/c503b86c2ff009ce1032b252c3aee68294581511/setup.sh) 经 ADB 把脚本推送到 `/data/local/tmp/appkiller.sh`，执行 `nohup sh … &`。
2. [`appkiller.sh`](https://github.com/Caldis/tv-vigil/blob/c503b86c2ff009ce1032b252c3aee68294581511/appkiller.sh) 在电视的 shell 身份下循环运行，每 60 秒通过 `dumpsys window`、`pidof` 检查前台及进程；目录锁防重复启动。它的业务是清理后台应用，而非保护一个 Android App。
3. [`watchdog.sh`](https://github.com/Caldis/tv-vigil/blob/c503b86c2ff009ce1032b252c3aee68294581511/watchdog.sh) 运行在另一台常开的 Mac/Linux 上，通常由 cron 每 5 分钟执行。电视重启或脚本消失后，它重新 ADB 连接、推送并启动脚本。

`nohup` 让 shell 脚本脱离启动终端，但不抵抗断电、系统重启或针对该进程的终止。恢复能力来自外部机器持续检查；“无需 root”仍以电视已授权 ADB 为前提。若电视冷启动后关闭网络 ADB，这条链也无法工作。

本项目已有前台服务、无障碍绑定、JobScheduler 和 AlarmManager。这些入口可以补救普通进程回收，但厂商的 `force-stop`、撤销权限和停止态广播限制，不能据此保证解除。

## 本项目的外部恢复工具

[`scripts/device-watchdog.py`](../scripts/device-watchdog.py) 使用 Python 3.10+ 标准库，支持 Windows、macOS、Linux。它针对 StudyOnTV 检查进程和实际前台服务，不清理其他应用。

前提：已经完成 App 首次设置，电脑/NAS 已安装 Python 和 ADB，电视已开启并授权局域网 ADB。仅在可信局域网使用，不做公网端口转发。

只读检查：

```sh
python scripts/device-watchdog.py status --serial 192.168.1.20:5555
```

执行一次恢复检查：

```sh
python scripts/device-watchdog.py ensure --serial 192.168.1.20:5555
```

持续每 30 秒检查：

```sh
python scripts/device-watchdog.py watch --serial 192.168.1.20:5555 --interval 30
```

同时修复缺失的无障碍记录：

```sh
python scripts/device-watchdog.py watch --serial 192.168.1.20:5555 --interval 30 --repair-accessibility
```

`--repair-accessibility` 会在缺失时追加本应用的服务组件，保留其他服务，过滤 `null` 和重复项。它不会反复开关仍在列表中的服务；系统已将服务标记为 crashed 时，仍可能需要设备端关闭后重开。

Windows 示例（ADB 不在 PATH 时）：

```powershell
python E:\study-on-tv\scripts\device-watchdog.py watch --serial 192.168.1.20:5555 --adb "C:\Android\platform-tools\adb.exe" --repair-accessibility
```

手机/平板包使用 `--package com.studyontv.mobile`；测试包分别为 `com.studyontv.tv.debug`、`com.studyontv.mobile.debug`。

## 检查与恢复边界

- `status` 只读，不启动应用、不改权限。
- `ensure/watch` 仅在确认电视已亮屏，且本应用进程或前台服务缺失时，显式启动本应用入口。不会每轮打断正常播放。
- 待机或无法识别屏幕状态时跳过恢复，避免夜间唤醒电视。
- 输出 JSON；请求恢复后由下一次轮询验证结果，不把“发出启动请求”等同于“服务已恢复”。
- ADB 离线、未授权、厂商拒绝显式启动时输出失败，不开启调试、不修改厂商全局设置、不重启设备，也不重启共享 ADB server。
- 恢复速度取决于轮询周期、ADB 响应和厂商限制，不能保证零空窗。

常开主机可自行通过 Windows 任务计划程序、systemd 或 cron 托管此工具。本轮没有在用户主机安装计划任务，也没有连接或修改真实电视。

## 各平台部署

[`deploy/watchdog/`](../deploy/watchdog/) 提供现成入口，完整步骤与安全说明见其中的 [README](../deploy/watchdog/README.md)：

| 主机 | 入口 | 说明 |
| --- | --- | --- |
| Windows | `windows/install-task.ps1` | 注册当前用户登录时启动的计划任务；`-Uninstall` 移除 |
| macOS | `macos/install-launchd.sh` | 当前用户 LaunchAgent，`--uninstall` 移除 |
| 树莓派 / Linux | `linux/studyontv-watchdog.service` + `watchdog.env.example` | systemd 服务，配置走环境变量文件 |
| NAS | `docker/compose.yaml` | 必须持久化 `/root/.android` 里的 ADB 密钥 |
| OpenWrt 路由器 | `openwrt/` | procd + UCI；需要固件已提供 `adb` 与 `timeout` |
| ESP32 / ESP8266（MicroPython） | `esp32/` | 自带最小 ADB 客户端，仅支持传统 TCP ADB |

各平台的共同边界：只在可信局域网使用；电视重启后若网络 ADB 被固件关闭，任何外部看门狗都连不上；恢复只是“请求启动”，由下一轮复核；待机或电源状态未知时不拉起。

ESP32 需要独立密钥：`python deploy/watchdog/esp32/provision.py --output-dir local --authorize <IP>:5555` 会调用 `adb keygen` 生成专用密钥、导出板子所需的 `auth.json`，并在电视上完成一次常规授权。它不支持 Android 11+ 需配对码与 TLS 的无线调试，也无法重新打开已被关闭的 ADB；定位是低功耗补充，不替代常开主机。

路由器脚本不依赖 Python，可用假 adb 自检：

```sh
sh deploy/watchdog/tests/test_openwrt.sh
```

ESP32 的协议与策略测试（在电脑上用 CPython 运行，模拟 ADB 服务端）：

```sh
python -m unittest deploy/watchdog/esp32/test_esp32.py -v
```

测试覆盖：RSA 挑战签名、ADB 流读写与输出上限、未授权时提交公钥、待机/未知电源不恢复、进程在但前台服务缺失、保留其他无障碍组件、拒绝含不安全字符的无障碍串、`am start` 被拒。

**本轮验证边界**：Windows / Linux / macOS 的启动脚本与配置已做语法检查，Python 与 OpenWrt 脚本在模拟 adb 下通过行为测试；没有真实电视机、OpenWrt 路由器、ESP32 或 NAS 环境，未验证实际厂商固件下的恢复率与 ADB 保持情况。不能把模拟测试等同于真机验证。

## 验证

```sh
python -m unittest discover -s scripts -p test_device_watchdog.py -v
```

测试覆盖正常运行不干预、进程仍在但服务丢失、待机/未知电源状态不恢复、保留其他无障碍服务、空值/重复值、缩写组件识别、shell 参数引用及未授权设备拒绝。另已用本地 Android TV 模拟器验证只读诊断命令。
