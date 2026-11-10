# ESP32 看门狗：真实 adbd 验证记录（Android 模拟器）

本轮用**真实 adbd**（AOSP 平板模拟器）验证 `deploy/watchdog/esp32/` 的 `adb_client.py` + `watchdog_policy.py` + `provision.py`。
此前只有 `deploy/watchdog/esp32/test_esp32.py` 用自建 mock 服务端跑过 18 项，凡是"协议理解错了、真机根本连不上"的风险都没有被排除。本轮把客户端原样指向真实 adbd，得到一个**明确结论：当前 ESP32 客户端在真实设备上无法完成一次 `inspect()`**，三处缺陷都在下面给出了帧级证据。

> 只连接了本机模拟器，**没有**接触任何真实电视/机顶盒，**没有**碰 ESP32 硬件。

## 结论速览

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| ADB 握手 + RSA 签名认证（`ro.adb.secure=1`） | **通过** | adbd 回 `AUTH TOKEN(1)`，接受 256 字节 `AUTH SIGNATURE(2)` 后回 `CNXN`；未授权密钥永远拿不到 `CNXN` |
| 单条 `shell:` 命令（`echo`/`getprop`） | **通过** | `echo studyontv-probe` 回 `studyontv-probe` |
| 同一连接上的第 2 条命令 | **失败（阻塞）** | `AdbError: ADB stream ID mismatch`，见缺陷 2 |
| `dumpsys power`（策略第一条命令） | **失败（阻塞）** | 68–110 KB > `max_output=49152`，`AdbError: ADB output exceeded memory limit`，见缺陷 3 |
| `provision.py` 生成板端 `auth.json` | **失败（阻塞）** | `Public/private ADB keys do not match`，见缺陷 1 |
| 策略逻辑本身（process / service / accessibility / 休眠 / 恢复） | **通过** | 见 `inspect_*`、`recovery_*`、`sleep_guard` 等 20 项，但这些是在绕开三处缺陷的 `CompatClient` 下测得的 |
| 厂商电视固件（小米/TCL/索尼等定制 dumpsys、定制 adbd） | **未验证** | 只有 AOSP 镜像 |
| ESP32 硬件/内存/Wi-Fi/硬件看门狗/重启 | **未验证** | 本轮在 PC 上跑 CPython |

因此：**协议理解是对的，认证也对，但客户端实现有三处会让真机完全不能工作的缺陷**。修完这三处后，策略逻辑在 AOSP 上是可用的。

## 环境

| 项 | 值 |
| --- | --- |
| 模拟器 | `C:\Users\zdy\AppData\Local\Android\Sdk\emulator\emulator.exe` |
| AVD | `StudyOnTV_Tablet`（Android 16 / API 36，`sdk_gphone64_x86_64`） |
| 实例序列号 | `emulator-5556`（`ro.serialno=EMULATOR36X2X12X0`） |
| 传统 TCP ADB 端口 | `127.0.0.1:5557`（控制台端口 5556；`emulator-5554/5555` 是另一台 `StudyOnTV_TV`） |
| `ro.adb.secure` | **1**（走 RSA 认证，不是 `0`） |
| 被测 APK | `app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk`，包名 `com.studyontv.mobile.debug`（versionName 0.3.21） |
| Python | 3.13（CPython，非 MicroPython） |
| adb | `C:\Users\zdy\AppData\Local\Android\Sdk\platform-tools\adb.exe` |

启动方式（本次实例由前序会话启动，参数一致）：

```sh
emulator -avd StudyOnTV_Tablet -no-window -no-audio -no-snapshot -no-boot-anim &
adb -s emulator-5556 shell getprop sys.boot_completed   # 1
netstat -ano | findstr 5557                              # 127.0.0.1:5557 LISTENING
```

端口对应关系用 `adb devices` + `adb -s <serial> shell getprop ro.boot.qemu.avd_name` 确认（`5555↔5554`、`5557↔5556`、`5559↔5558`）。

## 复现

```sh
cd deploy/watchdog/esp32/tests
python run_emulator_check.py \
  --port 5557 --serial emulator-5556 \
  --adb "C:/Users/zdy/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
  --package com.studyontv.mobile.debug \
  --apk ../../../../app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk \
  --auto-accept --json /tmp/esp32-emulator.json
```

* 密钥默认落在 `deploy/watchdog/esp32/local/`（该目录已在 `.gitignore`，切勿提交）。加 `--new-key` 重新生成。
* 首次运行需要电视侧点"允许 USB 调试"，`--auto-accept` 会用 `uiautomator` 读对话框并按 **RSA 指纹比对**后点"一律允许 + 允许"；指纹不匹配的残留对话框会被取消，否则 adbd 同一时刻只挂一个 prompt，新的一直弹不出来。
* 退出码：0 = 无失败项；1 = 有失败项（**当前库存代码必然是 1**）；2 = 环境/授权问题。
* 若 adbd 日志出现 `prompt currently pending, skipping`（上一次连接在用户点确认前就断了），重启模拟器后再跑。
* 其他实用开关：`--no-negative-control`、`--no-provision-cli`、`--check-accessibility-repair`、`--settle`、`--service-wait`、`--authorize-timeout`。

## 实测输出摘要

一次完整运行（新生成密钥、自动点授权、装 APK）共 26 项检查，20 项通过、6 项失败、0 项"无法判定"：

```
[PASS] device: serial=emulator-5556 model=sdk_gphone64_x86_64 sdk=36 ro.adb.secure=1
[FAIL] provision_export_key_as_shipped: provision.export_key() rejects a real `adb keygen` key
[FAIL] provision_authorize_cli: exit=2 provision.py: error: Public/private ADB keys do not match
[PASS] connect: CNXN allowed by real adbd (CNXN(4096,7) AUTH(0,20) AUTH(0,256) CNXN(4096,415))
[PASS] auth_signature: adbd sent AUTH TOKEN(1) and accepted AUTH SIGNATURE(2)
[PASS] auth_negative_control: unrelated key was refused, never reached CNXN
[PASS] shell_echo_as_shipped: first shell() returned 'studyontv-probe'
[FAIL] shell_second_command_as_shipped: AdbError: ADB stream ID mismatch
[FAIL] shell_clse_frame_leak: adbd sends a second CLSE after the client's closing OKAY
[FAIL] inspect_as_shipped: AdbError: ADB output exceeded memory limit
[FAIL] dumpsys_power_as_shipped: 109712 bytes > max_output=49152
[PASS] dumpsys_power_wakefulness: 'mWakefulness=Awake'
[PASS] shell_getprop: ro.product.model='sdk_gphone64_x86_64'
[PASS] shell_pidof / shell_activity_services / shell_settings_accessibility
[PASS] install: Success
[PASS] app_running: pidof com.studyontv.mobile.debug -> '3966'
[PASS] inspect_read_only: 1.2s status=diagnostic
       state={'awake': True, 'process': True, 'service': False, 'accessibility': False}
[PASS] force_stop
[PASS] recovery_start_application: actions=['start_application'] am_start=True pid='4082'
[PASS] service_foreground_delay: GateService isForeground=true after 5.8s
[PASS] recovery_no_repeat_2 / _3: status=healthy am_start=False
[PASS] sleep_guard: 0.3s status=sleeping actions=[] am_start=False
[PASS] wake_restored: mWakefulness=Awake
```

### 握手（真实 RSA 签名路径，`ro.adb.secure=1`）

```
> CNXN(0x01000001, 4096) host::
< AUTH(1, 20)                       ← 20 字节随机 token
> AUTH(2, 256)                      ← PKCS#1 v1.5 / SHA-1 签名（2048 bit）
< CNXN(0x01000001, 4096)            ← 认证通过
```

反证：用另一把**未被授权**的密钥重放同样的流程，只会得到

```
> AUTH(3, 721)                      ← 提交公钥请求授权
< (无 CNXN，超时)
```

即 adbd 确实在校验签名，不是"来者不拒"。第一次授权时界面上的指纹（MD5 of pubkey blob）与 `auth.json` 中的密钥一致，脚本按指纹比对后才点击。

### 各条命令的实测

| 命令 | 结果 |
| --- | --- |
| `echo studyontv-probe` | `studyontv-probe\n` |
| `getprop ro.product.model` | `sdk_gphone64_x86_64` |
| `dumpsys power` | **68 761 / 73 151 / 75 514 / 109 712 字节**（随系统状态变化），含 `mWakefulness=Awake` / `mWakefulness=Asleep` |
| `pidof com.studyontv.mobile.debug` | `''`（未运行）/ `3966`、`4082`（运行中） |
| `dumpsys activity services com.studyontv.mobile.debug` | 2.6–8.8 KB，含 `ServiceRecord{… com.studyontv.mobile.debug/com.studyontv.tv.GateService …}` 与 `isForeground=true` |
| `settings get secure enabled_accessibility_services` | `null`（未开启）；`--check-accessibility-repair` 写入后为 `com.studyontv.mobile.debug/com.studyontv.tv.WatchAccessibilityService` |

参考：同一台设备上 `dumpsys activity services`（不带包名）**288 597 字节**，`ps` 38 582 字节。

### 策略（在 `CompatClient` 下，`watchdog_policy.py` 未改动）

* `inspect(..., read_only=True)` → `status=diagnostic`，`state={'awake': True, 'process': True, 'service': True, 'accessibility': False}`，耗时 0.5–1.2 s，共 4 条命令（`dumpsys power`、`pidof`、`dumpsys activity services`、`settings get secure`）。`foreground_service()` 的解析逻辑（`ServiceRecord{` 切块 + `pkg/com.studyontv.tv.GateService` + `isForeground=true`）**在真实 dumpsys 文本上是对的**。
* 反向验证：`am force-stop` 后 `inspect(repair_accessibility=False)` → `actions=['start_application']` 且真的执行了 `am start -n com.studyontv.mobile.debug/com.studyontv.tv.MainActivity`，`pidof` 从 `''` 变为 `4082`。
* 再调两次 → `status=healthy`，**没有**再发 `am start`，不会每轮打断用户。
* 待机保护：`input keyevent 223`（`KEYCODE_SLEEP`）后 `dumpsys power` 变为 `mWakefulness=Asleep`，`inspect` 返回 `status=sleeping`、`actions=[]`，**没有** `am start`；`KEYCODE_WAKEUP` 后 `mWakefulness=Awake`。
* 无障碍修复（可选开关）：`actions=['restore_accessibility', ...]`，`settings put secure` 成功且被 `settings get` 读回；测试后已恢复为 `null` / `accessibility_enabled=0`。
* `service_foreground_delay`：冷启动后 GateService 变成前台服务需要 **5.8 / 7.4 / 9.8 s**（三次测量）。`am start` 之后这段时间内 `inspect()` 仍会报 `service: false` 并再次 `am start`——默认 `interval=30`（`main.py:16`）时这不影响使用，但**若把 interval 调到 10 s，会在冷启动窗口内重复拉起**。

## 未通过项（都是库存代码的真实缺陷）

### 缺陷 1：`provision.py` 读 Android 公钥格式错位，`--authorize` 永远失败

`provision.py:69`

```python
if int.from_bytes(public[4:8], "little") != 64 or int.from_bytes(public[8:264], "little") != modulus:
```

真实 `RSAPublicKey`（`adb keygen` 产物，524 字节）布局是：

| 偏移 | 内容 | 实测值 |
| --- | --- | --- |
| `[0:4]` | `len(n)`，以 32-bit word 计 | `64` |
| `[4:8]` | `n0inv`（Montgomery 常数） | `2814523255` |
| `[8:264]` | `n` | 与私钥匹配 |
| `[264:520]` | `rr` | |
| `[520:524]` | `e` | `65537` |

`len(n)` 在 `[0:4]`，`provision.py` 却在 `[4:8]` 上校验 `64`，而那里是 `n0inv`。结果：

```
$ python deploy/watchdog/esp32/provision.py --output-dir <tmp> --authorize 127.0.0.1:5557
usage: provision.py ...
provision.py: error: Public/private ADB keys do not match
exit=2
```

失败发生在**任何网络 I/O 之前**，所以 `auth.json` 永远生成不出来，板子上根本没有可用密钥。

为什么 mock 测试没抓到：`test_esp32.py:146`/`160` 自己构造公钥 blob 时写的是 `blob[4:8] = 64`，即**测试把同一个错误布局当成了正确布局**，于是自洽地通过。18 项 mock 全绿并不能说明这个格式理解正确。

修法：把校验改成 `[0:4]==64`，`n` 仍取 `[8:264]`，并让测试改用 `adb keygen` 生成的真实 `.pub`（或直接把本脚本的 `export_key_compat()` 搬进 `provision.py`）。

### 缺陷 2：`shell()` 漏读一帧，同一连接上第二条命令必然失败

`adb_client.py:138-171`。逐帧复现（`run_emulator_check.py` 的 `shell_clse_frame_leak`）：

```
> OPEN(1, "shell:echo studyontv-probe")
< OKAY(24, 1)
< WRTE(24, 1, 16) "studyontv-probe\n"
> OKAY(1, 24)          ← 客户端给最后一段输出回 ack
< CLSE(24, 1)          ← adbd 关闭流
> CLSE(1, 24)          ← 客户端回关
< CLSE(24, 1)          ← adbd 对"已关闭流的 OKAY/CLSE"再回一帧 CLSE，客户端从不读
```

下次 `shell()` 先 `OPEN(local=2)`，然后读到的却是这条残留的 `CLSE`（`arg1=1`），命中 `if second != local: raise AdbError("ADB stream ID mismatch")`：

```
[PASS] shell_echo_as_shipped: first shell() returned 'studyontv-probe'
[FAIL] shell_second_command_as_shipped: AdbError: ADB stream ID mismatch
```

用实验确认过触发源：只要客户端对最后一段 `WRTE` 回 `OKAY`，adbd 就会在 `CLSE` 之后再补一帧 `CLSE`；不回 `OKAY` 就干净（但那样流不会被正确确认）。真实 adb 客户端同样会收到这一帧，它只是**按 local id 丢弃未知流的帧**，所以不受影响。

后果：`watchdog_policy.inspect()` 一条连接要发 4 条命令，**第 2 条就抛异常**；`main.py:45` 捕获后只打印 `connection_or_authorization_error`，于是看门狗每轮都"看起来连不上"，什么都修不了。

修法：读帧时遇到不属于当前流的 `CLSE/OKAY` 直接丢弃（`stream_id` 递增的前提下，`second` 不等于当前 `local` 的帧就是历史帧）；或在 `shell()` 返回前把该流的残留帧读完。

### 缺陷 3：`max_output=49152` 装不下 `dumpsys power`

`adb_client.py:54` 默认 `max_output=49152`（48 KiB），而真实设备上：

| 命令 | 实测字节数 |
| --- | --- |
| `dumpsys power`（Android 16 模拟器） | 68 761 / 73 151 / 75 514 / 109 712 |
| `dumpsys activity services`（无包名） | 288 597 |
| `ps` | 38 582 |

```
[FAIL] dumpsys_power_as_shipped: AdbError: ADB output exceeded memory limit
[FAIL] inspect_as_shipped: inspect() on the unmodified client raises AdbError: ADB output exceeded memory limit
```

因为 `inspect()` 的**第一条**命令就是 `dumpsys power`（`watchdog_policy.py:51`），这一条足以让整个策略不可用；即使侥幸不超限，缺陷 2 也会在第二条命令上失败。

修法（三种都可，需在 ESP32 上验证内存）：把 `max_output` 提到 ≥128 KiB（P4 有 PSRAM 才现实）；或改成边收边解析、只保留含 `mWakefulness=` / `DisplayPower:` 的行（`screen_awake()` 只需要这些）；或用 adbd 支持的 shell 管道把命令本身缩小，例如 `dumpsys power | grep -m2 -E "mWakefulness=|DisplayPower: state="`（`shell:` 走 `sh -c`，实测可行）。

### 附带问题（非阻塞，但会在真机上出现）

1. 未授权密钥的失败路径只会得到 `TimeoutError` 而不是 `TV has not authorized this device key`：adbd 在等用户点确认时不回包，客户端 5 帧上限在哪里停完全取决于超时。`provision.py` 用 `timeout=60`，板子会**静默卡住 60 s**。建议把"发过公钥后仍无 `CNXN`"显式映射成授权错误。
2. 用户点"允许"的那条连接在模拟器上**不会完成授权**（需要重新连接一次才拿到 `CNXN`）。`main.py` 每轮都会 `close()` 后重连，所以下一轮能成功，但首次配置时人容易以为"没连上"。
3. `am start` 之后 GateService 变前台要 5.8–9.8 s，这段时间 `inspect()` 会判定 `service: false` 并再发一次 `am start`。配合 `interval=10` 会重复拉起（默认 30 s 不会）。

## 没能证明的部分

* **厂商电视固件**：只测了 AOSP `sdk_gphone64_x86_64`（Android 16 / API 36）。小米 `MitvWindowManager`、TCL/索尼定制 `dumpsys power` 段落、定制 adbd（某些机型对 `settings put secure` 直接 `Permission denial`、对 `am start` 返回 `Error: Activity not started`）都**未验证**。所有结论只对 AOSP 成立。
* **ESP32 硬件**：没有在板子上跑 MicroPython，`time.ticks_ms`/`socket.sendall`/`memoryview` 的兼容分支、Wi-Fi 重连、~80 KiB 可用内存、`machine.WDT` 120 s 超时、断电重启，全部**未验证**。特别是缺陷 3 的三种修法里哪种在 ESP32 上内存可行，本轮给不出答案。
* **其他厂商 `dumpsys` 变体**：`foreground_service()` 依赖 `ServiceRecord{` + `isForeground=true` 这两处文本；Android 8/9 时代的部分固件把前台标记写在别的行，未验证。
* **`ro.adb.secure=0` 的电视**：本轮 `ro.adb.secure=1`，签名路径是真跑的；反过来"电视完全不认证"的场景没测。
* **TLS 无线调试（`STLS`）**：客户端显式拒绝，未验证任何 Android 11+ 无线配对场景。
* **其他包名/花括号内的路径**：只测了 `com.studyontv.mobile.debug` 一个包名、一个构建（versionName 0.3.21）。`com.studyontv.tv`/`.tv.debug` 的 `MainActivity`/`GateService` 类名一致（`namespace=com.studyontv.tv`），未逐个实测。
* **长时间稳定性**：单轮 26 项检查，没跑"每 30 s 一轮、连跑数小时"，也没验证 adbd 主动断开、Wi-Fi 抖动、电视睡眠中被唤醒等长尾场景。
* **签名算法**：只验证了 PKCS#1 v1.5 + SHA-1 这一条路径（adbd 目前唯一接受的）。

## 判断：这个客户端够不够用在传统 TCP ADB 的电视上

**不够——当前版本在真机上连一次 `inspect()` 都跑不完。** 三个缺陷里任意一个都足以致命：

1. `provision.py` 导不出密钥 → 板上没有 `auth.json`，连认证都到不了；
2. `shell()` 漏读帧 → 同一连接第 2 条命令必崩，`inspect()` 必崩；
3. `max_output` 太小 → `dumpsys power` 在 Android 9+ 普遍超 48 KiB，第一 条命令就崩。

与此同时，**协议层面的理解是正确的**：握手/帧格式/校验、ACK/CLSE 流控、RSA 签名格式（PKCS#1 v1.5 + SHA-1、20 字节 token、2048 bit 大端填充）、`shell:` 语义、`dumpsys power` / `dumpsys activity services` / `settings get secure` 的输出解析、`am start` 恢复、休眠保护，全部在真实 adbd 上验证通过。也就是说这不是"方向错了"，而是三处实现细节需要修。

修完这三处后仍缺的验证（按风险排序）：

1. **在真电视上跑一遍**（小米/TCL/索尼任一，Android 9–13），确认 `dumpsys power` 体积与 `isForeground=true` 文本、`settings put secure` 是否被拒、`am start` 是否被厂商限制；
2. **ESP32 实板**：内存（缺陷 3 的修法）、Wi-Fi 断线重连、`machine.WDT` 与 30 s 间隔共存、断电后 `auth.json` 仍可复用；
3. **长时间运行**：连续 24 h、断网重连、电视重启后 ADB 端口是否还在（这决定看门狗是否还有意义）；
4. **首次授权的人工流程**：板子自己发公钥 → 电视弹窗 → 用户点允许 → 板子需要重连一次（缺陷 2 附带问题 2），建议在 `main.py` 里加一次显式重试而不是等下一轮。


---

# 修复后复测（第二轮）

三个阻塞缺陷与三项附带问题全部修复，本轮把**库存代码**原样指向真实 adbd 复测。

## 结论速览

| 项 | 第一轮 | 现在 | 关键证据 |
| --- | --- | --- | --- |
| `provision.py` 导出 `auth.json` | FAIL（`keys do not match`） | **PASS** | `provision_export_key_as_shipped` / `provision_export_cli` / `provision_authorize_cli` |
| 同一连接上第 2 条 `shell()` | FAIL（`stream ID mismatch`） | **PASS** | `shell_second_command_as_shipped`；`inspect()` 一条连接发 4 条命令 |
| `dumpsys power` 爆 `max_output` | FAIL（109712 B > 49152） | **PASS** | 服务端过滤后 **36 B**，原始 dump 本轮 **110209 B** |
| `inspect()` 本身 | FAIL | **PASS** | `inspect_as_shipped`、`inspect_read_only`、`recovery_*`、`sleep_guard` |
| 未授权路径 | `TimeoutError` | **PASS**（`AdbAuthorizationError`） | `auth_negative_control`，`mapped_authorization_error=true` |
| 修复后 `run_emulator_check.py` | 6 个 FAIL | **27 / 27 PASS，exit=0** | 见下方完整输出 |
| `test_esp32.py` | 18 项 | **28 项全绿** | 见下方完整输出 |

未通过项：**0**。未验证项（括号里为原因）见文末。

## 复测环境

| 项 | 值 |
| --- | --- |
| 模拟器 | `Medium_Phone_API_36.1`（Android 16 / API 36，`sdk_gphone64_x86_64`），`-port 5558` |
| 实例序列号 | **`emulator-5558`**（按要求未使用 `emulator-5554`） |
| 传统 TCP ADB 端口 | `127.0.0.1:5559`（控制台端口 5558 + 1） |
| `ro.adb.secure` | **1**（真跑 RSA 签名 + 首次授权弹窗） |
| 被测 APK | 同上，`com.studyontv.mobile.debug` |
| 密钥目录 | `%TEMP%\esp32-key-5558-final`（本轮为全新密钥，因此完整走了"弹窗 → 点允许 → 重连"流程） |

```sh
cd deploy/watchdog/esp32/tests
python run_emulator_check.py --port 5559 --serial emulator-5558 \
  --adb "C:/Users/zdy/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
  --key-dir "C:/Users/zdy/AppData/Local/Temp/esp32-key-5558-final" \
  --package com.studyontv.mobile.debug \
  --apk ../../../../app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk \
  --auto-accept
```

## 修复 1：`provision.py` 公钥布局

`deploy/watchdog/esp32/provision.py:41-70` 新增 `parse_android_public_key()`，按真实布局校验：

```python
    if len(blob) == 272:
        raise ValueError("Unsupported 272-byte Android public key blob; regenerate the key with "
                         "`adb keygen` (a 524-byte RSAPublicKey is required)")
    if len(blob) != 524:
        raise ValueError("Invalid Android ADB public key length: expected 524 bytes, got %d" % len(blob))
    words = int.from_bytes(blob[0:4], "little")
    if words != ANDROID_KEY_WORDS:          # 64 words == 2048-bit n
        raise ValueError("Unexpected RSAPublicKey length field: expected %d words, got %d" ...)
    modulus = int.from_bytes(blob[8:264], "little")
    exponent = int.from_bytes(blob[520:524], "little")
```

* `[0:4]` = `len(n)`（以 32-bit word 计，2048 bit → 64）；`[4:8]` 是 `n0inv`，**不再当成长度**；`n` 仍取 `[8:264]`；`[520:524]` 校验 `e ∈ {3, 65537}`。
* `[264:520]`（`rr`）与 `[4:8]`（`n0inv`）不参与校验——签名不需要它们；测试里专门用"任意 n0inv"证明这一点（`test_key_export_rejects_tampered_n0inv_or_exponent`）。
* **272 字节旧布局**：查不到可信字段定义（手上没有 Android 7 之前的 adb 源码可对照），所以**不猜**，直接报明确错误并指向 `adb keygen`。依据：`adb keygen` 的 `RSAPublicKey` 结构固定 `4+4+256+256+4 = 524`；272 = `4+4+256+4+4` 无法解释 256 字节的 `rr` 槽位，任何猜测都可能把 `n` 读错位，比报错更危险。
* `export_key()` 尾部改为调用它并比对模数（`provision.py:102-105`）。

测试（`test_esp32.py`）：自造公钥 blob 改为**真实布局**（`build_public_blob()`，含真实 `n0inv` 与 `rr = R^2 mod n`），并新增：

* `test_public_key_layout_matches_real_adbd:267` —— 断言 `[0:4]==64`、`[4:8]!=64`、`n` 在 `[8:264]`；
* `test_key_export_accepts_real_adb_keygen_output:279` —— **用真 `adb keygen` 产物跑 `export_key`**（`find_adb()` 会查 PATH / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `LOCALAPPDATA\Android\Sdk`；本轮真实执行，未 skip）；
* 负例：`test_key_export_rejects_tampered_length_field:304`（长度字段改 32 → 报 `length field`）、`test_key_export_rejects_tampered_n0inv_or_exponent:312`（`e=17` → 报错）、`test_key_export_refuses_272_byte_blob:324`。

## 修复 2：`shell()` 丢弃历史帧

`adb_client.py:186-215`：

```python
                kind, first, second, data = self._packet()
                if second != local:
                    # A frame for an older local id is history: adbd answers the
                    # ack for the last WRTE of a closed stream with one extra CLSE.
                    stale += 1
                    if stale > STALE_FRAME_LIMIT:
                        raise AdbError("ADB streams out of sync (too many stale frames)")
                    continue
```

* 只有 `second == local`（当前流）的帧才进入 OKAY/WRTE/CLSE 状态机，历史帧（OKAY/CLSE/WRTE）直接丢弃并继续读。
* 内存/时间安全：`STALE_FRAME_LIMIT = 8`（`adb_client.py:20`）+ 原有 `_deadline()`（每次 `recv` 前检查 `budget = timeout*2000`）。超过 8 帧说明真的失步，抛明确错误而不是死循环。
* 回归测试 `test_shell_survives_extra_clse_frame:539`：mock 服务端在 `CLSE` 之后再补一帧 `CLSE`，断言同一连接上连续 3 条 `shell()` 全部成功、`client.sock` 未被关闭、`stream_id == 3`。
* 真实 adbd 侧由 `shell_clse_frame_leak`（`run_emulator_check.py:544`）守住：先用裸帧复现"adbd 确实多发一帧"，再在**同一连接**上发一条正常 `shell()`，必须成功。

修复后的帧级证据（真实 adbd，`emulator-5558`）：

```json
{"frames": [["OKAY", 99, 1, 0], ["WRTE", 99, 1, 16], ["CLSE", 99, 1, 0], [">", "CLSE", 1, 99, 0]],
 "trailing": [["CLSE", 99, 1, 0]],
 "output_bytes": 16, "next_shell": "after-leak"}
```

`trailing` 里那一帧就是 adbd 的补发帧；旧代码下它会让下一次 `shell()` 命中 `stream ID mismatch`，现在按 local id 丢弃。

## 修复 3：`max_output` 装不下 `dumpsys`

采用**服务端过滤**（首选方案），保留客户端 `max_output` 作为兜底。`watchdog_policy.py:13-27`：

```python
POWER_COMMAND = "dumpsys power | grep -m 4 -E 'mWakefulness=|DisplayPower: state=|mScreenOn='"
ACCESSIBILITY_COMMAND = "settings get secure enabled_accessibility_services"
SERVICES_FILTER = "ServiceRecord|isForeground"

def process_command(package):          return "pidof " + package
def process_fallback_command(package): return "ps | grep ' " + package + "'"
def services_command(package):         return "dumpsys activity services " + package + " | grep -E '" + SERVICES_FILTER + "'"
```

`inspect()`（`watchdog_policy.py:82-91`）只用这些命令。理由与内存取舍：

* 实测 `dumpsys power` 原始输出 **69554 / 77231 / 110209 字节**；`dumpsys activity services`（无包名）288 KB。把 `max_output` 提到 96 KiB **仍然装不下 110209 字节**，而 96 KiB 的 `bytearray` 在 ESP32（约 80 KiB 可用堆、无 PSRAM）上根本分配不出来。所以**没有**提高默认值：`max_output` 保持 48 KiB，只做"超限就报明确错误"的兜底。
* 过滤后：`dumpsys power` → **36 字节**；`dumpsys activity services <pkg>` → 66 B（服务未起）～6.2 KB（已起）；`ps | grep` 只回 1 行。全部远小于 48 KiB，且 `-m 4` 让 adbd 不再产出整份 dump（省的是**电视侧**的 CPU/管道流量，不只是板子内存）。
* `-m 4`（而非 `-m2`）：若某厂商固件把 `DisplayPower: state=` 排在前面，多留两行仍能读到 `mWakefulness=`；`grep` 无匹配时输出为空 → `screen_awake()` 返回 `None` → `unknown_power` → **不恢复**（保守）。
* `screen_awake()`（`watchdog_policy.py:32-50`）兼容过滤后输出：单行 `mWakefulness=Awake`（真实输出前面还有 2 个空格，原解析已 `replace(" ", "")`）、`mWakefulness=` 与 `DisplayPower: state=` 混排、空输出。
* `main.py` 允许用 `config.json` 的 `max_output` 覆盖兜底值（`main.py:20-24`，限 4 KiB–256 KiB），默认仍 49152。

测试：

* `test_power_command_is_filtered_on_the_tv_side:358` —— 第一条命令必须是服务端过滤（含 `grep`/`|`，长度 < 200），防止以后改回裸 `dumpsys power`。
* `test_screen_state_parsing` —— 增加单行 / CRLF / 空输出用例。
* `test_oversized_shell_output_is_rejected:565` —— mock 返回 **200 KB**（按 4096 B 分帧、逐帧 ACK，与真机一致），`shell()` 必须抛 `AdbError(... memory limit ... max_output=49152 ...)` 并关闭连接，而不是 OOM。
* `test_filtered_commands_stay_small_on_a_large_dump:581` —— 过滤后的真实命令在 mock 里返回小文本时 `inspect()` 正常判 `healthy`。

## 附带问题

1. **未授权路径映射成显式授权错误**：新增 `AdbAuthorizationError`（`adb_client.py:29-31`）。发出 `AUTH(3) RSAPUBLICKEY` 之后只等 `authorize_wait`（默认 5 s，`adb_client.py:174`）；期间的 `OSError`/超时一律映射成 `AdbAuthorizationError("TV has not authorized this device key; accept the debugging prompt on the TV (this cycle is skipped, the next cycle retries)")`（`adb_client.py:142-180`）。
   * `main.py:54-60` 单独 catch，打印 `{"status": "authorization_pending", ...}` 并**跳过本轮**（进入正常的 `interval` 睡眠），不再"60 秒静默卡住"。
   * `provision.py:144-162` 先打印"请到电视上点允许"和等待秒数，失败时打印 `Authorization is still pending: ...` + "再跑一次本命令"，exit=2。
2. **首次授权成功后立刻重连**：`connect(retry_after_authorize=True)`（`adb_client.py:115-140`）在授权等待失败后自己 `close()` → 睡 `min(authorize_wait, 3)` → **用同一把密钥重新握手一次**（`offer_public_key=False`，避免二次弹窗）。因此首次授权那一轮就能完成，`main.py` 不用等 30 s。单元测试 `test_authorized_after_prompt_reconnects_immediately:521`（mock 第 2 条连接才被授权，断言 < 6 s 成功且只弹一次），真实 adbd 的 notes 也记录到 `authorization took effect on retry #1`。
3. **ESP32 兼容性**：`adb_client.py` / `watchdog_policy.py` / `main.py` 内**没有** f-string、海象运算符、推导式；`time.ticks_ms`/`ticks_diff`（`adb_client.py:35-39`）、`memoryview` 切片 `view[sent:]`（`:107`）、`socket.getaddrinfo(...)[0]`（`:125`）、三参 `pow` + 纯 Python 模幂回退（`:50-57`）全部保留。新增代码只用 `min()` / `int()` / `%` 格式化 / `time.sleep(float)`。

## 复测输出 1：单元测试（28 项全绿）

```text
$ python -m unittest deploy/watchdog/esp32/test_esp32.py -v
test_abbreviated_service_is_not_duplicated ... ok
test_authorized_after_prompt_reconnects_immediately
Connect that raised the prompt must reconnect at once, not next cycle. ... ok
test_filtered_commands_stay_small_on_a_large_dump
The policy commands must fit the shipped 48 KiB budget on real sizes. ... ok
test_foreground_service_only_inside_own_record ... ok
test_handshake_signature_and_shell_stream ... ok
test_key_export_accepts_real_adb_keygen_output ... ok
test_key_export_refuses_272_byte_blob ... ok
test_key_export_rejects_mismatched_public_key ... ok
test_key_export_rejects_tampered_length_field ... ok
test_key_export_rejects_tampered_n0inv_or_exponent ... ok
test_missing_process_gets_started_but_healthy_is_not ... ok
test_oversized_shell_output_is_rejected
200 KB from a raw dumpsys must fail loudly, not exhaust the board. ... ok
test_pending_authorization_raises_a_clear_error
No CNXN after offering the key must not look like a bare timeout. ... ok
test_power_command_is_filtered_on_the_tv_side
The first inspect() command must stay small: raw dumpsys power is ~110 KB. ... ok
test_process_without_foreground_service_recovers ... ok
test_public_key_layout_matches_real_adbd
[0:4] is len(n) in words; [4:8] is n0inv and must not be read as a length. ... ok
test_rejected_start_raises ... ok
test_repair_preserves_other_services ... ok
test_screen_state_parsing ... ok
test_service_list_cleanup_and_dedup ... ok
test_shell_survives_extra_clse_frame
Real adbd queues one extra CLSE; the next shell() must discard it. ... ok
test_signature_is_pkcs1_v15_sha1 ... ok
test_sleeping_screen_is_never_recovered ... ok
test_unauthorized_offers_public_key ... ok
test_unknown_power_is_never_recovered ... ok
test_unreachable_and_invalid_address ... ok
test_unsafe_accessibility_value_refused ... ok
test_unsupported_package_rejected ... ok

----------------------------------------------------------------------
Ran 28 tests in 5.150s

OK
```

（`test_key_export_accepts_real_adb_keygen_output` 本轮**真实执行**；只有在没有 `adb` 的机器上才会 `skipTest("no adb binary ...")`。）

## 复测输出 2：真实 adbd（27 项全 PASS，exit=0）

```text
$ cd deploy/watchdog/esp32/tests && python run_emulator_check.py --port 5559 --serial emulator-5558 ...
[PASS] device: serial=emulator-5558 model=sdk_gphone64_x86_64 sdk=36 ro.adb.secure=1
[PASS] provision_export_key_as_shipped: provision.export_key() accepted the `adb keygen` key
[PASS] provision_export_cli: exit=0 Keep adbkey and auth.json private; re-use them when updating the board.
[PASS] connect: CNXN accepted by real adbd (CNXN(4096,7) AUTH(0,20) AUTH(0,256) CNXN(4096,415))
[PASS] auth_signature: adbd sent AUTH TOKEN(1) and accepted AUTH SIGNATURE(2) (256-byte RSA block)
[PASS] provision_authorize_cli: exit=0 Authorized; the ESP32 can now use the same key (copy auth.json to the board).
[PASS] auth_negative_control: unrelated key was refused, never reached CNXN (AdbAuthorizationError: TV has not authorized this device key; accept the debugging prompt on the TV (this cycle is skipped, the next cycle retries)); mapped to AdbAuthorizationError=True
[PASS] shell_echo_as_shipped: first shell() returned 'studyontv-probe'
[PASS] shell_second_command_as_shipped: two shells on one connection worked
[PASS] shell_clse_frame_leak: real adbd queued 1 extra frame(s) after the closing ack; the next shell() on the same connection returned 'after-leak'
[PASS] inspect_as_shipped: inspect() succeeded on the unmodified client: status=recovery_requested
[PASS] dumpsys_power_as_shipped: "dumpsys power | grep -m 4 -E 'mWakefulness=|DisplayPower: state=|mScreenOn='" -> 36 bytes (raw `dumpsys power` is 110209 bytes; default max_output=49152) 'mWakefulness=Awake'
[PASS] dumpsys_power_wakefulness: 109915 bytes, 'mWakefulness=Awake'
[PASS] shell_getprop: ro.product.model='sdk_gphone64_x86_64'
[PASS] shell_pidof: pidof com.studyontv.mobile.debug -> ''
[PASS] shell_activity_services: 66 bytes, GateService listed=False, isForeground=true present=False
[PASS] shell_settings_accessibility: enabled_accessibility_services -> 'null'
[PASS] install: Success
[PASS] app_running: pidof com.studyontv.mobile.debug -> '6055'
[PASS] inspect_read_only: 1.5s status=diagnostic state={'awake': True, 'process': True, 'service': False, 'accessibility': False}
[PASS] force_stop: pidof after am force-stop -> ''
[PASS] recovery_start_application: actions=['start_application'] am_start=True pid='6206'
[PASS] service_foreground_delay: GateService isForeground=true after 4.9s (<= 40.0s budget)
[PASS] recovery_no_repeat_2: status=healthy am_start=False state={'awake': True, 'process': True, 'service': True, 'accessibility': False}
[PASS] recovery_no_repeat_3: status=healthy am_start=False state={'awake': True, 'process': True, 'service': True, 'accessibility': False}
[PASS] sleep_guard: 0.2s status=sleeping actions=[] am_start=False screen_awake=False
[PASS] wake_restored: after KEYCODE_WAKEUP: 'mWakefulness=Awake'
```

第一轮那 6 个 FAIL 现在对应的行：`provision_export_key_as_shipped`、`provision_authorize_cli`、`shell_second_command_as_shipped`、`shell_clse_frame_leak`、`inspect_as_shipped`、`dumpsys_power_as_shipped` —— **全部 PASS**，无一项依赖厂商固件。汇总：`checks=27, passed=27, failed=[], unproven=[]`。

补充记录（全新密钥那一轮，完整走了首次授权）：

```text
not authorized yet; waiting for the on-device dialog ...
cancelled a stale dialog for a different key (9D:20:07:43:3A:4C:80:E8:E7:B5:BF:E7:41:85:ED:1D)
[PASS] connect: CNXN accepted by real adbd (CNXN(4096,7) AUTH(0,20) AUTH(0,256) AUTH(0,20) AUTH(0,721) CNXN(4096,7) AUTH(0,20) AUTH(0,256) CNXN(4096,415))
notes: authorization dialog: tapped | authorization took effect on retry #1
```

即：`AUTH(3,721)` 发公钥 → 人点允许 → 客户端**自动重连一次** → `AUTH(2,256)` 通过 → `CNXN`。

## 仍未验证的部分

按风险排序，与第一轮相比没有实质进展：

1. **厂商电视固件**：仍然只有 AOSP `sdk_gphone64_x86_64`。小米/TCL/索尼的定制 `dumpsys` 段落、定制 adbd（`settings put secure` 被拒、`am start` 被限制），以及**没有 toybox `grep -m / -E`** 的极老固件都未验证。若某机型 `grep` 不支持 `-m`，`POWER_COMMAND` 会返回错误文本（不是空输出），`screen_awake()` 判为 `unknown_power` → 不恢复（不会误判成"睡着了"，但也救不回来），必须在真机上确认。
2. **ESP32 硬件与内存**：没有 MicroPython 实机。48 KiB `bytearray` + 4 KB 帧 + 过滤后 ~KB 级输出在 ~80 KiB 可用堆上是否够用未实测；本轮只**避免了**申请 96 KiB / 110 KB 缓冲。Wi-Fi 断线重连、`machine.WDT(120000)` 与 `interval` 共存、断电重启后 `auth.json` 复用均未验证。
3. **长时间运行**：没有 24 h 连跑、断网、电视重启、adbd 主动断开测试。"每轮一条连接、每连接 4 条命令"的泄漏与稳定性只做了单轮验证。
4. **`ro.adb.secure=0`**、**`STLS` 无线配对**、**Android 8/9 的 `isForeground` 写法**、**多包名 / 多构建**：与第一轮一致，未覆盖。

## 其他发现（只报告，未改动业务范围外的代码）

1. `run_emulator_check.py` 原来的 `provision_authorize_cli` 在**授权之前**执行，即使 `provision.py` 修好了也必然失败（新密钥没人点弹窗）。已拆成两步：`provision_export_cli`（keygen + 导出，无需网络）在前，`provision_authorize_cli` 挪到授权流程之后（`run_emulator_check.py:469-484`）。
2. 授权失败后 `provision.py` 仍会留下可用的 `auth.json`（密钥本身没问题，只是电视没点允许）。已在提示里写明"再跑一次本命令"。
3. `dumpsys power` 的 `mWakefulness=` 行在 Android 16 上带 **2 个前导空格**；`screen_awake()` 因先 `replace(" ", "")` 恰好没踩坑。厂商固件若写成 `mWakefulness: Awake`（冒号）则解析不到 → `unknown_power` → 不恢复（保守，但会导致看门狗永久不动作）。
4. 过滤后 `dumpsys power` 本轮只回 2 行，第二行是 `mWakefulness=1`（另一段 dump 的同名字段）。`screen_awake()` 先命中第一行返回，行为正确；但它说明同一关键字可能在别处重现，`-m` 不能随意调大。
5. `GateService` 变前台本轮 **2.3 s / 4.9 s**（第一轮 5.8–9.8 s），"`interval=10` 冷启动窗口内重复 `am start`"的提醒依然成立。
6. `AdbClient.shell()` 末尾 `bytes(output).decode()`（默认 UTF-8）。厂商 `dumpsys` 若输出非 UTF-8 字节会抛 `UnicodeDecodeError`；AOSP 未出现，但是潜在真机风险。
7. `deploy/watchdog/esp32/` 整个目录目前仍是**未跟踪**状态（`git status` 显示 `?? deploy/watchdog/`），所以没有可用的 `git diff`，本报告的定位一律用 `文件:行号`。
8. `python -m unittest deploy.watchdog.esp32.test_esp32`（点号形式）不可用，因为 `deploy/watchdog/esp32/` 没有 `__init__.py`；本文档与验证脚本使用的路径形式（`deploy/watchdog/esp32/test_esp32.py`）是可用的。
