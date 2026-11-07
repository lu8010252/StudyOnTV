# 家庭自动化联动（Home Assistant 等）

给局域网里的 Home Assistant、Node-RED 或自建脚本一个独立令牌，用来读取学习状态，或临时解锁 / 暂停设备。令牌和家长的「管理密码」是两套东西：不需要把管理密码交给其它系统，也可以随时吊销。

## 开启

1. 在家长面板打开 **设置 → 家庭自动化**；
2. 勾选权限：**只读**（学习状态、设备信息）和 / 或 **控制**（解锁、暂停）；
3. 点「生成令牌」，把只显示一次的令牌保存到 Home Assistant 的 `secrets.yaml` 或密码管理器；
4. 保持「启用接口」打开。

关闭开关或点「吊销」后，旧令牌立即失效。重新生成也会让旧令牌失效。

## 请求方式

- 只接受局域网访问，和面板使用同一个端口（默认 `8099`）；
- 请求头：`Authorization: Bearer <集成令牌>`；
- 限流：合计 240 次 / 分钟，超出返回 `429`；
- 令牌只保存 SHA-256，设备上不保留明文。

## 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/state` | 只读 | 规则、今日统计、学习状态 |
| GET | `/api/device` | 只读 | 设备名、版本、开机自启状态 |
| POST | `/api/remote` | 控制 | `unlock` / `lock` / `cancel` |

`POST /api/remote` 的请求体：

```json
{"action": "unlock", "minutes": 30}
```

- `unlock`：临时解锁 1–1440 分钟，不扣学习余额，到点自动恢复规则；
- `lock`：立即暂停，`minutes` 为 0 表示一直暂停到手动取消（最多 7 天）；
- `cancel`：取消当前的临时解锁或暂停。

集成令牌只能访问上表中的接口，不能修改规则、题库、密码或权限本身。

## 示例

读取状态：

```bash
curl "http://192.168.1.20:8099/api/state" \
  -H "Authorization: Bearer sotv_xxxxxxxxxxxxxxxx"
```

临时解锁 30 分钟：

```bash
curl -X POST "http://192.168.1.20:8099/api/remote" \
  -H "Authorization: Bearer sotv_xxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"action":"unlock","minutes":30}'
```

Home Assistant：

```yaml
# configuration.yaml
sensor:
  - platform: rest
    name: 题了个题
    resource: http://192.168.1.20:8099/api/state
    headers:
      Authorization: !secret studyontv_token
    value_template: "{{ value_json.learning.remainingSeconds }}"
    unit_of_measurement: 秒
    json_attributes:
      - learning
      - today
    scan_interval: 60

rest_command:
  studyontv_unlock:
    url: http://192.168.1.20:8099/api/remote
    method: POST
    headers:
      Authorization: !secret studyontv_token
      Content-Type: application/json
    payload: '{"action":"unlock","minutes":30}'
```

```yaml
# secrets.yaml
studyontv_token: "Bearer sotv_xxxxxxxxxxxxxxxx"
```

> 上面的 `Authorization` 需要写成 `Bearer sotv_...` 的完整值。

自动化示例：孩子完成当天任务后，家长在 Home Assistant 里点一下按钮，临时解锁 30 分钟。

```yaml
automation:
  - alias: 临时解锁电视 30 分钟
    trigger:
      - platform: state
        entity_id: input_boolean.studyontv_unlock
        to: "on"
    action:
      - service: rest_command.studyontv_unlock
      - delay: "00:00:05"
      - service: input_boolean.turn_off
        target:
          entity_id: input_boolean.studyontv_unlock
```

## 限制与安全

- **只开放局域网**：不要把 8099 端口映射到公网，也不要在路由器上做端口转发。
- **令牌等同所选权限**：只读令牌不能解锁；控制令牌可以暂停或解锁设备，请只保存在可信设备上。
- **HDMI / 机顶盒**：App 只能看到 Android 前台应用。电视切到 HDMI、直播信号源时，App 无法拦截，这部分仍需在 Home Assistant 或电视侧处理。
- **厂商限制**：系统强行停止 App、撤销权限或禁止后台运行时，接口可能暂时不可用；重新打开 App 后恢复。
- **令牌失效**：修改管理密码不会自动吊销集成令牌；不再使用时请手动点「吊销」。

## 常见返回

| 状态 | 含义 |
|---|---|
| `401` | 没有令牌，或令牌错误 / 已吊销 |
| `403` | 令牌有效，但没有该接口需要的权限 |
| `429` | 请求过于频繁，稍后再试 |
