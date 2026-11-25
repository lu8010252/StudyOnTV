import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArchiveRestore,
  Check,
  ChevronDown,
  CloudDownload,
  Copy,
  Download,
  HardDriveDownload,
  KeyRound,
  LoaderCircle,
  MonitorSmartphone,
  RadioTower,
  RefreshCw,
  Upload,
} from 'lucide-react';
import { toast } from 'sonner';
import { keepAliveSummary } from './keepalive';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { SectionHeading } from '@/components/ui/section-heading';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { api, setToken as setSessionToken } from './api';
import { download } from './components/bank/shared';
import { copyText } from './lib/clipboard';

const VIA: Record<string, string> = {
  boot: '开机广播',
  accessibility: '无障碍服务',
  job: '系统定时任务',
  update: '应用更新',
  app: '打开应用',
};

function autostartNote(status: any) {
  if (status.pending) return '刚安装或更新，重启一次设备后可以判断是否生效。';
  if (!status.known) return '这台设备不提供开机次数，建议开启无障碍服务。';
  const via = status.via
    ? `本次开机后${status.startedAfterSeconds >= 0 ? ` ${Math.round(status.startedAfterSeconds / 60)} 分钟` : ''}由${VIA[status.via] || status.via}启动`
    : '本次开机后尚未记录';
  return status.ok
    ? `${via}${status.accessibility ? ' · 无障碍服务已开启' : ''}`
    : `${via}。请在系统自启动管理里允许本应用，并开启无障碍服务。`;
}

const formatTime = (value: number) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未检查';

function monitorNote(monitor: any) {
  if (!monitor?.service) return '管控服务未运行，请检查自启动和后台运行权限。';
  if (monitor.accessibilityLost) return '无障碍服务已关闭，计时或拦截可能受影响。';
  if (monitor.accessibilityEnabled && !monitor.accessibility) {
    return '无障碍已授权但尚未连接，请重新开启服务。';
  }
  const missing: string[] = [];
  if (!monitor.overlay) missing.push('悬浮窗');
  if (!monitor.usage && !monitor.accessibility) missing.push('使用情况访问或无障碍');
  return missing.length ? `缺少${missing.join('、')}。` : '计时和拦截工作正常。';
}

function SettingsCard({
  title,
  description,
  icon: Icon,
  children,
  footer,
}: {
  title: string;
  description?: string;
  icon: any;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <section className="space-y-2">
      <SectionHeading icon={Icon} title={title} description={description} />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          <ItemGroup className="divide-y divide-border/60">{children}</ItemGroup>
          {footer && <div className="flex justify-end gap-2 border-t py-3">{footer}</div>}
        </CardContent>
      </Card>
    </section>
  );
}

function SettingItem({
  title,
  description,
  children,
  wideControl = false,
}: {
  title: string;
  description?: ReactNode;
  children: ReactNode;
  wideControl?: boolean;
}) {
  return (
    <Item
      size="sm"
      className={`items-start gap-3 rounded-none px-0 py-4 ${wideControl ? 'flex-col sm:flex-row' : ''}`}
    >
      <ItemContent className={wideControl ? 'w-full min-w-0 gap-1 sm:w-auto sm:flex-1' : 'min-w-0 gap-1'}>
        <ItemTitle>{title}</ItemTitle>
        {description && (
          <ItemDescription className="line-clamp-none text-xs leading-5">{description}</ItemDescription>
        )}
      </ItemContent>
      <ItemActions
        className={`min-w-0 flex-wrap justify-end ${wideControl ? 'ms-0 w-full sm:ms-auto sm:w-auto' : 'ms-auto'}`}
      >
        {children}
      </ItemActions>
    </Item>
  );
}

export default function DevicePanel({
  sessionId,
  onPasswordChanged,
  only,
  onOpenKeepAlive,
}: {
  sessionId: number;
  onPasswordChanged: () => void;
  only: 'device' | 'update' | 'security' | 'integration';
  onOpenKeepAlive?: () => void;
}) {
  const client = useQueryClient();
  const device = useQuery({
    queryKey: ['device', sessionId],
    queryFn: () => api('/api/device'),
    refetchInterval: 15000,
  });
  const [polling, setPolling] = useState(false);
  const pollTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
  }, []);
  const localOnly = !!device.data?.localOnly;
  const update = useQuery({
    queryKey: ['update', sessionId],
    queryFn: () => api('/api/update'),
    enabled: only === 'update' && !!device.data && !device.data.localOnly,
    refetchInterval: query => {
      const data = query.state.data as any;
      if (data?.installState === 'downloading' || data?.installState === 'installing') {
        return 1500;
      }
      return polling ? 2000 : false;
    },
  });
  const [name, setName] = useState('');
  const nameSession = useRef<number | null>(null);
  const submittedName = useRef('');
  const [endpoint, setEndpoint] = useState('');
  const [token, setToken] = useState('');
  const crashSession = useRef<number | null>(null);
  const keepalive = device.data?.keepalive ? keepAliveSummary(device.data) : null;

  // 整机备份与恢复状态
  const [includeQuestions, setIncludeQuestions] = useState(true);
  const [exportingBackup, setExportingBackup] = useState(false);
  const backupFileRef = useRef<HTMLInputElement>(null);
  const [pendingBackup, setPendingBackup] = useState<{ fileName: string; data: any } | null>(null);
  const [restoreMode, setRestoreMode] = useState<'replace' | 'merge'>('replace');

  useEffect(() => {
    if (device.data && nameSession.current !== sessionId) {
      setName(device.data.name || '');
      nameSession.current = sessionId;
      submittedName.current = '';
    }
  }, [device.data?.name, sessionId]);

  useEffect(() => {
    if (device.data?.crashUpload && crashSession.current !== sessionId) {
      setEndpoint(device.data.crashUpload.endpoint || '');
      crashSession.current = sessionId;
    }
  }, [device.data?.crashUpload?.endpoint, sessionId]);

  const startedAt = update.data?.logAt || update.data?.checkedAt;
  useEffect(() => {
    if (polling && update.data && !update.data.checking) {
      setPolling(false);
      if (update.data.hasUpdate && update.data.latest) {
        toast.message(`发现新版本 v${update.data.latest}，请升级`);
      } else if (
        update.data.log &&
        update.data.log.length &&
        update.data.log.every((item: any) => !item.ok)
      ) {
        toast.error(`检查更新失败：${update.data.log[0]?.message || '网络不通，请稍后再试'}`);
      } else {
        toast.success('当前已是最新版本');
      }
    }
  }, [polling, update.data?.checking, update.data?.hasUpdate]);

  const crash = useMutation({
    mutationFn: (patch: { consent?: boolean; endpoint?: string; token?: string }) =>
      api('/api/device/crash', 'PUT', patch),
    onSuccess: (result: any) => {
      client.setQueryData(['device', sessionId], (old: any) =>
        old ? { ...old, crashUpload: result.crashUpload } : old,
      );
      client.invalidateQueries({ queryKey: ['device', sessionId] });
    },
    onError: (error: any) => toast.error(error.message),
  });
  const enableGuard = useMutation({
    mutationFn: () => api('/api/device/uninstall-guard', 'PUT', {}),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('防卸载保护已恢复');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const disableGuard = useMutation({
    mutationFn: () => api('/api/device/uninstall-guard', 'DELETE'),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('防卸载已关闭');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const launcher = useMutation({
    mutationFn: (hidden: boolean) => api('/api/device/launcher', 'PUT', { hidden }),
    onSuccess: (result: any) => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success(result.launcherHidden ? '桌面图标已隐藏，应用仍在后台运行' : '桌面图标已恢复');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const desktop = useMutation({
    mutationFn: (enabled: boolean) => api('/api/device/desktop', 'PUT', { enabled }),
    onSuccess: (result: any) => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success(
        result.enabled
          ? result.isDefault
            ? '已设为电视桌面'
            : '已启用，请在电视上选择「题了个题」为桌面'
          : '已关闭，电视回到原来的桌面',
      );
    },
    onError: (error: any) => toast.error(error.message),
  });
  const chooseDesktop = useMutation({
    mutationFn: () => api('/api/device/desktop/choose', 'POST', {}),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('电视上已弹出桌面选择，用遥控器选「题了个题」');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const rename = useMutation({
    mutationFn: (nextName: string) => api('/api/device', 'PUT', { name: nextName.trim() }),
    onSuccess: (_result, nextName) => {
      const savedName = nextName.trim();
      setName(current => (current === nextName ? savedName : current));
      client.setQueryData(['device', sessionId], (old: any) =>
        old ? { ...old, name: savedName } : old,
      );
      client.invalidateQueries({ queryKey: ['device', sessionId] });
    },
    onError: (error: any) => {
      submittedName.current = '';
      toast.error(error.message);
    },
  });
  const saveName = () => {
    const nextName = name.trim();
    if (
      !nextName ||
      !device.data ||
      nextName === (device.data.name || '').trim() ||
      rename.isPending
    ) {
      return;
    }
    submittedName.current = name;
    rename.mutate(nextName);
  };
  const saveEndpoint = () => {
    const next = endpoint.trim();
    if (!device.data || crash.isPending) return;
    if (next === (device.data.crashUpload?.endpoint || '').trim()) return;
    crash.mutate({ endpoint: next });
  };
  const saveToken = () => {
    const next = token.trim();
    // 令牌只写不读：留空表示不改动已保存的值
    if (!next || crash.isPending) return;
    crash.mutate({ token: next }, { onSuccess: () => setToken('') });
  };

  useEffect(() => {
    const nextName = name.trim();
    if (
      !nextName ||
      !device.data ||
      nextName === (device.data.name || '').trim() ||
      rename.isPending ||
      submittedName.current === name
    ) {
      return;
    }
    const timer = window.setTimeout(() => {
      if (submittedName.current === name) return;
      submittedName.current = name;
      rename.mutate(nextName);
    }, 700);
    return () => window.clearTimeout(timer);
  }, [name, device.data?.name, rename.isPending, sessionId]);

  async function handleExportBackup() {
    if (exportingBackup) return;
    setExportingBackup(true);
    try {
      const data = await api(
        `/api/backup?includeQuestions=${includeQuestions ? 1 : 0}`,
        'GET',
        undefined,
        60000,
      );
      const now = new Date();
      const ymd = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
      download(data, `studyontv-backup-${ymd}.json`);
      toast.success('整机备份已导出');
    } catch (error: any) {
      toast.error(error?.message || '导出整机备份失败');
    } finally {
      setExportingBackup(false);
    }
  }

  async function handlePickBackupFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (backupFileRef.current) backupFileRef.current.value = '';
    if (!file) return;
    try {
      const text = (await file.text()).replace(/^\uFEFF/, '').trim();
      const parsed = JSON.parse(text);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('备份文件格式不正确');
      }
      setRestoreMode('replace');
      setPendingBackup({ fileName: file.name, data: parsed });
    } catch (error: any) {
      toast.error(error?.message ? `读取备份失败：${error.message}` : '备份文件不是有效的 JSON');
    }
  }

  const restoreBackup = useMutation({
    mutationFn: ({ data, mode }: { data: any; mode: 'replace' | 'merge' }) =>
      api(`/api/backup/restore?mode=${mode}`, 'POST', { backup: data, mode }, 60000),
    onSuccess: (res: any) => {
      const summary = res?.summary || res || {};
      const backupObj = pendingBackup?.data?.backup || pendingBackup?.data || {};
      const kidsCount =
        summary.children ??
        summary.childrenRestored ??
        summary.childCount ??
        (Array.isArray(backupObj.children) ? backupObj.children.length : 1);
      const qCount =
        summary.questions ??
        summary.questionsRestored ??
        summary.added ??
        summary.questionCount ??
        (Array.isArray(backupObj.questions)
          ? backupObj.questions.length
          : Array.isArray(backupObj.items)
            ? backupObj.items.length
            : 0);
      setPendingBackup(null);
      client.invalidateQueries();
      toast.success(
        res?.message || `已恢复 ${kidsCount} 个孩子档案、${qCount} 道题目及相关学习记录`,
      );
    },
    onError: (error: any) => toast.error(error?.message || '恢复备份失败'),
  });

  const check = useMutation({
    mutationFn: () => api('/api/update/check', 'POST', {}),
    onSuccess: result => {
      client.setQueryData(['update', sessionId], result);
      setPolling(true);
      toast.message('正在检查更新…');
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
      pollTimer.current = window.setTimeout(() => {
        pollTimer.current = null;
        setPolling(false);
      }, 70000);
    },
    onError: (error: any) => toast.error(error.message),
  });

  const installUpdate = useMutation({
    mutationFn: () => api('/api/update/install', 'POST', {}),
    onSuccess: (result: any) => {
      client.setQueryData(['update', sessionId], result);
      client.invalidateQueries({ queryKey: ['update', sessionId] });
      const cap = result?.silentCapability ?? update.data?.silentCapability;
      const silent = cap === 'device_owner' || cap === 'self_update';
      if (result?.installState === 'installing' || update.data?.apkReady) {
        toast.message(
          silent ? '安装包已就绪，正在后台静默安装…' : '安装包已就绪，正在设备上拉起安装…',
        );
      } else {
        toast.message('已开始在设备上下载更新包…');
      }
    },
    onError: (error: any) => toast.error(error?.message || '触发在线更新失败'),
  });

  const updateSettings = useMutation({
    mutationFn: (body: { autoDownload: boolean }) => api('/api/update', 'PUT', body),
    onSuccess: (result: any, variables) => {
      client.setQueryData(['update', sessionId], (old: any) =>
        result && typeof result === 'object'
          ? { ...old, ...result }
          : old
            ? { ...old, ...variables }
            : result,
      );
      client.invalidateQueries({ queryKey: ['update', sessionId] });
      const enabled =
        typeof result?.autoDownload === 'boolean' ? result.autoDownload : variables.autoDownload;
      toast.success(enabled ? '已开启自动后台下载更新包' : '已关闭自动后台下载更新包');
    },
    onError: (error: any) => toast.error(error?.message || '保存更新设置失败'),
  });

  const [password, setPassword] = useState({ current: '', next: '', confirm: '' });
  const changePassword = useMutation({
    mutationFn: () =>
      api('/api/password', 'POST', { current: password.current, password: password.next }),
    onSuccess: (res: any) => {
      setPassword({ current: '', next: '', confirm: '' });
      if (res?.token) {
        setSessionToken(res.token);
        toast.success('管理密码已修改，当前设备登录状态已自动续期');
      } else {
        toast.success('管理密码已修改，请用新密码重新登录');
        onPasswordChanged();
      }
    },
    onError: (error: any) => toast.error(error.message),
  });
  function submitPassword() {
    if (password.next.length < 6 || password.next.length > 64) {
      return toast.error('新密码需要 6–64 位');
    }
    if (password.next !== password.confirm) return toast.error('两次输入的新密码不一致');
    changePassword.mutate();
  }

  const integration = useQuery({
    queryKey: ['integration', sessionId],
    queryFn: () => api('/api/integration'),
  });
  const [scope, setScope] = useState({ read: true, control: false });
  const [issued, setIssued] = useState('');
  useEffect(() => {
    if (integration.data) {
      setScope({ read: !!integration.data.read, control: !!integration.data.control });
    }
  }, [integration.data?.read, integration.data?.control, integration.data?.hasToken]);
  const issue = useMutation({
    mutationFn: () => api('/api/integration', 'POST', scope),
    onSuccess: (result: any) => {
      setIssued(result.token);
      client.setQueryData(['integration', sessionId], result.integration);
      toast.success('集成令牌已生成');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const patch = useMutation({
    mutationFn: (body: any) => api('/api/integration', 'PUT', body),
    onSuccess: (result: any) => {
      client.setQueryData(['integration', sessionId], result);
      toast.success('已保存');
    },
    onError: (error: any) => {
      if (integration.data) {
        setScope({ read: !!integration.data.read, control: !!integration.data.control });
      }
      toast.error(error.message);
    },
  });
  const revoke = useMutation({
    mutationFn: () => api('/api/integration', 'DELETE'),
    onSuccess: (result: any) => {
      client.setQueryData(['integration', sessionId], result);
      setIssued('');
      toast.success('集成令牌已吊销');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const copy = async (value: string) => {
    const result = await copyText(value);
    if (result === 'ok') toast.success('已复制');
    else toast.error('复制失败，请手动选中文字复制');
  };

  const integrationBase = `http://${device.data?.ip || '设备IP'}:${device.data?.port || 8099}`;
  const displayToken = issued || '<集成令牌>';
  const stateCurl = `curl "${integrationBase}/api/state" -H "Authorization: Bearer ${displayToken}"`;
  const unlockCurl = `curl -X POST "${integrationBase}/api/remote" -H "Authorization: Bearer ${displayToken}" -H "Content-Type: application/json" -d '{"action":"unlock","minutes":30}'`;
  const haYaml = `sensor:\n  - platform: rest\n    name: 题了个题\n    resource: ${integrationBase}/api/state\n    headers:\n      Authorization: Bearer <集成令牌>\n    value_template: "{{ value_json.learning.remainingSeconds }}"\n    json_attributes:\n      - learning\n      - today\n    scan_interval: 60`;
  const updateInfo = update.data as
    | {
        hasUpdate?: boolean;
        latest?: string;
        notes?: string;
        url?: string;
        checking?: boolean;
        checkedAt?: number;
        logAt?: number;
        log?: any[];
        installError?: string;
        installState?: 'idle' | 'downloading' | 'installing' | 'error';
        installProgress?: number;
        installMessage?: string;
        autoDownload?: boolean;
        apkReady?: boolean;
        downloadedVersion?: string;
        silentCapability?: 'device_owner' | 'self_update' | 'confirm';
      }
    | undefined;
  const installState = updateInfo?.installState;
  const installProgress = Math.min(
    100,
    Math.max(0, Math.round(Number(updateInfo?.installProgress) || 0)),
  );
  const installMessage = String(updateInfo?.installMessage || '');
  const installingNow = installState === 'downloading' || installState === 'installing';
  const silentCapability = updateInfo?.silentCapability;
  const supportsSilentUpdate =
    silentCapability === 'device_owner' || silentCapability === 'self_update';
  const apkReady = Boolean(updateInfo?.hasUpdate && updateInfo?.apkReady);
  const readyVersion = String(updateInfo?.downloadedVersion || updateInfo?.latest || '');
  const failedSources = ((updateInfo?.log || []) as any[]).filter(item => item && !item.ok);
  const latestVer = updateInfo?.latest || '新版';
  const apkName =
    device.data?.flavor === 'tv'
      ? `StudyOnTV-Android-TV-v${latestVer}.apk`
      : `StudyOnTV-Mobile-v${latestVer}.apk`;
  const adbScript = `adb connect ${device.data?.ip || '电视IP'}:5555\nadb install -r ${apkName}`;

  return (
    <>
      <div className="space-y-4">
        {only === 'device' && (
          <>
          <SettingsCard
            title="设备"
            icon={MonitorSmartphone}
            description={
              device.data
                ? `${device.data.flavor === 'tv' ? '电视版' : '手机 / 平板版'}${device.data.desktop?.supported ? ' · 内测版' : ''} v${device.data.version} · ${device.data.ip || '未连接 WiFi'}:${device.data.port}`
                : undefined
            }
          >
            {device.data?.autostart && (
              <SettingItem title="开机自启" description={autostartNote(device.data.autostart)}>
                <span
                  className={
                    device.data.autostart.ok
                      ? 'text-sm text-emerald-600 dark:text-emerald-400'
                      : device.data.autostart.pending
                        ? 'text-sm text-muted-foreground'
                        : 'text-sm text-amber-600 dark:text-amber-400'
                  }
                >
                  {!device.data.autostart.known
                    ? '无法判断'
                    : device.data.autostart.ok
                      ? '正常'
                      : device.data.autostart.pending
                        ? '待重启确认'
                        : '可能被拦截'}
                </span>
              </SettingItem>
            )}
            <SettingItem
              title="后台保活"
              description="逐项检查服务和权限"
              wideControl
            >
              <div className="flex items-center gap-2">
                {keepalive && (
                  <span
                    className={
                      keepalive.level === 'ok'
                        ? 'text-sm text-emerald-600 dark:text-emerald-400'
                        : keepalive.level === 'warn'
                          ? 'text-sm text-amber-600 dark:text-amber-400'
                          : 'text-sm text-muted-foreground'
                    }
                  >
                    {keepalive.text}
                  </span>
                )}
                {onOpenKeepAlive && (
                  <Button size="sm" variant="outline" onClick={onOpenKeepAlive}>
                    查看自检
                  </Button>
                )}
              </div>
            </SettingItem>
            {device.data?.monitor && (
              <SettingItem title="管控能力" description={monitorNote(device.data.monitor)} wideControl>
                <div className="flex flex-wrap gap-1.5 sm:justify-end">
                  {[
                    ['服务', device.data.monitor.service],
                    ['悬浮窗', device.data.monitor.overlay],
                    ['使用情况', device.data.monitor.usage],
                    ['无障碍', device.data.monitor.accessibility],
                  ].map(([label, available]: any) => (
                    <Badge
                      key={label}
                      variant="outline"
                      className={
                        available
                          ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300'
                          : 'bg-amber-50 text-amber-700 dark:bg-amber-400/10 dark:text-amber-300'
                      }
                    >
                      {available ? <Check /> : null}
                      {label}
                    </Badge>
                  ))}
                </div>
              </SettingItem>
            )}
            {!!device.data?.monitor?.lastError && (
              <SettingItem
                title="最近一次弹窗失败"
                description={device.data.monitor.lastError}
              >
                <span className="text-xs text-muted-foreground">可反馈给开发者</span>
              </SettingItem>
            )}
            <SettingItem
              title="防卸载"
              description={
                device.data?.deviceOwner
                  ? '由设备所有者权限持续执行系统级卸载限制。'
                  : '设备管理员防桌面直删；配合无障碍权限与「不允许进系统设置与应用管理」可同时拦截从设置卸载。'
              }
            >
              {device.data?.uninstallProtected ? (
                <Button
                  size="sm"
                  variant="outline"
                  className="border-destructive/40 text-destructive hover:bg-destructive/10"
                  onClick={() => {
                    if (confirm('关闭后孩子可以直接卸载应用，确认吗？')) disableGuard.mutate();
                  }}
                >
                  关闭防卸载
                </Button>
              ) : device.data?.deviceOwner ? (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={enableGuard.isPending}
                  onClick={() => enableGuard.mutate()}
                >
                  恢复防卸载
                </Button>
              ) : (
                <span className="text-sm text-muted-foreground">未开启</span>
              )}
            </SettingItem>
            <SettingItem
              title="隐藏桌面图标"
              description="桌面和电视应用列表不再显示图标，孩子看不到也点不到；管控、后台服务照常运行。要恢复：点通知栏的「使用时间管理」通知打开应用，或在这里关闭。"
              wideControl
            >
              <Switch
                checked={!!device.data?.launcherHidden}
                disabled={launcher.isPending || !device.data}
                onCheckedChange={hidden => {
                  if (hidden && !confirm('隐藏后桌面不再显示图标。恢复方法：点通知栏的「使用时间管理」通知，或在家长面板这里关闭。确认隐藏吗？')) return;
                  launcher.mutate(hidden);
                }}
                aria-label="隐藏桌面图标"
              />
            </SettingItem>
            {/* 默认发的包不带电视桌面，设备会报 supported: false，这时整项隐藏 */}
            {device.data?.desktop?.supported !== false && (
            <SettingItem
              title="把题了个题设为电视桌面"
              description={
                device.data?.desktop?.isDefault
                  ? '当前就是电视桌面：开机和按 Home 都回到「题了个题」。想换回去，把开关关掉即可。'
                  : device.data?.desktop?.enabled
                    ? `已启用，但系统默认桌面还是「${device.data.desktop.homeLabel || '原桌面'}」。电视上没有弹出选择框的话，请到系统设置 → 应用 → 默认应用里把桌面改成「题了个题」。`
                    : '开启后，电视开机和按 Home 都回到「题了个题」，孩子不能再从系统桌面点开任意应用；时间用完时桌面直接显示锁屏和答题。部分电视需要手动改默认桌面，随时可以关掉。'
              }
              wideControl
            >
              <div className="flex flex-wrap items-center justify-end gap-2">
                {device.data?.desktop?.enabled && !device.data?.desktop?.isDefault && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={chooseDesktop.isPending}
                    onClick={() => chooseDesktop.mutate()}
                  >
                    去选择桌面
                  </Button>
                )}
                <Switch
                  checked={!!device.data?.desktop?.enabled}
                  disabled={desktop.isPending || !device.data}
                  onCheckedChange={enabled => {
                    if (
                      enabled &&
                      !confirm('开启后，孩子按 Home 也回不到系统桌面，只能从「题了个题」的应用列表进入。确认开启吗？')
                    )
                      return;
                    if (!enabled && !confirm('关闭后电视会回到原来的桌面，孩子可以按 Home 回到系统桌面。确认关闭吗？'))
                      return;
                    desktop.mutate(enabled);
                  }}
                  aria-label="设为电视桌面"
                />
              </div>
            </SettingItem>
            )}
            <SettingItem
              title="浏览器练习"
              description="供 iPad 或旧设备使用，题库独立。"
            >
              <Button variant="link" className="h-auto p-0" asChild>
                <a
                  target="_blank"
                  rel="noreferrer"
                  href={`http://${device.data?.ip || location.hostname}:${device.data?.port || 8099}/practice`}
                >
                  打开练习页
                </a>
              </Button>
            </SettingItem>
            {!localOnly && (
              <>
                <SettingItem
                  title="参与软件体验提升计划"
                  description="帮助我们持续优化设备兼容性与使用体验，可随时关闭。"
                >
                  <Switch
                    aria-label="参与软件体验提升计划"
                    checked={!!device.data?.crashUpload?.consent}
                    disabled={crash.isPending || !device.data}
                    onCheckedChange={value => crash.mutate({ consent: value })}
                  />
                </SettingItem>
                <details className="group py-2">
                  <summary className="flex cursor-pointer list-none items-center gap-1.5 py-2 text-xs text-muted-foreground transition-colors hover:text-foreground [&::-webkit-details-marker]:hidden">
                    <ChevronDown className="size-3 transition-transform group-open:rotate-180" />
                    高级自定义上报地址
                  </summary>
                  <div className="divide-y divide-border/60">
                    <SettingItem
                      title="上报地址"
                      description={
                        device.data?.crashUpload?.pending
                          ? '有待上传日志，下次启动会自动补传。'
                          : device.data?.crashUpload?.builtInEndpoint
                            ? '不填就用内置地址；填了会覆盖内置值。支持自定义接收端。'
                            : '自定义接收端地址，留空表示仅本地保留。'
                      }
                      wideControl
                    >
                      <Input
                        aria-label="日志上报地址"
                        className="h-9 w-full sm:w-80"
                        placeholder={device.data?.crashUpload?.builtInEndpoint || 'https://example.com/crash'}
                        autoComplete="off"
                        spellCheck={false}
                        value={endpoint}
                        onChange={event => setEndpoint(event.target.value)}
                        onBlur={saveEndpoint}
                        onKeyDown={event => {
                          if (event.key === 'Enter') event.currentTarget.blur();
                        }}
                      />
                    </SettingItem>
                    <SettingItem
                      title="访问令牌"
                      description={
                        device.data?.crashUpload?.builtInToken
                          ? '已内置令牌，留空不改；填了会覆盖它。'
                          : device.data?.crashUpload?.hasToken
                            ? '已保存令牌。留空表示不修改。'
                            : '用于校验自定义上报接口权限，可留空。'
                      }
                      wideControl
                    >
                      <Input
                        aria-label="崩溃上报令牌"
                        type="password"
                        className="h-9 w-full sm:w-80"
                        placeholder={device.data?.crashUpload?.hasToken ? '已保存，留空不修改' : '输入访问令牌（可选）'}
                        autoComplete="off"
                        spellCheck={false}
                        value={token}
                        onChange={event => setToken(event.target.value)}
                        onBlur={saveToken}
                        onKeyDown={event => {
                          if (event.key === 'Enter') event.currentTarget.blur();
                        }}
                      />
                    </SettingItem>
                  </div>
                </details>
              </>
            )}
            <SettingItem title="设备名称" wideControl>
              <div className="flex w-full items-center gap-2 sm:w-auto">
                <Input
                  aria-label="设备名称"
                  aria-invalid={!name.trim()}
                  className="h-9 w-full sm:w-56"
                  maxLength={32}
                  autoComplete="off"
                  value={name}
                  onChange={event => setName(event.target.value)}
                  onBlur={saveName}
                  onKeyDown={event => {
                    if (event.key === 'Enter') event.currentTarget.blur();
                  }}
                />
                <span
                  className="flex min-w-16 items-center justify-end gap-1 text-xs text-muted-foreground"
                  aria-live="polite"
                >
                  {rename.isPending ? (
                    <>
                      <LoaderCircle className="animate-spin" />
                      保存中
                    </>
                  ) : !name.trim() ? (
                    '不能为空'
                  ) : name.trim() === (device.data?.name || '').trim() ? (
                    <>
                      <Check />
                      已保存
                    </>
                  ) : (
                    '自动保存'
                  )}
                </span>
              </div>
            </SettingItem>
          </SettingsCard>

          <SettingsCard
            title="数据备份与换机迁移"
            icon={HardDriveDownload}
            description="一键打包全部孩子档案、规则计划、学习记录与题库配图，换新电视或新平板可直接恢复。"
          >
            <SettingItem
              title="导出整机备份"
              description="导出为单文件 JSON，保存在手机或电脑本地；不含管理密码与云端密钥。"
              wideControl
            >
              <div className="flex flex-wrap items-center justify-end gap-3">
                <Field orientation="horizontal" className="w-auto gap-2">
                  <Checkbox
                    id="backup-include-questions"
                    checked={includeQuestions}
                    onCheckedChange={checked => setIncludeQuestions(checked === true)}
                  />
                  <FieldLabel htmlFor="backup-include-questions" className="font-normal text-xs">
                    包含题库与配图
                  </FieldLabel>
                </Field>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={exportingBackup}
                  onClick={handleExportBackup}
                >
                  {exportingBackup ? <LoaderCircle className="animate-spin" /> : <Download />}
                  {exportingBackup ? '正在打包…' : '导出整机备份'}
                </Button>
              </div>
            </SettingItem>
            <SettingItem
              title="恢复备份"
              description="从之前导出的 studyontv-backup-*.json 恢复数据，支持全量覆盖换机或合并导入。"
              wideControl
            >
              <input
                ref={backupFileRef}
                type="file"
                accept=".json,application/json"
                className="hidden"
                onChange={handlePickBackupFile}
              />
              <Button
                size="sm"
                variant="outline"
                disabled={restoreBackup.isPending}
                onClick={() => backupFileRef.current?.click()}
              >
                <Upload />
                选择备份文件恢复
              </Button>
            </SettingItem>
          </SettingsCard>
          </>
        )}

        {only === 'security' && (
          <SettingsCard
            title="管理密码"
            icon={KeyRound}
            description="修改后其他设备需重新验证，当前操作设备会自动保持登录。"
            footer={
              <Button
                disabled={
                  changePassword.isPending || !password.current || !password.next || !password.confirm
                }
                onClick={submitPassword}
              >
                {changePassword.isPending ? <LoaderCircle className="animate-spin" /> : null}
                修改密码
              </Button>
            }
          >
            <FieldGroup className="grid gap-4 py-4 sm:grid-cols-3">
              <Field>
                <FieldLabel htmlFor="current-password">当前密码</FieldLabel>
                <Input
                  id="current-password"
                  type="password"
                  autoComplete="current-password"
                  value={password.current}
                  onChange={event => setPassword({ ...password, current: event.target.value })}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="new-password">新密码</FieldLabel>
                <Input
                  id="new-password"
                  type="password"
                  autoComplete="new-password"
                  placeholder="6–64 位"
                  value={password.next}
                  onChange={event => setPassword({ ...password, next: event.target.value })}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="confirm-password">确认新密码</FieldLabel>
                <Input
                  id="confirm-password"
                  type="password"
                  autoComplete="new-password"
                  value={password.confirm}
                  onChange={event => setPassword({ ...password, confirm: event.target.value })}
                />
              </Field>
            </FieldGroup>
          </SettingsCard>
        )}

        {only === 'integration' && (
          <SettingsCard
            title="家庭自动化"
            icon={RadioTower}
            description="供 Home Assistant 等使用，可吊销。"
          >
            <SettingItem title="启用接口" description="只接受局域网请求。">
              <Switch
                aria-label="启用家庭自动化接口"
                checked={!!integration.data?.enabled}
                onCheckedChange={enabled => patch.mutate({ enabled })}
              />
            </SettingItem>
            <SettingItem
              title="集成令牌"
              description={
                integration.data?.hasToken
                  ? `${integration.data.hint} · 创建于 ${new Date(integration.data.createdAt).toLocaleString('zh-CN', { hour12: false })}`
                  : '尚未生成。令牌只在创建时显示一次。'
              }
            >
              <Button
                variant="outline"
                onClick={() => issue.mutate()}
                disabled={issue.isPending || (!scope.read && !scope.control)}
              >
                {integration.data?.hasToken ? '重新生成' : '生成令牌'}
              </Button>
              {integration.data?.hasToken && (
                <Button
                  variant="ghost"
                  onClick={() => {
                    if (window.confirm('吊销后使用该令牌的系统会立即断开，确定吗？')) revoke.mutate();
                  }}
                  disabled={revoke.isPending}
                >
                  吊销
                </Button>
              )}
            </SettingItem>
            <SettingItem title="权限" description="只读：查询状态；控制：解锁或暂停。">
              <div className="grid w-full gap-3 sm:w-auto sm:grid-cols-2">
                <Field orientation="horizontal" className="w-auto">
                  <Checkbox
                    id="integration-read"
                    checked={scope.read}
                    onCheckedChange={read => {
                      if (!read && !scope.control) return toast.error('至少保留一种权限');
                      setScope({ ...scope, read: !!read });
                      patch.mutate({ read: !!read });
                    }}
                  />
                  <FieldLabel htmlFor="integration-read" className="font-normal">
                    只读
                  </FieldLabel>
                </Field>
                <Field orientation="horizontal" className="w-auto">
                  <Checkbox
                    id="integration-control"
                    checked={scope.control}
                    onCheckedChange={control => {
                      if (!control && !scope.read) return toast.error('至少保留一种权限');
                      setScope({ ...scope, control: !!control });
                      patch.mutate({ control: !!control });
                    }}
                  />
                  <FieldLabel htmlFor="integration-control" className="font-normal">
                    控制
                  </FieldLabel>
                </Field>
              </div>
            </SettingItem>
            <div className="py-3.5">
              <Accordion type="single" collapsible>
                <AccordionItem value="examples">
                  <AccordionTrigger>接口示例与 Home Assistant 配置</AccordionTrigger>
                  <AccordionContent className="space-y-4">
                    {[
                      ['读取状态', stateCurl],
                      ['临时解锁 30 分钟', unlockCurl],
                      ['Home Assistant REST sensor', haYaml],
                    ].map(([label, value]) => (
                      <div key={label} className="space-y-2">
                        <div className="flex items-center justify-between gap-3">
                          <span className="text-xs font-medium">{label}</span>
                          <Button size="sm" variant="ghost" onClick={() => copy(value)}>
                            <Copy />
                            复制
                          </Button>
                        </div>
                        <pre className="overflow-x-auto rounded-lg bg-muted/45 p-3 text-xs">{value}</pre>
                      </div>
                    ))}
                    <p className="text-xs leading-5 text-muted-foreground">
                      只开放给同一局域网，不要做端口转发。
                    </p>
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
            </div>
          </SettingsCard>
        )}

        {only === 'update' && (
          <SettingsCard title="版本更新" icon={CloudDownload}>
            {localOnly ? (
              <SettingItem
                title="纯本地版本"
                description="当前为纯本地版本，不包含在线检查更新或数据上报功能。"
              >
                <Badge variant="secondary">仅本地运行</Badge>
              </SettingItem>
            ) : (
            <>
            <SettingItem
              title={updateInfo?.hasUpdate ? `有新版本 v${updateInfo.latest}` : '当前已是最新版本'}
              description={
                <span className="inline-flex flex-wrap items-center gap-2">
                  <span>上次检查：{formatTime(startedAt)}</span>
                  {apkReady && readyVersion && (
                    <Badge
                      variant="outline"
                      className="bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                    >
                      <Check />
                      已预下载 v{readyVersion}
                    </Badge>
                  )}
                </span>
              }
              wideControl
            >
              <div className="flex flex-wrap items-center justify-end gap-2">
                {updateInfo?.hasUpdate && (
                  <Button
                    disabled={installUpdate.isPending || installingNow}
                    onClick={() => installUpdate.mutate()}
                  >
                    {installUpdate.isPending || installingNow ? (
                      <LoaderCircle className="animate-spin" />
                    ) : (
                      <Download />
                    )}
                    {installState === 'downloading'
                      ? `下载中 ${installProgress}%`
                      : installState === 'installing'
                        ? supportsSilentUpdate
                          ? '正在静默安装…'
                          : '正在拉起安装…'
                        : installState === 'error'
                          ? '重试在线更新'
                          : apkReady
                            ? '立即安装（安装包已就绪）'
                            : '立即在线更新'}
                  </Button>
                )}
                <Button
                  variant="outline"
                  disabled={check.isPending || polling || installingNow}
                  onClick={() => check.mutate()}
                >
                  <RefreshCw className={polling ? 'animate-spin' : ''} />
                  {polling ? '检查中…' : '检查更新'}
                </Button>
              </div>
            </SettingItem>
            <SettingItem
              title="自动后台下载更新包"
              description="检测到新版本时自动在后台预下载并校验安装包，点击更新即可快速安装。"
            >
              <Switch
                aria-label="自动后台下载更新包"
                checked={!!updateInfo?.autoDownload}
                disabled={updateSettings.isPending || !updateInfo}
                onCheckedChange={autoDownload => updateSettings.mutate({ autoDownload })}
              />
            </SettingItem>
            {silentCapability && (
              <SettingItem
                title="后台升级能力"
                description={
                  supportsSilentUpdate
                    ? '后台自动完成安装，装完自动恢复服务。'
                    : '支持 Web 端一键下载，下载完成后请在设备屏幕上确认安装。'
                }
              >
                {supportsSilentUpdate ? (
                  <Badge
                    variant="outline"
                    className="bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                  >
                    <Check />
                    支持官方静默更新
                  </Badge>
                ) : (
                  <Badge variant="secondary" className="text-muted-foreground">
                    需屏幕确认安装
                  </Badge>
                )}
              </SettingItem>
            )}
            {installState && installState !== 'idle' && (
              <div className="space-y-2 py-4">
                <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                  <span
                    className={
                      installState === 'error'
                        ? 'font-medium text-destructive'
                        : installState === 'installing'
                          ? 'font-medium text-emerald-600 dark:text-emerald-400'
                          : 'font-medium text-foreground'
                    }
                  >
                    {installMessage ||
                      (installState === 'downloading'
                        ? '正在设备上下载新版本安装包…'
                        : installState === 'installing'
                          ? supportsSilentUpdate
                            ? '安装包已就绪，正在后台静默安装，完成后将自动恢复服务'
                            : '安装包已下载完成，请在设备屏幕上确认安装'
                          : '在线更新失败，请检查设备网络后重试')}
                  </span>
                  {installState === 'downloading' && (
                    <span className="tabular-nums text-muted-foreground">{installProgress}%</span>
                  )}
                  {installState === 'error' && updateInfo?.hasUpdate && (
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-7 px-2 text-xs"
                      disabled={installUpdate.isPending}
                      onClick={() => installUpdate.mutate()}
                    >
                      重试
                    </Button>
                  )}
                </div>
                {(installState === 'downloading' || installState === 'installing') && (
                  <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full rounded-full bg-primary transition-all duration-300"
                      style={{ width: `${installState === 'installing' ? 100 : installProgress}%` }}
                    />
                  </div>
                )}
              </div>
            )}
            {updateInfo?.hasUpdate && (
              <SettingItem
                title="更新说明"
                description={updateInfo.notes?.replace(/\[force\]/gi, '').trim()}
              >
                <span className="text-xs text-muted-foreground">
                  可点击上方「{apkReady ? '立即安装（安装包已就绪）' : '立即在线更新'}」推送到设备，或在设备学习页右上角选择「更新」安装。
                </span>
              </SettingItem>
            )}
            {updateInfo?.installError && (
              <SettingItem
                title="上次安装失败"
                description={`${updateInfo.installError}。电视上点「立即更新」会再试一次；系统不给装的话，需要到 设置 → 应用 → 安装未知应用 里允许「题了个题」。`}
              >
                {null}
              </SettingItem>
            )}
            {failedSources.length > 0 && (
              <SettingItem
                title="上次检查没成功"
                description={
                  <>
                    {failedSources.map(item => `${item.source}：${item.message}`).join('；')}。
                    常见原因是电视没联网、路由器拦了 DNS，或者发布服务器暂时不可用，过一会儿再点「检查更新」即可。
                  </>
                }
              >
                {null}
              </SettingItem>
            )}
            {(updateInfo?.hasUpdate || updateInfo?.installError) && (
              <>
              <SettingItem
                wideControl
                title="电视上装不上时，用电脑安装"
                description="在电视上打开 设置 → 系统 → 开发者选项，开启「USB 调试」和「网络调试」，然后在电脑终端里依次执行："
              >
                <div className="flex w-full flex-wrap justify-end gap-2 sm:w-auto">
                  {updateInfo?.url && (
                    <Button variant="outline" asChild>
                      <a href={updateInfo.url} target="_blank" rel="noreferrer">
                        下载安装包
                      </a>
                    </Button>
                  )}
                  <Button variant="outline" onClick={() => copy(adbScript)}>
                    <Copy />
                    复制命令
                  </Button>
                </div>
              </SettingItem>
              <div className="pb-4">
                <pre className="overflow-x-auto rounded-lg border border-border/70 bg-muted/40 p-3 text-xs leading-5">
                  {adbScript}
                </pre>
              </div>
              </>
            )}
            </>
            )}
          </SettingsCard>
        )}
      </div>

      <Dialog
        open={!!pendingBackup}
        onOpenChange={open => {
          if (!open && !restoreBackup.isPending) setPendingBackup(null);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ArchiveRestore className="size-4 text-primary" />
              确认恢复整机备份
            </DialogTitle>
            <DialogDescription>
              已读取备份文件 <span className="font-mono text-foreground">{pendingBackup?.fileName}</span>，请选择恢复方式：
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2.5 py-2">
            <button
              type="button"
              onClick={() => setRestoreMode('replace')}
              className={`flex w-full flex-col items-start gap-1 rounded-lg border p-3 text-left transition-colors ${
                restoreMode === 'replace'
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 hover:bg-muted/40'
              }`}
            >
              <span className="text-sm font-medium">覆盖恢复（推荐换机使用）</span>
              <span className="text-xs text-muted-foreground">
                用备份完整替换当前设备上的孩子档案、规则计划与题库记录，适合新设备迁移还原。
              </span>
            </button>
            <button
              type="button"
              onClick={() => setRestoreMode('merge')}
              className={`flex w-full flex-col items-start gap-1 rounded-lg border p-3 text-left transition-colors ${
                restoreMode === 'merge'
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 hover:bg-muted/40'
              }`}
            >
              <span className="text-sm font-medium">合并导入</span>
              <span className="text-xs text-muted-foreground">
                保留当前设备已有数据，将备份中的题库与孩子档案合并进来（相同 ID 更新）。
              </span>
            </button>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              disabled={restoreBackup.isPending}
              onClick={() => setPendingBackup(null)}
            >
              取消
            </Button>
            <Button
              disabled={restoreBackup.isPending || !pendingBackup}
              onClick={() =>
                pendingBackup &&
                restoreBackup.mutate({ data: pendingBackup.data, mode: restoreMode })
              }
            >
              {restoreBackup.isPending ? <LoaderCircle className="animate-spin" /> : null}
              {restoreBackup.isPending ? '正在恢复…' : '确认恢复'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={!!issued}
        onOpenChange={open => {
          if (!open) setIssued('');
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>复制集成令牌</DialogTitle>
            <DialogDescription>令牌只显示这一次，请保存到密码管理器。</DialogDescription>
          </DialogHeader>
          <Textarea
            aria-label="集成令牌"
            className="min-h-20 break-all font-mono text-xs"
            readOnly
            value={issued}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setIssued('')}>
              已保存
            </Button>
            <Button onClick={() => copy(issued)}>
              <Copy />
              复制令牌
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
