# 验收记录：#106 自定义 AI 接口 / #87 朗读 / #81 遥控器语音作答 / #90 AI 设置页卡顿

> 执行方式：真实模拟器 + 真机侧局域网 API + 宿主机 mock AI 服务 + 浏览器（Playwright/Chromium）实测。
> 所有命令与响应均为本次实际执行结果；未跑通的部分在文中明确写“未验证”。

## 0. 环境

| 项 | 值 |
| --- | --- |
| AVD | `Medium_Phone_API_36.1`，序列号 `emulator-5558`（`emulator-5556` = StudyOnTV_Tablet，`emulator-5554` = StudyOnTV_TV，本次未使用） |
| 系统 | Android 16 / SDK 36，`sdk_gphone64_x86_64`，设备内网 IP `10.0.2.16` |
| 被测 APK | `app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk`，`ls -l` 时间 **2026-11-09 21:27**，`versionName=0.3.21` / `versionCode=26`，包名 `com.studyontv.mobile.debug` |
| 仓库状态 | HEAD `1e902da`（2026-11-07），工作区有大量未提交改动（其他子 Agent 正在改 `LearningEngine.kt`、`app.js` 等）。**本报告的“设备端”结论来自上述 APK；“面板前端”结论同时给出了两套实现：APK 内置的 `/admin` 包 与 当前源码 `npm run dev`** |
| mock AI 服务 | 宿主机 `0.0.0.0:18080`（`scripts/mock-openai-server.py`，本次新建/扩充，见 §1.2） |
| 设备 API 访问 | `adb forward tcp:18099 tcp:8099`（设备 `LanServer` 默认端口 8099），宿主机用 `X-Admin-Key: <管理密码>` 调用 |
| 面板 dev server | `npm --prefix parent-ui run dev -- --port 5199` → `http://127.0.0.1:5199/` |
| 宿主机音频服务 | `python -m http.server 18081 --directory /tmp/audio`（供 #87 `playAudio` 测试用 mp3） |
| 管理密码 | 设备首次安装时密码未知，用 debug 包的 `run-as` 直接写入 `shared_prefs/parent_access.xml`（PBKDF2-HMAC-SHA1, 60000 轮, 256 bit，与 `AdminAuth.kt` 一致）设为 `verify123`，避免用 UI 输入法敲密码。未修改任何应用代码 |

启动设备与裸机准备（实际执行）：

```bash
adb -s emulator-5558 install -r -d app/native/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk   # Success
adb -s emulator-5558 shell am start -n com.studyontv.mobile.debug/com.studyontv.tv.MainActivity
adb -s emulator-5558 forward tcp:18099 tcp:8099
curl -s http://127.0.0.1:18099/api/hello
# {"app":"StudyOnTV","version":"0.3.21","name":"sdk_gphone64_x86_64","ip":"10.0.2.16","port":8099,"questions":4,"needsToken":true,"passwordRequired":true}
curl -s -X POST http://127.0.0.1:18099/api/auth -H "Content-Type: application/json" -d '{"passphrase":"verify123"}'
# {"token":"...","expiresIn":43200}
```

---

## 1. #106 自定义 OpenAI 兼容接口

### 1.1 验证方法

1. 宿主机起 mock（`0.0.0.0:18080`），设备通过 `10.0.2.2:18080` 访问（`10.0.2.2` 在模拟器内指向宿主机）。
2. 用 `PUT /api/grading` 写入 `endpoint=http://10.0.2.2:18080/v1`。
3. 依次验证 `POST /api/grading/test`、`POST /api/grading/models`。
4. 用 mock 的“慢响应”模式复现“10 秒超时”，并分别测 **设备端**（connect 10s / read 30s、15s）与 **前端**（旧 10s / 新 60s）两条路径。
5. 观察 `GET /api/logs`；用浏览器实测面板里“测试连接”的真实文案。

### 1.2 新增文件：`scripts/mock-openai-server.py`

标准库实现，`POST /v1/chat/completions`（返回 `{"score":90,"feedback":"ok"}` 的 chat 包）、`GET /v1/models`（`mock-model`、`mock-vision`），并支持：

| 控制端点 | 作用 |
| --- | --- |
| `GET /__control/slow?seconds=N` | `chat/completions` 延迟 N 秒（0=关闭），无需重启 |
| `GET /__control/slowModels?seconds=N` | `GET /v1/models` 延迟 N 秒（用于验证 models 接口的 15s 超时） |
| `GET /__control/rejectResponseFormat?on=1` | 请求里带 `response_format` 时返回 HTTP 400，用于验证设备端“去掉 response_format 重试”的分支 |
| `GET /__requests` | 返回最近 100 次请求（含 `Authorization`、`User-Agent`、`model`、`response_format`），便于核对设备到底发了什么 |

启动：`python scripts/mock-openai-server.py --port 18080`（可选 `--slow 12`、`--slow-models 20`）。

### 1.3 实际输出

**a) 设备端接受 `http://10.0.2.2/v1`（`checkEndpoint` 把 10.0.2.2 判为私有地址）**

```bash
curl -s -X PUT http://127.0.0.1:18099/api/grading -H "X-Admin-Key: verify123" -H "Content-Type: application/json" \
  -d '{"mode":"ai","provider":"custom","endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key","vision":false,"passScore":60}'
# {"mode":"ai","provider":"custom","endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","vision":false,
#  "passScore":60,"hasKey":true,"chatUrl":"http://10.0.2.2:18080/v1/chat/completions"}
```

→ `privateHost()` 用 `InetAddress.getByName("10.0.2.2").isSiteLocalAddress` 判为私网，`http` 被放行（`GradingService.checkEndpoint` 行为确认）。

**b) 测试连接 / 拉取模型（mock 正常响应）**

```bash
curl -s -X POST .../api/grading/test -d '{"endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key"}'
# {"ok":true,"ms":16,"message":"连接成功，模型可以按要求批改（示例得分 90）"}
curl -s -X POST .../api/grading/models -d '{"endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key"}'
# {"ok":true,"items":["mock-model","mock-vision"]}
```

mock 侧收到的请求（`GET /__requests`）：

```
POST /v1/chat/completions  auth=Bearer test-key  user_agent=StudyOnTV  model=mock-model  response_format={"type":"json_object"}
messages=[{"role":"system","content":"只返回 JSON：{\"score\":数字,\"feedback\":\"中文\"}"},
          {"role":"user","content":"题目：1+1=？ 参考答案：2。学生答案：2。请评分。"}]
GET  /v1/models            auth=Bearer test-key  user_agent=StudyOnTV
```

**c) 慢响应 12 秒（正是旧前端 10s 超时会被打爆的场景）**

```bash
curl -s "http://127.0.0.1:18080/__control/slow?seconds=12"
curl -s -X POST .../api/grading/test -d '{...,"model":"mock-model",...}'
# {"ok":true,"ms":12010,"message":"连接成功，模型可以按要求批改（示例得分 90）"}     （real 12.7s）
```

→ **设备端能等到 12 秒的模型响应**（readTimeout=30s）。

**d) 设备端超时边界（chat readTimeout=30s / models readTimeout=15s）**

```bash
curl -s "http://127.0.0.1:18080/__control/slow?seconds=35"
curl -s -X POST .../api/grading/test -d '{...}'
# {"ok":false,"ms":30009,"message":"连接超时，请检查网络或稍后重试"}                （real 30.7s）

curl -s "http://127.0.0.1:18080/__control/slowModels?seconds=20"
curl -s -X POST .../api/grading/models -d '{...}'
# {"ok":false,"items":[],"message":"连接超时，请检查网络或稍后重试"}               （real 15.7s）
```

→ 设备端超时文案是 **“连接超时，请检查网络或稍后重试”**，不含 “WiFi” 字样，且以 HTTP 200 + `ok:false` 返回。

**e) 服务商不支持 `response_format` 时的自动重试**

```bash
curl -s "http://127.0.0.1:18080/__control/rejectResponseFormat?on=1"
curl -s -X POST .../api/grading/test -d '{...}'
# {"ok":true,"ms":11,"message":"连接成功，模型可以按要求批改（示例得分 90）"}
# mock 记录 2 次 chat：  #1 response_format={'type':'json_object'} → 400 ;  #2 response_format=None → 200
```

**f) 错误映射实测（都是真实网络行为）**

| 场景 | 设备返回 | 评价 |
| --- | --- | --- |
| 私网地址不可达 `http://10.255.255.1/v1` | `{"ok":false,"ms":5012,"message":"unexpected end of stream on com.android.okhttp.Address@21175c0e"}` | **未分类 IOException 把英文异常原文直接抛给用户**（含对象 hashCode） |
| 公网不可达 `https://192.0.2.1/v1`（TEST-NET-1） | `{"ok":false,"ms":7315,"message":"HTTPS 握手失败，设备系统可能太旧：connection closed"}` | **误导**：实际是链路被重置，不是“系统太旧” |
| 公网真站 + 无效 Key `https://api.deepseek.com/v1` | `{"ok":false,"ms":188,"message":"API Key 无效或没有权限（HTTP 401）：Authentication Fails, Your api key: ****test is invalid (request_id: ...)"}` | 正确、可读；同时证明设备出网正常 |

**g) 慢请求期间面板其余接口是否被拖死**

```bash
# mock slow=40，后台挂起一个 /api/grading/test
time curl -s -o /dev/null -w "%{http_code}\n" .../api/state    # 200，real 0.80s
time curl -s -o /dev/null -w "%{http_code}\n" .../api/grading  # 200，real 0.81s
```

→ `LanServer` 手机版固定 6 线程池，单个 AI 请求不会阻塞面板其它请求（连续 6 个慢请求才会排队）。

**h) `/api/logs` 记录情况**

```
11-10 02:25:55 I/HTTP pool-4-thread-5: POST /api/grading/test
11-10 02:34:51 I/HTTP pool-4-thread-4: POST /api/grading/test
11-10 02:35:03 E/HTTP pool-4-thread-4: POST /api/grading/test failed | SocketException: Broken pipe
```

→ 只记录 HTTP 请求行；**AI 请求失败的具体原因不落日志**（`GradingService.kt` 全文没有任何 `Diag.*` 调用）。`Broken pipe` 正好是“前端 10 秒就断开、设备 30 秒后才写回”的痕迹。

### 1.4 前端两条路径实测（Chromium + Playwright，打真实设备）

用 CDP 统计 `fetch` 次数与 React commit 次数；对 **APK 内置面板**（`http://127.0.0.1:18099/admin/`）与 **当前源码 dev server**（`http://127.0.0.1:5199/`）各跑一遍：

| 步骤 | APK 自带 `/admin` | 当前源码 `vite dev` |
| --- | --- | --- |
| 面板“测试连接”（mock slow=12） | `✓ 连接成功，模型可以按要求批改（示例得分 90） · 12.0 秒`，耗时 **12070 ms** | 同文案，耗时 **12123 ms** |
| 模拟旧前端逻辑（`AbortController` 10s） | `AbortError`，**10009 ms** | `AbortError`，**10003 ms** |

→ 旧代码在 10 秒整 abort，随后 `api.ts` 的 `else` 分支会抛 **“连接超时，请确认处于同一 WiFi”**；新代码等 60 秒，12 秒的响应正常返回。

补充：当**设备本身不可达**时（`adb forward --remove`）面板的表现：

| 场景 | 实际文案 |
| --- | --- |
| 登录页连不上设备 | `无法连接设备，请检查设备地址和 WiFi`（`main.tsx` 专门映射 `Failed to fetch`，合理） |
| 已登录后点“测试连接”，设备不可达 | `✗ Failed to fetch` ← **未翻译的浏览器原文**（`api.ts` 只处理 `AbortError`） |

### 1.5 结论：#106 通过（附残余风险）

- `PUT /api/grading` + `test` + `models` 全链路对自定义 OpenAI 兼容接口可用；`10.0.2.2` / 私网 http 被正确放行。
- 12 秒的模型响应：设备端能等（30s/15s），新前端能等（60s）。
- **“连接超时，请确认处于同一 WiFi”在 AI 路径上不再出现**：AI 路径的 abort 文案改为“AI 服务响应超时，请检查 API 地址、模型及设备网络后重试”，且该文案已随 APK 内置面板一起发布（见 §2 的二进制核对）。
- 仍然存在会把网络问题说错/说不清的地方，见 §2 与 §7。

---

## 2. #106 根因结论（专门回答）

**问：“连接超时，请确认处于同一 WiFi”这个提示是否还会误报？**

**答：在 AI 配置这条链路上不会再出现了；但“网络错误被说成 WiFi 问题/说成别的”仍有 4 处残留。**

### 2.1 两条路径各自的真实行为

| 路径 | 超时设定 | 触发时的表现 | 文案是否准确 |
| --- | --- | --- | --- |
| **前端 Chrome/WebView**（`parent-ui/src/api.ts`） | 旧：全局 10000ms；新：`/api/grading/test`、`/api/grading/models` 60000ms，其余 10000ms | `AbortController.abort()` → `AbortError` | 新代码：AI 路径 → “AI 服务响应超时，请检查 API 地址、模型及设备网络后重试”（准确）；**其它接口仍 → “连接超时，请确认处于同一 WiFi”** |
| **设备端**（`GradingService.kt`） | `connectTimeout=10000`；`readTimeout`：chat/test 30000、models 15000、出题 60000 | 抛出 `SocketTimeoutException`/`IOException`，被 `test()` 转成 `{"ok":false,"ms":...,"message":...}`，HTTP 200 | 超时文案 “连接超时，请检查网络或稍后重试”（不提 AI，但至少不甩锅 WiFi） |

### 2.2 根因链（为什么用户当初一直看到 WiFi 提示）

1. 用户的 AI 服务响应时间 > 10 秒（本次用 mock 12s 精确复现）。
2. 旧前端 10 秒整 `abort()`，请求被丢弃（设备侧留下 `Broken pipe` 日志，说明设备当时还在正常等待）。
3. `api.ts` 旧代码对所有路径统一抛 “连接超时，请确认处于同一 WiFi” → 用户被误导为网络/WiFi 问题。
4. 设备端 30 秒后其实会返回 `ok:true`，但前端早已放弃；**即使设备端返回错误，前端也不会等**。

现状：前端 60s > 设备 30s，因此设备永远是先答复的一方；`test()`/`models()` 的 `ok:false` 文案会原样展示，AI 路径的 `AbortError` 分支几乎不会触发（属兜底）。

### 2.3 二进制核对（改动是否真的随 APK 发布）

`admin/` 是面板构建产物，会被打进 APK 的 `assets/admin/`。直接读 APK：

```python
zipfile.ZipFile('...app-mobile-debug.apk').read('assets/admin/assets/index-BgHtVmTU.js')
# 命中： ...?6e4:1e4){const d=new AbortController,c=setTimeout(()=>d.abort(),i)...
#        ... r==="/api/grading/test"||r==="/api/grading/models"||r==="/api/questions/generate"
#            ?"AI 服务响应超时，请检查 API 地址、模型及设备网络后重试" : "连接超时，请确认处于同一 WiFi"
```

→ **60000 / 10000 三元判定 + AI 专属文案已在被测 APK 内**，不是“只改了源码没进包”。（`assets/web/assets/js/app.js` 里的 `autoRead`/`read-aloud`/`answerVoice` 也都在包内。）

### 2.4 仍会把网络问题说错的 4 个场景（不改代码，仅报告）

1. **非 AI 接口 10s 默认超时** → “连接超时，请确认处于同一 WiFi”。例：设备正在跑 5 个以上慢请求把 6 线程池占满，或手机休眠网络挂起时，`/api/state`、`/api/update/check` 等会误报 WiFi。
2. **设备不可达时的 AI 测试** → 直接显示浏览器原文 `Failed to fetch`（本次实测），比 WiFi 文案更难懂。
3. **设备端 `SSLException` 一律翻译成“HTTPS 握手失败，设备系统可能太旧”** → 实测链路被重置（`connection closed`）时这句话是错的。
4. **设备端未分类 `IOException` 直传英文消息** → 实测 `unexpected end of stream on com.android.okhttp.Address@21175c0e`。

---

## 3. #87 朗读

### 3.1 代码审查结论

| 项 | 结论 |
| --- | --- |
| 路由（`SpeechReader.kt`） | `mode` ∈ `{system, azure, aliyun, auto}`；`auto` = 有 `azureKey` 走 azure，否则有 `aliyunSecret` 走 aliyun，否则 system |
| 系统通路 | `TextToSpeech` 懒初始化；`ready` 前把朗读动作存进 `pending`，初始化回调里执行；初始化失败则 `shutdown()` + `system=null`（**静默，无 UI 反馈**） |
| 音频属性 | TTS 与 MediaPlayer 都用 `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY + CONTENT_TYPE_SPEECH` ✔ |
| 云端失败回落 | 三处 `catch` 全部调用 `systemRead(context, parts.drop(index).joinToString(""), language, request)`：① `cloudPart` 的 worker catch（网络/鉴权失败）② `MediaPlayer.setOnErrorListener` ③ `prepare()/start()` 抛异常（含 `setDataSource` 不支持该 mp3 时） |
| 长文本 | `read()` 先按 150 字切块，云端逐块合成播放，失败时 `parts.drop(index)` 把**剩余全文**交回系统语音，不丢内容 |
| 中断 | `stop()` → `generation++` + `system.stop()` + `player.release()` + 删临时文件，异步回调都用 `request == generation` 校验，旧任务不会“复活” |

### 3.2 设备实测（模拟器装有 `com.google.android.tts`）

```bash
adb -s emulator-5558 shell "cat /proc/net/unix | grep webview"        # 有 webview_devtools_remote -> 可用 CDP 直接调用桥
adb -s emulator-5558 forward tcp:9333 localabstract:webview_devtools_remote_6391
# Playwright connect_over_cdp('http://127.0.0.1:9333') → page http://127.0.0.1:8099/#/quiz
# 页面内枚举桥方法： ["answerInput","answerText","answerVoice",...,"playAudio","readAloud","readInLanguage","stopReading",...]
```

**a) 系统 TTS 中文**

```js
SOTVNative.readInLanguage('苹果是红色的水果。','zh-CN')
```

```
I TextToSpeech: Sucessfully bound to com.google.android.tts
I TextToSpeech: Connected to TTS engine
I GoogleTTSServiceImpl: For default lang zh-cn is name zh-CN-language (cmn-cn-x-ssa-server)
I GoogleTTSServiceImpl: Synthesis request for locale zho-CHN and name zh-CN-language
I GoogleTTSServiceImpl: TTS dispatch: cmn-cn-x-ssa-seanet-embedded
D AudioTrack: stop(16): called with 52803 frames delivered     ← 真的出声了
```

→ `TextToSpeech` 初始化 SUCCESS，中文（cmn-CN）在模拟器上可用。

**b) 云端假 Key 必定回落到系统语音（端到端）**

```bash
PUT /api/speech {"mode":"azure","autoRead":false,"azureKey":"fake-key-123","azureRegion":"eastasia"}
# 然后 SOTVNative.readInLanguage('云端失败回落测试。','zh-CN')
```

```
I GoogleTTSServiceImpl: Synthesis request for locale zho-CHN and name zh-CN-language   ← 约 1.5~2s 后系统语音接管
D AudioTrack: stop(30): called with 53010 frames delivered
adb shell pidof com.studyontv.mobile.debug → 6391（进程仍在）
```

→ Azure 请求失败（401）→ `require(code in 200..299)` 抛异常 → `catch` → `systemRead(...)`，与代码审查一致；**无崩溃、无弹窗**。另注：`mode=system, autoRead=false` 时同一次调用只产生 **1 次** synthesis（确认没有重复朗读）。

**c) 语言不支持时“静默”**

```js
SOTVNative.readInLanguage('こんにちは、テストです。','ja-JP')
```
```
I GoogleTTSServiceImpl: Synthesis request for locale jpn-JPN and name ja-JP-language
（窗口内没有 AudioTrack stop；无异常、无 Toast、进程存活）
```
→ `SpeechReader` 里 `system?.language = Locale.forLanguageTag(language)` 的返回值被丢弃，也没有 `UtteranceProgressListener`，所以“语言缺失/合成失败”对用户完全静默。**这正是不崩溃的代价：用户不知道没读出来。**

**d) `playAudio`（MediaPlayer + http mp3）**

```js
SOTVNative.playAudio('http://10.0.2.2:18081/test.mp3')
```
```
V MediaHTTPService: makeHTTPConnection ... Cookies: null
D NuPlayerDriver: NuPlayerDriver(0x...) created, clientPid(6391)
D AudioTrack: stop(18): called with 88200 frames delivered      ← 2s 的 mp3 播完
D NuPlayerDriver: reset(...) at state 6                         ← onCompletion 里 release()
```
→ 手机版 http mp3 通路可用。`https` 未验证（无证书服务，见 §6）。

**e) 后台/悬浮场景的一条风险证据**

HOME 之后（应用不可见）再调 `readInLanguage`：

```
I GoogleTTSServiceImpl: Synthesis request for locale zho-CHN and name zh-CN-language
I AS.AudioService: AudioHardening background playback would be muted for com.google.android.tts (10157), level: full
D AudioTrack: stop(26): called with 28032 frames delivered
```

→ Android 16 明确打了“后台播放会被静音”的系统日志。本次仍写出 28032 帧（可能被系统静音或音量压到 0），**悬浮窗（`GateOverlay` 用 Service context）里的朗读是否真的有声，需要在真机上人工听；模拟器 `-no-audio` 无法判断。**

### 3.3 全屏页与悬浮页两个桥的对称性

| 方法 | `DeviceBridge`（MainActivity / ParentActivity 注入 `SOTVNative`） | `GateOverlay.Bridge`（悬浮层注入 `SOTVNative`） |
| --- | --- | --- |
| `readAloud(text)` | ✔ `SpeechReader.read(activity, text)` | ✔ `SpeechReader.read(context, text)` |
| `readInLanguage(text, lang)` | ✔ | ✔ |
| `stopReading()` | ✔ | ✔ |
| `playAudio(url)` | ✔ | ✔ |
| 其他 | `answerVoice/answerText/answerInput/...` | `answerVoice/answerText/answerInput/...`（`answerVoice` 额外 `suspend()` 收起悬浮层） |

两个桥**都实现了 4 个朗读方法**，且都调用同一个 `SpeechReader` 单例（`generation` 全局递增，所以换页/关悬浮层时 `GateOverlay.suspend()` 的 `SpeechReader.stop()` 会真正打断上一段朗读）。前端也统一用 `nativeCall('readInLanguage', ...)`，不存在“同一份前端在两种页面行为不一致”的桥缺失。

### 3.4 结论：#87 通过（系统通路实测可用；云端为代码+实测回落证据）

---

## 4. #81 遥控器语音作答

### 4.1 代码审查结论

| 检查点 | tv (`TvTextInputActivity`) | mobile (`AnswerInputActivity`) |
| --- | --- | --- |
| 语音按钮 | “语音输入 / 重录” ✔ | “语音输入” ✔ |
| 遥控器语音键 | `dispatchKeyEvent` 捕获 `KEYCODE_SEARCH` / `KEYCODE_VOICE_ASSIST` → `voice()` ✔ | 未处理（手机/平板无此需求，合理） |
| `EXTRA_LANGUAGE` | ✔ `intent.getStringExtra("language") ?: "zh-CN"` | ✔ 同 |
| 重录是否覆盖 | ✔ `edit.setText(spoken)`（**替换**，不追加）+ 光标移到末尾 | ✔ `text.setText(value)` 同 |
| 取消是否保留草稿 | ✔ `onActivityResult` 非 `RESULT_OK` 直接 return；`onDestroy` 把草稿回传 | ✔ 同（且有 120s `voiceTimeout` 防止卡在识别页） |
| 无识别服务时 | `catch (Exception)` → Toast “这台电视没有语音识别” | `catch` → 页内红字 “此设备没有语音识别服务，可使用输入法的语音按钮” |
| 语言来源 | `app.js` 传题目 `item.language`，经 `InputSupport.openVoice` 白名单过滤 | 同 |

`AnswerInput.language()` 读 `active_round.question.language`，供 `openText`（遥控器打字/手写页）使用；`openVoice` 用的是 JS 传进来的题目语言。

**两处不一致 / 缺口（只报告）：**

1. **语言白名单过窄**：`InputSupport.openVoice` 两份都是 `language.takeIf { it in listOf("zh-CN","en-US","en-GB") } ?: "zh-CN"`。题库里若有 `ja-JP`/`ko-KR`/`zh-TW` 的题目，识别语言会**静默变成 zh-CN**（结合 §3.2c，失败也不会有提示）。
2. **TV 的 `InputSupport.open(context, web, voice)` 忽略 `voice` 参数**（固定走文字输入页）。当前调用链是 `answerInput`→`open`、`answerVoice`→`openVoice`，所以用户视角没错；但如果以后有人用 `answerInput(voice=true)` 期望直接进语音，会被静默降级。
3. **mobile 丢 `prompt`**：`answerText(draft, prompt)` → 手机版 `InputSupport.openText` 只放 `draft`/`language`，`AnswerInputActivity` 也不展示题干；TV 版会显示 `prompt` 前 200 字。

### 4.2 设备实测

```bash
# 模拟器是否有识别服务接收者？
adb -s emulator-5558 shell "cmd package query-activities -a android.speech.action.RECOGNIZE_SPEECH"
# 1 activities found:
#   name=com.google.android.apps.speech.tts.googletts.service.GoogleTTSActivity
#   packageName=com.google.android.tts  exported=true isDefault=true
adb -s emulator-5558 shell "cmd package query-services -a android.intent.action.TTS_SERVICE"
# name=com.google.android.apps.speech.tts.googletts.service.GoogleTtsService  (com.google.android.tts)
adb -s emulator-5558 shell "settings get secure tts_default_synth"   # null（未显式指定，用系统默认）
```

从页面调桥：

```js
SOTVNative.answerVoice('', '1+1=？', 'zh-CN')
```
```
I ActivityTaskManager: START u0 {cmp=com.studyontv.mobile.debug/com.studyontv.tv.AnswerInputActivity (has extras)}
I ActivityTaskManager: START u0 {act=android.speech.action.RECOGNIZE_SPEECH ... cmp=com.google.android.tts/...GoogleTTSActivity (has extras)}
topResumedActivity=com.google.android.tts/...GoogleTTSActivity
```

截图确认：`AnswerInputActivity`（“填写答案 / 语音输入 / 清空手写 / 使用此答案 / 取消”）之上叠加了 Google 识别界面，界面底部显示 **“Chinese (China)”** → `EXTRA_LANGUAGE=zh-CN` 生效。返回键顺序：识别页 → `AnswerInputActivity` → `MainActivity`，全程 `pidof` 不变（6391），logcat 无本应用的 `ANR in` / `FATAL EXCEPTION`。

> 附注：截图里出现过 “System UI isn't responding” 对话框，这是无窗口模拟器的 `SystemUI` 自身 ANR，与本应用无关（logcat 无本应用 ANR 记录）。
> 语音**识别结果**无法在模拟器里构造（没有可注入的麦克风音频），所以“识别文本回填、重录覆盖”只能靠代码审查（`setText`），实测到“识别页被正确拉起 + 语言正确”为止。

### 4.3 未实现 / 未验证

- **发音评分**：完全没有（只做语音转文字，无音素/流利度评估）。
- **按题语言切换的边界**：白名单外语言静默降级为 zh-CN（见上）。
- **厂商拦截语音键**：部分电视遥控器语音键被厂商助手独占、`KEYCODE_VOICE_ASSIST` 根本不上抛；需真机（TCL/小米/华为等）验证，本次未验证。
- 手机版 `EXTRA_PREFER_OFFLINE=true`：模拟器带离线模型可用；无离线的机型会转在线识别，行为未验证。

### 4.4 结论：#81 通过（语音入口/语言/降级链路实测；发音评分为“未实现”，非缺陷）

---

## 5. #90 AI 设置页面卡顿复查

### 5.1 静态分析（`AiConfig.tsx` / `AiSettings.tsx` 挂载路径）

挂载链：`main.tsx → Dashboard → SettingsPage(section==='ai') → AiSettings → AiConfig`（`open` 时以全屏 fixed 层渲染）。

- 输入框 onChange 只做 `setDirty(true); setForm({...form, apiKey: e.target.value})`，**没有任何请求、没有 debounce 需求**（键盘输入不触发 `/api/grading`）。
- `useEffect(..., [settings.data, dirty])`：`dirty` 一旦为 true，`settings.data` 变化时**只更新 `hasKey`**，不再重置 `provider/endpoint/model` → 修掉了“父组件重渲染/查询刷新把输入值顶回去、光标跳末尾”的老问题（git diff 可见旧版是无条件 `setForm(f=>({...f, provider…}))`）。
- 唯一会“长时间转圈”的是“测试连接”按钮：`test.isPending` 期间按钮变成“测试中…” —— 旧代码 10 秒后失败并给出 **WiFi 文案**，用户很容易把它读成“卡住/页面卡”。这就是 #90 与 #106 同一根因的界面表现。

### 5.2 实测数据（Playwright 驱动真实 Chromium，两套前端各一遍）

注入 `__REACT_DEVTOOLS_GLOBAL_HOOK__` 统计 commit 次数，包裹 `window.fetch` 统计请求：

| 指标（在 `#ai-key` 连续输入 100 个字符） | APK 自带 `/admin`（生产构建） | 当前源码 `vite dev` |
| --- | --- | --- |
| 总耗时 | **414 ms**（≈4.1 ms/键） | 874 ms（dev 未压缩） |
| React commit 次数 | **100**（= 每键 1 次，无级联） | 100 |
| 期间发出的请求数 | **0** | 0 |
| 输入框最终长度 / 光标位置 | 100 / 100（无丢字、无光标跳动） | 100 / 100 |
| 页面 JS 报错 | 无 | 无 |

接口地址框同样 100 字符：736 ms、100 commits、**仅 1 个请求**（是 `Dashboard` 里 `refetchInterval: 5000` 的 `/api/state` 轮询，与输入无关）。

CPU 降频（CDP `Emulation.setCPUThrottlingRate`，模拟低端手机）：

| 节流倍数 | 输入 100 字符 | 每键平均 | 单次输入→两帧渲染延迟 |
| --- | --- | --- | --- |
| 1x | 311 ms | 3.1 ms | 28~35 ms |
| 4x | 2414 ms | 24.1 ms | 22~34 ms |
| 6x | 4565 ms | 45.7 ms | 23~59 ms |

→ 工作量与 CPU 成正比（受控组件每键 1 次 commit），**没有“每次按键查一次接口”的放大**；在 6x 降频下每键 46 ms（约 21 fps）会略微“跟手迟滞”，属性能下限，不是逻辑卡死。

### 5.3 结论：现在不卡；“卡很久”的原始反馈更像 #106 的 10 秒超时错觉

- 输入密钥本身：0 请求、100 键 100 commit、无光标跳动（两套前端一致）。
- 用户感知的“卡住很久”，最可能是点了“测试连接”后：旧前端 10 秒 abort → 显示“连接超时，请确认处于同一 WiFi”，既久又答非所问（本次用 12s 慢响应精确复现，旧逻辑在 10003 ms 抛 `AbortError`）。
- 现状：同样的 12 秒响应，面板 12.07 s 后正常显示“✓ 连接成功… · 12.0 秒”。

---

## 6. 仍需真机 / 凭据才能验证的部分

1. **Azure / 阿里云朗读真实合成**：无 Key。已验证“失败必定回落到系统语音 + 不崩溃”，但正确鉴权下的音质、`azureVoice`/`aliyunVoice` 生效、长文本分块接缝未验证。
2. **MediaPlayer 播放 https mp3**：本地只测了 `http` mp3。部分电视（老 WebView/MediaPlayer 组合）不支持 `https` 流或 mp3 编码时，`playAudio` 是**唯一没有回落的分支**（`setOnErrorListener` 只 release，不会退化成系统朗读）——需真机验证。
3. **悬浮层（Service context）朗读是否真出声**：Android 16 已打日志 `AudioHardening background playback would be muted`；模拟器 `-no-audio` 无法听。
4. **音频焦点竞争**：`MediaPause` 会 `dispatchMediaKeyEvent(KEYCODE_MEDIA_PAUSE)` 并静音/恢复 `STREAM_MUSIC`，而朗读用 `USAGE_ASSISTANCE_ACCESSIBILITY`；理论上不互相影响，但“正在朗读时到点暂停视频”的时序未在真机复现。
5. **遥控器语音键**：需要真实电视遥控器；`KEYCODE_SEARCH` / `KEYCODE_VOICE_ASSIST` 是否上抛取决于厂商。
6. **语音识别结果回填/重录**：模拟器无音频注入，仅代码审查。
7. **TV 版 APK**：本次只装了 `mobile` 变体，`TvTextInputActivity` 的实际布局/焦点/输入法弹出未在 TV 设备上跑（代码审查通过）。

---

## 7. 发现的其他 bug（仅报告，未修改）

1. **设备端错误文案泄露英文/误导**
   - 未分类 `IOException` 原样返回：`unexpected end of stream on com.android.okhttp.Address@21175c0e`。
   - 所有 `SSLException` 都说“设备系统可能太旧”（实测 `connection closed` 与系统版本无关）。
   - `GradingService.kt` 无任何 `Diag` 日志，`/api/logs` 只有一行 HTTP 记录，AI 排查缺少依据。
2. **面板 `api()` 只翻译 `AbortError`**：设备不可达时“测试连接”显示 `✗ Failed to fetch`（实测），未复用登录页那句“无法连接设备，请检查设备地址和 WiFi”。
3. **`/api/questions/generate` 的兜底文案与超时不一致**：`api.ts` 的 abort 分支为它准备了 AI 文案，但它默认超时是 10000 ms（设备端出题 readTimeout 是 60000 ms）。`QuestionBank.tsx` 目前显式传了 `90000`，所以暂时不触发；一旦有人漏传就会立刻误报“AI 服务响应超时”。
4. **`SpeechReader` 不检查 `setLanguage` 返回值、没有 `UtteranceProgressListener`**：语言缺数据时完全静默（实测 ja-JP 无任何用户可见反馈）。
5. **`playAudio` 无回落**：云端 `MediaPlayer` 有“失败转系统朗读”，而题目音频 `playAudio` 失败只是静默 release（老电视 mp3/https 兼容性差的场景会“点了没反应”）。
6. **`InputSupport.openVoice` 语言白名单只放行 zh-CN/en-US/en-GB**：其它语言的题目会被静默当成中文识别。
7. **mobile 丢题干**：`DeviceBridge.answerText(draft, prompt)` → 手机版 `InputSupport.openText` 不传 `prompt`，`AnswerInputActivity` 也不显示题干（TV 版会显示），两端体验不一致。
8. **观察到一次“连续两次朗读请求”**（后台时 `readInLanguage` 后 1.35 s 内出现两次 `Synthesis request`，帧数不同），随后 20 秒空转未再复现；`autoRead` 开启时页面重渲染是否会重复触发朗读，建议补一条回归测试。

---

## 8. 复现命令速查

```bash
# mock AI
python scripts/mock-openai-server.py --port 18080
curl "http://127.0.0.1:18080/__control/slow?seconds=12"
curl "http://127.0.0.1:18080/__control/slowModels?seconds=20"
curl "http://127.0.0.1:18080/__control/rejectResponseFormat?on=1"
curl "http://127.0.0.1:18080/__requests"

# 设备 API
adb -s emulator-5558 forward tcp:18099 tcp:8099
A='-H "X-Admin-Key: verify123" -H "Content-Type: application/json"'
curl -s -X PUT .../api/grading -d '{"mode":"ai","provider":"custom","endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key"}'
curl -s -X POST .../api/grading/test  -d '{"endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key"}'
curl -s -X POST .../api/grading/models -d '{"endpoint":"http://10.0.2.2:18080/v1","model":"mock-model","apiKey":"test-key"}'
curl -s -H ... ".../api/logs?limit=500"

# 朗读 / 语音作答（在 WebView 里执行，用 CDP）
adb -s emulator-5558 forward tcp:9333 localabstract:webview_devtools_remote_<pid>
# Playwright: connect_over_cdp('http://127.0.0.1:9333') → page.evaluate(...)
SOTVNative.readInLanguage('苹果是红色的水果。','zh-CN')
SOTVNative.stopReading()
SOTVNative.playAudio('http://10.0.2.2:18081/test.mp3')
SOTVNative.answerVoice('', '1+1=？', 'zh-CN')

# 面板
npm --prefix parent-ui run dev -- --port 5199     # 源码版
# 或直接用设备自带面板
http://127.0.0.1:18099/admin/
```
