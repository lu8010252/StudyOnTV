# Telegram 机器人

在 Telegram 私聊里给电视发命令：

```text
/unlock 30   临时解锁 30 分钟
/lock        立即锁定
/cancel      取消临时解锁或暂停
/credit 10   奖励或扣减今日时长（如 10 或 -10）
/status      查看当前孩子、剩余时间和今日统计
```

实现是一个零依赖的 Cloudflare Worker（`worker.js`），只做四件事：校验 Telegram 的 webhook 密钥、检查 chat 白名单、调用家里的设备接口、把结果发回 Telegram。

## 为什么需要 cloudflared

Cloudflare Worker 跑在 Cloudflare 的公网上，**不能直接访问家里的 `192.168.x.x`**。必须先把电视的 8099 端口通过隧道暴露成一个 HTTPS 地址：

```text
Telegram → Cloudflare Worker → https://xxx.trycloudflare.com → 家里的 cloudflared → 电视 8099
```

cloudflared 需要跑在家里一台常开的机器上（树莓派、NAS、软路由或常开电脑），电视本身不用装任何东西。

### 临时隧道

在局域网内的常开机器上执行：

```bash
cloudflared tunnel --url http://192.168.1.20:8099
```

命令会输出一个 `https://xxxx.trycloudflare.com` 地址。这个地址就是 `STUDYONTV_URL`，**不要带结尾斜杠**。临时隧道重启后会换地址，适合先验证；长期使用建议在 Cloudflare 控制台建命名隧道并绑定自己的域名。

> 不要把 `8099` 直接映射到公网，也不要在路由器上做端口转发。

## 一、创建机器人

1. 在 Telegram 搜索 `@BotFather`，发送 `/newbot`，按提示取名字；
2. 记下 BotFather 返回的机器人令牌（形如 `123456789:ABC...`），这就是 `TELEGRAM_BOT_TOKEN`；
3. 先给机器人发一条任意消息，方便后面查 chat id。

## 二、部署 Worker

```bash
cd integrations/telegram
npx wrangler login
npx wrangler deploy
```

第一次部署会返回一个 `https://studyontv-telegram.<你的子域>.workers.dev` 地址，webhook 路径是 `/telegram`。

### 写入变量

敏感值全部用 `wrangler secret put` 写入，不要写进 `wrangler.toml`，也不要提交到 Git：

```bash
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put TELEGRAM_WEBHOOK_SECRET
npx wrangler secret put STUDYONTV_URL
npx wrangler secret put STUDYONTV_TOKEN
npx wrangler secret put ALLOWED_CHAT_IDS
```

| 变量 | 填写内容 |
|---|---|
| `TELEGRAM_BOT_TOKEN` | BotFather 给的机器人令牌 |
| `TELEGRAM_WEBHOOK_SECRET` | 自己随机生成的一串字符，例如 `openssl rand -hex 32` 的输出；同时要填进 `setWebhook` 的 `secret_token` |
| `STUDYONTV_URL` | cloudflared 输出的 HTTPS 地址，例如 `https://xxxx.trycloudflare.com`，结尾不要加 `/` |
| `STUDYONTV_TOKEN` | 家长面板生成的集成令牌，`sotv_` 开头 |
| `ALLOWED_CHAT_IDS` | 允许使用的 chat id，多个用逗号分隔，例如 `123456789,-1001234567890` |

`wrangler secret put` 写入后立即对已部署的 Worker 生效，不需要重新部署；只有改了 `worker.js` 或 `wrangler.toml` 才需要再执行一次 `npx wrangler deploy`。

### 查 chat id

给机器人发一条消息，然后执行：

```bash
curl "https://api.telegram.org/bot<BOT_TOKEN>/getUpdates"
```

在返回 JSON 里找 `message.chat.id`。私聊是正数，群组是负数。把 id 填进 `ALLOWED_CHAT_IDS`。也可以把机器人拉进一个家庭群，只允许这个群的 id，这样全家都能用；群里的任何成员都能发命令，介意的话只用私聊。

## 三、设置 webhook

把 `<BOT_TOKEN>`、`<WORKER_URL>`、`<WEBHOOK_SECRET>` 换成实际值：

```bash
curl -X POST "https://api.telegram.org/bot<BOT_TOKEN>/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://studyontv-telegram.<你的子域>.workers.dev/telegram",
    "secret_token": "<WEBHOOK_SECRET>",
    "allowed_updates": ["message"]
  }'
```

检查是否生效：

```bash
curl "https://api.telegram.org/bot<BOT_TOKEN>/getWebhookInfo"
```

`last_error_message` 为空、`url` 正确即可。之后给机器人发 `/status` 测试。

## 四、令牌与权限

在家长面板 **设置 → 家庭自动化** 里生成 **集成令牌**，不要使用家长管理密码：

- `/unlock`、`/lock`、`/cancel` 需要 **控制** 权限；
- `/status` 会读取 `/api/state`，还需要 **只读** 权限；
- 因此建议这张令牌同时勾选 **只读 + 控制**。如果只想给「控制」，`/status` 会返回 403，可以从 Worker 里删掉这个命令；
- 绝对不要把管理密码（`X-Admin-Key`）写进 Worker 或任何环境变量。集成令牌可以单独吊销，管理密码泄露则要改密码并影响所有设备。

## 命令与返回

| 命令 | 作用 | 设备调用 |
|---|---|---|
| `/unlock` | 默认解锁 30 分钟 | `POST /api/remote`，`{"action":"unlock","minutes":30}` |
| `/unlock 60` | 解锁 60 分钟，范围 1–1440 | 同上 |
| `/lock` | 立即锁定，直到 `/cancel` | `POST /api/remote`，`{"action":"lock","minutes":0}` |
| `/cancel` | 取消临时解锁或暂停 | `POST /api/remote`，`{"action":"cancel"}` |
| `/credit 10` | 奖励或扣减今日余额（范围 -480 到 480 的非零整数） | `POST /api/remote`，`{"action":"credit","minutes":10}` |
| `/status` | 查看当前孩子、剩余时间、今日观看和答题统计 | `GET /api/state` |
| `/start`、`/help` | 显示帮助 | 不访问设备 |

常见错误提示：

| 提示 | 原因 |
|---|---|
| 集成令牌无效或已吊销 | `401`，令牌错了或被吊销，重新生成并更新 `STUDYONTV_TOKEN` |
| 集成令牌权限不足 | `403`，控制命令缺「控制」，或 `/status` 缺「只读」 |
| 请求太频繁 | `429`，设备端限流 240 次/分钟 |
| 连不上电视 | 隧道离线、`STUDYONTV_URL` 写错、电视 App 没在运行 |
| 连接电视超时 | Worker 等待设备超过 10 秒，通常是隧道或设备休眠 |

## 本地调试

创建 `.dev.vars`（不要提交）：

```dotenv
TELEGRAM_BOT_TOKEN=...
TELEGRAM_WEBHOOK_SECRET=test-secret
STUDYONTV_URL=https://xxxx.trycloudflare.com
STUDYONTV_TOKEN=sotv_xxxxxxxxxxxxxxxx
ALLOWED_CHAT_IDS=123456789
```

启动：

```bash
npx wrangler dev
```

模拟一条 Telegram 更新：

```bash
curl -X POST "http://127.0.0.1:8787/telegram" \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: test-secret" \
  -d '{"message":{"chat":{"id":123456789},"from":{"id":123456789},"text":"/status"}}'
```

## 风险与限制

- **公网入口**：Worker 地址和临时隧道地址都是公网可访问的。Worker 会校验 `X-Telegram-Bot-Api-Secret-Token`，设备端还会校验 Bearer 令牌，但隧道本身仍暴露了电视的 8099。长期使用建议用 Cloudflare 命名隧道配合 Access 策略，或把隧道限制在可信网络。
- **令牌等同权限**：控制令牌可以解锁和暂停设备，只保存在 Worker secret 里。怀疑泄露时，在家长面板点「吊销」后重新生成，并更新 `STUDYONTV_TOKEN`。
- **机器人令牌**：拿到 `TELEGRAM_BOT_TOKEN` 的人可以重新设置 webhook 并接管机器人，同样只能放进 Worker secret。`TELEGRAM_WEBHOOK_SECRET` 必须与 `setWebhook` 时一致，否则 Worker 会拒绝所有请求。
- **限流**：设备端对集成令牌合计限流 240 次/分钟，Telegram 本身也有消息频率限制。这个 Worker 没有再额外限流，正常家庭使用远低于上限；不要把机器人公开给大群。
- **明文与局域网**：电视到 cloudflared 这一段仍是明文 HTTP，只应在家庭局域网内使用。
- **HDMI / 机顶盒**：本项目只能拦电视内的前台 App，切到 HDMI 或直播信号源后管不到，需要 Home Assistant 等设备层方案配合。
