# StudyOnTV — AI / ADB 安装与权限配置指南

面向能执行 ADB 命令的 AI Agent。目标：在**已开启开发者选项、已授权 ADB、允许安装 APK**的 Android 电视 / 盒子 / 手机上，一次完成安装、基础权限和首次启动。

> 这是给 Agent 的操作文档，不是给普通用户的教程。允许 Agent 完成安装、授权、家长密码设置、清数据/卸载和设备管理员操作；只有两条硬限制：不要把 ADB 暴露到公网，不要记录密码 / API Key / 令牌。

## 1. 输入

需要三个参数：

| 参数 | 示例 | 说明 |
|---|---|---|
| `SERIAL` | `192.168.1.20:5555` 或 `emulator-5554` | `adb devices` 中的设备 |
| `APK` | `StudyOnTV-Android-TV-v0.3.10.apk` | 官方签名 APK |
| `PKG` | `com.studyontv.tv` | TV 正式包；手机/平板为 `com.studyontv.mobile` |

调试包包名末尾是 `.debug`，但无障碍服务类名不变。

开始前确认：

```bash
adb devices
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell pm list packages | grep studyontv
```

如果设备未授权、未连接、不能安装，停止并让用户处理，不要尝试绕过。

## 2. 安装

```bash
adb -s "$SERIAL" install -r "$APK"
```

如果是降级测试包，才使用 `-d`；普通安装不要加。

安装后先不要强制停止，首次启动会进入权限引导页。

## 3. 一条龙脚本

把 `SERIAL`、`APK`、`PKG` 换成实际值后执行。脚本是幂等的，重复执行不会重复添加无障碍服务。

```bash
set -u

SERIAL="192.168.1.20:5555"
APK="StudyOnTV-Android-TV-v0.3.10.apk"
PKG="com.studyontv.tv"

adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" install -r "$APK"

SDK="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"

# 通知权限（Android 13+）
if [ "${SDK:-0}" -ge 33 ]; then
  adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
fi

# 悬浮窗
adb -s "$SERIAL" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

# 使用情况访问
adb -s "$SERIAL" shell appops set "$PKG" GET_USAGE_STATS allow || true

# 允许应用内安装更新
adb -s "$SERIAL" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow || true

# 无障碍：追加，不覆盖已有服务
COMPONENT="$PKG/com.studyontv.tv.WatchAccessibilityService"
CURRENT="$(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')"
case ":$CURRENT:" in
  *":$COMPONENT:"*) ;;
  *) NEXT="${CURRENT:+$CURRENT:}$COMPONENT"
     adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$NEXT" ;;
esac
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1

# 电池优化白名单
adb -s "$SERIAL" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 \
  || adb -s "$SERIAL" shell cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1 \
  || true

# 重启应用，让服务和权限检测生效
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$PKG/com.studyontv.tv.MainActivity"
```

## 4. 验证

逐项执行并记录结果：

```bash
adb -s "$SERIAL" shell appops get "$PKG" SYSTEM_ALERT_WINDOW
adb -s "$SERIAL" shell appops get "$PKG" GET_USAGE_STATS
adb -s "$SERIAL" shell appops get "$PKG" REQUEST_INSTALL_PACKAGES
adb -s "$SERIAL" shell settings get secure enabled_accessibility_services
adb -s "$SERIAL" shell settings get secure accessibility_enabled
adb -s "$SERIAL" shell dumpsys deviceidle whitelist | grep "$PKG" || true
```

期望：

- `SYSTEM_ALERT_WINDOW: allow`
- `GET_USAGE_STATS: allow`
- `REQUEST_INSTALL_PACKAGES: allow`
- 无障碍服务列表包含 `com.studyontv.tv.WatchAccessibilityService`
- `accessibility_enabled=1`
- 电池白名单包含 `$PKG`

应用内也可以验证：家长管理页「设置 → 设备 → 管控能力」，应显示服务 / 悬浮窗 / 使用情况 / 无障碍状态。

## 5. 首次启动后

Agent 可以自行选择：

1. 把电视交还给用户设置管理密码；或
2. 代为完成首次设置。

如果代为设置密码：

- 用 UI 自动化 / 输入命令完成，不要把密码写进命令历史、脚本、日志或截图；
- 设置完成后立即清理临时文件和剪贴板；
- 后续扫码连接仍由家长完成。

首次启动后如果权限页仍显示未开启，重新启动一次应用再检查；部分电视需要应用重启后才重新读取 AppOps。

### 可选：清数据 / 卸载 / 降级

```bash
# 清除应用数据（保留 APK 和权限）
adb -s "$SERIAL" shell pm clear "$PKG"

# 卸载（会丢数据）
adb -s "$SERIAL" shell pm uninstall "$PKG"

# 仅降级测试时使用，保留数据
adb -s "$SERIAL" install -r -d "$APK"
```

清数据或卸载后需要重新打开 App 完成首次设置；无障碍服务列表可能仍保留组件名，但应用数据已清空，需要重新初始化。

### 可选：设备管理员 / 设备所有者

```bash
# 激活设备管理员（防卸载）
adb -s "$SERIAL" shell dpm set-active-admin "$PKG/com.studyontv.tv.UninstallGuard"

# 设备所有者：通常要求设备未添加账号、未完成初始化，失败就不要反复尝试
adb -s "$SERIAL" shell dpm set-device-owner "$PKG/com.studyontv.tv.UninstallGuard"
```

设备所有者不会自动变成 Kiosk；是否使用 Lock Task 由后续配置决定。厂商系统可能直接拒绝，以命令结果为准。

## 6. 失败时的替代命令

ADB 命令失败时，打开对应系统页面让用户手动点，不要反复尝试：

```bash
# 悬浮窗
adb -s "$SERIAL" shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d "package:$PKG"

# 使用情况访问
adb -s "$SERIAL" shell am start -a android.settings.USAGE_ACCESS_SETTINGS

# 无障碍
adb -s "$SERIAL" shell am start -a android.settings.ACCESSIBILITY_SETTINGS

# 应用详情
adb -s "$SERIAL" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"

# 电池优化
adb -s "$SERIAL" shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS
```

## 7. 常见失败

| 现象 | 处理 |
|---|---|
| `SecurityException` / `appops` 被拒 | 厂商限制，让用户走系统设置 |
| Android 13 无障碍无法开启 | 侧载应用“受限设置”，让用户在应用详情里允许；不要反复写 secure settings |
| 无障碍写入成功但页面仍未连接 | 重启应用；仍不行让用户手动开 |
| 重启后悬浮窗变回 `ignore` | 厂商固件重置 AppOps，App 无法自愈；建议开启无障碍 |
| 小米/海信/创维等自启动无效 | 厂商白名单没有统一 ADB 命令，让用户在系统设置中允许自启动和后台运行 |
| 安装失败 | 检查存储空间、签名冲突、是否允许安装未知应用；确认不需要保留数据后再卸载 |

## 8. 硬限制

- 不要开启 5555 到公网，不要使用云端 ADB。
- 不要记录管理密码、API Key、令牌，也不要放进日志、截图或公开内容。
- 权限授权完成后建议断开 ADB，或至少关闭网络调试。

除此之外，Agent 可以按用户授权完成安装、权限、密码设置、清数据、卸载、设备管理员/设备所有者等操作。

## 9. 支持范围

- TV 正式包：`com.studyontv.tv`
- 手机 / 平板正式包：`com.studyontv.mobile`
- 最低 Android 5.0（API 21）
- 无障碍类：`com.studyontv.tv.WatchAccessibilityService`
- 主 Activity：`com.studyontv.tv.MainActivity`

厂商定制系统可能限制其中一步；以验证结果为准，不要假设全部成功。
