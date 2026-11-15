# 第三方自动化模板

这里放的是把「题了个题」接进其它自动化工具的模板。所有模板都只使用设备已有的局域网 HTTP 接口，不需要改 App，也不需要把家长管理密码交出去。

| 模板 | 位置 | 适用场景 |
|---|---|---|
| iOS / macOS 快捷指令 | [`shortcuts/`](./shortcuts/README.md) | 手机、手表或 Mac 上一键临时解锁、查看剩余时间、立即锁定；在家直连，在外需要自建私有通道。 |
| Telegram 机器人 | [`telegram/`](./telegram/README.md) | 家长在 Telegram 聊天里发 `/unlock 30`、`/lock`、`/status`；需要 Cloudflare Worker 和 cloudflared 隧道。 |
| n8n 自动化 | [`n8n/`](./n8n/README.md) | 已有 n8n 的家庭做定时锁定、每晚学习汇总、达标奖励；导入 JSON 即可。 |
| Home Assistant 集成 | [`home-assistant/README.md`](./home-assistant/README.md) | 需要设备层联动（HDMI / 直播信号源、音量上限、到点关机）时使用；与 App 的内容层分工。现有说明见 [`docs/home-assistant.md`](../docs/home-assistant.md)。 |

## 先准备集成令牌

无论用哪个模板，都先在家长面板 **设置 → 家庭自动化** 里：

1. 勾选需要的权限：**只读**（读学习状态）和 / 或 **控制**（解锁、暂停、奖励）；
2. 点「生成令牌」，复制 `sotv_` 开头的令牌，存进密码管理器；
3. 保持「启用接口」打开。

令牌只在生成时显示一次；关闭开关或点「吊销」后立即失效，重新生成也会让旧令牌失效。集成令牌不能修改规则、题库、密码或权限本身。

> 不要把家长管理密码（`X-Admin-Key`）写进任何第三方模板。管理密码等同家长身份，而集成令牌可以单独吊销。

## 接口速查

- 地址：`http://<电视IP>:8099`
- 鉴权：`Authorization: Bearer <集成令牌>`
- 限流：合计 240 次 / 分钟，超出返回 `429`
- 只开放局域网；远程使用请通过 cloudflared、Tailscale、WireGuard 等私有通道，不要把 8099 映射到公网

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/state` | 只读 | 规则、今日统计、学习状态 |
| GET | `/api/device` | 只读 | 设备名、版本、开机自启状态 |
| POST | `/api/remote` | 控制 | 解锁 / 锁定 / 取消 / 持续关闭 / 调整今日余额 |

`POST /api/remote` 的请求体：

```json
{"action": "unlock", "minutes": 30}
```

| `action` | `minutes` | 说明 |
|---|---|---|
| `unlock` | 1–1440 | 临时解锁，不扣学习余额，到点自动恢复规则 |
| `lock` | 0–10080 | 立即暂停；`0` 表示一直暂停到手动取消 |
| `cancel` | 不需要 | 取消当前的临时解锁或暂停 |
| `indefinite` | 不需要 | 持续关闭管控，直到手动取消 |
| `credit` | -480–480 的非零整数 | 增加或扣减今天的奖励余额，跨天清零 |

`GET /api/state` 里常用的字段：

- `today.usedMinutes`、`today.answered`、`today.correct`、`today.accuracy`：今日观看与答题统计；
- `roundsDoneToday`：今天完成的答题组数；
- `learning.remainingSeconds`：剩余可看秒数，`-1` 表示不限时或持续锁定；
- `learning.reason`：锁定原因，`quota` / `rounds` / `schedule` / `timeup` / `locked` / `rest` / `feedback`，空字符串表示可以看；
- `learning.nextAtText`：下次可用的时间文本。

更完整的接口说明见 [`docs/integration.md`](../docs/integration.md)。

## 常见返回

| 状态 | 含义 | 处理 |
|---|---|---|
| `401` | 没有令牌，或令牌错误 / 已吊销 | 重新生成并更新模板里的令牌 |
| `403` | 令牌有效，但没有该接口需要的权限 | 在家长面板补勾「只读」或「控制」 |
| `429` | 请求过于频繁 | 等一分钟，或降低轮询频率 |

## 安全提醒

- **只开放局域网**：HTTP 是明文，不要做端口映射。需要在外访问时，用带鉴权的私有隧道，并优先使用只读令牌做展示。
- **令牌等同所选权限**：控制令牌可以暂停或解锁设备，只放在可信设备上；怀疑泄露就立即吊销。
- **HDMI / 机顶盒拦不住**：App 只能看到 Android 前台应用，切到 HDMI、直播信号源时管不到，这部分需要 Home Assistant 等设备层方案配合。
- **设备休眠或 App 被停止**：8099 会暂时不响应，模板里的请求会超时；重新打开 App 后恢复。
