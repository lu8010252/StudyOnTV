# 外部看门狗（家长版说明）

这个目录里的工具，全部运行在**电视之外**的电脑、树莓派、NAS 或路由器上。它通过局域网 ADB 定时检查电视上的「题了个题 / StudyOnTV」是否还在正常运行；发现进程或前台服务缺失时，**请求**电视重新打开应用，然后下一轮再检查。

它是一道“外部兜底”，不是系统级防卸载，也不能替代应用自身的保护。

---

## 一、它能做什么，不能做什么

### 能做的

- 只读检查：应用进程是否存在、`GateService` 是否在前台、无障碍服务是否还启用。
- 发现缺失时（电视处于唤醒状态）执行一次恢复：`am start -n <包名>/com.studyontv.tv.MainActivity`。
- 可选修复无障碍：只补齐本应用缺少的无障碍组件，保留其他应用已有的条目，删掉无意义的 `null`，并把 `accessibility_enabled` 设为 `1`（默认关闭，需显式开启）。
- 支持 `status`（只看）、`ensure`（检查一次并最多恢复一次）、`watch`（循环执行）三种模式。
- 可以跑在 Windows / macOS / Linux 电脑、树莓派、NAS、OpenWrt 路由器、ESP32 上。

### 不能做的

- **不能开启电视的 ADB**。如果电视重启后厂商固件关闭了网络 ADB，外部看门狗连不上，也就无能为力；这需要家长重新在电视上打开调试，或者用电脑侧的其他手段兜底。
- 不能卸载/重装应用、不能改厂商私有设置、不能强制停止其他应用、不能重启电视。
- 不能保证“零空窗”。从应用异常退出到下一轮检查之间，仍然可能有一段时间没有拦截。
- 不能对抗已经拿到电视 root/shell 权限的人；ADB 本身就是高权限通道。
- 不同品牌/固件的 `dumpsys` 输出格式不同，睡眠判断、前台服务判断、无障碍条目的缩写形式都可能随固件变化。代码只保证“本机识别到缺失时请求恢复，并在下一轮复核”，不保证在所有固件上都准确。
- 本仓库的 OpenWrt 与 ESP32 路径**尚未在真实路由器和真实开发板上验证**；请先 `status` 观察，再启用自动恢复。

---

## 二、开始前的前置条件

1. 电视上已经安装「题了个题 / StudyOnTV」，并完成首次设置。
2. 电视已开机，和看门狗所在设备在**同一个可信局域网**。
3. 电视已开启开发者选项和网络调试（不同品牌叫“网络调试 / ADB over network / 无线调试”），并已授权这台看门狗设备的 ADB 密钥。
4. 在电脑上先验证一次（把 IP 换成电视的）：

   ```sh
   adb connect 192.168.1.20:5555
   adb devices
   ```

   看到 `192.168.1.20:5555    device` 才算就绪。显示 `unauthorized` 时，到电视上点“允许”；显示 `offline` 时重新执行 `adb connect`。
5. 看门狗设备需要安装 `adb`；Python 方案还需要 Python 3.10 或更新版本。
   - Windows：下载 Android SDK Platform-Tools，把所在目录加入 `PATH`。
   - Debian / Ubuntu / 树莓派：`sudo apt install android-tools-adb python3`。
   - macOS：`brew install android-platform-tools`。

> 不同电视的网络调试端口可能不是 5555；以电视实际显示或路由器上看到的端口为准，把下文命令里的 `5555` 替换掉。

> 安全提醒：ADB 等于电视的 shell 权限。**只在可信局域网内使用，不要把 5555 端口映射到公网**，也不要在公共 Wi-Fi 上开启。

---

## 三、电脑：Windows / macOS / Linux（Python）

仓库根目录执行。先看只读状态：

```sh
python3 scripts/device-watchdog.py status --serial 192.168.1.20:5555
```

检查一次，必要时恢复：

```sh
python3 scripts/device-watchdog.py ensure --serial 192.168.1.20:5555
```

持续守护（默认 30 秒一轮，最小 10 秒）：

```sh
python3 scripts/device-watchdog.py watch --serial 192.168.1.20:5555 --interval 30
```

Windows PowerShell 里把 `python3` 换成 `python`（或 `py -3`）即可：

```powershell
python scripts\device-watchdog.py ensure --serial 192.168.1.20:5555
```

常用参数：

- `--package`：默认 `com.studyontv.tv`；手机版可用 `com.studyontv.mobile`，调试包也可用 `com.studyontv.tv.debug` / `com.studyontv.mobile.debug`。
- `--adb`：adb 可执行文件路径；不在 PATH 里时显式指定。
- `--log-file`：可选，写入滚动 JSON 日志（1 MiB，保留两份）。
- `--repair-accessibility`：可选，**默认关闭**。开启后，如果本应用的无障碍组件缺失，会修改系统“已启用的无障碍服务”设置。它只追加本应用组件、保留其他应用的组件、去掉 `null`；遇到含分号等不安全字符的值会拒绝写入。该设置会影响电视的辅助功能行为，请确认自己需要后再开。

`status` 是只读的：只读取状态，不会启动应用，也不会写设置。`ensure` / `watch` 在电视处于待机或电源状态无法判断时，不会强行拉起应用。

> macOS 上如果没有 `python3`，先安装 Python 3.10+；`adb` 可用 `brew install android-platform-tools`。

---

## 四、树莓派 / Linux 小主机（systemd）

适合 7×24 小时常开的小主机。下面假设项目放在 `/opt/studyontv-watchdog`，用专用用户 `studyontv` 运行。

```sh
sudo useradd --system --home /opt/studyontv-watchdog --shell /usr/sbin/nologin studyontv
sudo mkdir -p /opt/studyontv-watchdog
sudo cp -a /path/to/study-on-tv/. /opt/studyontv-watchdog/
sudo chown -R studyontv:studyontv /opt/studyontv-watchdog

sudo cp /opt/studyontv-watchdog/deploy/watchdog/watchdog.env.example /etc/studyontv-watchdog.env
sudo nano /etc/studyontv-watchdog.env
sudo chmod 600 /etc/studyontv-watchdog.env
```

编辑 `/etc/studyontv-watchdog.env`，至少改 `SOTV_SERIAL`；`SOTV_ADB` 和 `SOTV_PYTHON` 指向系统里的真实路径：

```sh
SOTV_SERIAL=192.168.1.20:5555
SOTV_PACKAGE=com.studyontv.tv
SOTV_INTERVAL=30
SOTV_REPAIR_ACCESSIBILITY=0
SOTV_ADB=/usr/bin/adb
SOTV_PYTHON=/usr/bin/python3
```

先以服务用户身份授权一次 ADB（电视上点“允许”）：

```sh
sudo -u studyontv adb connect 192.168.1.20:5555
sudo -u studyontv adb devices
```

安装并启动：

```sh
sudo cp /opt/studyontv-watchdog/deploy/watchdog/linux/studyontv-watchdog.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now studyontv-watchdog
journalctl -u studyontv-watchdog -f
```

停止 / 查看状态：

```sh
sudo systemctl stop studyontv-watchdog
systemctl status studyontv-watchdog
```

服务通过 `deploy/watchdog/run-watchdog.sh` 启动 Python，配置全部来自环境变量文件。ADB 密钥默认在 `studyontv` 用户的 `~/.android` 下，**重装系统或换用户前先备份密钥**。

---

## 五、macOS（LaunchAgent）

安装为当前用户的 LaunchAgent（不需要 root）：

```sh
sh deploy/watchdog/macos/install-launchd.sh 192.168.1.20:5555
```

需要显式指定 Python / adb 路径或开启无障碍修复时：

```sh
sh deploy/watchdog/macos/install-launchd.sh 192.168.1.20:5555 \
  "$(command -v python3)" "$(command -v adb)" 0
```

最后一个参数 `0` / `1` 表示是否开启 `--repair-accessibility`。日志写在 `~/Library/Logs/StudyOnTV/watchdog.log`。安装脚本会检查 Python 3.10+ 和路径是否为绝对路径；项目目录必须保留在原位。

卸载（保留 ADB 密钥和日志）：

```sh
sh deploy/watchdog/macos/install-launchd.sh --uninstall
```

---

## 六、NAS（Docker Compose）

`deploy/watchdog/docker/` 里是现成的 Compose 配置：

```sh
cd deploy/watchdog/docker
cp .env.example .env
nano .env          # 至少修改 SOTV_SERIAL
docker compose build
```

**ADB 密钥必须持久化**：容器把 `/root/.android` 挂成命名卷 `adb-keys`。首次使用需要授权，临时在同一个卷里执行：

```sh
docker compose run --rm --entrypoint sh watchdog -c \
  'adb connect 192.168.1.20:5555; adb devices'
```

电视上点“允许”后，再确认一次：

```sh
docker compose run --rm --entrypoint adb watchdog devices
```

看到 `device` 后启动常驻服务：

```sh
docker compose up -d
docker compose logs -f
```

注意：

- 不要执行 `docker compose down -v`，那会删除 `adb-keys` 卷和里面的密钥，需要重新授权。
- 也可以把已有的、已授权的 `adbkey` / `adbkey.pub` 备份进该卷，避免重复授权。
- `.env` 是本地配置，已在 `.gitignore` 中；不要提交或截图。

停止：

```sh
docker compose down
```

---

## 七、OpenWrt 路由器（procd）

适合路由器常开、电视也接在同一局域网的家庭。要求路由器已安装 **`adb`** 和 **`timeout`** 两个命令（不同固件包名不同，例如 `adb` / `android-tools-adb`，`timeout` 通常由 `coreutils-timeout` 或 BusyBox 提供）。`watchdog.sh` 只接受 `主机:端口` 形式的地址，并会先校验参数，非法配置不会调用 adb。

把三个文件放到路由器上：

```sh
scp deploy/watchdog/openwrt/watchdog.sh root@192.168.1.1:/usr/libexec/studyontv-watchdog.sh
scp deploy/watchdog/openwrt/studyontv-watchdog.init root@192.168.1.1:/etc/init.d/studyontv-watchdog
scp deploy/watchdog/openwrt/studyontv-watchdog.config root@192.168.1.1:/etc/config/studyontv-watchdog
ssh root@192.168.1.1 'chmod 755 /usr/libexec/studyontv-watchdog.sh /etc/init.d/studyontv-watchdog'
```

先在路由器上手动验证（电视上点“允许”）：

```sh
ssh root@192.168.1.1
adb connect 192.168.1.20:5555
SOTV_SERIAL=192.168.1.20:5555 sh /usr/libexec/studyontv-watchdog.sh status
```

确认输出 JSON 正常后，改 UCI 配置并启用：

```sh
uci set studyontv-watchdog.main.enabled='1'
uci set studyontv-watchdog.main.serial='192.168.1.20:5555'
uci set studyontv-watchdog.main.package='com.studyontv.tv'
uci set studyontv-watchdog.main.interval='30'
uci set studyontv-watchdog.main.repair_accessibility='0'
uci commit studyontv-watchdog
/etc/init.d/studyontv-watchdog enable
/etc/init.d/studyontv-watchdog start
logread -f | grep -i studyontv
```

说明：

- init 脚本要求 `enabled='1'` 才会启动；`interval` 只接受 10..3600 秒。
- `repair_accessibility='1'` 等同于电脑方案的 `--repair-accessibility`，会修改电视的无障碍设置；默认保持 `0`。
- 路由器以 root 运行，ADB 密钥通常在 `/root/.android`；升级固件或恢复出厂前请备份。
- **电视重启后网络 ADB 必须仍然可用**，否则路由器也连不上。很多电视断电重启后会关闭网络调试，这属于固件行为，外部看门狗无法绕过。
- 自检脚本（不需要真实电视和网络）：

  ```sh
  sh deploy/watchdog/tests/test_openwrt.sh
  ```

---

## 八、ESP32（MicroPython）

ESP32 适合做一个低功耗的小看门狗，但它能力有限，**只支持传统 TCP ADB（通常是 5555 端口 + RSA 密钥）**，不支持 Android 11+“无线调试”的 TLS 配对流程。如果电视只提供带配对码的无线调试，ESP32 无法连接。

### 1. 在电脑上生成专用密钥并完成授权

```sh
cd deploy/watchdog/esp32
python3 provision.py --output-dir local --authorize 192.168.1.20:5555
```

- 会生成 `local/adbkey`、`local/adbkey.pub` 和板子要用的 `local/auth.json`。
- 电视上弹出授权提示时点“允许”。`--authorize` 只用于传统 TCP ADB。
- `local/`、`auth.json`、`adbkey*` 都已在 `.gitignore` 中。**这些是密钥，不要提交、不要截图、不要发群里。**

### 2. 准备配置

```sh
cp config.example.json config.json
```

编辑 `config.json`，至少填写 Wi-Fi 和电视地址：

```json
{
  "wifi_ssid": "家里的WiFi",
  "wifi_password": "WiFi密码",
  "tv_host": "192.168.1.20",
  "tv_port": 5555,
  "package": "com.studyontv.tv",
  "interval": 30,
  "repair_accessibility": false
}
```

`interval` 允许 10..3600 秒；`repair_accessibility` 默认 `false`，含义与电脑方案的 `--repair-accessibility` 相同。

### 3. 拷贝到开发板

把下面这些文件复制到 ESP32 文件系统根目录（可用 `mpremote`、Thonny 或 rshell）：

- `main.py`
- `adb_client.py`
- `watchdog_policy.py`
- `config.json`
- `local/auth.json`（复制后文件名保持 `auth.json`）

用 `mpremote` 的示例：

```sh
mpremote cp main.py :main.py
mpremote cp adb_client.py :adb_client.py
mpremote cp watchdog_policy.py :watchdog_policy.py
mpremote cp config.json :config.json
mpremote cp local/auth.json :auth.json
mpremote reset
```

上电后串口会打印 JSON 结果。首次连接时如果电视再次提示授权，选择允许。

### 4. 边界

- ESP32 **不能**在电视重启、ADB 被固件关闭后重新打开 ADB，所以它不能替代电脑/树莓派对“电视重启后 ADB 关闭”的兜底。
- 它也不会重新配网、不会改厂商设置、不会重启电视。
- 部分开发板的硬件看门狗可用性不同；网络异常时会打印 `connection_or_authorization_error`，并继续下一轮。
- 同样**尚未在真实 ESP32 上验证**，请先在串口观察几轮 `status` 类输出再长期通电。

---

## 九、安全与隐私

1. **只在可信局域网使用。** 不要把 ADB 的 5555 端口暴露到公网，也不要在路由器上做端口映射/UPnP 放行。
2. **密钥就是密码。** `auth.json`、`adbkey`、`adbkey.pub`、`.env`、`/etc/studyontv-watchdog.env` 都属于敏感文件；不要提交到 Git、不要发截图、不要上传网盘。丢失密钥后应在电视的“已授权的调试设备”里移除旧密钥，再重新授权。
3. **无障碍修复默认关闭。** `--repair-accessibility` / `repair_accessibility` 会修改电视的系统无障碍设置；虽然只追加本应用组件并保留其他组件，但仍属于系统级变更，可能影响其他辅助功能应用。除非确实需要，否则保持 `0` / `false`。
4. **看门狗本身也有权限。** 能执行 ADB 的人可以读取电视界面、安装应用、执行 shell 命令。请把运行看门狗的电脑/树莓派/NAS 也当作敏感设备管理。
5. 不需要时及时停用：`systemctl stop`、`launchctl bootout`、`docker compose down`、`/etc/init.d/studyontv-watchdog stop`，或断开 ESP32 电源。

---

## 十、已知边界（请务必阅读）

- 恢复动作只是**请求**：调用 `am start` 后，下一轮才会复核进程和前台服务是否真的恢复。启动被系统拒绝、应用崩溃、权限被收回时，看门狗只能记录失败，不能保证一定恢复，也不保证零空窗。
- 检查间隔、睡眠判断、前台服务判断、无障碍条目格式等阈值与行为都依赖厂商固件；本代码只保证“本机发现缺失时请求恢复，并在下一轮复核”。
- 睡眠/待机判断依赖 `dumpsys power` 的输出。电视处于待机、关机或电源状态无法识别时，自动恢复会被跳过，避免半夜把电视点亮。
- 前台服务判断依赖 `dumpsys activity services` 中 `GateService` 附近的 `isForeground=true`；厂商定制可能改变输出格式。
- 无障碍条目的写法可能是全名，也可能是 `包名/.类名` 缩写；目前 OpenWrt 脚本对主包 `com.studyontv.tv` 同时识别两种写法，其他包名/调试包请先用 `status` 核实。
- 本仓库的 OpenWrt 脚本和 ESP32 代码**尚未在真实路由器和真实 ESP32 上验证**；请先用 `status` 模式观察，再开启 `ensure`/`watch`。
- 如果电视固件在重启后关闭网络 ADB，任何外部看门狗都无法自动恢复，除非另有能重新打开 ADB 的机制（例如厂商提供的开机调试设置）。这是本方案最大的硬边界。

---

## 十一、相关文件速查

| 场景 | 入口 |
| --- | --- |
| 电脑（跨平台） | `scripts/device-watchdog.py` |
| 通用启动器 | `deploy/watchdog/run-watchdog.sh` + `watchdog.env.example` |
| 树莓派 / Linux | `deploy/watchdog/linux/studyontv-watchdog.service` |
| macOS | `deploy/watchdog/macos/install-launchd.sh` |
| NAS | `deploy/watchdog/docker/compose.yaml` |
| OpenWrt | `deploy/watchdog/openwrt/` |
| OpenWrt 自检 | `deploy/watchdog/tests/test_openwrt.sh` |
| ESP32 | `deploy/watchdog/esp32/` |
| Windows 计划任务（可选） | `deploy/watchdog/windows/install-task.ps1` |
