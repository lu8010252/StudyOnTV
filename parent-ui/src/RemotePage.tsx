import { useEffect, useState, type CSSProperties } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowUpRight,
  BookOpen,
  BookX,
  ChartColumn,
  Check,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  Clock3,
  Flame,
  GraduationCap,
  LockKeyhole,
  LockKeyholeOpen,
  Pause,
  Play,
  Repeat2,
  RotateCcw,
  Sparkles,
} from 'lucide-react';
import { toast } from 'sonner';
import { api } from './api';
import { ClearExtraPoolButton } from './components/ClearExtraPoolButton';
import { defaults } from './schema';
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
  DialogTrigger,
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

function MiniWatchBars({ days }: { days: any[] }) {
  const ordered = days
    .slice()
    .reverse()
    .map(d => ({
      label: String(d?.label || '—'),
      watch: Math.max(0, Number(d?.watch) || 0),
    }));
  const max = Math.max(10, ...ordered.map(d => d.watch));
  if (!ordered.length) return null;
  return (
    <div className="flex h-full items-end gap-1.5" role="img" aria-label="近 7 天使用时长缩略柱状图">
      {ordered.map((d, i) => (
        <div
          key={`${d.label}-${i}`}
          className="group relative flex h-full min-w-0 flex-1 items-end justify-center"
          title={`${d.label}：${d.watch.toFixed(1)} 分钟`}
        >
          <span
            className="block w-full max-w-4 rounded-t-[5px] rounded-b-[2px] bg-[var(--chart-3)] transition-opacity group-hover:opacity-80"
            style={{
              height: `${Math.max(d.watch > 0 ? 10 : 3, (d.watch / max) * 100)}%`,
              opacity: d.watch > 0 ? 1 : 0.25,
            }}
          />
        </div>
      ))}
    </div>
  );
}

function SevenDayWatchChart({ days }: { days: any[] }) {
  const chartDays = days
    .slice()
    .reverse()
    .map(d => ({
      label: String(d?.label || '—'),
      watch: Math.max(0, Number(d?.watch) || 0),
      answered: Math.max(0, Number(d?.answered) || 0),
      accuracy: Math.min(100, Math.max(0, Math.round(Number(d?.accuracy) || 0))),
    }));
  const chartMax = Math.max(
    40,
    Math.ceil(Math.max(1, ...chartDays.map(d => d.watch)) / 10) * 10,
  );
  const ticks = Array.from({ length: 5 }, (_, index) =>
    Math.round(chartMax - (chartMax / 4) * index),
  );
  return (
    <div
      className="grid h-64 grid-cols-[30px_minmax(0,1fr)] gap-2"
      role="img"
      aria-label="最近 7 天每日使用时长柱状图"
    >
      <div className="flex flex-col justify-between pb-7 pt-1 text-right text-xs tabular-nums text-muted-foreground">
        {ticks.map(t => (
          <span key={t}>{t}</span>
        ))}
      </div>
      <div className="relative min-w-0 pb-7">
        <div className="pointer-events-none absolute inset-x-0 bottom-7 top-1 flex flex-col justify-between">
          {ticks.map(t => (
            <span key={t} className="border-t border-dashed border-border/70" />
          ))}
        </div>
        <div className="relative z-10 flex h-[calc(100%-1.75rem)] items-end justify-around gap-2">
          {chartDays.map((day, index) => (
            <div
              key={`${day.label}-${index}`}
              className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
              title={`${day.label}：使用 ${day.watch.toFixed(1)} 分钟，答题 ${day.answered} 题`}
            >
              <div className="pointer-events-none absolute -top-7 z-20 hidden whitespace-nowrap rounded-md border bg-popover px-2 py-0.5 text-[11px] text-popover-foreground shadow-sm group-hover:block">
                {day.watch.toFixed(1)} 分钟
              </div>
              <div className="relative flex w-full max-w-7 flex-1 items-end overflow-hidden rounded-t-md bg-muted/55">
                <span
                  className="block w-full rounded-t-[5px] bg-[var(--chart-1)] transition-[height] duration-300 group-hover:opacity-85"
                  style={{
                    height: `${(day.watch / chartMax) * 100}%`,
                    minHeight: day.watch > 0 ? 5 : 0,
                  }}
                />
              </div>
              <span className="absolute -bottom-7 whitespace-nowrap text-xs text-muted-foreground">
                {day.label}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

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
  const study = state?.study || {};
  const supervisionEnabled = state?.config?.supervisionEnabled !== false;
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

  const switchMode = useMutation({
    mutationFn: (patch: Record<string, any>) =>
      api('/api/config', 'PUT', { ...defaults, ...(state?.config || {}), ...patch }),
    onSuccess: (result, patch) => {
      client.setQueryData(['state', sessionId], (old: any) =>
        old ? { ...old, config: result.config } : old,
      );
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      if ('supervisionEnabled' in patch) {
        toast.success(
          patch.supervisionEnabled
            ? '已切换为「先学再看管控模式」'
            : '已切换为「纯粹学习提分模式」，免锁屏随时刷题背诵',
        );
      } else {
        toast.success('学习配置已更新');
      }
    },
    onError: (error: any) => toast.error(error.message),
  });

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
  const title = !supervisionEnabled
    ? '纯粹学习模式 · 不锁屏'
    : mode === 'indefinite'
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
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [showChart, setShowChart] = useState(false);
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
  const blocked =
    supervisionEnabled && (mode === 'lock' || (!mode && !learning.free && !!learning.reason));
  const unlimited = !supervisionEnabled || mode === 'indefinite';
  const remaining = mode ? left : (learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0);
  const open = (action: 'unlock' | 'lock') => setCustom({ mode: action, minutes: '30' });

  const quickStudy = !!(state.config?.memorizeFirst || state.config?.spacedRepetitionEnabled);

  return (
    <div className="space-y-4 pb-6">
      <Toolbar
        aria-label="首页工具栏"
        className="flex-wrap items-center justify-between gap-3 px-0 py-1"
      >
        <div className="min-w-0">
          <p className="text-xs font-medium tracking-wide text-muted-foreground">设备管理</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">今日概览</h1>
        </div>
        {supervisionEnabled && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={run.isPending}
              onClick={() => {
                setAdjustSign(1);
                setCustom({ mode: 'credit', minutes: '15' });
              }}
            >
              调整剩余时间
            </Button>
            {!mode && (
              <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                <DialogTrigger asChild>
                  <Button variant="destructive" size="sm" disabled={run.isPending}>
                    <LockKeyholeOpen />
                    关闭管控
                  </Button>
                </DialogTrigger>
                <DialogContent className="max-w-md">
                  <DialogHeader>
                    <DialogTitle>确认关闭管控？</DialogTitle>
                    <DialogDescription>
                      关闭后孩子可以无限使用，直到您手动恢复学习规则。如果只想当刷题背诵软件用，建议直接切换上方的「纯粹学习提分模式」。
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button variant="outline" onClick={() => setConfirmOpen(false)}>
                      取消
                    </Button>
                    <Button
                      variant="destructive"
                      disabled={run.isPending}
                      onClick={() => {
                        go('indefinite', 0, '管控已关闭');
                        setConfirmOpen(false);
                      }}
                    >
                      确认关闭
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
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
        )}
      </Toolbar>

      {/* 模式切换与低门槛新手引导卡 */}
      <Card className="gap-3 border-primary/20 bg-gradient-to-br from-primary/[0.04] via-card to-card py-4 shadow-xs">
        <CardHeader className="pb-0">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              <GraduationCap className="size-4 text-primary" />
              运行模式选择
            </CardTitle>
            {Number(study.streakDays) > 0 && (
              <Badge variant="secondary" className="gap-1">
                <Flame className="size-3.5 text-amber-500" />
                连续学习 {study.streakDays} 天
              </Badge>
            )}
          </div>
          <CardDescription className="text-xs">
            无需繁琐系统权限也能直接当成 Anki / 多邻国式智能错题本与背诵提分工具使用，随时一键切换。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-2.5 sm:grid-cols-2">
            <button
              type="button"
              disabled={switchMode.isPending}
              onClick={() => {
                if (supervisionEnabled) switchMode.mutate({ supervisionEnabled: false });
              }}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3.5 text-left transition-colors ${
                !supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">📚 纯粹学习提分模式</span>
                {!supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />当前启用
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                免锁屏、免复杂权限，打开即是闪卡背诵、错题先背再测与艾宾浩斯复习中心
              </span>
            </button>
            <button
              type="button"
              disabled={switchMode.isPending}
              onClick={() => {
                if (!supervisionEnabled) switchMode.mutate({ supervisionEnabled: true });
              }}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3.5 text-left transition-colors ${
                supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">⏳ 先学再看管控模式</span>
                {supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />当前启用
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                答题达标赢取屏幕使用时间，到点自动提醒休息与学习
              </span>
            </button>
          </div>

          {!supervisionEnabled && (
            <div className="rounded-xl border border-border/70 bg-background/80 p-3.5">
              <div className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-semibold text-foreground">🚀 3 步快速提分上手</span>
                <span className="text-xs text-muted-foreground">
                  今日已练 {study.todayAnswered || 0} 题 · 待复习 {study.dueCount || 0} 题 ·{' '}
                  <button
                    type="button"
                    className="font-medium text-primary underline-offset-4 hover:underline"
                    onClick={() => (location.hash = '/wrong')}
                  >
                    错题 {study.wrongCount || 0} 道
                  </button>
                </span>
              </div>
              <div className="grid gap-2 sm:grid-cols-3">
                <button
                  type="button"
                  onClick={() => {
                    location.hash = '/bank';
                  }}
                  className="flex items-center justify-between gap-2 rounded-lg border border-border/60 bg-card px-3 py-2.5 text-left text-xs transition-colors hover:bg-accent"
                >
                  <div>
                    <div className="font-medium">1. 导入题库或 AI 出题</div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">
                      {state?.questions ? `已有 ${state.questions} 题` : '10 秒模板生成'}
                    </div>
                  </div>
                  <BookOpen className="size-4 shrink-0 text-primary" />
                </button>
                <button
                  type="button"
                  disabled={switchMode.isPending}
                  onClick={() => {
                    if (!quickStudy) {
                      switchMode.mutate({
                        memorizeFirst: true,
                        spacedRepetitionEnabled: true,
                      });
                    } else {
                      location.hash = '/rules';
                    }
                  }}
                  className="flex items-center justify-between gap-2 rounded-lg border border-border/60 bg-card px-3 py-2.5 text-left text-xs transition-colors hover:bg-accent"
                >
                  <div>
                    <div className="font-medium">2. 开启先背再测 / 间隔重复</div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">
                      {quickStudy ? '已开启记忆提分策略 ✓' : '点击一键开启科学背题'}
                    </div>
                  </div>
                  <Repeat2 className="size-4 shrink-0 text-primary" />
                </button>
                <button
                  type="button"
                  onClick={() => {
                    location.hash = '/records';
                  }}
                  className="flex items-center justify-between gap-2 rounded-lg border border-border/60 bg-card px-3 py-2.5 text-left text-xs transition-colors hover:bg-accent"
                >
                  <div>
                    <div className="font-medium">3. 学情页追踪薄弱项</div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">
                      查看错题榜并一键举一反三
                    </div>
                  </div>
                  <ChartColumn className="size-4 shrink-0 text-primary" />
                </button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <Card className="h-full gap-4 rounded-md border-sky-200/70 bg-sky-50/55 shadow-sm sm:col-span-2 xl:col-span-1 dark:border-sky-400/15 dark:bg-sky-400/[0.055]">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Clock3 className="size-4 text-sky-600 dark:text-sky-400" />
              {supervisionEnabled ? '设备使用状态' : '今日学习进度'}
            </CardTitle>
            <CardDescription>
              <span className={`me-2 inline-block size-2 rounded-full ${blocked ? 'bg-rose-500' : 'bg-emerald-500'}`} />
              {title}
            </CardDescription>
            <CardAction>
              <Badge variant="outline" className="bg-background/70">
                {!supervisionEnabled ? '自主刷题' : mode ? '家庭遥控中' : '学习规则生效'}
              </Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col justify-end">
            {supervisionEnabled ? (
              <>
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
                  <strong className="text-3xl font-semibold tracking-tight tabular-nums">
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
              </>
            ) : (
              <>
                <p className="text-xs text-muted-foreground">今日已练习题目</p>
                <div className="mt-2 flex items-end gap-2">
                  <strong className="text-3xl font-semibold tracking-tight tabular-nums">
                    {study.todayAnswered || 0}
                  </strong>
                  <span className="pb-1 text-sm text-muted-foreground">
                    题{study.todayAnswered > 0 ? ` · 正确率 ${Math.round(((study.todayCorrect || 0) * 100) / study.todayAnswered)}%` : ''}
                  </span>
                </div>
              </>
            )}
          </CardContent>
          <CardFooter className="border-t border-sky-200/60 pt-4 dark:border-sky-400/10">
            {supervisionEnabled ? (
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
            ) : (
              <Button
                variant="outline"
                size="sm"
                className="bg-background/75"
                onClick={() => (location.hash = '/records')}
              >
                <ChartColumn />
                查看学情提分报告
              </Button>
            )}
          </CardFooter>
        </Card>

        <Card className="h-full gap-4 rounded-md shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Clock3 className="size-4" />
              </span>
              今日已用
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col">
            <p className="text-2xl font-semibold tracking-tight tabular-nums">
              {Number(used.toFixed(1))}
              <span className="ms-1 text-sm font-normal text-muted-foreground">分钟</span>
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {!supervisionEnabled || limit === null ? '每日时长不限' : `今日上限 ${limit} 分钟`}
            </p>
            <div className="mt-auto h-20 pt-3">
              {days.length > 0 && <MiniWatchBars days={days} />}
            </div>
          </CardContent>
        </Card>

        <Card className="h-full gap-4 rounded-md shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <ClipboardCheck className="size-4" />
              </span>
              今日学习任务
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col items-center justify-between gap-3">
            <p className="self-start text-2xl font-semibold tracking-tight tabular-nums">
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
          <CardTitle className="text-sm">今日计划</CardTitle>
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
              label: supervisionEnabled ? '答题奖励' : '记忆模式',
              value: !supervisionEnabled
                ? state.config?.memorizeFirst
                  ? '先背再测'
                  : '闪卡 + 闯关'
                : state.config?.difficultyRewardEnabled
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
              label: supervisionEnabled ? '待完成加练' : '待攻克错题',
              value: `${supervisionEnabled ? learning.extraPool || 0 : study.wrongCount || 0} 题`,
              href: '/wrong',
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
            <CardTitle className="text-sm">使用时间</CardTitle>
            <CardAction>
              <Button
                variant="ghost"
                size="sm"
                aria-expanded={showChart}
                onClick={() => setShowChart(v => !v)}
              >
                <ChevronDown className={`transition-transform ${showChart ? 'rotate-180' : ''}`} />
                {showChart ? '收起' : '查看 7 天记录'}
              </Button>
            </CardAction>
          </CardHeader>
          {showChart && (
            <CardContent>
              <div className="min-w-0">
                {days.length ? (
                  <SevenDayWatchChart days={days} />
                ) : (
                  <FieldDescription className="py-16 text-center">暂无使用记录</FieldDescription>
                )}
              </div>
            </CardContent>
          )}
        </Card>

        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="text-sm">设备控制</CardTitle>
            <CardDescription>{supervisionEnabled ? '临时调整会自动恢复' : '快捷进入学习管理'}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-1 px-3">
            {supervisionEnabled ? (
              <>
                <Button
                  variant="ghost"
                  className="h-auto w-full justify-start gap-3 px-3 py-3"
                  disabled={run.isPending}
                  onClick={() => open('unlock')}
                >
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
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
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
                    <Pause className="size-4" />
                  </span>
                  <span className="text-start">
                    <span className="block text-sm font-medium">暂停使用</span>
                    <span className="block text-xs font-normal text-muted-foreground">设置设备暂停时长</span>
                  </span>
                  <ChevronRight className="ms-auto" />
                </Button>
                <Button
                  variant="ghost"
                  className="h-auto w-full justify-start gap-3 px-3 py-3"
                  disabled={run.isPending}
                  onClick={() => {
                    setAdjustSign(1);
                    setCustom({ mode: 'credit', minutes: '15' });
                  }}
                >
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
                    <Clock3 className="size-4" />
                  </span>
                  <span className="text-start">
                    <span className="block text-sm font-medium">调整剩余时间</span>
                    <span className="block text-xs font-normal text-muted-foreground">临时奖励或扣减今日时长</span>
                  </span>
                  <ChevronRight className="ms-auto" />
                </Button>
              </>
            ) : (
              <>
                <Button
                  variant="ghost"
                  className="h-auto w-full justify-start gap-3 px-3 py-3"
                  onClick={() => (location.hash = '/bank')}
                >
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
                    <Sparkles className="size-4" />
                  </span>
                  <span className="text-start">
                    <span className="block text-sm font-medium">AI 错题举一反三出题</span>
                    <span className="block text-xs font-normal text-muted-foreground">针对薄弱考点一键生成巩固练习</span>
                  </span>
                  <ChevronRight className="ms-auto" />
                </Button>
                <Button
                  variant="ghost"
                  className="h-auto w-full justify-start gap-3 px-3 py-3"
                  onClick={() => (location.hash = '/wrong')}
                >
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
                    <BookX className="size-4" />
                  </span>
                  <span className="text-start">
                    <span className="block text-sm font-medium">查看错题库</span>
                    <span className="block text-xs font-normal text-muted-foreground">
                      当前 {study.wrongCount || 0} 道错题，可一键加入加练池
                    </span>
                  </span>
                  <ChevronRight className="ms-auto" />
                </Button>
                <Button
                  variant="ghost"
                  className="h-auto w-full justify-start gap-3 px-3 py-3"
                  onClick={() => (location.hash = '/records')}
                >
                  <span className="flex size-5 items-center justify-center text-muted-foreground">
                    <ChartColumn className="size-4" />
                  </span>
                  <span className="text-start">
                    <span className="block text-sm font-medium">查看学情与记忆曲线</span>
                    <span className="block text-xs font-normal text-muted-foreground">掌握度分布、顽固错题与复习预测</span>
                  </span>
                  <ChevronRight className="ms-auto" />
                </Button>
              </>
            )}
          </CardContent>
          <Separator />
          <CardContent className="space-y-2 py-4 text-sm">
            <div className="flex items-center justify-between gap-4">
              <span className="text-muted-foreground">每组题量</span>
              <span>{state.config?.questionsPerRound || 0} 题</span>
            </div>
            <div className="flex items-center justify-between gap-4">
              <span className="text-muted-foreground">{supervisionEnabled ? '通过一组解锁' : '复习范围'}</span>
              <span>
                {!supervisionEnabled
                  ? state.config?.spacedScope === 'wrong_only'
                    ? '只背错题'
                    : '全部启用题目'
                  : state.config?.difficultyRewardEnabled
                    ? '按星级奖励'
                    : `${state.config?.sessionMinutes || 0} 分钟`}
              </span>
            </div>
            <div className="flex items-center justify-between gap-2 pt-1">
              <span className="text-muted-foreground">
                加练池：<strong className="font-medium text-foreground tabular-nums">{learning.extraPool || 0}</strong> 题
              </span>
              <ClearExtraPoolButton
                sessionId={sessionId}
                count={Number(learning.extraPool) || 0}
                size="sm"
                className="h-7 px-2 text-xs"
              />
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
          <CardTitle className="text-sm">学习记录</CardTitle>
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
                        <span className="text-xs tabular-nums text-muted-foreground"><b className="font-medium">{day.accuracy}</b>%</span>
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
                ? `当前余额 ${Math.ceil(((learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0)) / 60)} 分钟；只调整今天，不改每日规则。`
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
