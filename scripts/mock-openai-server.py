#!/usr/bin/env python3
"""本地 OpenAI 兼容 mock 服务，用于验证 #106（自定义 AI 接口）。

用法：
    python scripts/mock-openai-server.py --port 18080
    python scripts/mock-openai-server.py --port 18080 --slow 12   # 每次 chat/completions 延迟 12 秒

运行时也可以切换“慢响应”模式（不需要重启）：
    curl "http://127.0.0.1:18080/__control/slow?seconds=12"
    curl "http://127.0.0.1:18080/__control/slow?seconds=0"
    curl "http://127.0.0.1:18080/__control/slowModels?seconds=20"   # GET /v1/models 也变慢

实现的标准接口：
    POST /v1/chat/completions   OpenAI chat completions（支持/不支持 response_format 两种）
    POST /chat/completions      兼容直接填完整地址的服务
    GET  /v1/models             模型列表
    GET  /models                同上
    GET  /                     简单说明 + 请求计数
    GET  /__requests            最近的请求记录（JSON），便于核对设备到底发了什么

只依赖 Python 标准库。
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SLOW_SECONDS = 0
SLOW_MODELS_SECONDS = 0
REJECT_RESPONSE_FORMAT = False
LOCK = threading.Lock()
REQUESTS = []
COUNTER = {"chat": 0, "models": 0, "slow": 0}

ANSWER = {"score": 90, "feedback": "ok"}


def record(entry):
    with LOCK:
        REQUESTS.append(entry)
        del REQUESTS[:-100]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mock-openai] " + (fmt % args), flush=True)

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return {"_raw": raw.decode("utf-8", "replace")}

    def do_GET(self):
        global SLOW_SECONDS, SLOW_MODELS_SECONDS, REJECT_RESPONSE_FORMAT
        path = self.path.split("?", 1)[0]
        if path in ("/v1/models", "/models"):
            with LOCK:
                COUNTER["models"] += 1
                slow = SLOW_MODELS_SECONDS
            if slow > 0:
                print("[mock-openai] 慢响应模式（models）：延迟 %s 秒" % slow, flush=True)
                time.sleep(slow)
            record({"method": "GET", "path": self.path, "auth": self.headers.get("Authorization", ""),
                    "user_agent": self.headers.get("User-Agent", "")})
            return self._json({"object": "list", "data": [
                {"id": "mock-model", "object": "model", "owned_by": "mock"},
                {"id": "mock-vision", "object": "model", "owned_by": "mock"},
            ]})
        if path == "/__requests":
            with LOCK:
                return self._json({"counter": dict(COUNTER), "slowSeconds": SLOW_SECONDS,
                                   "slowModelsSeconds": SLOW_MODELS_SECONDS, "requests": list(REQUESTS)})
        if path == "/__control/rejectResponseFormat":
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            params = dict(part.split("=", 1) for part in query.split("&") if "=" in part)
            REJECT_RESPONSE_FORMAT = params.get("on", "1") in ("1", "true", "yes")
            return self._json({"ok": True, "rejectResponseFormat": REJECT_RESPONSE_FORMAT})
        if path == "/__control/slowModels":
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            params = dict(part.split("=", 1) for part in query.split("&") if "=" in part)
            SLOW_MODELS_SECONDS = max(0, int(params.get("seconds", "0")))
            return self._json({"ok": True, "slowModelsSeconds": SLOW_MODELS_SECONDS})
        if path == "/__control/slow":
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            params = dict(part.split("=", 1) for part in query.split("&") if "=" in part)
            SLOW_SECONDS = max(0, int(params.get("seconds", "0")))
            return self._json({"ok": True, "slowSeconds": SLOW_SECONDS})
        if path in ("/", "/health"):
            with LOCK:
                return self._json({"ok": True, "service": "mock-openai", "counter": dict(COUNTER),
                                   "slowSeconds": SLOW_SECONDS, "slowModelsSeconds": SLOW_MODELS_SECONDS,
                                   "endpoints": ["/v1/chat/completions", "/v1/models", "/__requests", "/__control/slow?seconds=N"]})
        return self._json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path in ("/v1/chat/completions", "/chat/completions"):
            with LOCK:
                COUNTER["chat"] += 1
                slow = SLOW_SECONDS
            record({"method": "POST", "path": self.path, "auth": self.headers.get("Authorization", ""),
                    "user_agent": self.headers.get("User-Agent", ""),
                    "model": body.get("model"), "response_format": body.get("response_format"),
                    "slowSeconds": slow, "messages": body.get("messages")})
            if REJECT_RESPONSE_FORMAT and "response_format" in body:
                # 模拟不支持 response_format 的服务商：返回 400，设备端应去掉该字段重试
                return self._json({"error": {"message": "response_format is not supported"}}, 400)
            if slow > 0:
                with LOCK:
                    COUNTER["slow"] += 1
                print("[mock-openai] 慢响应模式：延迟 %s 秒" % slow, flush=True)
                time.sleep(slow)
                if not self._client_alive(slow):
                    # 客户端已超时断开；服务端仍然返回，写失败时忽略
                    pass
            # 兼容不支持 response_format 的服务商分支：
            # 请求里带了 response_format 就按 JSON 模式返回；不带（或服务商忽略）也返回同样的内容。
            content = json.dumps(ANSWER, ensure_ascii=False)
            return self._json({
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": body.get("model") or "mock-model",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30},
            })
        return self._json({"error": {"message": "not found"}}, 404)

    def _client_alive(self, waited):
        # 尽力探测：真实客户端已断开时 write 会抛异常，由 _json 外层吞掉
        return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--slow", type=int, default=0, help="chat/completions 延迟秒数（0=关闭）")
    parser.add_argument("--slow-models", type=int, default=0, help="GET /v1/models 延迟秒数（0=关闭）")
    args = parser.parse_args()
    global SLOW_SECONDS, SLOW_MODELS_SECONDS, REJECT_RESPONSE_FORMAT
    SLOW_SECONDS = args.slow
    SLOW_MODELS_SECONDS = args.slow_models
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("[mock-openai] listening on http://%s:%d (slow=%ss)" % (args.host, args.port, SLOW_SECONDS), flush=True)
    print("[mock-openai] POST /v1/chat/completions  GET /v1/models  GET /__requests  GET /__control/slow?seconds=N", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
