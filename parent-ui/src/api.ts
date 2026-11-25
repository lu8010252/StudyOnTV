export const SESSION_STORAGE_KEY = 'sotv-panel-session';

let previewMode = false;
export function enablePreview() {
  previewMode = true;
}

let host = typeof location !== 'undefined' ? location.host : '';
let scheme: 'http:' | 'https:' =
  typeof location !== 'undefined' && location.protocol === 'https:' ? 'https:' : 'http:';
let explicitHttps = false;
let token = '';

export function setHost(value: string) {
  const raw = value.trim();
  const hasHttps = /^https:\/\//i.test(raw);
  const hasHttp = /^http:\/\//i.test(raw);
  const clean = raw.replace(/^https?:\/\//i, '').replace(/\/.*$/, '');
  if (!clean || !/^[\w.:[\]-]+$/.test(clean)) {
    throw new Error('请填写有效的设备地址');
  }
  host = clean;
  explicitHttps = hasHttps;
  if (hasHttps) {
    scheme = 'https:';
  } else if (hasHttp) {
    scheme = 'http:';
  } else if (typeof location !== 'undefined' && clean === location.host && location.protocol === 'https:') {
    scheme = 'https:';
  } else {
    scheme = 'http:';
  }
}

export function getHost() {
  if (explicitHttps && (typeof location === 'undefined' || host !== location.host)) {
    return `https://${host}`;
  }
  return host;
}

export function getApiOrigin(): string {
  const sameHost = typeof location !== 'undefined' && (!host || host === location.host);
  if (sameHost) {
    return `${location.protocol}//${location.host}`;
  }
  return `${scheme}//${host}`;
}

export function setToken(value: string) {
  token = value;
  try {
    if (typeof localStorage !== 'undefined') {
      if (value) {
        const storedHost = getHost();
        if (storedHost) {
          localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ host: storedHost, token: value }));
        }
      } else {
        localStorage.removeItem(SESSION_STORAGE_KEY);
      }
    }
  } catch {}
}

export function resolveMediaUrl(src: string): string {
  if (!src) return '';
  if (src.startsWith('/api/media/')) {
    if (host && (typeof location === 'undefined' || host !== location.host)) {
      return `${scheme}//${host}${src}`;
    }
    return src;
  }
  return src;
}

function buildApiUrl(path: string): string {
  const sameHost = typeof location !== 'undefined' && (!host || host === location.host);
  if (sameHost) return path;
  return `${scheme}//${host}${path}`;
}

const AI_GRADING_TIMEOUT = 60000;
const BULK_IO_TIMEOUT = 60000;
const AI_GENERATE_TIMEOUT = 90000;
const LAN_TIMEOUT = 10000;

export function isAiPath(path: string) {
  return path.startsWith('/api/grading/') || path === '/api/questions/generate';
}

export function defaultTimeout(path: string, method = 'GET') {
  if (path === '/api/questions/generate') return AI_GENERATE_TIMEOUT;
  if (path.startsWith('/api/grading/') || path.startsWith('/api/backup')) return AI_GRADING_TIMEOUT;
  if (path === '/api/peers/sync' || (path === '/api/questions' && method.toUpperCase() === 'POST')) return BULK_IO_TIMEOUT;
  return LAN_TIMEOUT;
}

export async function api(
  path: string,
  method = 'GET',
  body?: unknown,
  timeoutMs = defaultTimeout(path, method),
) {
  if (import.meta.env.DEV && previewMode) {
    const { previewApi } = await import('./dev-preview');
    return previewApi(path, method, body);
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const response = await fetch(buildApiUrl(path), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
      signal: controller.signal,
    });
    let result: any;
    try {
      result = await response.json();
    } catch {
      throw new Error('设备返回异常，请重试');
    }
    if (!response.ok || result.error) {
      const error: any = new Error(result.message || result.error || '请求失败');
      error.status = response.status;
      if (response.status === 401 && path !== '/api/auth' && path !== '/api/logout') {
        window.dispatchEvent(new Event('session-expired'));
      }
      throw error;
    }
    return result;
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      throw new Error(
        isAiPath(path)
          ? 'AI 服务响应超时，请检查 API 地址、模型及设备网络后重试'
          : '连接超时，请确认处于同一 WiFi',
      );
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export async function downloadLogs() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), LAN_TIMEOUT * 3);
  try {
    const headers: Record<string, string> = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const response = await fetch(buildApiUrl('/api/logs/export'), {
      headers,
      cache: 'no-store',
      signal: controller.signal,
    });
    if (response.status === 401) window.dispatchEvent(new Event('session-expired'));
    if (!response.ok) throw new Error('导出失败（HTTP ' + response.status + '）');
    const blob = await response.blob();
    const name =
      (response.headers.get('content-disposition') || '').match(/filename="?([^";]+)"?/)?.[1] ||
      'studyontv-logs.zip';
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 3000);
  } catch (error: any) {
    if (error?.name === 'AbortError') throw new Error('导出超时，请确认处于同一 WiFi');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
