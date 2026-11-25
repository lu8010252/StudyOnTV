export const SAVED_PEERS_STORAGE_KEY = 'sotv-saved-peers';

export type SyncScope = 'config' | 'questions' | 'speech' | 'grading';
export type SyncMode = 'merge' | 'replace';

export interface PeerSelf {
  id?: string;
  name?: string;
  ip?: string;
  port?: number;
  host?: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
}

export interface PeerInfo {
  id?: string;
  name?: string;
  ip?: string;
  port?: number;
  host: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  lastSeenAt?: number;
  online?: boolean;
}

export interface SavedPeer {
  host: string;
  name?: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  addedAt: number;
}

export interface MergedPeer {
  id?: string;
  host: string;
  name: string;
  version?: string;
  isTv?: boolean;
  questions?: number;
  lastSeenAt?: number;
  online: boolean;
  discovered: boolean;
  saved: boolean;
}

export interface SyncTargetResult {
  host: string;
  name?: string;
  ok: boolean;
  message?: string;
}

export interface SyncResponse {
  ok: boolean;
  results?: SyncTargetResult[];
  message?: string;
}

export function normalizePeerHost(raw: string): string {
  const cleaned = String(raw || '')
    .trim()
    .replace(/^https?:\/\//i, '')
    .replace(/\/.*$/, '');
  if (!cleaned) return '';
  if (!cleaned.includes(':') && /^\d{1,3}(\.\d{1,3}){3}$/.test(cleaned)) {
    return `${cleaned}:8099`;
  }
  return cleaned;
}

export function isValidPeerHostInput(raw: string): boolean {
  const normalized = normalizePeerHost(raw);
  if (!normalized) return false;
  return /^[\w.-]+:\d{1,5}$/.test(normalized);
}

export function loadSavedPeers(): SavedPeer[] {
  try {
    if (typeof localStorage === 'undefined') return [];
    const raw = JSON.parse(localStorage.getItem(SAVED_PEERS_STORAGE_KEY) || '[]');
    if (!Array.isArray(raw)) return [];
    const seen = new Set<string>();
    const list: SavedPeer[] = [];
    for (const item of raw) {
      const host = normalizePeerHost(item?.host || '');
      if (!host) continue;
      const key = host.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      list.push({
        host,
        name: typeof item?.name === 'string' && item.name.trim() ? item.name.trim() : undefined,
        version: typeof item?.version === 'string' ? item.version : undefined,
        isTv: typeof item?.isTv === 'boolean' ? item.isTv : undefined,
        questions: typeof item?.questions === 'number' ? item.questions : undefined,
        addedAt: typeof item?.addedAt === 'number' ? item.addedAt : Date.now(),
      });
    }
    return list;
  } catch {
    return [];
  }
}

export function saveSavedPeers(peers: SavedPeer[]) {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(SAVED_PEERS_STORAGE_KEY, JSON.stringify(peers));
  } catch {}
}

export function upsertSavedPeer(list: SavedPeer[], peer: Omit<SavedPeer, 'addedAt'>): SavedPeer[] {
  const host = normalizePeerHost(peer.host);
  if (!host) return list;
  const key = host.toLowerCase();
  const existing = list.find(item => item.host.toLowerCase() === key);
  const nextItem: SavedPeer = {
    host,
    name: peer.name || existing?.name,
    version: peer.version ?? existing?.version,
    isTv: peer.isTv ?? existing?.isTv,
    questions: peer.questions ?? existing?.questions,
    addedAt: existing?.addedAt || Date.now(),
  };
  const rest = list.filter(item => item.host.toLowerCase() !== key);
  return [nextItem, ...rest];
}

export function removeSavedPeer(list: SavedPeer[], host: string): SavedPeer[] {
  const key = normalizePeerHost(host).toLowerCase();
  return list.filter(item => item.host.toLowerCase() !== key);
}

export function mergePeers(
  discovered: PeerInfo[],
  saved: SavedPeer[],
  selfHosts: string[] = [],
  probedOnlineHosts: Record<string, PeerInfo> = {},
): MergedPeer[] {
  const selfSet = new Set(
    selfHosts.map(h => normalizePeerHost(h).toLowerCase()).filter(Boolean),
  );
  const map = new Map<string, MergedPeer>();

  for (const item of discovered || []) {
    const host = normalizePeerHost(item.host || (item.ip && item.port ? `${item.ip}:${item.port}` : ''));
    if (!host) continue;
    const key = host.toLowerCase();
    if (selfSet.has(key)) continue;
    map.set(key, {
      id: item.id,
      host,
      name: (item.name || '').trim() || host,
      version: item.version,
      isTv: item.isTv,
      questions: typeof item.questions === 'number' ? item.questions : undefined,
      lastSeenAt: item.lastSeenAt,
      online: item.online !== false,
      discovered: true,
      saved: false,
    });
  }

  for (const item of saved || []) {
    const host = normalizePeerHost(item.host);
    if (!host) continue;
    const key = host.toLowerCase();
    if (selfSet.has(key)) continue;
    const existing = map.get(key);
    const probed = probedOnlineHosts[key];
    if (existing) {
      existing.saved = true;
      if (!existing.name || existing.name === existing.host) {
        existing.name = item.name || existing.host;
      }
    } else {
      map.set(key, {
        id: probed?.id,
        host,
        name: (probed?.name || item.name || '').trim() || host,
        version: probed?.version ?? item.version,
        isTv: probed?.isTv ?? item.isTv,
        questions: typeof probed?.questions === 'number' ? probed.questions : item.questions,
        lastSeenAt: probed?.lastSeenAt,
        online: probed ? probed.online !== false : false,
        discovered: false,
        saved: true,
      });
    }
  }

  return Array.from(map.values()).sort((a, b) => {
    if (a.online !== b.online) return a.online ? -1 : 1;
    if (a.discovered !== b.discovered) return a.discovered ? -1 : 1;
    return a.name.localeCompare(b.name, 'zh-CN');
  });
}

export function extractProbedPeer(result: any, fallbackHost: string): PeerInfo {
  const src = result?.peer && typeof result.peer === 'object' ? result.peer : result || {};
  const host = normalizePeerHost(src.host || fallbackHost);
  return {
    id: typeof src.id === 'string' ? src.id : undefined,
    host,
    name: typeof src.name === 'string' && src.name.trim() ? src.name.trim() : host,
    version: typeof src.version === 'string' ? src.version : undefined,
    isTv: typeof src.isTv === 'boolean' ? src.isTv : src.flavor === 'tv' ? true : src.flavor === 'mobile' ? false : undefined,
    questions: typeof src.questions === 'number' ? src.questions : undefined,
    online: src.online !== false,
    lastSeenAt: Date.now(),
  };
}
