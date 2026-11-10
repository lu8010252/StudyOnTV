import { useEffect, useState, type CSSProperties } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowUpRight,
  BookOpen,
  ChevronRight,
  ClipboardCheck,
  Clock3,
  LockKeyhole,
  LockKeyholeOpen,
  Pause,
  Play,
  RotateCcw,
  Sparkles,
} from 'lucide-react';
import { toast } from 'sonner';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from './api';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { FieldDescription } from '@/components/ui/field';
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemMedia,
  ItemTitle,
} from '@/components/ui/item';
import { Separator } from '@/components/ui/separator';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Toolbar, ToolbarGroup } from '@/components/ui/toolbar';

const fmt = (seconds: number) => {
  const minutes = Math.ceil(seconds / 60);
  if (minutes >= 60) {
    return `${Math.floor(minutes / 60)} 小时${minutes % 60 ? ` ${minutes % 60} 分` : ''}`;
  }
  return `${minutes} 分钟`;
};

const minutesToMidnight = () => {
  const now = new Date();
  const end = new Date(now);
  end.setHours(24, 0, 0, 0);
  return Math.max(1, Math.round((Number(end) - Number(now)) / 60000));
};

const reasonText: Record<string, string> = {
  quota: '今天的使用时间已用完',
  rounds: '今天的任务已全部完成',
  schedule: '不在可用时段',
  timeup: '需要先完成学习任务',
  rest: '强制休息中',
  feedback: '请先查看答题解析',
  '': '可以使用',
};

export default function RemotePage({ state, sessionId }: { state: any; sessionId: number }) {
  const client = useQueryClient();
  const reviews = useQuery({
    queryKey: ['reviews', sessionId],
    queryFn: () => api('/api/reviews'),
    refetchInterval: 15000,
  });
  const pendingCount = reviews.data?.items?.length || 0;
  const history = useQuery({
    queryKey: ['history', sessionId],
    queryFn: () => api('/api/history?days=7'),
  });
  const days = history.data?.items || [];
  const learning = state?.learning;
  const override = learning?.override;

  const [now, setNow] = useState(Date.now());
  const [base, setBase] = useState({ at: Date.now(), left: 0 });
  useEffect(() => {
    setBase({ at: Date.now(), left: override?.remainingSeconds || 0 });
  }, [override?.remainingSeconds, override?.at]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const left = Math.max(0, base.left - Math.max(0, Math.floor((now - base.at) / 1000)));

  const run = useMutation({
    mutationFn: (body: { action: string; minutes?: number; label: string }) =>
      api('/api/remote', 'POST', { action: body.action, minutes: body.minutes || 0 }),
    onSuccess: (result, body) => {
      client.setQueryData(['state', sessionId], (old: any) =>
        old ? { ...old, learning: result.learning } : old,
      );
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      toast.success(body.label);
    },
    onError: (error: any) => toast.error(error.message),
  });
  const go = (action: string, minutes: number, label: string) =>
    run.mutate({ action, minutes, label });
  const mode = override?.mode as 'unlock' | 'lock' | 'indefinite' | undefined;
  const title =
    mode === 'indefinite'
      ? '管控已关闭（无限时）'
      : mode === 'unlock'
        ? '已临时解锁'
        : mode === 'lock'
          ? '已锁定'
          : learning?.free
            ? '自由时段'
            : learning?.reason
              ? `管控中 · ${reasonText[learning.reason] || '暂不可用'}`
              : '管控中 · 可以使用';

  const [adjustSign, setAdjustSign] = useState(1);
  const [custom, setCustom] = useState<{
    mode: 'unlock' | 'lock' | 'indefinite' | 'credit';
    minutes: string;
  } | null>(null);

  function submitCustom() {
    const minutes = Number(custom?.minutes);
    if (!custom) return;
    if (custom.mode === 'credit') {
      if (!Number.isInteger(minutes) || minutes < 1 || minutes > 480) {
        return toast.error('请输入 1–480 分钟');
      }
      go(
        'credit',
        minutes * adjustSign,
        adjustSign > 0 ? `已加时 ${minutes} 分钟` : `已收走 ${minutes} 分钟`,
      );
      setCustom(null);
      return;
    }
    if (
      custom.mode !== 'indefinite' &&
      (!Number.isInteger(minutes) ||
        minutes < 1 ||
        minutes > (custom.mode === 'unlock' ? 1440 : 10080))
    ) {
      return toast.error(
        custom.mode === 'unlock' ? '请输入 1–1440 分钟' : '请输入 1–10080 分钟',
      );
    }
    go(
      custom.mode,
      minutes,
      custom.mode === 'unlock' ? `已解锁 ${fmt(minutes * 60)}` : `已锁定 ${fmt(minutes * 60)}`,
    );
    setCustom(null);
  }

  if (!learning) return <FieldDescription className="py-16 text-center">正在连接设备…</FieldDescription>;
  const used = Number(learning.usedMinutes || 0);
  const limit =
    learning.todayLimitMinutes === null
      ? null
      : Number(learning.todayLimitMinutes ?? state.config?.dailyMinutes ?? 40);
  const blocked = mode === 'lock' || (!mode && !learning.free && !!learning.reason);
  const unlimited = mode === 'indefinite';
  const remaining = mode ? left : (learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0);
  const open = (action: 'unlock' | 'lock') => setCustom({ mode: action, minutes: '30' });

  return (
    <div className="space-y-4 pb-6">
      <Toolbar
        aria-label="首页工具栏"
        className="items-center justify-between gap-3 px-0 py-1"
      >
        <div className="min-w-0">
          <p className="text-[11px] font-medium tracking-wide text-muted-foreground">设备管理</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight sm:text-3xl">今日概览</h1>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            className="hidden lg:inline-flex"
            disabled={run.isPending}
            onClick={() => {
              setAdjustSign(1);
              setCustom({ mode: 'credit', minutes: '15' });
            }}
          >
            调整剩余时间
          </Button>
          {!mode && (
            <Button
              variant="destructive"
              size="sm"
              disabled={run.isPending}
              onClick={() => {
                if (window.confirm('关闭管控后孩子可无限使用，直到您手动恢复，确定吗？')) {
                  go('indefinite', 0, '管控已关闭');
                }
              }}
            >
              <LockKeyholeOpen />
              关闭管控
            </Button>
          )}
          {mode === 'indefinite' && (
            <Button
              size="sm"
              disabled={run.isPending}
              onClick={() => go('cancel', 0, '管控已恢复')}
            >
              <LockKeyhole />
              恢复管控
            </Button>
          )}
        </div>
      </Toolbar>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-12">
        <Card className="h-full gap-5 border-sky-200/70 bg-sky-50/55 shadow-sm md:col-span-2 xl:col-span-6 dark:border-sky-400/15 dark:bg-sky-400/[0.055]">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Clock3 className="size-4 text-sky-600 dark:text-sky-400" />
              设备使用状态
            </CardTitle>
            <CardDescription>
              <span className={`me-2 inline-block size-2 rounded-full ${blocked ? 'bg-rose-500' : 'bg-emerald-500'}`} />
              {title}
            </CardDescription>
            <CardAction>
              <Badge variant="outline" className="bg-background/70">
                {mode ? '家庭遥控中' : '学习规则生效'}
              </Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col justify-end">
            <p className="text-xs text-muted-foreground">
              {unlimited
                ? '管控状态'
                : mode === 'lock'
                  ? '暂停剩余'
                  : mode === 'unlock'
                    ? '临时解锁剩余'
                    : '剩余可用时间'}
            </p>
            <div className="mt-2 flex items-end gap-2">
              <strong className="text-4xl font-semibold tracking-[-1.5px] tabular-nums sm:text-5xl">
                {unlimited
                  ? '不限时'
                  : learning.free && !mode
                    ? '自由'
                    : blocked && !mode
                      ? '暂停'
                      : Math.ceil(remaining / 60)}
              </strong>
              {!unlimited && !(!mode && (learning.free || blocked)) && (
                <span className="pb-1 text-sm text-muted-foreground">分钟</span>
              )}
            </div>
          </CardContent>
          <CardFooter className="border-t border-sky-200/60 pt-4 dark:border-sky-400/10">
            <Button
              variant="outline"
              size="sm"
              className="bg-background/75"
              disabled={run.isPending}
              onClick={() => (mode ? go('cancel', 0, '已恢复学习规则') : open('lock'))}
            >
              {mode ? <RotateCcw /> : <Pause />}
              {mode ? '恢复学习规则' : '暂停使用'}
            </Button>
          </CardFooter>
        </Card>

        <Card className="h-full gap-4 shadow-sm xl:col-span-3">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="grid size-7 place-items-center rounded-lg bg-amber-100 text-amber-700 dark:bg-amber-400/10 dark:text-amber-300">
                <Clock3 className="size-4" />
              </span>
              今日已用
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col">
            <p className="text-3xl font-semibold tracking-tight tabular-nums">
              {Number(used.toFixed(1))}
              <span className="ms-1 text-sm font-normal text-muted-foreground">分钟</span>
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {limit === null ? '每日时长不限' : `今日上限 ${limit} 分钟`}
            </p>
            <div className="mt-auto h-20 pt-3">
              {days.length > 0 && (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={days.slice().reverse()}>
                    <Bar dataKey="watch" fill="var(--chart-3)" maxBarSize={16} radius={[5, 5, 2, 2]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </div>
          </CardContent>
        </Card>

        <Card className="h-full gap-4 shadow-sm xl:col-span-3">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="grid size-7 place-items-center rounded-lg bg-emerald-100 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300">
                <ClipboardCheck className="size-4" />
              </span>
              今日学习任务
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col items-center justify-between gap-3">
            <p className="self-start text-3xl font-semibold tracking-tight tabular-nums">
              {learning.roundsDoneToday || 0}
              <span className="ms-1 text-sm font-normal text-muted-foreground">
                / {state.config?.roundsPerDay || 0} 组
              </span>
            </p>
            <div
              className="dashboard-ring"
              style={
                {
                  '--completion': `${Math.min(
                    100,
                    ((learning.roundsDoneToday || 0) /
                      Math.max(1, state.config?.roundsPerDay || 1)) *
                      100,
                  )}%`,
                } as CSSProperties
              }
            >
              <div>
                <strong>
                  {Math.max(
                    0,
                    (state.config?.roundsPerDay || 0) - (learning.roundsDoneToday || 0),
                  )}
                </strong>
                <span>组待完成</span>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="gap-0 shadow-sm">
        <CardHeader>
          <CardTitle className="text-base">今日计划</CardTitle>
          <CardDescription>按当前学习规则汇总</CardDescription>
          <CardAction>
            <Button variant="ghost" size="sm" onClick={() => (location.hash = '/rules')}>
              调整
              <ArrowUpRight />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t p-0">
          <ItemGroup className="grid sm:grid-cols-2 lg:grid-cols-4">
          {[
            { icon: BookOpen, label: '每组题量', value: `${state.config?.questionsPerRound || 0} 题` },
            {
              icon: Clock3,
              label: '答题奖励',
              value: state.config?.difficultyRewardEnabled
                ? '按星级奖励'
                : `${state.config?.sessionMinutes || 0} 分钟`,
            },
            {
              icon: ClipboardCheck,
              label: '待批改',
              value: `${pendingCount} 份`,
              href: '/grading',
            },
            {
              icon: Sparkles,
              label: '待完成加练',
              value: `${learning.extraPool || 0} 题`,
              href: '/records',
            },
          ].map(item => (
            <Item key={item.label} size="sm" className="rounded-none border-b px-4 py-3 last:border-b-0 sm:border-e sm:last:border-e-0 lg:border-b-0">
              <ItemMedia variant="icon"><item.icon /></ItemMedia>
              <ItemContent className="min-w-0 gap-0.5">
                <ItemDescription>{item.label}</ItemDescription>
                <ItemTitle className="truncate">{item.value}</ItemTitle>
              </ItemContent>
              {item.href && (
                <ItemActions>
                  <Button variant="ghost" size="icon" className="size-8" aria-label={`查看${item.label}`} onClick={() => (location.hash = item.href!)}>
                    <ChevronRight />
                  </Button>
                </ItemActions>
              )}
            </Item>
          ))}
          </ItemGroup>
        </CardContent>
      </Card>

      {pendingCount > 0 && (
        <Alert className="border-amber-200 bg-amber-50/55 dark:border-amber-400/20 dark:bg-amber-400/[0.055]">
          <ClipboardCheck className="text-amber-700 dark:text-amber-300" />
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span className="font-medium">{pendingCount} 份作业待批改</span>
            <Button size="sm" variant="outline" className="ms-auto bg-background/70" onClick={() => (location.hash = '/grading')}>去批改</Button>
          </AlertDescription>
        </Alert>
      )}
      {!state?.questions && (
        <Alert className="border-amber-200 bg-amber-50/55 dark:border-amber-400/20 dark:bg-amber-400/[0.055]">
          <BookOpen className="text-amber-700 dark:text-amber-300" />
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span className="font-medium">题库还没有题目</span>
            <Button size="sm" variant="outline" className="ms-auto bg-background/70" onClick={() => (location.hash = '/bank')}>添加题目</Button>
          </AlertDescription>
        </Alert>
      )}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="shadow-sm lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">使用时间</CardTitle>
            <CardDescription>最近 7 天实际使用时长</CardDescription>
            <CardAction className="text-xs text-muted-foreground">最近 7 天</CardAction>
          </CardHeader>
          <CardContent>
            <div className="h-64 min-w-0">
              {days.length ? (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={days.slice().reverse()} margin={{ top: 12, right: 8, bottom: 0, left: -15 }}>
                    <CartesianGrid vertical={false} stroke="var(--border)" strokeDasharray="3 3" />
                    <XAxis
                      dataKey="label"
                      axisLine={false}
                      tickLine={false}
                      tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                    />
                    <YAxis
                      axisLine={false}
                      tickLine={false}
                      tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                    />
                    <Tooltip
                      formatter={(value: any) => [`${Number(value).toFixed(1)} 分钟`, '使用时间']}
                      contentStyle={{
                        border: '1px solid var(--border)',
                        borderRadius: 10,
                        background: 'var(--popover)',
                        fontSize: 12,
                      }}
                      cursor={{ fill: 'var(--muted)' }}
                    />
                    <Bar dataKey="watch" fill="var(--chart-1)" maxBarSize={28} radius={[5, 5, 2, 2]} />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <FieldDescription className="py-16 text-center">暂无使用记录</FieldDescription>
              )}
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="text-base">设备控制</CardTitle>
            <CardDescription>临时调整后会自动恢复学习规则</CardDescription>
          </CardHeader>
          <CardContent className="space-y-1 px-3">
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              disabled={run.isPending}
              onClick={() => open('unlock')}
            >
              <span className="grid size-9 place-items-center rounded-xl bg-sky-100 text-sky-700 dark:bg-sky-400/10 dark:text-sky-300">
                <Play className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">临时解锁</span>
                <span className="block text-xs font-normal text-muted-foreground">到时自动恢复学习规则</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              disabled={run.isPending}
              onClick={() => open('lock')}
            >
              <span className="grid size-9 place-items-center rounded-xl bg-muted text-muted-foreground">
                <Pause className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">暂停使用</span>
                <span className="block text-xs font-normal text-muted-foreground">设置设备暂停时长</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
          </CardContent>
          <Separator />
          <CardContent className="space-y-2 py-4 text-sm">
            <div className="flex items-center justify-between gap-4">
              <span className="text-muted-foreground">每组题量</span>
              <span>{state.config?.questionsPerRound || 0} 题</span>
            </div>
            <div className="flex items-center justify-between gap-4">
              <span className="text-muted-foreground">通过一组解锁</span>
              <span>
                {state.config?.difficultyRewardEnabled
                  ? '按星级奖励'
                  : `${state.config?.sessionMinutes || 0} 分钟`}
              </span>
            </div>
          </CardContent>
          <CardFooter className="border-t pt-4">
            <Button variant="ghost" size="sm" onClick={() => (location.hash = '/rules')}>
              调整学习计划
              <ChevronRight />
            </Button>
          </CardFooter>
        </Card>
      </div>

      <Card className="gap-0 overflow-hidden shadow-sm">
        <CardHeader>
          <CardTitle className="text-base">学习记录</CardTitle>
          <CardAction>
            <Button variant="ghost" size="sm" onClick={() => (location.hash = '/records')}>
              查看全部
              <ChevronRight />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="px-0">
          {days.length ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="ps-6">日期</TableHead>
                  <TableHead>答题数量</TableHead>
                  <TableHead>正确率</TableHead>
                  <TableHead>完成任务</TableHead>
                  <TableHead className="pe-6 text-end">使用时间</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {days.slice(0, 5).map((day: any) => (
                  <TableRow key={day.label}>
                    <TableCell className="ps-6">{day.label}</TableCell>
                    <TableCell className="text-muted-foreground">{day.answered} 题</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary">{day.accuracy}%</Badge>
                        <div className="h-1.5 w-14 overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-emerald-500"
                            style={{ width: `${day.accuracy}%` }}
                          />
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{day.rounds} 组</TableCell>
                    <TableCell className="pe-6 text-end text-muted-foreground">
                      {Number(day.watch).toFixed(1)} 分钟
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <FieldDescription className="py-10 text-center">暂无学习记录</FieldDescription>
          )}
        </CardContent>
      </Card>

      <Dialog
        open={!!custom}
        onOpenChange={open => {
          if (!open) setCustom(null);
        }}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {custom?.mode === 'credit'
                ? '调整剩余时间'
                : custom?.mode === 'lock'
                  ? '暂停使用'
                  : '临时解锁'}
            </DialogTitle>
            <DialogDescription>
              {custom?.mode === 'credit'
                ? `当前余额 ${Math.ceil(((learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0)) / 60)} 分钟。只调整今天的余额和总额度，不修改每日规则。`
                : custom?.mode === 'lock'
                  ? '锁定期间孩子不能答题，到时间自动恢复；家长可随时解除。'
                  : '期间不需要答题，也不计入使用时间；到时间自动恢复规则。'}
            </DialogDescription>
          </DialogHeader>
          {custom?.mode === 'credit' && (
            <ToolbarGroup className="grid grid-cols-2" aria-label="调整方向">
              <Button
                type="button"
                variant={adjustSign === 1 ? 'secondary' : 'outline'}
                onClick={() => setAdjustSign(1)}
              >
                加时
              </Button>
              <Button
                type="button"
                variant={adjustSign === -1 ? 'secondary' : 'outline'}
                onClick={() => setAdjustSign(-1)}
              >
                提前收走
              </Button>
            </ToolbarGroup>
          )}
          <ToolbarGroup className="grid grid-cols-2 gap-2" aria-label="常用时长">
            {(custom?.mode === 'credit'
              ? ([
                  [5, '5 分钟'],
                  [10, '10 分钟'],
                  [15, '15 分钟'],
                  [30, '30 分钟'],
                ] as const)
              : ([
                  [30, '30 分钟'],
                  [60, '1 小时'],
                  [120, '2 小时'],
                  [minutesToMidnight(), '到今晚 24 点'],
                ] as const)
            ).map(([minutes, label]) => (
              <Button
                key={String(label)}
                type="button"
                variant={custom?.minutes === String(minutes) ? 'secondary' : 'outline'}
                onClick={() =>
                  setCustom(current => (current ? { ...current, minutes: String(minutes) } : current))
                }
              >
                {label}
              </Button>
            ))}
          </ToolbarGroup>
          <div className="space-y-2">
            <Label htmlFor="custom-minutes">分钟</Label>
            <Input
              id="custom-minutes"
              type="number"
              inputMode="numeric"
              min={1}
              max={custom?.mode === 'credit' ? 480 : custom?.mode === 'lock' ? 10080 : 1440}
              value={custom?.minutes || ''}
              onChange={event =>
                setCustom(current =>
                  current ? { ...current, minutes: event.target.value } : current,
                )
              }
              placeholder={custom?.mode === 'lock' ? '例如 60' : '例如 45'}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCustom(null)}>
              取消
            </Button>
            <Button onClick={submitCustom} disabled={run.isPending}>
              确定
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
