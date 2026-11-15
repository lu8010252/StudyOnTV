# StudyOnTV for Home Assistant

把「题了个题」电视端的学习状态接进 Home Assistant。这是一个只读的自定义集成（custom integration），通过设备自带的局域网 HTTP 接口轮询数据，用于面板展示和周报统计。

- 只读：只请求 `GET /api/state` 和 `GET /api/device`，不会修改规则、题库、密码或权限。
- 不包含自动发现：设备没有 mDNS / SSDP 广播，需要在 HA 里手动填写电视的 IP 和端口。
- 不做设备层控制：HDMI、音量、关机等仍由 HA 自己的自动化负责。
- 只用 Home Assistant 内置依赖，`requirements` 为空；需要 Home Assistant 2024.11 或更新版本。

## 一、先在电视上生成令牌

1. 打开家长面板，进入 **设置 → 家庭自动化**；
2. 权限勾选 **只读**（学习状态、设备信息）。如果以后还要用 Node-RED 等调用 `/api/remote`，再单独勾 **控制**；
3. 点「生成令牌」，复制只显示一次的令牌（形如 `sotv_xxxxxxxxxxxxxxxx`），保存到密码管理器；
4. 确认「启用接口」处于打开状态。

默认使用 `8099` 端口；如果 8099 被占用，设备会退到 `8090`，以家长面板上显示的地址为准。

> 不要用家长的管理密码代替集成令牌。只读令牌即使泄露也不能解锁或暂停设备，并且可以随时吊销。

## 二、安装

### 方式一：手动复制（推荐）

1. 找到 Home Assistant 配置目录。HA OS / Supervisor 是 `/config`，容器安装通常是映射到容器内的 `/config` 目录；该目录里应有 `configuration.yaml`；
2. 把本目录下的 `custom_components/studyontv` 整个文件夹复制过去，最终结构为：

   ```
   <config>/
   └── custom_components/
       └── studyontv/
           ├── __init__.py
           ├── binary_sensor.py
           ├── config_flow.py
           ├── const.py
           ├── coordinator.py
           ├── manifest.json
           ├── sensor.py
           ├── strings.json
           └── translations/
   ```

3. 重启 Home Assistant；
4. 打开 **设置 → 设备与服务 → 添加集成**，搜索 **StudyOnTV**；
5. 填写主机、端口和令牌，提交。

### 方式二：HACS（拆成独立仓库）

HACS 只从仓库根目录的 `custom_components/` 安装集成。本仓库把它放在 `integrations/home-assistant/` 下，因此不能把主仓库直接加进 HACS。可以这样处理：

1. 把 `integrations/home-assistant` 复制成一个独立仓库，根目录保留 `custom_components/`、`hacs.json` 和本 README；
2. 或者从主仓库拆出该目录：

   ```bash
   git subtree split --prefix=integrations/home-assistant -b studyontv-ha
   git push https://github.com/<your-github-user>/studyontv-ha.git studyontv-ha:main
   ```

3. 在 HACS 中打开 **集成** 页面，右上角三点 → **自定义仓库**，填入该仓库 URL，类别选 **Integration**；
4. 搜索 **StudyOnTV** 并下载，然后重启 Home Assistant；
5. 再到 **设置 → 设备与服务 → 添加集成 → StudyOnTV** 完成配置。

`hacs.json` 已声明最低 Home Assistant 版本为 2024.11.0。

## 三、配置字段

| 字段 | 默认值 | 说明 |
|---|---|---|
| 主机 | 无 | 电视的 IP 或主机名。不要带 `http://`、路径或端口；集成会自动去掉误粘贴的协议和结尾斜杠 |
| 端口 | `8099` | 设备面板的 HTTP 端口；如果设备退到 8090，请填 8090 |
| 集成令牌 | 无 | `sotv_` 开头的令牌，必须包含 **只读** 权限（校验用的 `/api/device` 需要读权限） |

- 配置条目的 `unique_id` 是 `主机:端口`，同一台设备不能重复添加；
- 令牌如果误粘贴成 `Bearer sotv_xxxxxxxxxxxxxxxx`，集成会自动去掉前缀；
- 添加时集成会请求一次 `GET /api/device` 校验令牌，失败不会创建设置条目。

### 选项：扫描间隔

在集成卡片上点 **配置**，可以修改扫描间隔：

- 范围 10–600 秒，默认 30 秒；
- 每次轮询发 2 个请求（`/api/state` 和 `/api/device`）；
- 设备对所有集成请求合计限流 240 次/分钟，默认间隔远低于上限；
- 修改后集成会自动重载，不需要重启 HA。

电视是低配设备，展示用途建议保持 30–60 秒，不建议低于 10 秒。

## 四、实体

实体 ID 会根据设备名自动生成，实际值可能不同；在 **开发者工具 → 状态** 里搜索 `studyontv` 即可找到。下面是实体名称与数据来源的对应关系。

| 实体名称 | API 字段 | 说明 |
|---|---|---|
| 剩余可用分钟 | `learning.remainingSeconds` ÷ 60 | 本次答题解锁、今天还能使用的时长；保留 1 位小数；设备返回 `-1`（不限时或持续锁定）时显示为 unknown |
| 今日已用分钟 | `today.usedMinutes` | 今天已经观看的分钟数，保留 1 位小数 |
| 学习状态 | `learning.reason` | 枚举，见下表；空字符串映射为 `available` |
| 今日答题组数 | `roundsDoneToday` | 今天完成的答题组数 |
| 题库题数 | `questions` | 启用中的题目数量 |
| 下次可用时间 | `learning.nextAvailableAt` | 时间戳（设备返回毫秒，集成转换为 HA 时间）；`0` 表示没有，显示为 unknown |
| 设备在线 | 轮询是否成功 | `on` = 最近一次轮询成功；`off` = 轮询失败 |

学习状态的取值：

| 值 | 中文文案 | 含义 |
|---|---|---|
| `available` | 可以看 | 当前没有限制（原因为空字符串时也归到这里） |
| `quota` | 今日配额已用完 | 达到当天观看上限 |
| `rounds` | 今日答题组数已用完 | 达到当天答题组数上限，且没有剩余时长 |
| `schedule` | 不在可用时段 | 当前不在允许观看的时段 |
| `timeup` | 可看时长已用完 | 余额用完 |
| `locked` | 家长已暂停 | 家长从面板或集成接口暂停了设备 |
| `rest` | 强制休息中 | 强制休息倒计时 |
| `feedback` | 待查看答题反馈 | 等待查看上一题的反馈 |
| `unknown` | 未知状态 | 设备返回了本集成不认识的 reason |

> `剩余可用分钟` 只对应答题奖励余额。若你在设备上启用了「每日免费时长」，免费部分在 `learning.freeQuotaSeconds` / `learning.bootFreeSeconds` 中，本集成暂未单独建实体。

## 五、安全提醒

- **默认只开只读**。本集成不需要「控制」权限；不要把带控制权限的令牌交给不必要的第三方。
- **不要把管理密码交给任何第三方系统**。集成令牌和家长密码是两套凭据；管理密码可以改规则、题库和权限，集成令牌不能。
- 令牌保存在 HA 的配置条目（`.storage/core.config_entries`）中。HA 备份、诊断包和截图可能包含它，请按敏感信息处理。
- 接口是明文 HTTP，只应在可信局域网内使用；不要做端口映射或公网转发。
- 在设备端关闭「启用接口」、点「吊销」或重新生成令牌，旧令牌会立即失效。
- 令牌只保存 SHA-256，设备端不保留明文；但 Home Assistant 一侧需要保存明文才能发起请求。

## 六、故障排查

### `invalid_auth`

- 令牌被吊销、重新生成，或设备端「启用接口」被关闭；
- 复制时少了字符，或用了别的令牌；
- 生成令牌时没有勾 **只读**：`/api/device` 会返回 403，集成同样提示 `invalid_auth`；
- 管理密码不能填到这里。

处理：在设备端重新生成只读令牌，然后在 HA 中删除该集成并重新添加（当前版本没有单独的重新认证流程）。

### `cannot_connect`

- 主机或端口填错。先确认家长面板显示的地址，再在 HA 所在机器上测试：

  ```bash
  curl "http://<电视IP>:8099/api/device" \
    -H "Authorization: Bearer sotv_xxxxxxxxxxxxxxxx"
  ```

- 电视 IP 变了。建议在路由器上给电视设置 DHCP 固定租约；
- 题了个题 App 被系统清理或手动关闭，8099 不再响应；重新打开 App 后恢复；
- HA 和电视不在同一网段，或 Wi-Fi 开了 AP 隔离 / 访客网络隔离；
- HA 跑在 Docker 里且网络模式特殊，容器可能访问不到局域网，可先用 `docker exec` 在容器内执行上面的 `curl`；
- 设备只提供 HTTP，不支持 HTTPS；主机字段也不要带端口（端口单独填）。

### `rate_limited`

设备对所有集成请求合计限流 240 次/分钟，超出返回 429。把扫描间隔调大；如果同一令牌还被 Node-RED 或其它脚本使用，需要一起降频。

### 实体变为 unavailable / 设备在线变为 off

- 首次添加时如果连不上，HA 会重试，日志里会出现 `ConfigEntryNotReady`；
- 添加成功后掉线，协调器会保留旧数据并把「设备在线」置为 `off`，其它实体按 HA 规则变为 `unavailable`；
- 检查 **设置 → 系统 → 日志**，搜索 `studyontv` 或 `StudyOnTV`。

### 下次可用时间显示 unknown

`learning.nextAvailableAt` 为 `0` 表示没有下一次可用的时间点（例如一直暂停到手动取消，或没有配额限制）。如果设备系统时间不对，时间戳也会偏移，请先校准电视时间。

### 找不到设备 / 没有自动发现

本项目没有 mDNS 广播，添加集成时不会自动出现设备，必须手动输入 IP。也不要尝试在 HACS 中添加主仓库，`custom_components` 不在仓库根目录。

## 七、移除

1. 在 HA 中删除 StudyOnTV 集成；
2. 删除 `<config>/custom_components/studyontv` 文件夹并重启 HA；
3. 在设备端「家庭自动化」里点「吊销」，让令牌立即失效。

## 文件结构

```
integrations/home-assistant/
├── custom_components/
│   └── studyontv/
│       ├── __init__.py
│       ├── binary_sensor.py
│       ├── config_flow.py
│       ├── const.py
│       ├── coordinator.py
│       ├── manifest.json
│       ├── sensor.py
│       ├── strings.json
│       └── translations/
│           ├── en.json
│           └── zh-Hans.json
├── hacs.json
└── README.md
```
