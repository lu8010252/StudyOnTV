import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  ArrowLeftRight,
  Bookmark,
  BookmarkCheck,
  CheckCircle2,
  ExternalLink,
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  MonitorSmartphone,
  Plus,
  Radar,
  RefreshCw,
  Send,
  Smartphone,
  Trash2,
  Tv,
  Wifi,
  WifiOff,
  XCircle,
} from 'lucide-react';
import { toast } from 'sonner';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { SectionHeading } from '@/components/ui/section-heading';
import { Separator } from '@/components/ui/separator';
import { api, getHost } from './api';
import {
  extractProbedPeer,
  isValidPeerHostInput,
  loadSavedPeers,
  mergePeers,
  normalizePeerHost,
  removeSavedPeer,
  saveSavedPeers,
  upsertSavedPeer,
  type MergedPeer,
  type PeerInfo,
  type PeerSelf,
  type SavedPeer,
  type SyncMode,
  type SyncResponse,
  type SyncScope,
  type SyncTargetResult,
} from './peers';

interface DevicesPageProps {
  sessionId: number;
  device?: any;
  questionCount?: number;
  onSwitchHost: (host: string, name?: string) => void;
}

const SYNC_SCOPES: { id: SyncScope; label: string; description: string }[] = [
  { id: 'config', label: '学习计划规则', description: '每组题量、先背再测、可用时段、年级科目与白名单' },
  { id: 'questions', label: '题库题目', description: '同步本机题库题目到目标设备（支持合并或覆盖）' },
  { id: 'speech', label: '朗读与语音识别', description: '自动朗读开关、朗读范围、音色引擎与语速设置' },
  { id: 'grading', label: 'AI 批改与出题配置', description: '模型服务商、接口地址、模型名称与批改参数' },
];

export default function DevicesPage({ sessionId, device, questionCount, onSwitchHost }: DevicesPageProps) {
  const queryClient = useQueryClient();
  const [savedPeers, setSavedPeers] = useState<SavedPeer[]>(() => loadSavedPeers());
  const [probedMap, setProbedMap] = useState<Record<string, PeerInfo>>({});
  const [selectedHosts, setSelectedHosts] = useState<string[]>([]);
  const [scopes, setScopes] = useState<SyncScope[]>(['config', 'questions', 'speech', 'grading']);
  const [mode, setMode] = useState<SyncMode>('merge');
  const [defaultPassword, setDefaultPassword] = useState('');
  const [showDefaultPassword, setShowDefaultPassword] = useState(false);
  const [perHostPasswords, setPerHostPasswords] = useState<Record<string, string>>({});
  const [showPerHostPassword, setShowPerHostPassword] = useState<Record<string, boolean>>({});
  const [expandedPasswordHosts, setExpandedPasswordHosts] = useState<Record<string, boolean>>({});
  const [syncResults, setSyncResults] = useState<SyncTargetResult[] | null>(null);

  // 手动添加设备弹窗
  const [addOpen, setAddOpen] = useState(false);
  const [addHostInput, setAddHostInput] = useState('');
  const [addError, setAddError] = useState('');
  const [probingHost, setProbingHost] = useState<string | null>(null);

  const peersQuery = useQuery<{ self?: PeerSelf; peers?: PeerInfo[] }>({
    queryKey: ['peers', sessionId],
    queryFn: () => api('/api/peers?scan=1'),
    staleTime: 10000,
  });

  const selfInfo = useMemo(() => {
    const apiSelf = peersQuery.data?.self;
    const host =
      apiSelf?.host ||
      (apiSelf?.ip && apiSelf?.port ? `${apiSelf.ip}:${apiSelf.port}` : '') ||
      (device?.ip && device?.port ? `${device.ip}:${device.port}` : '') ||
      getHost();
    return {
      name: apiSelf?.name || device?.name || '当前设备',
      host,
      version: apiSelf?.version || device?.version || '',
      isTv: typeof apiSelf?.isTv === 'boolean' ? apiSelf.isTv : device?.flavor === 'tv',
      questions: typeof apiSelf?.questions === 'number' ? apiSelf.questions : questionCount,
    };
  }, [peersQuery.data?.self, device, questionCount]);

  const peers: MergedPeer[] = useMemo(() => {
    const selfHosts = [
      selfInfo.host,
      getHost(),
      peersQuery.data?.self?.ip && peersQuery.data?.self?.port
        ? `${peersQuery.data.self.ip}:${peersQuery.data.self.port}`
        : '',
    ].filter(Boolean);
    return mergePeers(peersQuery.data?.peers || [], savedPeers, selfHosts, probedMap);
  }, [peersQuery.data?.peers, peersQuery.data?.self, savedPeers, selfInfo.host, probedMap]);

  const persistSaved = (next: SavedPeer[]) => {
    setSavedPeers(next);
    saveSavedPeers(next);
  };

  const toggleSavePeer = (peer: MergedPeer) => {
    if (peer.saved) {
      persistSaved(removeSavedPeer(savedPeers, peer.host));
      toast.success(`已取消收藏 ${peer.name}`);
    } else {
      persistSaved(
        upsertSavedPeer(savedPeers, {
          host: peer.host,
          name: peer.name,
          version: peer.version,
          isTv: peer.isTv,
          questions: peer.questions,
        }),
      );
      toast.success(`已收藏 ${peer.name}`);
    }
  };

  const handleRemoveSaved = (peer: MergedPeer) => {
    persistSaved(removeSavedPeer(savedPeers, peer.host));
    setSelectedHosts(prev => prev.filter(h => h !== peer.host));
    toast.success(`已移除 ${peer.name}`);
  };

  const rescanMutation = useMutation({
    mutationFn: () => api('/api/peers?scan=1'),
    onSuccess: data => {
      queryClient.setQueryData(['peers', sessionId], data);
      const count = Array.isArray(data?.peers) ? data.peers.length : 0;
      toast.success(count > 0 ? `扫描完成，发现 ${count} 台局域网设备` : '扫描完成，暂未发现其他在线设备');
    },
    onError: (err: any) => {
      toast.error(err?.message || '扫描局域网设备失败');
    },
  });

  const probeAddMutation = useMutation({
    mutationFn: async (rawHost: string) => {
      const normalized = normalizePeerHost(rawHost);
      if (!isValidPeerHostInput(normalized)) {
        throw new Error('请输入合法的局域网地址，例如 192.168.1.20:8099');
      }
      if (normalized.toLowerCase() === normalizePeerHost(selfInfo.host).toLowerCase()) {
        throw new Error('这是当前正在管理的设备，无需重复添加');
      }
      const res = await api('/api/peers/probe', 'POST', { host: normalized });
      if (res?.ok === false || res?.online === false || res?.peer?.online === false) {
        throw new Error(res?.message || '目标设备离线或无法连接，请确认已打开「题了个题」且在同一局域网');
      }
      return { res, normalized };
    },
    onSuccess: ({ res, normalized }) => {
      const probed = extractProbedPeer(res, normalized);
      setProbedMap(prev => ({ ...prev, [probed.host.toLowerCase()]: probed }));
      persistSaved(
        upsertSavedPeer(savedPeers, {
          host: probed.host,
          name: probed.name,
          version: probed.version,
          isTv: probed.isTv,
          questions: probed.questions,
        }),
      );
      setAddOpen(false);
      setAddHostInput('');
      setAddError('');
      toast.success(`已连接并收藏「${probed.name}」`);
    },
    onError: (err: any) => {
      setAddError(err?.message || '无法探测该设备，请确认地址正确且处于同一局域网');
    },
  });

  const handleReprobePeer = async (peer: MergedPeer) => {
    if (probingHost) return;
    setProbingHost(peer.host);
    try {
      const res = await api('/api/peers/probe', 'POST', { host: peer.host });
      if (res?.ok === false || res?.online === false || res?.peer?.online === false) {
        throw new Error(res?.message || `未能连接到 ${peer.host}`);
      }
      const probed = extractProbedPeer(res, peer.host);
      setProbedMap(prev => ({ ...prev, [probed.host.toLowerCase()]: probed }));
      if (peer.saved) {
        persistSaved(
          upsertSavedPeer(savedPeers, {
            host: probed.host,
            name: probed.name,
            version: probed.version,
            isTv: probed.isTv,
            questions: probed.questions,
          }),
        );
      }
      toast.success(`「${probed.name}」在线`);
    } catch (err: any) {
      toast.error(err?.message || `未能连接到 ${peer.host}`);
    } finally {
      setProbingHost(null);
    }
  };

  const validSelectedHosts = useMemo(
    () => selectedHosts.filter(h => peers.some(p => p.host === h)),
    [selectedHosts, peers],
  );

  const allSelected = peers.length > 0 && validSelectedHosts.length === peers.length;

  const toggleSelectHost = (host: string) => {
    setSelectedHosts(prev => (prev.includes(host) ? prev.filter(item => item !== host) : [...prev, host]));
  };

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelectedHosts([]);
    } else {
      setSelectedHosts(peers.map(p => p.host));
    }
  };

  const toggleScope = (scope: SyncScope) => {
    setScopes(prev => (prev.includes(scope) ? prev.filter(s => s !== scope) : [...prev, scope]));
  };

  const syncMutation = useMutation({
    mutationFn: async () => {
      if (!validSelectedHosts.length) throw new Error('请先勾选至少 1 台目标设备');
      if (!scopes.length) throw new Error('请先勾选至少 1 项同步内容');
      const fallbackPwd = defaultPassword.trim();
      const missingPwdHosts = validSelectedHosts.filter(
        host => !(perHostPasswords[host] || '').trim() && !fallbackPwd,
      );
      if (missingPwdHosts.length > 0) {
        throw new Error('请输入目标设备的管理密码（多台相同时填写统一管理密码即可，不同时可为单台点「单独密码」）');
      }
      const targets = validSelectedHosts.map(host => {
        const override = (perHostPasswords[host] || '').trim();
        const pwd = override || fallbackPwd;
        return { host, password: pwd };
      });
      const payload: Record<string, any> = {
        targets,
        scopes,
        mode,
      };
      if (fallbackPwd) {
        payload.defaultPassword = fallbackPwd;
      }
      return (await api('/api/peers/sync', 'POST', payload)) as SyncResponse;
    },
    onSuccess: data => {
      const results = Array.isArray(data?.results) ? data.results : [];
      setSyncResults(results);
      const okCount = results.filter(r => r.ok).length;
      const failCount = results.length - okCount;
      if (results.length === 0) {
        toast.success(data?.message || '同步请求已完成');
      } else if (failCount === 0) {
        toast.success(`已成功同步到全部 ${okCount} 台设备`);
      } else if (okCount === 0) {
        toast.error(`同步失败（${failCount} 台设备未完成），请查看下方明细`);
      } else {
        toast.warning(`部分完成：${okCount} 台成功，${failCount} 台失败`);
      }
    },
    onError: (err: any) => {
      toast.error(err?.message || '批量同步失败');
    },
  });

  const handleOpenInNewTab = (peer: MergedPeer) => {
    window.open(`http://${peer.host}/admin/`, '_blank', 'noopener,noreferrer');
  };

  return (
    <div className="space-y-6 pb-8">
      {/* 当前主控设备 */}
      <section className="space-y-2.5">
        <SectionHeading
          icon={Tv}
          title="当前主控设备"
          description="批量同步将把这台设备的学习计划、题库或配置同步到下方勾选的其他设备。"
        />
        <Card className="border-primary/25 bg-primary/[0.03] shadow-xs">
          <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                {selfInfo.isTv ? <Tv className="size-5" /> : <Smartphone className="size-5" />}
              </div>
              <div className="min-w-0 space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="truncate text-sm font-semibold">{selfInfo.name}</span>
                  <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">当前管理中</Badge>
                  <Badge variant="outline">{selfInfo.isTv ? '电视版' : '手机 / 平板版'}</Badge>
                </div>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span className="font-mono">{selfInfo.host}</span>
                  {selfInfo.version && <span>v{selfInfo.version}</span>}
                  {typeof selfInfo.questions === 'number' && <span>{selfInfo.questions} 道题</span>}
                </div>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => rescanMutation.mutate()}
                disabled={rescanMutation.isPending || peersQuery.isFetching}
              >
                {rescanMutation.isPending || peersQuery.isFetching ? (
                  <LoaderCircle className="size-4 animate-spin" />
                ) : (
                  <RefreshCw className="size-4" />
                )}
                重新扫描
              </Button>
              <Button
                size="sm"
                onClick={() => {
                  setAddError('');
                  setAddHostInput('');
                  setAddOpen(true);
                }}
              >
                <Plus className="size-4" />
                手动添加设备
              </Button>
            </div>
          </CardContent>
        </Card>
      </section>

      {/* 局域网其他设备与收藏列表 */}
      <section className="space-y-2.5">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <SectionHeading
            icon={Radar}
            title="局域网设备与收藏列表"
            description="自动发现同一 WiFi 下的 StudyOnTV 设备，并合并本浏览器收藏的设备。"
          />
          {peers.length > 0 && (
            <Button variant="ghost" size="sm" className="h-8 text-xs" onClick={toggleSelectAll}>
              {allSelected ? '取消全选' : `全选 (${peers.length} 台)`}
            </Button>
          )}
        </div>

        {peersQuery.isError && (
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              自动扫描失败：{(peersQuery.error as any)?.message || '请确认设备已升级到最新版本并处于同一局域网'}。
              仍可通过「手动添加设备」输入 IP:端口管理。
            </AlertDescription>
          </Alert>
        )}

        {peersQuery.isLoading && peers.length === 0 ? (
          <Card className="shadow-xs">
            <CardContent className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <LoaderCircle className="size-4 animate-spin" />
              正在扫描局域网中的 StudyOnTV 设备…
            </CardContent>
          </Card>
        ) : peers.length === 0 ? (
          <Card className="border-dashed shadow-xs">
            <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
              <MonitorSmartphone className="size-9 text-muted-foreground/70" />
              <div className="space-y-1">
                <p className="text-sm font-medium">暂未发现其他 StudyOnTV 设备</p>
                <p className="max-w-md text-xs text-muted-foreground">
                  请确认另一台电视或平板已打开「题了个题」且连接在同一家庭 WiFi 下；如果路由器隔绝了局域网广播，可点击「手动添加设备」直接输入对方 IP:端口。
                </p>
              </div>
              <div className="flex flex-wrap justify-center gap-2 pt-1">
                <Button variant="outline" size="sm" onClick={() => rescanMutation.mutate()} disabled={rescanMutation.isPending}>
                  <RefreshCw className="size-4" />
                  重新扫描
                </Button>
                <Button size="sm" onClick={() => setAddOpen(true)}>
                  <Plus className="size-4" />
                  手动添加设备
                </Button>
              </div>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {peers.map(peer => {
              const checked = validSelectedHosts.includes(peer.host);
              const customPwdExpanded = !!expandedPasswordHosts[peer.host] || !!perHostPasswords[peer.host];
              const pwdVisible = !!showPerHostPassword[peer.host];
              return (
                <Card
                  key={peer.host}
                  className={`transition-colors shadow-xs ${checked ? 'border-primary/50 bg-primary/[0.02]' : ''}`}
                >
                  <CardContent className="space-y-3 p-4">
                    <div className="flex items-start gap-3">
                      <Checkbox
                        id={`peer-select-${peer.host}`}
                        checked={checked}
                        onCheckedChange={() => toggleSelectHost(peer.host)}
                        className="mt-1"
                        aria-label={`勾选同步目标 ${peer.name}`}
                      />
                      <div className="min-w-0 flex-1 space-y-1.5">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <label
                            htmlFor={`peer-select-${peer.host}`}
                            className="cursor-pointer truncate text-sm font-semibold"
                          >
                            {peer.name}
                          </label>
                          {typeof peer.isTv === 'boolean' && (
                            <Badge variant="outline" className="text-[11px]">
                              {peer.isTv ? (
                                <>
                                  <Tv className="mr-1 size-3" />
                                  电视
                                </>
                              ) : (
                                <>
                                  <Smartphone className="mr-1 size-3" />
                                  手机/平板
                                </>
                              )}
                            </Badge>
                          )}
                          {peer.online ? (
                            <Badge
                              variant="secondary"
                              className="bg-emerald-50 text-[11px] text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                            >
                              <Wifi className="mr-1 size-3" />
                              在线
                            </Badge>
                          ) : (
                            <Badge variant="secondary" className="text-[11px] text-muted-foreground">
                              <WifiOff className="mr-1 size-3" />
                              未探测到
                            </Badge>
                          )}
                          {peer.saved && (
                            <Badge variant="outline" className="text-[11px] text-muted-foreground">
                              已收藏
                            </Badge>
                          )}
                        </div>
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          <span className="font-mono">{peer.host}</span>
                          {peer.version && <span>v{peer.version}</span>}
                          {typeof peer.questions === 'number' && <span>{peer.questions} 道题</span>}
                        </div>
                      </div>
                    </div>

                    {/* 单独密码覆盖（勾选同步且需要不同密码时展开） */}
                    {checked && customPwdExpanded && (
                      <div className="rounded-md border bg-muted/30 p-2.5">
                        <Field>
                          <FieldLabel htmlFor={`pwd-${peer.host}`} className="text-xs">
                            该台设备单独管理密码（可选，优先于统一密码）
                          </FieldLabel>
                          <InputGroup className="h-8 bg-background">
                            <InputGroupAddon>
                              <KeyRound className="size-3.5" />
                            </InputGroupAddon>
                            <InputGroupInput
                              id={`pwd-${peer.host}`}
                              type={pwdVisible ? 'text' : 'password'}
                              autoComplete="off"
                              placeholder="留空则使用下方统一密码"
                              value={perHostPasswords[peer.host] || ''}
                              onChange={e =>
                                setPerHostPasswords(prev => ({ ...prev, [peer.host]: e.target.value }))
                              }
                            />
                            <InputGroupAddon align="inline-end">
                              <InputGroupButton
                                size="icon-xs"
                                aria-label={pwdVisible ? '隐藏密码' : '显示密码'}
                                onClick={() =>
                                  setShowPerHostPassword(prev => ({ ...prev, [peer.host]: !prev[peer.host] }))
                                }
                              >
                                {pwdVisible ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
                              </InputGroupButton>
                            </InputGroupAddon>
                          </InputGroup>
                        </Field>
                      </div>
                    )}

                    <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
                      <Button
                        size="sm"
                        variant="default"
                        className="h-8"
                        onClick={() => onSwitchHost(peer.host, peer.name)}
                      >
                        <ArrowLeftRight className="size-3.5" />
                        切换管理
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="h-8"
                        title="在新标签页打开该设备后台"
                        onClick={() => handleOpenInNewTab(peer)}
                      >
                        <ExternalLink className="size-3.5" />
                        新窗口
                      </Button>
                      {checked && !customPwdExpanded && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-8 text-xs text-muted-foreground"
                          onClick={() =>
                            setExpandedPasswordHosts(prev => ({ ...prev, [peer.host]: true }))
                          }
                        >
                          <KeyRound className="size-3.5" />
                          单独密码
                        </Button>
                      )}
                      {!peer.online && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-8 text-xs"
                          disabled={probingHost === peer.host}
                          onClick={() => handleReprobePeer(peer)}
                        >
                          {probingHost === peer.host ? (
                            <LoaderCircle className="size-3.5 animate-spin" />
                          ) : (
                            <Radar className="size-3.5" />
                          )}
                          探测
                        </Button>
                      )}
                      <div className="ms-auto flex items-center gap-1">
                        {peer.discovered ? (
                          <Button
                            size="icon"
                            variant="ghost"
                            className="size-8 text-muted-foreground"
                            title={peer.saved ? '取消收藏' : '收藏此设备'}
                            aria-label={peer.saved ? '取消收藏' : '收藏此设备'}
                            onClick={() => toggleSavePeer(peer)}
                          >
                            {peer.saved ? (
                              <BookmarkCheck className="size-4 text-primary" />
                            ) : (
                              <Bookmark className="size-4" />
                            )}
                          </Button>
                        ) : (
                          <Button
                            size="icon"
                            variant="ghost"
                            className="size-8 text-muted-foreground hover:text-destructive"
                            title="移除收藏"
                            aria-label="移除收藏"
                            onClick={() => handleRemoveSaved(peer)}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        )}
                      </div>
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </div>
        )}
      </section>

      {/* 一键批量同步向导 */}
      <section className="space-y-2.5">
        <SectionHeading
          icon={Send}
          title="一键批量同步"
          description="将当前主控设备的学习计划、题库、朗读或 AI 配置同步到勾选的其他设备。"
        />
        <Card className="shadow-xs">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">同步配置</CardTitle>
            <CardDescription>
              仅同步下方勾选的内容，不会覆盖目标设备的观看倒计时、今日学习记录、设备名称或管理密码。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            {/* 同步范围 */}
            <div className="space-y-2.5">
              <div className="text-xs font-medium text-muted-foreground">1. 选择要同步的内容</div>
              <div className="grid gap-2.5 sm:grid-cols-2">
                {SYNC_SCOPES.map(item => {
                  const checked = scopes.includes(item.id);
                  return (
                    <label
                      key={item.id}
                      htmlFor={`scope-${item.id}`}
                      className={`flex cursor-pointer items-start gap-2.5 rounded-lg border p-3 transition-colors ${
                        checked ? 'border-primary/45 bg-primary/[0.03]' : 'hover:bg-accent/50'
                      }`}
                    >
                      <Checkbox
                        id={`scope-${item.id}`}
                        checked={checked}
                        onCheckedChange={() => toggleScope(item.id)}
                        className="mt-0.5"
                      />
                      <div className="space-y-0.5">
                        <div className="text-sm font-medium leading-none">{item.label}</div>
                        <p className="text-xs leading-5 text-muted-foreground">{item.description}</p>
                      </div>
                    </label>
                  );
                })}
              </div>
            </div>

            {/* 题库合并 / 覆盖模式 */}
            {scopes.includes('questions') && (
              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">2. 题库同步方式</div>
                <div className="grid gap-2.5 sm:grid-cols-2">
                  <button
                    type="button"
                    onClick={() => setMode('merge')}
                    className={`rounded-lg border p-3 text-left transition-colors ${
                      mode === 'merge'
                        ? 'border-primary bg-primary/[0.04] ring-1 ring-primary/30'
                        : 'hover:bg-accent/50'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium">合并（推荐）</span>
                      {mode === 'merge' && <Badge variant="secondary">已选</Badge>}
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      保留目标设备已有题目，只补充新增或更新的题目。
                    </p>
                  </button>
                  <button
                    type="button"
                    onClick={() => setMode('replace')}
                    className={`rounded-lg border p-3 text-left transition-colors ${
                      mode === 'replace'
                        ? 'border-primary bg-primary/[0.04] ring-1 ring-primary/30'
                        : 'hover:bg-accent/50'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium">覆盖</span>
                      {mode === 'replace' && <Badge variant="secondary">已选</Badge>}
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      将目标设备题库完全替换为当前设备的题库内容。
                    </p>
                  </button>
                </div>
              </div>
            )}

            {/* 密码设置 */}
            <div className="space-y-2">
              <Field>
                <FieldLabel htmlFor="sync-default-password" className="text-xs font-medium text-muted-foreground">
                  {scopes.includes('questions') ? '3.' : '2.'} 目标设备统一管理密码
                </FieldLabel>
                <InputGroup className="max-w-md">
                  <InputGroupAddon>
                    <KeyRound className="size-4" />
                  </InputGroupAddon>
                  <InputGroupInput
                    id="sync-default-password"
                    type={showDefaultPassword ? 'text' : 'password'}
                    autoComplete="off"
                    placeholder="请输入目标设备的管理密码，多台相同时填此处即可"
                    value={defaultPassword}
                    onChange={e => setDefaultPassword(e.target.value)}
                  />
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton
                      size="icon-sm"
                      aria-label={showDefaultPassword ? '隐藏密码' : '显示密码'}
                      onClick={() => setShowDefaultPassword(v => !v)}
                    >
                      {showDefaultPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                    </InputGroupButton>
                  </InputGroupAddon>
                </InputGroup>
                <FieldDescription>
                  出于安全考虑，密码仅用于本次同步认证，不会保存到本地存储。如某台设备密码不同，可在上方该设备卡片点「单独密码」覆盖。
                </FieldDescription>
              </Field>
            </div>

            <Separator />

            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="text-xs text-muted-foreground">
                {validSelectedHosts.length > 0 ? (
                  <>
                    已选 <span className="font-semibold text-foreground">{validSelectedHosts.length}</span> 台设备 ·{' '}
                    <span className="font-semibold text-foreground">{scopes.length}</span> 项同步内容
                  </>
                ) : (
                  '请在上方设备列表中勾选至少 1 台目标设备'
                )}
              </div>
              <Button
                onClick={() => syncMutation.mutate()}
                disabled={syncMutation.isPending || validSelectedHosts.length === 0 || scopes.length === 0}
              >
                {syncMutation.isPending ? (
                  <>
                    <LoaderCircle className="size-4 animate-spin" />
                    正在同步…
                  </>
                ) : (
                  <>
                    <Send className="size-4" />
                    同步到所选设备（{validSelectedHosts.length}）
                  </>
                )}
              </Button>
            </div>

            {/* 同步结果明细 */}
            {syncResults && syncResults.length > 0 && (
              <div className="space-y-2 rounded-lg border bg-muted/20 p-3">
                <div className="text-xs font-semibold">最近一次同步结果</div>
                <div className="space-y-2">
                  {syncResults.map(res => (
                    <div
                      key={res.host}
                      className="flex flex-wrap items-start justify-between gap-2 rounded-md border bg-background px-3 py-2 text-xs"
                    >
                      <div className="flex min-w-0 items-start gap-2">
                        {res.ok ? (
                          <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                        ) : (
                          <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" />
                        )}
                        <div className="min-w-0 space-y-0.5">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium">{res.name || res.host}</span>
                            <span className="font-mono text-muted-foreground">{res.host}</span>
                          </div>
                          <p className={res.ok ? 'text-muted-foreground' : 'text-destructive'}>
                            {res.message || (res.ok ? '同步成功' : '同步失败')}
                          </p>
                        </div>
                      </div>
                      {!res.ok && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-7 text-xs"
                          onClick={() => {
                            if (!selectedHosts.includes(res.host)) {
                              setSelectedHosts(prev => [...prev, res.host]);
                            }
                            setExpandedPasswordHosts(prev => ({ ...prev, [res.host]: true }));
                          }}
                        >
                          <KeyRound className="size-3" />
                          填写单独密码
                        </Button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </section>

      {/* 手动添加设备弹窗 */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>手动添加局域网设备</DialogTitle>
            <DialogDescription>
              输入另一台电视或平板的局域网地址（例如 192.168.1.20:8099），探测在线后自动收藏。
            </DialogDescription>
          </DialogHeader>
          <form
            onSubmit={e => {
              e.preventDefault();
              setAddError('');
              probeAddMutation.mutate(addHostInput);
            }}
            className="space-y-4"
          >
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="manual-peer-host">设备地址（IP:端口）</FieldLabel>
                <Input
                  id="manual-peer-host"
                  autoFocus
                  autoComplete="off"
                  autoCapitalize="none"
                  spellCheck={false}
                  placeholder="192.168.1.20:8099"
                  value={addHostInput}
                  onChange={e => {
                    setAddHostInput(e.target.value);
                    if (addError) setAddError('');
                  }}
                />
                <FieldDescription>如果未填写端口，将默认使用 :8099。</FieldDescription>
                <FieldError>{addError}</FieldError>
              </Field>
            </FieldGroup>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setAddOpen(false)}>
                取消
              </Button>
              <Button type="submit" disabled={probeAddMutation.isPending || !addHostInput.trim()}>
                {probeAddMutation.isPending ? (
                  <>
                    <LoaderCircle className="size-4 animate-spin" />
                    探测中…
                  </>
                ) : (
                  '探测并收藏'
                )}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
