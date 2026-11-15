# 贡献指南

感谢您愿意改进「题了个题」。这份文档说明怎么开始、改哪里、怎么提交，尽量让每一次贡献都能顺利合入。

## 5 分钟上手

不用 Android 设备也能先把家长面板跑起来：`npm run dev` 在不填设备地址时使用内置预览数据。

1. Fork 仓库，克隆并切一个分支：

```bash
git clone https://github.com/<你的用户名>/study-on-tv.git
cd study-on-tv
git switch -c docs/first-pr
```

2. 启动家长面板：

```bash
cd parent-ui
npm ci
npm run dev
```

3. 浏览器打开终端里显示的地址（默认 `http://127.0.0.1:5173`）。改一句文案、一个按钮或一段样式，保存后页面会自动刷新。
4. 提交前跑检查：

```bash
npm test
```

5. 提交、推送、开 PR。描述里写清楚「改了什么、为什么」，并关联 Issue。

第一次贡献建议从这几类开始：文档与文案、设备兼容性记录、题库、家长面板的界面小修。`GateService.kt`、`LearningEngine.kt`、`Watchdog.kt` 涉及管控主链路，没有真机回归前不要大改。

## 贡献地图

### 家长面板 `parent-ui/`

| 位置 | 负责 |
|---|---|
| `src/QuestionBank.tsx`、`src/components/bank/` | 题库页：列表、筛选、导入、在线题库、AI 出题 |
| `src/RulesPage.tsx`、`src/WeekSchedule.tsx`、`src/TimePoints.tsx` | 时间规则、可用时段、网课时段 |
| `src/DevicePanel.tsx` | 设备信息、权限、更新、自启动 |
| `src/RecordsPage.tsx`、`src/GradingPage.tsx` | 学习记录、AI 批改 |
| `src/api.ts` | 所有 HTTP 请求封装 |
| `src/schema.ts` | 配置与导入数据校验，改字段要同步补测试 |
| `src/components/ui/` | 通用组件（shadcn/ui） |

### 学习页 `app/web/`

| 位置 | 负责 |
|---|---|
| `practice.html`、`index.html` | 答题页、锁定页 |
| `assets/js/app.js` | 页面状态机、答题、断电后恢复上一组题 |
| `assets/js/` 其它脚本 | 朗读、图表等按需加载模块 |

学习页跑在老旧 WebView 上：只用 ES5（`var` / `function`），不要用 `?.`、`??`、箭头函数或 `const`；不要引入构建步骤，也不要压缩。

### Android `app/native/`

完整模块职责见 [MODULES.md](app/native/app/src/main/java/com/studyontv/tv/MODULES.md)。最常改的几个：

| 文件 | 负责 |
|---|---|
| `LanServer.kt` | 局域网 HTTP 接口；新接口在 `registerRoutes()` 里登记 |
| `LearningEngine.kt` | 出题、计时、奖励、错题与覆盖优先 |
| `GateService.kt` | 每秒 tick 的管控循环 |
| `LocalStore.kt` | SQLite 题库、统计、配置、多孩子 |
| `BankMarket.kt`、`BankAutomation.kt` | 在线题库与题库自动同步 |
| `IntegrationAuth.kt` | Home Assistant 等第三方集成的独立令牌 |
| `WatchGap.kt`、`WatchWhitelist.kt` | 漏记时长补算、不计时应用白名单 |

### 题库、文档与模板

- 内置题库：`data/seed.json`；格式见 [题目格式](docs/question-format.md)
- 文档：`docs/`；第三方自动化模板：`integrations/`
- 共享题库仓库：https://github.com/chadyi/StudyOnTV-banks

## 分支命名

| 类型 | 格式 | 示例 |
|---|---|---|
| 功能 | `feat/<issue号>-<简述>` | `feat/149-question-coverage` |
| 修复 | `fix/<issue号>-<简述>` | `fix/122-fullscreen-debounce` |
| 维护 | `chore/<简述>` | `chore/gitee-mirror` |
| 发布 | `release/v<版本>` | `release/v0.3.37` |

从 `main` 切出，不要把多个不相关的改动放在同一个分支里。

## 提交信息

```
<type>: <一句话说明（中文）>

- 细节 1
- 细节 2
```

`type` 可用：`feat` / `fix` / `docs` / `style` / `refactor` / `chore` / `build` / `release`。

提交信息写「改了什么、为什么」，不需要写测试过程；一个提交只做一件事，方便回滚。

## 本地开发

### 家长面板（parent-ui）

```bash
cd parent-ui
npm ci
npm run dev      # 浏览器打开 127.0.0.1，填入设备的 IP:8099
```

`npm run dev` 不带设备时使用内置的预览数据，可以离线改界面。`/api` 会被代理到 `127.0.0.1:8099`，本机开着模拟器或连了真机时会直接读真实数据。

### Android

`admin/` 是 parent-ui 的构建产物，不入库。先构建面板，再编译 APK：

```bash
cd parent-ui && npm ci && npm run build
cd ../app/native && ./gradlew assembleTvDebug
```

构建产物 `admin/`、`app/native/app/src/main/assets/` 都由 Gradle 自动同步，不要提交。

回归测试（需要连接真机或模拟器，使用独立数据库，不影响家庭题库）：

```bash
adb install -r app/native/app/build/outputs/apk/tv/debug/*.apk
adb shell am instrument -w com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```

前端检查：

```bash
cd parent-ui && npx tsc --noEmit && npm test
```

### 接口文档

设备端 HTTP 接口的完整定义在 [openapi.yaml](docs/openapi.yaml)，浏览器里可以打开 [api.html](docs/api.html) 查看 Swagger 页面。

## PR 检查清单

提交前逐条确认，PR 里可以直接勾：

- [ ] 描述里关联 Issue（`closes #N`），并写清楚复现步骤和修复思路
- [ ] `cd parent-ui && npm test` 通过（包含 TypeScript 类型检查；只改文档可跳过）
- [ ] 没有提交 `admin/`、`app/native/app/src/main/assets/admin/`、`node_modules/` 或 `*.apk`
- [ ] 改了 `app/web/`：保持 ES5，没有新增依赖，没有压缩代码
- [ ] 改了 Kotlin：没有新增第三方依赖；新接口在 `LanServer.registerRoutes()` 里登记
- [ ] 改了数据库：写了 `onUpgrade` 迁移，并补一条 upgrade 回归用例
- [ ] 改了界面：附截图；改了管控逻辑：说明测试设备与 Android 版本
- [ ] 题库内容不直接塞进 PR，走共享题库仓库

## Issue 认领

想认领一个 Issue，直接在 Issue 下评论：

```
/claim
```

机器人会把你设为 assignee，并加上 `claimed` 标签。已经有人认领时不会重复分配，请先在评论里沟通，避免做重。做不了或想放弃时评论：

```
/unclaim
```

取消认领后，Issue 会重新回到待认领状态。

## 代码风格

- Kotlin：不用第三方依赖，标准库 + Android SDK 即可；注释写「为什么」而不是「做了什么」
- React / TypeScript：组件拆到 `parent-ui/src/components/`，数据结构用 zod schema 校验
- 新增接口放在 `LanServer.registerRoutes()` 里登记，鉴权在进入路由前统一完成
- 数据库结构变更必须写 `onUpgrade` 迁移，并补一条 upgrade 回归用例
- 文档、注释和提交信息使用中文；面向家长的文案尽量不用术语

## 设备兼容性

各品牌、机型的实测状态汇总在 [设备兼容性](docs/device-compat.md)。买到新设备、踩到新坑，欢迎按那里的模板补一条。

改代码前必读的五个坑：

1. 海信 VIDAA（issue #66）：前台包名就是桌面包名，看片时检测不到前台应用 → 用播放状态兜底计时
2. TCL 电视关机（issue #57）：PowerManager 仍报 interactive → 同时检查系统广播和显示状态
3. 部分电视悬浮窗 0 尺寸（issue #110）：`addView` 成功但窗口不可见 → 按可见性判断是否重试，不能只看 `isShowing`
4. 遥控器待机不发开机广播（小米等）：BootReceiver 不可靠 → 结合 RevivalListener 和 Watchdog 双重保活
5. Android 5（API 21）：部分 `JobScheduler` 特性不可用，SQLite 没有 `JSON_EXTRACT`；数据库迁移不要依赖 JSON 函数

## 共享题库

题库内容单独放在 https://github.com/chadyi/StudyOnTV-banks ，不占本仓库的体积，也不和代码发版绑在一起。投稿方式、目录规范和质量分级（`verified` / `auto-checked` / `draft`）见该仓库的 `CONTRIBUTING.md`。

家长面板「题库 → 在线题库」会读取这份仓库的 `index.json`，点一下就能导入；CI 会校验格式、重建索引，并在 PR 里贴出题数与题型分布。
