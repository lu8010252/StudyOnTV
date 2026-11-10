# 厂商问题根因分析：#17 / #44 / #93 / #92

> 只读审查，未修改产品代码。行号：`HEAD` = 已发布 v0.3.21（`1e902da`）；`工作区` = 当前正在被其他子 Agent 修改的版本，差异单独标注。本次无真机、无 `dumpsys`；标注「需要设备证据」的判断不构成结论。

## 总表

| Issue | 判定 | 置信度 | 一句话结论 |
| --- | --- | --- | --- |
| #17 Mate50 设 10 分钟无反应 | **我们的 bug**（多条确定漏洞；命中的哪条需日志） | 中 | 前台包名可能恒定落进 `exempt()`，既不扣时也不弹页；「无任何可用权限」时全程静默，界面仍显示余额。 |
| #44 当贝腾讯视频不答题 | **信息不足**（未发现对腾讯视频的误判；另有 2 处可加固点） | 中 | 代码里没有腾讯包名特判，误判只能来自「前台拿到桌面包 + 桌面被静态名单否决」。 |
| #93 Sony 20/21 计时与拦截全失效 | **我们的 bug**（0.3.20 引入的前台识别回归） | 中高 | `Foreground.current()` 可能返回**我们自己**或陈旧包名 → `exempt()` 恒真 → `active=false && protecting=false && hideAll()`，同时解释两个症状。 |
| #92 长虹一直提示悬浮窗未开启 | **我们的 bug（兼容性缺陷，待 AppOps 确认）** | 中 | 只读 `Settings.canDrawOverlays`，且它说 false 时就放弃，从不做一次真实 `addView`。 |

---

## 1. #17 Mate 50：设 10 分钟限制，超时无反应，App 仍显示「可以使用 10 分钟」

### 反馈中的关键事实
- 设 10 分钟限制（`dailyMinutes` 或默认 `sessionMinutes=10`，`LocalStore.kt:169`）。
- 到点没有任何响应（无学习页、无通知）。
- 打开 App 仍显示「还可以使用 10 分钟」——该文案来自 `app/web/assets/js/app.js:124`
  `'还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟'`，即 `status()` 的
  `remainingSeconds` **完全没减少**：不是「弹窗弹不出」，而是**计时从未开始**。

### 代码审查路径
1. `GateService.kt:81-93`（工作区）：
   ```kotlin
   val monitoring = canOverlay || WatchAccessibilityService.connected || Foreground.hasUsageAccess(this)
   val pkg = Foreground.current(this)
   val active = awake && monitoring && when {
       pkg == null -> canOverlay
       !exempt(pkg) -> true
       else -> launcherPlaying(pkg) || …
   }
   wasUsing = active && !free && reason.isEmpty() && !feedbackVisible   // :107
   ```
   `consume(elapsed)` 仅在 `wasUsing==true` 时执行（`:61`），`active=false` ⇒ 永不扣时。
2. `:94 protecting = awake && monitoring && if (pkg==null) canOverlay else !exempt(pkg,false)`；
   `:108 … || (pkg != null && exempt(pkg,false)) → hideAll()`。
   **`pkg` 一旦被豁免，同一 tick 内不计时、不弹 Gate、还会收掉已弹出的 Gate。**
3. 包名来源 `Foreground.kt:76-93`（工作区）：
   - 只有悬浮窗：`current()` → `lastRealPackage`（无无障碍时为 null）→ `active=canOverlay`，**能计时**；
   - 有无障碍：`isTransient(cached)` 时退到 `usageFallback()`；**HEAD `Foreground.kt:71` 是
     `usageFallback(context)?.let { return it }`，不过滤自身包名**（工作区 `:86` 已加 `takeUnless`）；
   - 只有使用情况访问：直接返回 `usageFallback()`，HEAD 同样可能返回 `com.studyontv.mobile`；
     `usageLatest` 是粘性的（`:95-118` 无新事件时返回旧值）。
4. 口径不一致：`LearningEngine.kt:236` `available = minOf(capacity(config), time.beforeRest(config))`，
   而 `status()` 的 `remaining`（`:28 usableCredit`）**只看 capacity、不看 beforeRest**。
   `TimePolicy.kt:33-35`：`restLeft()` 在 `rest_deadline` 为空时返回 0，但 `beforeRest()` 只要
   `rest_watched_ms >= restWatchMinutes` 就返回 0 ⇒ 存在 `beforeRest()==0 && restLeft()==0` 死区：
   一毫秒都扣不动，而 `reason` 仍走 `freeQuota>0`/`remaining>0` 返回 `""`（`:199-210`）
   ⇒ 显示有余额、永不扣、永不锁。**正是 #17 的字面症状**（触发：家长把 `restWatchMinutes` 从 60 调小到 30，而 `rest_watched_ms` 已 40 分钟）。
5. 静默失效：`GateService.kt:284-296` 只在 `overlay_was_granted==true`（曾经授权过）时才发权限缺失通知；
   新装机 `!canOverlay && !connected && !hasUsageAccess` → `monitoring=false`，一声不响。

### 触发条件（布尔）
- A：`ownActivity==null && (lastPackage==null || isTransient(lastPackage)) && usageLatest ∈ {自身, 桌面, systemui}`
  → `exempt(pkg)=true && launcherPlaying(pkg)=false` → `active=false && protecting=false`。
- B：`canOverlay=false && !connected && !hasUsageAccess` → `monitoring=false`，且无任何用户可见提示。
- C：`restEnabled && rest_watched_ms >= restWatchMinutes*60000 && rest_deadline=="" && capacity>0` → `reason==""` 但扣 0。

### 结论
**是我们的 bug**：B 是产品缺陷（静默失败），C 是纯逻辑漏洞（代码 `LearningEngine.kt:21-29` 的注释自己写了「显示必须等于可扣」，但漏了 `beforeRest`），A 是前台识别回归（HEAD 上确定存在，工作区在修）。

### 补丁建议
```kotlin
// 1) GateService.checkForeground()（:81 之后）：无可用能力时不要静默
if (!monitoring && !warnedNoCapability) {          // 新字段 private var warnedNoCapability = false
    warnedNoCapability = true
    Diag.warn("Gate", "no capability: overlay/accessibility/usage 全缺失 → 计时与拦截都不会生效")
    notifyMissingCapability()                       // 复用 notifyGateFallback 通道
}
// 2) LearningEngine：让显示口径 = 可扣口径
private fun capacity(config: JSONObject): Long =
    minOf((time.dailyLimit(config) - number("used_ms")).coerceAtLeast(0), time.beforeRest(config))
// 3) 休息额度自愈，消灭 beforeRest==0 却无 deadline 的死区
if (c.optBoolean("restEnabled") && time.beforeRest(c) == 0L && time.left("rest_deadline") == 0L)
    time.deadline("rest_deadline", c.optLong("restMinutes", 10) * 60000)
// 4) 与 #93 同补丁：current() 任一路径不得返回自身包名
```

### 需要的设备证据
`I/Gate` 快照 `reason=… free=… awake=… monitoring=… pkg=… active=… overlay=…`：`pkg=自身/桌面` ⇒ A；`monitoring=false` ⇒ B；`active=true` 但 `remainingSeconds` 不动 ⇒ C。另需 `adb shell appops get com.studyontv.mobile SYSTEM_ALERT_WINDOW`、`GET_USAGE_STATS`。

---

## 2. #44 当贝投影：进入腾讯视频不触发答题

### 代码审查路径（逐条核对）
1. `Foreground.kt:24-31 transientPackages`：systemui / android / *permissioncontroller / *packageinstaller，**不含任何腾讯包名** → `isTransient()` 不会误伤腾讯视频。
2. `GateService.kt:398-405 exempt()`：自身、systemui、android、`*packageinstaller`、`*permissioncontroller`、`*inputmethod`、`*keyboard`、设置类、电话/紧急、HOME/静态桌面。腾讯视频命中不了任何一条。
3. 当贝桌面：静态名单只有 `com.dangbei.launcher`（`Foreground.kt:63-73`），实际 HOME 常见 `com.dangbei.tvlauncher` → 由 `exempt()` 的动态 `resolveActivity(HOME)` 豁免（正确行为）。
4. `Playback.hasActive()`（`Playback.kt:24-33`）：`isMusicActive() || activePlaybackConfigurations.isNotEmpty()`，3 秒滞回不会「永久 false」；仅 Android 8 以下且厂商屏蔽该列表时才可能恒 false。
5. **可疑点** `GateService.kt:369-395 launcherPlaying()`：
   ```kotlin
   if (pkg != launcherPackage()) return false      // 必须与 resolveActivity(HOME) 精确相等
   if (Foreground.isLauncher(pkg)) return false    // 静态名单直接否决
   … return Playback.hasActive(this)
   ```
   多桌面时 `resolveActivity` 可能返回解析器（`android`/`com.android.intentresolver`）→ 兜底永不命中；HOME 若在静态名单里则被第二种直接否决（VIDAA 那种「HOME 与影视共包」机型会失去兜底）。

### 触发条件（布尔）
- D1：`pkg ∈ {桌面包} && isLauncher(pkg)=true` → `active=false && protecting=false`。
- D2：`resolveActivity(HOME)=resolver && pkg!=launcherPackage()` → `launcherPlaying()` 恒 false。
- D3：`!canOverlay && !connected`（#77 已记录当贝无障碍会被系统停用）→ `monitoring=false`。

### 结论
**信息不足**：`isTransient()`/`exempt()` 对腾讯视频无任何特判，不存在「把腾讯视频豁免掉」的代码；失效只可能来自「前台识别的不是腾讯视频」或「权限/服务掉了」。同时 `launcherPlaying()` 的精确匹配（D2）是可确认的逻辑缺陷。

### 建议
```kotlin
private fun launcherPlaying(pkg: String): Boolean {
    if (pkg != launcherPackage() && !Foreground.isHomePackage(pkg)) return false   // 见下
    val cls = Foreground.currentClass(pkg)
    if (cls != null && cls != launcherClassCache && !cls.contains("launcher", true) &&
        !cls.startsWith("android.") && !cls.startsWith("androidx.")) return true
    return Playback.hasActive(this)     // 只有「桌面 Activity 本身」才依赖媒体状态
}
// Foreground：新增 fun isHomePackage(pkg: String) = pkg == resolveActivity(HOME)?.packageName，供 GateService 复用
```

### 需要的设备证据
视频播放中的 `I/Gate` 一行（`pkg=`/`active=`/`monitoring=`）、`adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`、`adb shell dumpsys activity activities | grep mResumedActivity`（腾讯视频真实包名）、诊断 JSON 的 `accessibility`/`usage`/`overlay`。

---

## 3. #93 Sony：20/21 版计时不管用、锁定也不弹页（18 版正常）

### 关键事实与历史定位
`git diff v0.3.18 v0.3.19 --stat`：只改了版本号、Manifest 广播、`BootReceiver`、`OnboardingActivity` 文案，**`GateService.kt`/`Foreground.kt` 零改动**；`v0.3.19..HEAD` 才动前台识别（`Foreground +61 / GateService +63 / Playback +39 / CountdownOverlay +87`）。「18 好、20 坏」与前台识别改造的时间点完全吻合。

### 代码审查路径
1. HEAD `Foreground.kt:61-77`：
   ```kotlin
   if (WatchAccessibilityService.connected) {
       val cached = lastPackage
       if (cached != null && !isTransient(cached)) return cached                 // :70 不过滤自身
       if (hasUsageAccess(context)) usageFallback(context)?.let { return it }    // :71 不过滤自身
       lastRealPackage?.takeIf { it != context.packageName }?.let { return it }  // :73 过滤了
       return cached
   }
   ```
   0.3.18 此处是 `lastPackage?.let { return it }`，而 `lastPackage` 只由无障碍事件写入、且自身悬浮窗事件被丢弃（`WatchAccessibilityService.kt:15-21`），几乎拿不到自身包名。0.3.20 新增的 `usageFallback` 与 `lastRealPackage` 兜底**绕过了这条保护**（`resumeOwn()` 会把 `lastRealPackage` 设成自己，`Foreground.kt:33-44`）。工作区 `:85-89` 已加自身过滤，说明团队判断一致。
2. 命中后的后果：`exempt(pkg,false)` 对 `pkg==packageName` 恒 true ⇒ `protecting=false`（不弹）**且** `hideAll()` 每秒执行（已弹出的被收掉），同时 `active=false` ⇒ 不扣时。**一行代码解释两个症状。**
3. `usageLatest` 粘性（`:95-118`）+ `DeviceBridge.launchPackage()` 失败时静默返回 false（`DeviceBridge.kt:157-168`）⇒ 解锁后「回到原画面」失败时没有新 `MOVE_TO_FOREGROUND` 事件，卡在自己/桌面上（与 #74 Sony 同族）。
4. 弹窗链路有回退：`GateOverlay.show()` 对非 application 类型不做权限预检（`GateOverlay.kt:106` 只在 `windowType == applicationWindowType()` 时检查），`addView` 失败 → `fail("window") → destroy() → false` → `ensureGateVisible()` 再试 `overlay.show()` → 再失败 `fallbackToActivity()`。所以「Sony 拒绝 TYPE_ACCESSIBILITY_OVERLAY」不会完全静默，会在 `lastGateError` 留下 `window=TYPE_ACCESSIBILITY_OVERLAY …`，与「安静地什么都不发生」不符，进一步指向 `protecting` 恒 false。

### 触发条件（布尔）
- E（主假设）：`pkg == context.packageName` → `active=false && protecting=false && hideAll()`。
- F（次）：`!canDraw && !connected && !hasUsageAccess` → `monitoring=false`。
- G（弹窗）：`lastGateError` 含 `addView failed` → 需 `dumpsys window`；现有代码未发现静默分支。

### 结论
**是我们的 bug**，0.3.20 引入的回归：兜底路径把「我们自己」当成了「孩子在用的应用」。Sony 的 `mCurrentFocus` 为空、`TYPE_APPLICATION_OVERLAY` 被拒等需 `dumpsys` 才能确认，但它们**解释不了「计时一起失效」**——计时只看 `active`。

### 补丁建议（工作区已覆盖一半，建议补全后半）
```kotlin
// Foreground.current()：任何路径都不得返回自身；宁可返回 null（按亮屏兜底 + 能走 GateActivity）
if (WatchAccessibilityService.connected) {
    val cached = lastPackage
    if (cached != null && cached != context.packageName && !isTransient(cached)) return cached
    if (hasUsageAccess(context)) usageFallback(context)
        ?.takeUnless { it == context.packageName || isTransient(it) }?.let { return it }
    lastRealPackage?.takeIf { it != context.packageName }?.let { return it }
    return null                       // 关键：不要 return cached（可能是自己/桌面）
}
// GateService：pkg==null 时不要只依赖 canOverlay
val active = awake && monitoring && when {
    pkg == null -> canOverlay || Foreground.hasUsageAccess(this)   // 有使用情况权限就按亮屏时间计时
    …
}
// Foreground.usageFallback()：记录事件时跳过自身（除非自己的 Activity 真的在前台）
if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && !isTransient(event.packageName) &&
    (event.packageName != context.packageName || hasResumedActivity())) latest = event.packageName
```

### 需要的设备证据
失效时 `I/Gate` 的 `pkg=com.studyontv.*`（确认 E）；`adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"`；`adb shell dumpsys window windows | grep -i com.studyontv`；诊断 JSON 的 `gateFailures`/`overlayErrorKind`/`overlayWindowType`（G 会非零）。

---

## 4. #92 长虹电视：一直提示悬浮窗未开启，打开权限也不起作用

### 代码审查路径
1. `GateOverlay.kt:277-281`：唯一判据 `Settings.canDrawOverlays(context)`（API≥23），否则 `checkCallingOrSelfPermission`。该 API 内部读 AppOps `OPSTR_SYSTEM_ALERT_WINDOW`，部分国产固件把开关映射到私有 op / 另一 uid（多用户、分身），或只在重启后同步。
2. `GateOverlay.kt:103-110 show()`：`if (windowType == applicationWindowType() && !canDraw(context)) return false` —— **在真正 `addView` 之前就放弃**，系统实际允许也永不尝试。
3. `GateService.kt:63` 每秒重算 `canDraw`，`OnboardingActivity.kt:29 / 177-190` 每秒 `refresh()`：**不是「权限状态只读一次」，也不需要重启进程**，两条链路都是每 1 秒重新读取。剩下的可能只有「读到的值与系统真实策略不一致」。
4. `updateAnchor()`（`GateService.kt:268-282`）本身是一次真实 `addView`，但被 `canOverlay && config.backgroundAnchorEnabled` 门控，无权限时不会试，浪费了这个天然探针。

### 触发条件（布尔）
- H：`Settings.canDrawOverlays=false && AppOps(OPSTR_SYSTEM_ALERT_WINDOW)==MODE_ALLOWED` → 我们认为无权限，实际有。
- I：`Settings.canDrawOverlays=false && addView(1x1 探针)==成功` → 同上，且探针可纠正。

### 结论
**我们的 bug（兼容性缺陷）**：把「厂商可能不忠实的 API」当成唯一真值，且它说不时连一次真实尝试都不做。映射到私有 op 属厂商行为，但「只信一个信号」是我们代码的问题。仍需 `appops get` 确认是 H 还是 I。

### 补丁建议
```kotlin
// GateOverlay.canDraw()：多信号取并集 + 保留真实 addView 探针结论
fun canDraw(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT < 23)
        context.checkCallingOrSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == PackageManager.PERMISSION_GRANTED
    else Settings.canDrawOverlays(context) || appOpsAllows(context) || probeOk
} catch (_: Exception) { false }

private fun appOpsAllows(context: Context): Boolean = try {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29)
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
    else @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
    mode == android.app.AppOpsManager.MODE_ALLOWED
} catch (_: Throwable) { false }

// GateService：canDraw=false 时每分钟做一次 1x1 探针 addView（失败不报错），成功则记 probeOk
if (!canOverlay && now - probeAt > 60_000) {
    probeAt = now
    try { manager.addView(probeView, params); probeOk = true
          Diag.warn("Gate", "Settings.canDrawOverlays=false 但 addView 成功 → 厂商 AppOps 不一致，按有权限处理") }
    catch (_: Exception) { probeOk = false }
}
// 并把 GatePresentation.failureSummary() 的 !canDraw 文案改成可区分「已授权但读取失败」，避免家长反复授权
```

### 需要的设备证据
`adb shell appops get com.studyontv.tv SYSTEM_ALERT_WINDOW`（看 `0`/`allow` 还是 `default`）、`adb shell dumpsys package com.studyontv.tv | grep -i -A2 SYSTEM_ALERT_WINDOW`、授权后 `am force-stop` 再开（区分「需重启进程」与「读取口径不一致」）、`I/Gate overlay refused: permission-denied canDraw=false …` 行。

---

## 共同根因假设

四条反馈指向**三组**缺陷，#17/#44/#93 高度同源：

| 集群 | 缺陷 | 涉及 | 证据强度 |
| --- | --- | --- | --- |
| A 前台识别 `Foreground.current()` | 兜底可能返回**自身/桌面/陈旧包名**；一旦 `exempt()` 恒真，`active=false && protecting=false && hideAll()` 同时关掉计时与拦截 | #93（主）、#17（主）、#44（次） | 代码可读出的确定漏洞，工作区已部分修复 |
| B 窗口权限 `GateOverlay.canDraw()` | 只信 `Settings.canDrawOverlays`，且它说 false 时不试真实 `addView` | #92（主）、#93（次）、#17（次：无权限静默） | 确定缺陷，厂商差异待 AppOps 证据 |
| C 计时口径 `status()` vs `consume()` | 显示用 `capacity`、扣时用 `min(capacity, beforeRest)`，休息触顶无 deadline 时成死区；`review_feedback` 残留也会让 `wasUsing=false` | #17（次） | 纯逻辑，可 instrumentation 复现 |

### 优先级
1. **P0 #93（A）**：同一段代码能同时解释两个症状，修复面小（`current()` 三行 + `pkg==null` 分支），直接决定「到点会不会锁」。
2. **P0 #92（B）**：整机完全失效、家长无法自救；修复纯增量，不改成功路径。
3. **P1 #17**：先补 C 的死区与「无权限静默」，再用日志确认 A 是否命中。
4. **P1 #44**：先加固 `launcherPlaying()` 的精确匹配与静态名单否决，再要一条 `pkg=` 日志；在此之前不宣称修复。

### 最小验证方法
1. 发病状态下导出「设备诊断」，取一条 `I/Gate` 快照 `reason=… free=… awake=… monitoring=… pkg=… active=… overlay=…`。
2. 分区：`pkg=com.studyontv.*`/桌面包 ⇒ **A**；`monitoring=false` 或「系统已授权但 overlay=false」⇒ **B**；`active=true` 但 App 内 `remainingSeconds` 不动 ⇒ **C**。
3. 不依赖真机即可回归的三项：`Foreground.current()` 在 `lastPackage=自身 / usageLatest=自身 / 全 transient` 下必须返回 `null` 或外部包名；`LearningEngine` 在 `rest_watched_ms>=restWatchMinutes && rest_deadline==""` 时不得出现 `reason=="" && 扣不动`；`GateOverlay.canDraw()` 在 `AppOps=ALLOWED、Settings=false` 时必须为 true。
