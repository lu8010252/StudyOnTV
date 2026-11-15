import { useQuery } from '@tanstack/react-query';
import {
  ChartColumn,
  Clock3,
  History,
  ListChecks,
  RefreshCw,
  Target,
} from 'lucide-react';
import { api } from './api';
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
                        <p className="truncate text-sm">{item.q || '（无题干）'}</p>
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
  const totalAnswered = days.reduce(
    (sum, day) => sum + Math.max(0, toNumber(day.answered)),
    0,
  );
  const weightedAccuracy =
    totalAnswered > 0
      ? clampPercent(
          days.reduce(
            (sum, day) =>
              sum + Math.max(0, toNumber(day.answered)) * clampPercent(day.accuracy),
            0,
          ) / totalAnswered,
        )
      : null;
  const pending = history.isPending && !history.data;
  const refreshing = history.isFetching || events.isFetching;
  const duration = formatDuration(totalWatch);
  const averageWatch = days.length ? totalWatch / days.length : 0;
  const accuracyToneValue =
    weightedAccuracy === null ? null : accuracyTone(weightedAccuracy, totalAnswered > 0);
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
  const summaryCards = [
    {
      key: 'watch',
      label: '总时长',
      value: pending ? '' : duration.value,
      unit: pending ? '' : duration.unit,
      icon: Clock3,
      valueClass: '',
    },
    {
      key: 'answered',
      label: '总答题',
      value: pending ? '' : String(Math.round(totalAnswered)),
      unit: pending ? '' : '题',
      icon: ListChecks,
      valueClass: '',
    },
    {
      key: 'accuracy',
      label: '正确率',
      value:
        pending || weightedAccuracy === null ? '' : String(weightedAccuracy),
      unit: pending || weightedAccuracy === null ? '' : '%',
      icon: Target,
      valueClass: accuracyToneValue?.text || '',
    },
  ];

  function refreshAll() {
    void history.refetch();
    void events.refetch();
  }

  return (
    <div className="space-y-4">
      <Card
        className="gap-0 overflow-hidden py-0 shadow-sm"
        aria-labelledby="records-summary-title"
      >
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle
            id="records-summary-title"
            className="flex items-center gap-2 text-sm"
          >
            <ChartColumn className="size-4 text-muted-foreground" />
            近 7 天概览
          </CardTitle>
          <CardAction className="flex items-center gap-1.5">
            <Badge variant="outline" className="hidden font-normal sm:inline-flex">
              自动汇总
            </Badge>
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
          <dl className="grid grid-cols-3 divide-x divide-border/70">
            {summaryCards.map(card => {
              const Icon = card.icon;
              return (
                <div key={card.key} className="min-w-0 px-3 py-4 sm:px-5 sm:py-5">
                  <dt className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
                    <Icon className="size-3.5 shrink-0" aria-hidden="true" />
                    <span className="truncate">{card.label}</span>
                  </dt>
                  <dd
                    className="mt-3 flex min-w-0 items-end gap-1 whitespace-nowrap"
                    aria-label={`${card.label} ${pending ? '正在读取' : `${card.value}${card.unit}`}`}
                  >
                    {pending ? (
                      <>
                        <Skeleton className="h-7 w-14 sm:h-8 sm:w-16" />
                        <Skeleton className="mb-0.5 h-3 w-5" />
                      </>
                    ) : (
                      <>
                        <span
                          className={`text-2xl font-semibold leading-none tracking-tight tabular-nums sm:text-3xl ${card.valueClass}`}
                        >
                          {card.value}
                        </span>
                        {card.unit && (
                          <span className="pb-0.5 text-xs text-muted-foreground">
                            {card.unit}
                          </span>
                        )}
                      </>
                    )}
                  </dd>
                </div>
              );
            })}
          </dl>
        </CardContent>
      </Card>

      <CoverageCard sessionId={sessionId} />

      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <ChartColumn className="size-4 text-muted-foreground" />
            使用趋势
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            每日实际使用时长，单位：分钟
          </CardDescription>
          <CardAction>
            {pending ? (
              <Skeleton className="h-5 w-24" />
            ) : (
              <Badge variant="secondary" className="font-normal tabular-nums">
                日均 {Math.round(averageWatch)} 分钟
              </Badge>
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
              <div className="flex flex-col justify-between pb-7 pt-1 text-right text-[10px] tabular-nums text-muted-foreground">
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
                      <span className="absolute -bottom-7 whitespace-nowrap text-[10px] text-muted-foreground sm:text-[11px]">
                        {day.label}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
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
            <CardDescription className="text-xs">
              按日汇总答题情况，查看最近学习动态。
            </CardDescription>
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
                        className="mt-1.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-muted"
                      >
                        <span
                          className={`size-2 rounded-full ${eventTone(event.type, event.payload)}`}
                        />
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="break-words text-sm font-medium leading-5">
                          {eventLabel(event)}
                        </p>
                      </div>
                      <time className="mt-0.5 shrink-0 text-[11px] tabular-nums text-muted-foreground">
                        {formatEventTime(event.ts)}
                      </time>
                    </li>
                  ))}
                </ol>
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
    </div>
  );
}
