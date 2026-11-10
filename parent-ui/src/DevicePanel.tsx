import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Check,
  CloudDownload,
  Copy,
  KeyRound,
  LoaderCircle,
  MonitorSmartphone,
  RadioTower,
  RefreshCw,
} from 'lucide-react';
import { toast } from 'sonner';
import { keepAliveSummary } from './keepalive';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
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
import { api } from './api';
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
    <Card className="gap-0 overflow-hidden py-0 shadow-xs">
      <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
            <Icon className="size-4" />
          </span>
          {title}
        </CardTitle>
        {description && <CardDescription className="text-xs leading-5">{description}</CardDescription>}
      </CardHeader>
      <CardContent className="border-t p-0">
        <ItemGroup className="divide-y divide-border/60">{children}</ItemGroup>
      </CardContent>
      {footer && (
        <CardFooter className="justify-end gap-2 border-t px-4 py-3 sm:px-6">{footer}</CardFooter>
      )}
    </Card>
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
      className={`items-start gap-3 rounded-none px-4 py-4 sm:px-6 ${wideControl ? 'flex-col sm:flex-row' : ''}`}
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
  const update = useQuery({
    queryKey: ['update', sessionId],
    queryFn: () => api('/api/update'),
    refetchInterval: polling ? 2000 : false,
  });
  const [name, setName] = useState('');
  const nameSession = useRef<number | null>(null);
  const submittedName = useRef('');
  const keepalive = device.data?.keepalive ? keepAliveSummary(device.data) : null;

  useEffect(() => {
    if (device.data && nameSession.current !== sessionId) {
      setName(device.data.name || '');
      nameSession.current = sessionId;
      submittedName.current = '';
    }
  }, [device.data?.name, sessionId]);

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
        toast.error('检查更新失败，请稍后再试');
      } else {
        toast.success('当前已是最新版本');
      }
    }
  }, [polling, update.data?.checking, update.data?.hasUpdate]);

  const bugly = useMutation({
    mutationFn: (enabled: boolean) => api('/api/device/bugly', 'PUT', { enabled }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success(device.data?.buglyConsent ? '崩溃报告已关闭，下次启动生效' : '崩溃报告已开启');
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
    onError: (error: any) => toast.error(error.message),
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

  const check = useMutation({
    mutationFn: () => api('/api/update/check', 'POST', {}),
    onSuccess: result => {
      client.setQueryData(['update', sessionId], result);
      setPolling(true);
      toast.message('正在检查更新…');
      window.setTimeout(() => setPolling(false), 70000);
    },
    onError: (error: any) => toast.error(error.message),
  });

  const [password, setPassword] = useState({ current: '', next: '', confirm: '' });
  const changePassword = useMutation({
    mutationFn: () =>
      api('/api/password', 'POST', { current: password.current, password: password.next }),
    onSuccess: () => {
      toast.success('管理密码已修改，请用新密码重新登录');
      setPassword({ current: '', next: '', confirm: '' });
      onPasswordChanged();
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
    onError: (error: any) => toast.error(error.message),
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
  const updateInfo = update.data;

  return (
    <>
      <div className="space-y-4">
        {only === 'device' && (
          <SettingsCard
            title="设备"
            icon={MonitorSmartphone}
            description={
              device.data
                ? `${device.data.flavor === 'tv' ? '电视版' : '手机 / 平板版'} v${device.data.version} · ${device.data.ip || '未连接 WiFi'}:${device.data.port}`
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
              description="服务、权限和开机自启逐项检查，并按品牌给出设置步骤"
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
                          ? 'border-emerald-600/30 text-emerald-700 dark:text-emerald-400'
                          : 'border-amber-600/30 text-amber-700 dark:text-amber-400'
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
                  ? '由设备所有者权限持续执行卸载限制。'
                  : '普通设备管理员；部分电视的厂商卸载入口可能不受保护。'
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
              title="浏览器练习"
              description="供 iPad 或旧设备使用；题库与学习端相互独立。"
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
            <SettingItem
              title="崩溃报告"
              description="仅上传调用堆栈、设备型号和应用版本。"
            >
              <Switch
                aria-label="崩溃报告"
                checked={!!device.data?.buglyConsent}
                onCheckedChange={value => bugly.mutate(value)}
              />
            </SettingItem>
            <SettingItem title="设备名称" description="修改后自动保存。" wideControl>
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
        )}

        {only === 'security' && (
          <SettingsCard
            title="管理密码"
            icon={KeyRound}
            description="修改后，所有家长设备都需要重新登录。"
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
            <FieldGroup className="grid gap-4 px-4 py-4 sm:grid-cols-3 sm:px-6">
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
            description="供 Home Assistant 等局域网系统使用，可随时吊销。"
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
            <SettingItem title="权限" description="只读用于查询状态，控制用于解锁或暂停。">
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
            <div className="px-4 py-2 sm:px-6">
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
            <SettingItem
              title={updateInfo?.hasUpdate ? `有新版本 v${updateInfo.latest}` : '当前已是最新版本'}
              description={`上次检查：${formatTime(startedAt)}`}
            >
              <Button
                variant="outline"
                disabled={check.isPending || polling}
                onClick={() => check.mutate()}
              >
                <RefreshCw className={polling ? 'animate-spin' : ''} />
                {polling ? '检查中…' : '检查更新'}
              </Button>
            </SettingItem>
            {updateInfo?.hasUpdate && (
              <SettingItem
                title="更新说明"
                description={updateInfo.notes?.replace(/\[force\]/gi, '').trim()}
              >
                <span className="text-xs text-muted-foreground">在学习页右上角选择“更新”即可安装。</span>
              </SettingItem>
            )}
          </SettingsCard>
        )}
      </div>

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
