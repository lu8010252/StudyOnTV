# n8n 自动化模板

三个可以直接导入 n8n 的工作流，用定时任务控制电视端「题了个题」：

| 文件 | 触发时间 | 作用 | 需要的设备权限 |
|---|---|---|---|
| `lock-2000.json` | 每天 20:00 | 调用 `/api/remote`，立即锁定电视 | 控制 |
| `daily-summary-2100.json` | 每天 21:00 | 读取 `/api/state`，汇总今日学习；可发 Telegram | 只读 |
| `reward-credit.json` | 每天 21:30 | 连续 5 天完成答题组数后，`credit 30` 分钟 | 只读 + 控制 |

时间按 `Asia/Shanghai` 写在每个工作流的 `settings.timezone` 里；如果 n8n 实例不在中国时区，可以改成自己的时区，或相应调整 cron 表达式。三个工作流导入后默认是 **未激活** 状态，测试通过再打开。

## 一、导入

在 n8n 里打开 **Workflows**，右上角菜单选择 **Import from File**（或 **Import from Clipboard**），选中 `integrations/n8n/` 下的 JSON 文件。三个文件可以分别导入。

导入后需要改两处地方：

1. **设备地址**：把 HTTP Request 节点里的 `http://192.168.1.20:8099` 改成电视的实际 IP。建议在路由器里给电视做 DHCP 地址保留；
2. **凭据**：给每个 HTTP Request 节点选择下面创建的「HTTP Header Auth」凭据。

如果 n8n 跑在 Docker 里，`localhost` 指的是容器自己，必须写局域网 IP；n8n Cloud 无法直接访问家里的局域网，需要像 Telegram 模板那样用 cloudflared 把 8099 暴露成 HTTPS，并把地址换成隧道地址。

## 二、创建凭据

### HTTP Header Auth（设备令牌）

在 **Credentials → New → HTTP Header Auth** 里创建：

| 字段 | 值 |
|---|---|
| Name | `StudyOnTV 集成令牌`（名字随意） |
| Header Name | `Authorization` |
| Header Value | `Bearer sotv_xxxxxxxxxxxxxxxx` |

注意 `Bearer` 和令牌之间有一个空格，`Bearer` 大小写敏感。工作流里引用的是名为「StudyOnTV 集成令牌」的凭据，导入后如果显示红色「Credential not found」，点开节点重新选择刚创建的凭据即可。

只使用家长面板生成的 **集成令牌**，不要把管理密码写进 n8n 凭据：

- `lock-2000.json` 和 `reward-credit.json` 需要 **控制**；
- `daily-summary-2100.json` 和奖励判断需要 **只读**；
- 三套都使用时，最简单是生成一张同时勾选 **只读 + 控制** 的令牌。

### Telegram（可选）

`daily-summary-2100.json` 最后一个节点默认是禁用状态。要用的话：

1. 在 **Credentials → New → Telegram** 里填入 BotFather 给的机器人令牌；
2. 打开「发送到 Telegram（可选）」节点，选择该凭据；
3. 把 `chatId` 从 `123456789` 改成你的 chat id（获取方法见 `../telegram/README.md`）；
4. 启用节点（右键 → Enable），再激活工作流。

不配置 Telegram 也能用：前面的「整理今日汇总」节点会产出汇总文本，可以在 n8n 执行记录里查看，或自行接邮件、企业微信、Slack 等节点。

## 三、工作流说明

### 每天 20:00 自动锁定

```text
Schedule Trigger（cron 0 20 * * *）
  → HTTP Request POST /api/remote
      {"action":"lock","minutes":0}
```

`minutes: 0` 表示一直暂停，直到在家长面板点取消，或调用 `cancel`。想改成锁定 60 分钟后自动恢复，把请求体改成 `{"action":"lock","minutes":60}`。手动运行一次也会立即锁定，适合先测试。

### 每天 21:00 学习汇总

```text
Schedule Trigger（cron 0 21 * * *）
  → HTTP Request GET /api/state
  → Code：整理今日汇总
  → Telegram（可选，默认禁用）
```

汇总内容：当前状态、剩余可看时间、今日已看分钟数、答题正确数 / 总数 / 准确率、完成答题组数。判断状态用到的字段：

- `today.usedMinutes`、`today.answered`、`today.correct`、`today.accuracy`；
- `state.roundsDoneToday`；
- `learning.remainingSeconds`、`learning.reason`。

### 连续 5 天达标奖励 30 分钟

```text
Schedule Trigger（cron 30 21 * * *）
  → HTTP Request GET /api/state
  → Code：计算连续天数
  → IF：连续天数满 5 天的倍数，且今天还没奖励
      → 真：HTTP Request POST /api/remote {"action":"credit","minutes":30}
      → 真：Code：记录今天已奖励
      → 假：结束
```

判断标准是「当天 `roundsDoneToday >= config.roundsPerDay`」，也就是完成了规则要求的答题组数。连续天数用 n8n 的工作流静态数据（`$getWorkflowStaticData('global')`）保存：

- 同一天只累计一次，手动重跑不会把天数刷高；
- 中间断一天就清零；
- 每满 5 天奖励一次 30 分钟，第 10 天、第 15 天会再次奖励；
- 奖励成功后记录 `rewardedDate`，同一天重跑不会重复加分。

`config.roundsPerDay` 来自 `/api/state` 里的规则，不需要在 n8n 里重复配置。`credit` 只调整今天的奖励余额，不改动规则里的 `dailyMinutes`，跨天清零；原有时段、强制休息和家长锁定仍然生效。

注意：静态数据只有在工作流 **激活并正式执行** 时才会写入数据库。手动执行可以验证请求能否打通，但不会累计连续天数；上线前请先确认定时触发正常。工作流默认 21:30 运行，如果孩子经常在这个时间之后才完成答题，可以把 cron 调晚一些。

## 四、测试与排错

1. 先点工作流右上角的 **Execute Workflow** 手动跑一次；
2. 在 HTTP Request 节点看返回：成功时 `/api/remote` 返回 `{"learning":{...}}`，`/api/state` 返回完整状态；
3. 确认无误后，把工作流右上角的 **Active** 打开。

| 现象 | 处理 |
|---|---|
| `401` | 凭据里的令牌错了或已吊销，重新生成并更新 Header Auth |
| `403` | 令牌权限不足：锁定/奖励需要「控制」，读取状态需要「只读」 |
| `429` | 设备端限流 240 次/分钟，降低频率或稍后再试 |
| `ECONNREFUSED` / 超时 | IP 不对、电视 App 没运行、n8n 和电视不在同一网络，或 Docker 里写了 `localhost` |
| Telegram 节点报凭据错误 | 没选 Telegram 凭据，或 chat id 不对 |

## 五、安全提醒

- n8n 凭据里只放集成令牌，**不要放家长管理密码**；令牌可以单独吊销；
- 工作流 JSON 不包含任何真实令牌，导入后凭据保存在 n8n 自己的数据库里，注意备份和访问控制；
- 不要把 8099 端口映射到公网。需要远程运行时用 cloudflared、Tailscale 等私有通道；
- n8n 的定时任务依赖实例持续运行；Docker 或 NAS 休眠后可能错过执行时间，建议开启实例常驻。
