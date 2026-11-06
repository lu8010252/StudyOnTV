# 电视/投影上用 ADB 开启「识别前台应用」权限

只开悬浮窗时，应用认不出你现在开的是哪个 App，只能按「离开学习页后的亮屏时间」粗略计时：在桌面、在别的 App 里、甚至画面停着没人看，都算在用。想计时准，需要下面任意一项（推荐两项都开）。

电视页右上角会显示当前的计时方式：`无障碍` / `使用情况` / `仅悬浮窗` / `未监控`。看到「仅悬浮窗」就说明还没开成功。

电视系统往往不给无障碍和使用情况的设置入口，但可以用 ADB 直接授权（包名 `com.studyontv.tv`）。已经用「Design Menu → Execute Shell」或电脑 `adb connect` 连上电视的话，在同一个 shell 里执行：

```sh
# 1) 使用情况访问：能识别当前前台 App（计时准）
appops set com.studyontv.tv GET_USAGE_STATS allow

# 2) 悬浮窗（一般已开）
appops set com.studyontv.tv SYSTEM_ALERT_WINDOW allow

# 3) 无障碍：能及时发现 App 切换，并让锁屏更难绕开
#    先看现在已启用了哪些无障碍服务，已有的要保留，用冒号拼在前面
settings get secure enabled_accessibility_services
settings put secure enabled_accessibility_services com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService
settings put secure accessibility_enabled 1

# 4) 防止被系统省电杀后台
dumpsys deviceidle whitelist +com.studyontv.tv
```

如果第 3 步前面查到已经有别的无障碍服务（输出不是 `null`），写成：
`settings put secure enabled_accessibility_services 原有的内容:com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService`

执行后重开 StudyOnTV，看右上角计时方式有没有变。想撤销：`settings delete secure enabled_accessibility_services`，`appops set com.studyontv.tv GET_USAGE_STATS default`。

## 小房子（Home）键

Android 不允许普通应用拦截 Home 键，所以只靠悬浮窗时按 Home 会回到桌面。开了无障碍之后：时间用完后，应用在桌面、其他 App 里都会重新盖上学习页；还有时间的时候，在桌面的时间不计入使用。
