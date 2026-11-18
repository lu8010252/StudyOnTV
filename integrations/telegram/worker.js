/**
 * 「题了个题」Telegram 机器人（Cloudflare Worker，零依赖 ES module）
 *
 * 消息流：
 *   Telegram → 本 Worker（校验 secret token + chat 白名单）→ cloudflared 隧道 → 电视 8099
 *
 * 需要的环境变量（用 wrangler secret put 写入，不要写进 wrangler.toml）：
 *   TELEGRAM_BOT_TOKEN        BotFather 给的机器人令牌
 *   TELEGRAM_WEBHOOK_SECRET   setWebhook 时设置的 secret_token
 *   STUDYONTV_URL             cloudflared 隧道地址，例如 https://xxx.trycloudflare.com
 *   STUDYONTV_TOKEN           家长面板生成的集成令牌（sotv_ 开头）
 *   ALLOWED_CHAT_IDS          允许使用的 chat id，逗号或空格分隔
 */

const HELP_TEXT = [
  "可用命令：",
  "/unlock 30  临时解锁 30 分钟（1–1440，默认 30）",
  "/lock       立即锁定，直到手动取消",
  "/cancel     取消当前的临时解锁或暂停",
  "/credit 10  奖励或扣减今日时长（-480–480 非零整数，如 10 或 -10）",
  "/status     查看剩余时间和今日学习统计",
].join("\n");

const REASONS = {
  "": "可以观看",
  locked: "家长锁定中",
  rest: "强制休息中",
  feedback: "等待批改结果",
  quota: "今日配额已用完",
  schedule: "不在可用时段",
  rounds: "今日答题组数已用完",
  timeup: "可看时长已用完",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/telegram" || request.method !== "POST") {
      return jsonResponse({ ok: false, message: "Not Found" }, 404);
    }

    if (!env.TELEGRAM_WEBHOOK_SECRET) {
      return jsonResponse({ ok: false, message: "Worker 未配置 TELEGRAM_WEBHOOK_SECRET" }, 500);
    }
    const secretHeader = request.headers.get("X-Telegram-Bot-Api-Secret-Token");
    if (!safeEqual(secretHeader, env.TELEGRAM_WEBHOOK_SECRET)) {
      return jsonResponse({ ok: false, message: "Webhook 校验失败" }, 401);
    }

    let update;
    try {
      update = await request.json();
    } catch {
      return jsonResponse({ ok: false, message: "请求体不是合法 JSON" }, 400);
    }

    const message = update && (update.message || update.edited_message);
    if (!message || !message.chat || (message.from && message.from.is_bot)) {
      return jsonResponse({ ok: true, ignored: true });
    }

    const chatId = String(message.chat.id);
    if (!allowedChatIds(env).has(chatId)) {
      // 不回复未授权会话，避免任何人拿到机器人后反复试探。
      return jsonResponse({ ok: true, ignored: true });
    }

    const text = typeof message.text === "string" ? message.text.trim() : "";
    if (!text) return jsonResponse({ ok: true, ignored: true });

    let reply;
    try {
      reply = await handleCommand(env, text);
    } catch (error) {
      reply = describeError(error);
    }

    try {
      await sendTelegram(env, chatId, reply);
    } catch (error) {
      console.error("回复 Telegram 失败", error && error.message ? error.message : error);
    }
    return jsonResponse({ ok: true });
  },
};

async function handleCommand(env, text) {
  const parts = text.split(/\s+/);
  // Telegram 群组里命令可能带 @BotName 后缀。
  const command = (parts[0] || "").split("@")[0].toLowerCase();

  switch (command) {
    case "/start":
    case "/help":
      return HELP_TEXT;

    case "/unlock": {
      const raw = parts[1];
      const minutes = raw === undefined ? 30 : Number(raw);
      if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) {
        return "用法：/unlock 30\n分钟数必须是 1–1440 的整数。";
      }
      const data = await deviceFetch(env, "/api/remote", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "unlock", minutes }),
      });
      const left = data && data.learning && data.learning.override
        ? data.learning.override.remainingSeconds
        : undefined;
      const leftText = typeof left === "number" && left >= 0 ? `，剩余 ${formatDuration(left)}` : "";
      return `已临时解锁 ${minutes} 分钟${leftText}。到点会自动恢复原有规则。`;
    }

    case "/lock":
      await deviceFetch(env, "/api/remote", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "lock", minutes: 0 }),
      });
      return "已立即锁定，直到在家长面板或这里发送 /cancel。";

    case "/cancel":
      await deviceFetch(env, "/api/remote", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "cancel" }),
      });
      return "已取消当前的临时解锁 / 暂停，按当前规则恢复管控。";

    case "/credit": {
      const raw = parts[1];
      const minutes = raw === undefined ? NaN : Number(raw);
      if (!Number.isInteger(minutes) || minutes === 0 || minutes < -480 || minutes > 480) {
        return "用法：/credit 10 或 /credit -10\n分钟数必须是 -480 到 480 之间的非零整数。";
      }
      const data = await deviceFetch(env, "/api/remote", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "credit", minutes }),
      });
      const remaining = data && data.learning ? data.learning.remainingSeconds : undefined;
      const remainingText = typeof remaining === "number" ? `，剩余可看 ${formatDuration(remaining)}` : "";
      const actionText = minutes > 0 ? `已奖励 ${minutes} 分钟` : `已扣减 ${Math.abs(minutes)} 分钟`;
      return `${actionText}${remainingText}。`;
    }

    case "/status": {
      const state = await deviceFetch(env, "/api/state", { method: "GET" });
      return formatState(state);
    }

    default:
      return `不认识这个命令。\n\n${HELP_TEXT}`;
  }
}

function formatState(state) {
  const learning = (state && state.learning) || {};
  const today = (state && state.today) || {};
  const rounds = Number(
    state && state.roundsDoneToday !== undefined
      ? state.roundsDoneToday
      : (learning.roundsDoneToday || 0),
  );
  const used = Math.round(Number(today.usedMinutes || 0));
  const answered = Number(today.answered || 0);
  const correct = Number(today.correct || 0);
  const accuracy = Number(today.accuracy || 0);

  const children = Array.isArray(state && state.children) ? state.children : [];
  const activeChildId = state && typeof state.activeChild === "string" ? state.activeChild : "";
  const activeChildItem =
    children.find((item) => item && item.id === activeChildId) ||
    children.find((item) => item && item.active);
  const activeChildName =
    activeChildItem && typeof activeChildItem.name === "string" ? activeChildItem.name.trim() : "";

  const lines = [];
  if (activeChildName) lines.push(`当前孩子：${activeChildName}`);
  lines.push(
    `学习状态：${reasonText(learning.reason)}`,
    `剩余可看：${formatDuration(learning.remainingSeconds)}`,
    `今日已看：${used} 分钟`,
    `今日答题：答对 ${correct} / ${answered} 题（准确率 ${accuracy}%）`,
    `完成答题组：${rounds} 组`,
  );
  if (learning.nextAtText) lines.push(`下次可用：${learning.nextAtText}`);

  const override = learning.override;
  if (override && override.mode === "unlock") {
    lines.push(`临时解锁剩余：${formatDuration(override.remainingSeconds)}`);
  } else if (override && override.mode === "lock") {
    lines.push("家长锁定：等待 /cancel 或到点解除");
  } else if (override && override.mode === "indefinite") {
    lines.push("已持续关闭管控：等待 /cancel");
  }
  return lines.join("\n");
}

function formatDuration(seconds) {
  if (typeof seconds !== "number" || Number.isNaN(seconds)) return "未知";
  if (seconds < 0) return "不限时";
  const total = Math.max(0, Math.round(seconds));
  if (total < 60) return `${total} 秒`;
  const hours = Math.floor(total / 3600);
  const minutes = Math.ceil((total % 3600) / 60);
  if (hours > 0 && minutes > 0) return `${hours} 小时 ${minutes} 分钟`;
  if (hours > 0) return `${hours} 小时`;
  return `${minutes} 分钟`;
}

function reasonText(reason) {
  if (!reason) return REASONS[""];
  return REASONS[reason] || `未知状态（${reason}）`;
}

async function deviceFetch(env, path, init = {}) {
  const base = String(env.STUDYONTV_URL || "").trim().replace(/\/+$/, "");
  const token = String(env.STUDYONTV_TOKEN || "").trim();
  if (!base) {
    throw new AppError("Worker 未配置 STUDYONTV_URL，请填入 cloudflared 提供的 HTTPS 地址。");
  }
  if (!token) {
    throw new AppError("Worker 未配置 STUDYONTV_TOKEN，请填入家长面板生成的集成令牌。");
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  let response;
  try {
    response = await fetch(`${base}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init.headers || {}),
        Authorization: token.startsWith("Bearer ") ? token : `Bearer ${token}`,
      },
      signal: controller.signal,
    });
  } catch (error) {
    if (error && error.name === "AbortError") {
      throw new AppError("连接电视超时（10 秒）。请确认电视开着、App 在运行，并且 cloudflared 隧道在线。");
    }
    throw new AppError(`连不上电视：${base}。请确认 STUDYONTV_URL 是 cloudflared 提供的 HTTPS 隧道地址，并且隧道在线。`);
  } finally {
    clearTimeout(timer);
  }

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!response.ok) throw new DeviceError(response.status, data);
  if (data === null) {
    throw new AppError("电视返回了非 JSON 内容，请检查 STUDYONTV_URL 是否指向 8099 端口。");
  }
  return data;
}

async function sendTelegram(env, chatId, text) {
  const token = String(env.TELEGRAM_BOT_TOKEN || "");
  if (!token) throw new AppError("Worker 未配置 TELEGRAM_BOT_TOKEN。");
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text,
      disable_web_page_preview: true,
    }),
  });
  if (!response.ok) {
    const detail = await response.text();
    console.error("Telegram sendMessage 失败", response.status, detail);
    throw new Error("Telegram 回复失败");
  }
}

function allowedChatIds(env) {
  return new Set(
    String(env.ALLOWED_CHAT_IDS || "")
      .split(/[\s,]+/)
      .map((value) => value.trim())
      .filter(Boolean),
  );
}

function describeError(error) {
  if (error instanceof DeviceError) {
    if (error.status === 401) {
      return "集成令牌无效或已吊销。请在家长面板重新生成令牌，并更新 Worker 的 STUDYONTV_TOKEN。";
    }
    if (error.status === 403) {
      return "集成令牌权限不足：控制命令需要「控制」权限，/status 需要「只读」权限。";
    }
    if (error.status === 429) {
      return "请求太频繁：设备端限流 240 次/分钟，请稍后再试。";
    }
    const detail = error.data && error.data.message ? `：${error.data.message}` : "";
    return `电视返回 ${error.status}${detail}`;
  }
  if (error instanceof AppError) return error.message;
  return "处理命令时出错，请稍后重试。";
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function safeEqual(a, b) {
  const left = new TextEncoder().encode(String(a || ""));
  const right = new TextEncoder().encode(String(b || ""));
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) diff |= left[i] ^ right[i];
  return diff === 0;
}

class AppError extends Error {
  constructor(message) {
    super(message);
    this.name = "AppError";
  }
}

class DeviceError extends Error {
  constructor(status, data) {
    super(`设备返回 ${status}`);
    this.name = "DeviceError";
    this.status = status;
    this.data = data;
  }
}
