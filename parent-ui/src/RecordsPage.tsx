import { lazy, Suspense, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  Brain,
  CalendarClock,
  ChartColumn,
  Clock3,
  Flame,
  GraduationCap,
  History,
  ListChecks,
  RefreshCw,
  Sparkles,
  Target,
  TrendingDown,
  TrendingUp,
} from 'lucide-react';
import { api } from './api';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { answerOf, MathText } from './components/bank/shared';
import type { AiGeneratePreset } from './components/bank/AiGenerateDialog';

const AiGenerateDialog = lazy(() => import('./components/bank/AiGenerateDialog'));

type HistoryDay = {
  label?: string;
  watch?: number | string;
  answered?: number | string;
  accuracy?: number | string;
  rounds?: number | string;
};

type EventItem = {
  type?: string;
  payload?: Record<string, any>;
  ts?: number | string;
};

function toNumber(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function clampPercent(value: unknown) {
  return Math.min(100, Math.max(0, Math.round(toNumber(value))));
}

function formatDuration(minutes: number) {
  if (minutes >= 60) {
    const hours = minutes / 60;
    return {
      value: String(hours >= 10 ? Math.round(hours) : Math.round(hours * 10) / 10),
      unit: '小时',
    };
  }
  return { value: String(Math.round(minutes)), unit: '分钟' };
}

function eventLabel(event: EventItem) {
  const payload = event.payload || {};
  if (event.type === 'answer') return payload.correct ? '答题正确' : '答题错误';
  const labels: Record<string, string> = {
    quiz: '完成任务',
    quiz_pass: `解锁 ${payload.minutes || 0} 分钟`,
    extra_added: `加练池增加 ${payload.count || 0} 题`,
    review_submitted: '提交作答，等待批改',
    review_graded: `作答评分 ${payload.score || 0} 分`,
    question_added: '新增题目',
  };
  return labels[event.type || ''] || event.type || '学习动态';
}

function formatEventTime(value: unknown) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '时间未知';
  return new Date(timestamp).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function accuracyTone(value: number, hasAnswers: boolean) {
  if (!hasAnswers) {
    return {
      text: 'text-muted-foreground',
      dot: 'bg-muted-foreground/35',
    };
  }
  if (value >= 85) {
    return {
      text: 'text-emerald-600 dark:text-emerald-400',
      dot: 'bg-emerald-500',
    };
  }
  if (value >= 60) {
    return {
      text: 'text-amber-600 dark:text-amber-400',
      dot: 'bg-amber-500',
    };
  }
  return {
    text: 'text-rose-600 dark:text-rose-400',
    dot: 'bg-rose-500',
  };
}

function eventTone(type: string | undefined, payload: Record<string, any> | undefined) {
  if (type === 'answer') return payload?.correct ? 'bg-emerald-500' : 'bg-rose-500';
  if (type === 'quiz_pass') return 'bg-amber-500';
  if (type === 'quiz') return 'bg-sky-500';
  return 'bg-slate-400';
}

type CoverageBank = {
  name?: string;
  total?: number | string;
  covered?: number | string;
  percent?: number | string;
};

type CoverageItem = {
  id?: string;
  q?: string;
  bank?: string;
  level?: string;
  subject?: string;
  presented?: number | string;
  answered?: number | string;
};

function ListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="divide-y divide-border/70" aria-label="正在读取记录">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="flex items-center gap-3 px-4 py-3.5 sm:px-5">
          <Skeleton className="size-7 shrink-0 rounded-full" />
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <Skeleton className="h-4 w-28" />
            <Skeleton className="ms-auto h-4 w-16" />
          </div>
        </div>
      ))}
    </div>
  );
}

function CoverageCard({ sessionId }: { sessionId: number }) {
  const coverage = useQuery({
    queryKey: ['coverage', sessionId],
    queryFn: () => api('/api/coverage'),
    refetchInterval: 30000,
  });
  const data = coverage.data || {};
  const total = Math.max(0, toNumber(data.total));
  const covered = Math.max(0, toNumber(data.covered));
  const percent = clampPercent(data.percent);
  const pending = Math.max(0, toNumber(data.pending));
  const untouched = Math.max(0, toNumber(data.untouched));
  const banks: CoverageBank[] = Array.isArray(data.banks) ? data.banks : [];
  const items: CoverageItem[] = Array.isArray(data.items) ? data.items : [];
  const loading = coverage.isPending && !coverage.data;
  const shown = items.slice(0, 6);

  return (
    <Card
      className="gap-0 overflow-hidden rounded-md py-0 shadow-sm"
      aria-labelledby="records-coverage-title"
    >
      <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
        <CardTitle
          id="records-coverage-title"
          className="flex items-center gap-2 text-sm"
        >
          <Target className="size-4 text-muted-foreground" />
          答题覆盖
        </CardTitle>
        <CardDescription className="col-span-full text-xs">
          答对一次算覆盖；没答对的题会优先出给孩子，直到整库都答对一遍。
        </CardDescription>
        <CardAction>
          <Badge variant="secondary" className="font-normal tabular-nums">
            {loading ? '读取中' : `${covered} / ${total} 题`}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-5 p-0 px-4 pb-5 pt-5 sm:px-5">
        {loading ? (
          <Skeleton className="h-24 w-full" />
        ) : coverage.isError ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <Target className="size-5 text-destructive/70" />
            <FieldDescription className="text-destructive">无法读取答题覆盖情况</FieldDescription>
          </div>
        ) : total === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <Target className="size-5 text-muted-foreground/70" />
            <FieldDescription>题库里还没有启用的题目</FieldDescription>
          </div>
        ) : (
          <>
            <div>
              <div className="flex items-end justify-between gap-2">
                <span className="flex items-end gap-1 whitespace-nowrap tabular-nums">
                  <span className="text-3xl font-semibold leading-none tracking-tight">
                    {percent}
                  </span>
                  <span className="pb-0.5 text-xs text-muted-foreground">%</span>
                </span>
                <span className="text-xs text-muted-foreground">
                  {pending > 0 ? `还差 ${pending} 题答对就全覆盖` : '所有题都答对过了'}
                </span>
              </div>
              <div
                className="mt-3 h-2 w-full overflow-hidden rounded-full bg-muted/65"
                role="img"
                aria-label={`答题覆盖率 ${percent}%`}
              >
                <span
                  className="block h-full rounded-full bg-primary transition-[width] duration-500"
                  style={{ width: `${percent}%` }}
                />
              </div>
            </div>

            <dl className="grid grid-cols-3 divide-x divide-border/70 rounded-lg border border-border/70">
              {[
                { label: '已答对', value: covered },
                { label: '未答对', value: pending },
                { label: '从未出过', value: untouched },
              ].map(cell => (
                <div key={cell.label} className="min-w-0 px-3 py-3">
                  <dt className="truncate text-xs text-muted-foreground">
                    {cell.label}
                  </dt>
                  <dd className="mt-1.5 whitespace-nowrap text-lg font-semibold leading-none tabular-nums">
                    {cell.value}
                    <span className="ms-0.5 text-[11px] font-normal text-muted-foreground">
                      题
                    </span>
                  </dd>
                </div>
              ))}
            </dl>

            {banks.length > 1 && (
              <div className="space-y-2.5">
                <p className="text-xs text-muted-foreground">按题库</p>
                {banks.slice(0, 5).map(bank => {
                  const bankTotal = Math.max(0, toNumber(bank.total));
                  const bankCovered = Math.max(0, toNumber(bank.covered));
                  const bankPercent = clampPercent(bank.percent);
                  return (
                    <div key={bank.name || '默认题库'} className="space-y-1">
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <span className="truncate">{bank.name || '默认题库'}</span>
                        <span className="shrink-0 tabular-nums text-muted-foreground">
                          {bankCovered} / {bankTotal}
                        </span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                        <span
                          className="block h-full rounded-full bg-primary/80"
                          style={{ width: `${bankPercent}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {shown.length > 0 && (
              <div className="space-y-2">
                <p className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
                  <span>还没答对的题</span>
                  <span className="shrink-0 tabular-nums">共 {pending} 题</span>
                </p>
                <ol className="divide-y divide-border/70 rounded-lg border border-border/70">
                  {shown.map((item, index) => (
                    <li
                      key={item.id || index}
                      className="flex items-start gap-3 px-3 py-2.5"
                    >
                      <span className="mt-0.5 shrink-0 text-[11px] tabular-nums text-muted-foreground">
                        {index + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <MathText text={item.q || '（无题干）'} className="truncate text-sm" />
                        <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                          {item.bank || '默认题库'}
                          {item.level ? ` · ${item.level}` : ''}
                          {item.subject ? ` · ${item.subject}` : ''} · 出题{' '}
                          {toNumber(item.presented)} 次 · 作答{' '}
                          {toNumber(item.answered)} 次
                        </p>
                      </div>
                    </li>
                  ))}
                </ol>
                {pending > shown.length && (
                  <p className="text-[11px] text-muted-foreground">
                    还有 {pending - shown.length} 题没答对，可在题库页逐题查看。
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

export default function RecordsPage({ sessionId }: { sessionId: number }) {
  const [aiPreset, setAiPreset] = useState<AiGeneratePreset | null>(null);
  const analytics = useQuery({
    queryKey: ['analytics', sessionId],
    queryFn: () => api('/api/analytics?days=14'),
    refetchInterval: 20000,
  });
  const history = useQuery({
    queryKey: ['history', sessionId],
    queryFn: () => api('/api/history?days=7'),
    refetchInterval: 15000,
  });
  const events = useQuery({
    queryKey: ['events', sessionId],
    queryFn: () => api('/api/events?limit=100'),
    refetchInterval: 10000,
  });

  const days: HistoryDay[] = history.data?.items || [];
  const recentEvents: EventItem[] = events.data?.items?.slice(0, 20) || [];
  const totalWatch = days.reduce((sum, day) => sum + Math.max(0, toNumber(day.watch)), 0);
  const historyAnswered = days.reduce(
    (sum, day) => sum + Math.max(0, toNumber(day.answered)),
    0,
  );
  const weightedAccuracy =
    historyAnswered > 0
      ? clampPercent(
          days.reduce(
            (sum, day) =>
              sum + Math.max(0, toNumber(day.answered)) * clampPercent(day.accuracy),
            0,
          ) / historyAnswered,
        )
      : null;

  const aData = analytics.data || {};
  const overview = aData.overview || {};
  const maturity = aData.maturity || {};
  const dailyRaw: any[] = Array.isArray(aData.daily) ? aData.daily : [];
  const bySubject: any[] = Array.isArray(aData.bySubject) ? aData.bySubject : [];
  const hardQuestions: any[] = Array.isArray(aData.hardQuestions) ? aData.hardQuestions : [];
  const forecast: any[] = Array.isArray(aData.forecast) ? aData.forecast : [];

  // 归一化近 14 天每日数据（从远到近排序展示）
  const daily14 = (dailyRaw.length ? dailyRaw : days.slice().reverse()).map((d: any) => ({
    label: String(d?.label || d?.date || '—').replace(/^\d{4}-/, ''),
    answered: Math.max(0, toNumber(d?.answered)),
    correct: Math.max(0, toNumber(d?.correct)),
    accuracy: clampPercent(
      d?.accuracy ?? (toNumber(d?.answered) > 0 ? (toNumber(d?.correct) * 100) / toNumber(d?.answered) : 0),
    ),
  }));

  // 计算近 7 天 vs 前 7 天正确率变化趋势（优先使用后端值，否则按 daily14 兜底推算）
  const computeDeltaFallback = (): number | null => {
    if (overview.accuracyDelta !== undefined && overview.accuracyDelta !== null) {
      return Math.round(toNumber(overview.accuracyDelta));
    }
    const hasPrevAnswers = daily14.length >= 8 && daily14.slice(0, daily14.length - 7).some(d => d.answered > 0);
    if (hasPrevAnswers && overview.recent7dAccuracy != null && overview.prev7dAccuracy != null) {
      return Math.round(toNumber(overview.recent7dAccuracy) - toNumber(overview.prev7dAccuracy));
    }
    if (daily14.length >= 8) {
      const prevSlice = daily14.slice(0, Math.max(1, daily14.length - 7));
      const currSlice = daily14.slice(-7);
      const prevAns = prevSlice.reduce((s, x) => s + x.answered, 0);
      const currAns = currSlice.reduce((s, x) => s + x.answered, 0);
      if (prevAns > 0 && currAns > 0) {
        const prevAcc = prevSlice.reduce((s, x) => s + x.answered * x.accuracy, 0) / prevAns;
        const currAcc = currSlice.reduce((s, x) => s + x.answered * x.accuracy, 0) / currAns;
        return Math.round(currAcc - prevAcc);
      }
    }
    return null;
  };
  const accuracyDelta = computeDeltaFallback();

  const totalAnsweredAll = Math.max(
    historyAnswered,
    toNumber(overview.totalAnswered ?? overview.answered),
  );
  const rawOverviewAcc = overview.accuracyPercent ?? overview.accuracy;
  const overallAccuracy =
    rawOverviewAcc !== undefined && rawOverviewAcc !== null && totalAnsweredAll > 0
      ? clampPercent(rawOverviewAcc)
      : weightedAccuracy;
  const streakDays = Math.max(0, toNumber(overview.streakDays ?? overview.streak));
  const dueCount = Math.max(0, toNumber(overview.dueNow ?? overview.dueCount ?? overview.due));
  const wrongCount = Math.max(0, toNumber(overview.wrongCount ?? overview.wrong));

  // Anki 记忆成熟度四阶段
  const matUnseen = Math.max(0, toNumber(maturity.unseen));
  const matLearning = Math.max(0, toNumber(maturity.learning));
  const matYoung = Math.max(0, toNumber(maturity.young));
  const matMature = Math.max(0, toNumber(maturity.mature));
  const matTotal = matUnseen + matLearning + matYoung + matMature;

  const pending = history.isPending && !history.data && analytics.isPending && !analytics.data;
  const refreshing = history.isFetching || events.isFetching || analytics.isFetching;
  const duration = formatDuration(totalWatch);
  const averageWatch = days.length ? totalWatch / days.length : 0;

  const chartDays = days
    .slice()
    .reverse()
    .map(day => ({
      ...day,
      label: day.label || '—',
      watch: Math.max(0, toNumber(day.watch)),
      answered: Math.max(0, toNumber(day.answered)),
      accuracy: clampPercent(day.accuracy),
    }));
  const chartMax = Math.max(
    40,
    Math.ceil(Math.max(1, ...chartDays.map(day => day.watch)) / 10) * 10,
  );
  const chartTicks = Array.from({ length: 5 }, (_, index) =>
    Math.round(chartMax - (chartMax / 4) * index),
  );

  const dailyMaxAnswered = Math.max(10, ...daily14.map(d => d.answered));
  const forecastMax = Math.max(5, ...forecast.map(f => toNumber(f?.dueCount ?? f?.due ?? f?.count)));

  function refreshAll() {
    void analytics.refetch();
    void history.refetch();
    void events.refetch();
  }

  return (
    <div className="space-y-4 pb-6">
      {/* 1. 核心学情概览卡 */}
      <Card
        className="gap-0 overflow-hidden rounded-md py-0 shadow-sm"
        aria-labelledby="records-summary-title"
      >
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle
            id="records-summary-title"
            className="flex items-center gap-2 text-sm"
          >
            <GraduationCap className="size-4 text-primary" />
            学情提分总览
          </CardTitle>
          <CardAction className="flex items-center gap-1.5">
            {streakDays > 0 && (
              <Badge variant="secondary" className="gap-1 font-normal">
                <Flame className="size-3.5 text-amber-500" />
                连学 {streakDays} 天
              </Badge>
            )}
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-7"
              onClick={refreshAll}
              disabled={refreshing}
              aria-label="刷新成长记录"
            >
              <RefreshCw className={refreshing ? 'animate-spin' : ''} />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="p-0">
          <dl className="grid grid-cols-2 divide-x divide-y divide-border/70 sm:grid-cols-4 sm:divide-y-0">
            <div className="min-w-0 px-4 py-4 sm:px-5">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <ListChecks className="size-3.5 shrink-0" />
                <span>累计答题</span>
              </dt>
              <dd className="mt-2.5 flex items-end gap-1 tabular-nums">
                {pending ? (
                  <Skeleton className="h-7 w-16" />
                ) : (
                  <>
                    <span className="text-2xl font-semibold leading-none tracking-tight">
                      {Math.round(totalAnsweredAll)}
                    </span>
                    <span className="pb-0.5 text-xs text-muted-foreground">题</span>
                  </>
                )}
              </dd>
            </div>

            <div className="min-w-0 px-4 py-4 sm:px-5">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Target className="size-3.5 shrink-0" />
                <span>总正确率</span>
              </dt>
              <dd className="mt-2.5 flex flex-wrap items-end gap-1.5 tabular-nums">
                {pending ? (
                  <Skeleton className="h-7 w-20" />
                ) : overallAccuracy === null ? (
                  <span className="text-2xl font-semibold leading-none text-muted-foreground">—</span>
                ) : (
                  <>
                    <span
                      className={`text-2xl font-semibold leading-none tracking-tight ${accuracyTone(overallAccuracy, totalAnsweredAll > 0).text}`}
                    >
                      {overallAccuracy}%
                    </span>
                    {accuracyDelta !== null && accuracyDelta !== 0 && (
                      <span
                        title="近 7 天对比前 7 天正确率变化"
                        className={`inline-flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[11px] font-medium ${
                          accuracyDelta > 0
                            ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                            : 'bg-rose-500/10 text-rose-600 dark:text-rose-400'
                        }`}
                      >
                        {accuracyDelta > 0 ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}
                        {accuracyDelta > 0 ? `+${accuracyDelta}%` : `${accuracyDelta}%`}
                      </span>
                    )}
                  </>
                )}
              </dd>
            </div>

            <div className="min-w-0 px-4 py-4 sm:px-5">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <CalendarClock className="size-3.5 shrink-0" />
                <span>今日待复习</span>
              </dt>
              <dd className="mt-2.5 flex items-end gap-1 tabular-nums">
                {pending ? (
                  <Skeleton className="h-7 w-14" />
                ) : (
                  <>
                    <span className="text-2xl font-semibold leading-none tracking-tight">
                      {dueCount}
                    </span>
                    <span className="pb-0.5 text-xs text-muted-foreground">题到期</span>
                  </>
                )}
              </dd>
            </div>

            <div className="min-w-0 px-4 py-4 sm:px-5">
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <AlertTriangle className="size-3.5 shrink-0" />
                <span>待攻克错题</span>
              </dt>
              <dd className="mt-2.5 flex items-center justify-between gap-2 tabular-nums">
                <div className="flex items-end gap-1">
                  {pending ? (
                    <Skeleton className="h-7 w-14" />
                  ) : (
                    <>
                      <span className={`text-2xl font-semibold leading-none tracking-tight ${wrongCount > 0 ? 'text-rose-600 dark:text-rose-400' : 'text-emerald-600 dark:text-emerald-400'}`}>
                        {wrongCount}
                      </span>
                      <span className="pb-0.5 text-xs text-muted-foreground">道</span>
                    </>
                  )}
                </div>
                {wrongCount > 0 && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    onClick={() => setAiPreset({ fromWrongBook: true })}
                  >
                    <Sparkles className="size-3 text-primary" />
                    变式巩固
                  </Button>
                )}
              </dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      <Tabs defaultValue="learning" className="space-y-4">
        <TabsList className="grid w-full grid-cols-2 sm:w-auto sm:inline-flex">
          <TabsTrigger value="learning">
            <Brain />
            学情诊断与记忆报告
          </TabsTrigger>
          <TabsTrigger value="usage">
            <Clock3 />
            屏幕时长与答题明细
          </TabsTrigger>
        </TabsList>

        <TabsContent value="learning" className="m-0 space-y-4">
          {/* 2. Anki 记忆成熟度分布条 */}
          <Card className="gap-0 overflow-hidden py-0 shadow-sm">
            <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Brain className="size-4 text-muted-foreground" />
                Anki 记忆成熟度分布
              </CardTitle>
              <CardDescription className="col-span-full text-xs">
                按艾宾浩斯复习间隔划分：未接触 → 初学（间隔 &lt; 1天）→ 巩固中（1–20天）→ 已熟记（≥ 21天）
              </CardDescription>
              <CardAction>
                <Badge variant="outline" className="font-normal tabular-nums">
                  共 {matTotal} 题
                </Badge>
              </CardAction>
            </CardHeader>
            <CardContent className="space-y-4 px-4 py-5 sm:px-5">
              {matTotal === 0 ? (
                <FieldDescription className="py-6 text-center">
                  题库暂无题目，添加题目并开始练习后将自动生成记忆成熟度图谱
                </FieldDescription>
              ) : (
                <>
                  <div
                    className="flex h-3.5 w-full overflow-hidden rounded-full bg-muted/60"
                    role="img"
                    aria-label="记忆成熟度四阶段占比条"
                  >
                    {[
                      { key: 'mature', count: matMature, cls: 'bg-emerald-500', label: '已熟记' },
                      { key: 'young', count: matYoung, cls: 'bg-sky-500', label: '巩固中' },
                      { key: 'learning', count: matLearning, cls: 'bg-amber-500', label: '初学' },
                      { key: 'unseen', count: matUnseen, cls: 'bg-slate-300 dark:bg-slate-600', label: '未接触' },
                    ].map(seg =>
                      seg.count > 0 ? (
                        <span
                          key={seg.key}
                          title={`${seg.label}：${seg.count} 题（${Math.round((seg.count * 100) / matTotal)}%）`}
                          className={`h-full transition-[width] duration-500 ${seg.cls}`}
                          style={{ width: `${(seg.count / matTotal) * 100}%` }}
                        />
                      ) : null,
                    )}
                  </div>
                  <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                    {[
                      { label: '已熟记', desc: '间隔 ≥ 21 天', count: matMature, dot: 'bg-emerald-500' },
                      { label: '巩固中', desc: '间隔 1–20 天', count: matYoung, dot: 'bg-sky-500' },
                      { label: '初学阶段', desc: '近期刚接触/重学', count: matLearning, dot: 'bg-amber-500' },
                      { label: '未接触', desc: '尚未出过题', count: matUnseen, dot: 'bg-slate-400' },
                    ].map(item => (
                      <div key={item.label} className="rounded-lg border border-border/70 px-3 py-2.5">
                        <div className="flex items-center gap-1.5 text-xs font-medium">
                          <span className={`size-2 rounded-full ${item.dot}`} />
                          <span>{item.label}</span>
                        </div>
                        <div className="mt-1.5 flex items-baseline justify-between gap-1 tabular-nums">
                          <span className="text-lg font-semibold">{item.count}</span>
                          <span className="text-[11px] text-muted-foreground">
                            {Math.round((item.count * 100) / matTotal)}%
                          </span>
                        </div>
                        <div className="mt-0.5 text-[11px] text-muted-foreground">{item.desc}</div>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          {/* 3. 分科目掌握度与薄弱项诊断 */}
          <Card className="gap-0 overflow-hidden py-0 shadow-sm">
            <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Sparkles className="size-4 text-primary" />
                分科目掌握度与薄弱项诊断
              </CardTitle>
              <CardDescription className="col-span-full text-xs">
                自动定位低正确率与高频错题科目，点击「AI 举一反三出巩固题」可针对薄弱点一键生成同考点变式题。
              </CardDescription>
            </CardHeader>
            <CardContent className="divide-y divide-border/70 px-4 sm:px-5">
              {bySubject.length === 0 ? (
                <FieldDescription className="py-10 text-center">
                  暂无分科目作答数据，答题后将按学科展示掌握度与薄弱点诊断
                </FieldDescription>
              ) : (
                bySubject.map((item: any, idx: number) => {
                  const subj = String(item.subject || item.name || '未分类');
                  const total = Math.max(0, toNumber(item.total));
                  const answered = Math.max(0, toNumber(item.answered));
                  const acc = clampPercent(item.accuracy);
                  const mastered = Math.max(0, toNumber(item.mastered ?? item.covered));
                  const wrong = Math.max(0, toNumber(item.wrong ?? item.wrongCount));
                  const isWeak = wrong > 0 || (answered >= 3 && acc < 80);
                  const tone = accuracyTone(acc, answered > 0);
                  return (
                    <div key={`${subj}-${idx}`} className="space-y-2.5 py-4">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-semibold">{subj}</span>
                          <Badge variant="outline" className="text-[11px] font-normal tabular-nums">
                            已掌握 {mastered}/{total} 题
                          </Badge>
                          {wrong > 0 && (
                            <Badge variant="destructive" className="text-[11px] font-normal tabular-nums">
                              错题 {wrong} 道
                            </Badge>
                          )}
                          {answered > 0 && (
                            <span className={`text-xs font-medium tabular-nums ${tone.text}`}>
                              正确率 {acc}%
                            </span>
                          )}
                        </div>
                        <Button
                          type="button"
                          size="sm"
                          variant={isWeak ? 'default' : 'outline'}
                          className="h-7 gap-1 px-2.5 text-xs"
                          onClick={() =>
                            setAiPreset({
                              fromWrongBook: wrong > 0,
                              subject: subj === '未分类' ? '' : subj,
                              topic: wrong > 0 ? '' : `${subj}核心易错考点巩固练习`,
                              bank: wrong > 0 ? `${subj}错题变式` : '默认题库',
                            })
                          }
                        >
                          <Sparkles className="size-3.5" />
                          ✨ AI 举一反三出巩固题
                        </Button>
                      </div>
                      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                        <div className="space-y-1">
                          <div className="flex justify-between text-[11px] text-muted-foreground">
                            <span>答题正确率</span>
                            <span className="tabular-nums">{answered > 0 ? `${acc}%（作答 ${answered} 次）` : '尚未作答'}</span>
                          </div>
                          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                            <span
                              className={`block h-full rounded-full ${tone.dot}`}
                              style={{ width: `${answered > 0 ? acc : 0}%` }}
                            />
                          </div>
                        </div>
                        <div className="space-y-1">
                          <div className="flex justify-between text-[11px] text-muted-foreground">
                            <span>题库掌握进度</span>
                            <span className="tabular-nums">
                              {total > 0 ? `${Math.round((mastered * 100) / total)}%` : '0%'}
                            </span>
                          </div>
                          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                            <span
                              className="block h-full rounded-full bg-primary"
                              style={{ width: `${total > 0 ? Math.min(100, (mastered * 100) / total) : 0}%` }}
                            />
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </CardContent>
          </Card>

          {/* 4. 顽固错题 TOP 榜 */}
          <Card className="gap-0 overflow-hidden py-0 shadow-sm">
            <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
              <CardTitle className="flex items-center gap-2 text-sm">
                <AlertTriangle className="size-4 text-rose-500" />
                顽固错题 TOP 榜
              </CardTitle>
              <CardDescription className="col-span-full text-xs">
                优先展示反复答错的题目；建议让孩子在设备端打开「错题先背再测」，或针对单题生成同考点变式。
              </CardDescription>
            </CardHeader>
            <CardContent className="divide-y divide-border/70 px-4 sm:px-5">
              {hardQuestions.length === 0 ? (
                <FieldDescription className="py-10 text-center">
                  🎉 太棒了！目前没有反复出错的顽固错题
                </FieldDescription>
              ) : (
                hardQuestions.slice(0, 10).map((q: any, idx: number) => {
                  const wrongTimes = Math.max(
                    1,
                    toNumber(
                      q.wrongTimes ??
                        q.wrongCount ??
                        q.wrong ??
                        q.incorrect ??
                        (toNumber(q.answered) - toNumber(q.correct)),
                    ),
                  );
                  const attempts = Math.max(wrongTimes, toNumber(q.answered ?? q.attempts));
                  const acc = clampPercent(
                    q.accuracy ?? (attempts > 0 ? ((attempts - wrongTimes) * 100) / attempts : 0),
                  );
                  const ansText = q.answerDisplay || answerOf(q);
                  return (
                    <div key={q.id || idx} className="py-3.5">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="flex min-w-0 flex-1 items-start gap-2.5">
                          <Badge variant="destructive" className="mt-0.5 shrink-0 tabular-nums">
                            错 {wrongTimes} 次
                          </Badge>
                          <div className="min-w-0 flex-1">
                            <MathText
                              text={q.q || '（题目内容）'}
                              className="whitespace-pre-wrap text-sm font-medium leading-6"
                            />
                            <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
                              {q.subject && <span>{q.subject}</span>}
                              {q.level && <span>· {q.level}</span>}
                              <span>作答 {attempts} 次 · 正确率 {acc}%</span>
                            </div>
                          </div>
                        </div>
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          className="h-7 shrink-0 gap-1 px-2.5 text-xs"
                          onClick={() =>
                            setAiPreset({
                              fromWrongBook: true,
                              subject: q.subject || '',
                              level: q.level || '',
                              bank: q.bank ? `${q.bank}-变式` : '错题巩固题库',
                              topic: `${q.subject || ''}同考点变式巩固`.trim(),
                              extra: `请围绕这道高频错题背后的考点出几道换数字/换问法的举一反三变式题：「${String(q.q || '').slice(0, 120)}」`,
                            })
                          }
                        >
                          <Sparkles className="size-3 text-primary" />
                          生成变式题
                        </Button>
                      </div>
                      <Accordion type="single" collapsible className="mt-1">
                        <AccordionItem value="detail" className="border-0">
                          <AccordionTrigger className="py-1.5 text-xs text-muted-foreground hover:no-underline">
                            查看解析与提示
                          </AccordionTrigger>
                          <AccordionContent className="space-y-1.5 rounded-lg bg-muted/40 p-3 text-xs">
                            {ansText && (
                              <MathText
                                text={'正确答案：' + ansText}
                                className="font-medium text-emerald-700 dark:text-emerald-400"
                              />
                            )}
                            {q.tip ? (
                              <MathText text={'解析：' + q.tip} className="whitespace-pre-wrap leading-5 text-muted-foreground" />
                            ) : (
                              <p className="text-muted-foreground">暂无解析，可在题库页搜索该题补充记忆口诀或点击右上方生成变式题。</p>
                            )}
                          </AccordionContent>
                        </AccordionItem>
                      </Accordion>
                    </div>
                  );
                })
              )}
            </CardContent>
          </Card>

          {/* 5. 近 14 天做题量/正确率趋势 & 未来 7 天复习预测 */}
          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="gap-0 overflow-hidden py-0 shadow-sm lg:col-span-2">
              <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <ChartColumn className="size-4 text-muted-foreground" />
                  近 14 天练习量与正确率
                </CardTitle>
                <CardDescription className="col-span-full text-xs">
                  柱高代表每日做题数，颜色代表正确率（绿 ≥85% · 黄 60–84% · 红 &lt;60%）
                </CardDescription>
              </CardHeader>
              <CardContent className="px-4 pb-5 pt-5 sm:px-5">
                {daily14.length === 0 ? (
                  <FieldDescription className="py-14 text-center">暂无近 14 天做题记录</FieldDescription>
                ) : (
                  <div className="flex h-52 items-end justify-between gap-1.5 pb-6 pt-4">
                    {daily14.slice(-14).map((d, i) => {
                      const barColor =
                        d.answered === 0
                          ? 'bg-muted-foreground/25'
                          : d.accuracy >= 85
                            ? 'bg-emerald-500'
                            : d.accuracy >= 60
                              ? 'bg-amber-500'
                              : 'bg-rose-500';
                      return (
                        <div
                          key={`${d.label}-${i}`}
                          className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                          title={`${d.label}：作答 ${d.answered} 题，正确率 ${d.accuracy}%`}
                        >
                          <div className="pointer-events-none absolute -top-6 z-20 hidden whitespace-nowrap rounded border bg-popover px-1.5 py-0.5 text-[10px] text-popover-foreground shadow-sm group-hover:block">
                            {d.answered}题 · {d.accuracy}%
                          </div>
                          <div className="flex w-full max-w-6 flex-1 items-end overflow-hidden rounded-t bg-muted/50">
                            <span
                              className={`block w-full rounded-t transition-[height] duration-300 ${barColor}`}
                              style={{
                                height: `${(d.answered / dailyMaxAnswered) * 100}%`,
                                minHeight: d.answered > 0 ? 5 : 2,
                              }}
                            />
                          </div>
                          <span className="absolute -bottom-6 truncate text-[10px] tabular-nums text-muted-foreground">
                            {d.label}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </CardContent>
            </Card>

            <Card className="gap-0 overflow-hidden py-0 shadow-sm">
              <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <CalendarClock className="size-4 text-muted-foreground" />
                  未来 7 天复习预测
                </CardTitle>
                <CardDescription className="col-span-full text-xs">
                  预计每日到期的艾宾浩斯复习卡片数
                </CardDescription>
              </CardHeader>
              <CardContent className="px-4 pb-5 pt-5 sm:px-5">
                {forecast.length === 0 ? (
                  <FieldDescription className="py-14 text-center">暂无待复习排期</FieldDescription>
                ) : (
                  <div className="flex h-52 items-end justify-around gap-2 pb-6 pt-4">
                    {forecast.slice(0, 7).map((f: any, i: number) => {
                      const count = Math.max(0, toNumber(f?.dueCount ?? f?.due ?? f?.count));
                      const label = String(f?.label || f?.date || `+${i}天`).replace(/^\d{4}-/, '');
                      return (
                        <div
                          key={`${label}-${i}`}
                          className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                          title={`${label}：预计 ${count} 题到期复习`}
                        >
                          <span className="mb-1 text-[10px] tabular-nums text-muted-foreground">
                            {count > 0 ? count : ''}
                          </span>
                          <div className="flex w-full max-w-6 flex-1 items-end overflow-hidden rounded-t bg-muted/50">
                            <span
                              className="block w-full rounded-t bg-sky-500 transition-[height] duration-300"
                              style={{
                                height: `${(count / forecastMax) * 100}%`,
                                minHeight: count > 0 ? 5 : 2,
                              }}
                            />
                          </div>
                          <span className="absolute -bottom-6 whitespace-nowrap text-[10px] text-muted-foreground">
                            {i === 0 ? '今天' : label}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </CardContent>
            </Card>
          </div>

          <CoverageCard sessionId={sessionId} />
        </TabsContent>

        <TabsContent value="usage" className="m-0 space-y-4">
          <Card className="gap-0 overflow-hidden py-0 shadow-sm">
            <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Clock3 className="size-4 text-muted-foreground" />
                近 7 天屏幕使用趋势
              </CardTitle>
              <CardDescription className="col-span-full text-xs">
                累计使用 {duration.value} {duration.unit}（单位：分钟）
              </CardDescription>
              <CardAction>
                {pending ? (
                  <Skeleton className="h-5 w-24" />
                ) : (
                  <span className="inline-flex items-baseline gap-1 text-xs text-muted-foreground">
                    日均<b className="font-medium tabular-nums">{Math.round(averageWatch)}</b>分钟
                  </span>
                )}
              </CardAction>
            </CardHeader>
            <CardContent className="px-4 pb-5 pt-5 sm:px-5">
              {pending ? (
                <div className="flex h-64 items-end gap-3 border-b border-border/70 px-4 pb-1 sm:h-72">
                  {Array.from({ length: 7 }, (_, index) => (
                    <Skeleton
                      key={index}
                      className="min-h-3 flex-1 rounded-t-md rounded-b-none"
                      style={{ height: `${22 + ((index * 19) % 62)}%` }}
                    />
                  ))}
                </div>
              ) : chartDays.length ? (
                <div
                  className="grid h-64 grid-cols-[30px_minmax(0,1fr)] gap-2 sm:h-72"
                  role="img"
                  aria-label="最近 7 天每日使用时长柱状图"
                >
                  <div className="flex flex-col justify-between pb-7 pt-1 text-right text-xs tabular-nums text-muted-foreground">
                    {chartTicks.map(tick => (
                      <span key={tick}>{tick}</span>
                    ))}
                  </div>
                  <div className="relative min-w-0 pb-7">
                    <div className="pointer-events-none absolute inset-x-0 bottom-7 top-1 flex flex-col justify-between">
                      {chartTicks.map(tick => (
                        <span key={tick} className="border-t border-dashed border-border/70" />
                      ))}
                    </div>
                    <div className="relative z-10 flex h-[calc(100%-1.75rem)] items-end justify-around gap-2">
                      {chartDays.map((day, index) => (
                        <div
                          key={`${day.label}-${index}`}
                          className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                          title={`${day.label}：${day.watch.toFixed(1)} 分钟，${day.answered} 题，正确率 ${day.accuracy}%`}
                        >
                          <div className="relative flex w-full max-w-9 flex-1 items-end overflow-hidden rounded-t-md bg-muted/65">
                            <span
                              className="block w-full rounded-t-md bg-primary transition-[height] duration-500 group-hover:bg-primary/85"
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
              ) : history.isError ? (
                <div className="flex flex-col items-center gap-2 py-14 text-center">
                  <ChartColumn className="size-5 text-destructive/70" />
                  <FieldDescription className="text-destructive">无法读取使用趋势</FieldDescription>
                </div>
              ) : (
                <div className="flex flex-col items-center gap-2 py-14 text-center">
                  <ChartColumn className="size-5 text-muted-foreground/70" />
                  <FieldDescription>暂无使用趋势</FieldDescription>
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="gap-0 overflow-hidden py-0 shadow-sm">
            <Tabs defaultValue="answers" className="gap-0">
              <CardHeader className="gap-2 border-b border-border/70 px-4 py-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:px-5">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <History className="size-4 text-muted-foreground" />
                  学习明细
                </CardTitle>
                <CardAction className="col-span-full col-start-1 row-start-3 w-full justify-self-stretch sm:col-span-1 sm:col-start-2 sm:row-span-2 sm:row-start-1 sm:w-auto sm:justify-self-end">
                  <TabsList className="grid w-full grid-cols-2 sm:inline-flex sm:w-auto">
                    <TabsTrigger value="answers">
                      <ListChecks />
                      答题记录
                    </TabsTrigger>
                    <TabsTrigger value="activity">
                      <History />
                      近期动态
                    </TabsTrigger>
                  </TabsList>
                </CardAction>
              </CardHeader>
              <CardContent className="p-0">
                <TabsContent value="answers" className="m-0">
                  {history.isPending ? (
                    <ListSkeleton rows={5} />
                  ) : days.length ? (
                    <Table aria-label="近 7 天答题记录">
                      <TableHeader className="bg-muted/35">
                        <TableRow className="hover:bg-transparent">
                          <TableHead className="ps-4 sm:ps-5">日期</TableHead>
                          <TableHead className="text-end">作答</TableHead>
                          <TableHead className="text-end">正确率</TableHead>
                          <TableHead className="pe-4 text-end sm:pe-5">解锁</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {days.map(day => {
                          const answered = Math.max(0, Math.round(toNumber(day.answered)));
                          const rounds = Math.max(0, Math.round(toNumber(day.rounds)));
                          const accuracy = clampPercent(day.accuracy);
                          const tone = accuracyTone(accuracy, answered > 0);
                          const label = day.label || '—';
                          return (
                            <TableRow key={label}>
                              <TableCell className="ps-4 font-medium sm:ps-5">{label}</TableCell>
                              <TableCell className="text-end tabular-nums text-muted-foreground">
                                {answered} 题
                              </TableCell>
                              <TableCell className="text-end">
                                <span className="inline-flex items-center justify-end gap-1.5 tabular-nums">
                                  <span
                                    aria-hidden="true"
                                    className={`size-1.5 rounded-full ${tone.dot}`}
                                  />
                                  <span className={tone.text}>
                                    {answered ? `${accuracy}%` : '—'}
                                  </span>
                                </span>
                              </TableCell>
                              <TableCell className="pe-4 text-end tabular-nums text-muted-foreground sm:pe-5">
                                {rounds} 组
                              </TableCell>
                            </TableRow>
                          );
                        })}
                      </TableBody>
                    </Table>
                  ) : history.isError ? (
                    <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                      <ListChecks className="size-5 text-destructive/70" />
                      <FieldDescription className="text-destructive">无法读取答题记录</FieldDescription>
                    </div>
                  ) : (
                    <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                      <ListChecks className="size-5 text-muted-foreground/70" />
                      <FieldDescription>还没有答题记录</FieldDescription>
                    </div>
                  )}
                </TabsContent>

                <TabsContent value="activity" className="m-0">
                  {events.isPending ? (
                    <ListSkeleton rows={6} />
                  ) : recentEvents.length ? (
                    <ol className="divide-y divide-border/70">
                      {recentEvents.map((event, index) => (
                        <li
                          key={`${event.ts}-${index}`}
                          className="flex items-start gap-3 px-4 py-3 transition-colors hover:bg-muted/35 sm:px-5"
                        >
                          <span
                            aria-hidden="true"
                            className={`mt-2 size-2 shrink-0 rounded-full ${eventTone(event.type, event.payload)}`}
                          />
                          <div className="min-w-0 flex-1">
                            <p className="break-words text-sm font-medium leading-5">
                              {eventLabel(event)}
                            </p>
                          </div>
                          <time className="mt-0.5 shrink-0 text-xs tabular-nums text-muted-foreground">
                            {formatEventTime(event.ts)}
                          </time>
                        </li>
                      ))}
                    </ol>
                  ) : events.isError ? (
                    <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                      <History className="size-5 text-destructive/70" />
                      <FieldDescription className="text-destructive">无法读取学习动态</FieldDescription>
                    </div>
                  ) : (
                    <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                      <History className="size-5 text-muted-foreground/70" />
                      <FieldDescription>还没有学习动态</FieldDescription>
                    </div>
                  )}
                </TabsContent>
              </CardContent>
            </Tabs>
          </Card>
        </TabsContent>
      </Tabs>

      {aiPreset && (
        <Suspense fallback={null}>
          <AiGenerateDialog
            open={!!aiPreset}
            onOpenChange={open => {
              if (!open) setAiPreset(null);
            }}
            sessionId={sessionId}
            initialPreset={aiPreset}
          />
        </Suspense>
      )}
    </div>
  );
}
