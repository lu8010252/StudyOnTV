import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  BookCheck,
  BookX,
  CheckCircle2,
  ChevronDown,
  Flame,
  LoaderCircle,
  PlusCircle,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import { toast } from 'sonner';
import { api } from './api';
import { ClearExtraPoolButton } from './components/ClearExtraPoolButton';
import { FilterSelect, MathText, labels } from './components/bank/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';

interface WrongQuestionItem {
  id: string;
  q: string;
  type: string;
  subject?: string;
  level?: string;
  bank?: string;
  options?: string[];
  answer?: string;
  explain?: string;
  answered: number;
  correct: number;
  wrongCount: number;
  lastAt?: number;
  lastCorrect?: number;
}

interface WrongQuestionsResponse {
  items: WrongQuestionItem[];
  total: number;
  extraPool: number;
  extraQueueIds: string[];
}

function formatLastAt(ts?: number): string {
  if (!ts) return '—';
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export default function WrongBookPage({ sessionId, state }: { sessionId: number; state?: any }) {
  const client = useQueryClient();
  const [subject, setSubject] = useState('__all__');
  const [level, setLevel] = useState('__all__');
  const [keyword, setKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [selected, setSelected] = useState<string[]>([]);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedKeyword(keyword.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [keyword]);

  const bankMeta = useQuery({
    queryKey: ['bank-meta', sessionId],
    queryFn: () => api('/api/questions/meta'),
  });

  const queryString = useMemo(() => {
    const params = new URLSearchParams();
    if (subject !== '__all__') params.set('subject', subject);
    if (level !== '__all__') params.set('level', level);
    if (debouncedKeyword) params.set('q', debouncedKeyword);
    const qs = params.toString();
    return qs ? `?${qs}` : '';
  }, [subject, level, debouncedKeyword]);

  const wrongQuery = useQuery<WrongQuestionsResponse>({
    queryKey: ['wrong-questions', sessionId, subject, level, debouncedKeyword],
    queryFn: () => api(`/api/wrong-questions${queryString}`),
  });

  const items: WrongQuestionItem[] = wrongQuery.data?.items || [];
  const total = Number(wrongQuery.data?.total ?? items.length) || 0;
  const extraPool = Number(wrongQuery.data?.extraPool ?? state?.learning?.extraPool ?? 0) || 0;
  const extraQueueSet = useMemo(
    () => new Set<string>(Array.isArray(wrongQuery.data?.extraQueueIds) ? wrongQuery.data.extraQueueIds : []),
    [wrongQuery.data?.extraQueueIds],
  );

  // 列表变动时清理已不在当前列表中的勾选 ID
  useEffect(() => {
    const valid = new Set(items.map(it => it.id));
    setSelected(prev => {
      const next = prev.filter(id => valid.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [items]);

  const subjectOptions = useMemo<[string, string][]>(() => {
    const set = new Set<string>(bankMeta.data?.subjects || []);
    items.forEach(it => {
      if (it.subject) set.add(it.subject);
    });
    return [['__all__', '全部科目'], ...Array.from(set).map(s => [s, s] as [string, string])];
  }, [bankMeta.data?.subjects, items]);

  const levelOptions = useMemo<[string, string][]>(() => {
    const set = new Set<string>(bankMeta.data?.levels || []);
    items.forEach(it => {
      if (it.level) set.add(it.level);
    });
    return [['__all__', '全部年级'], ...Array.from(set).map(l => [l, l] as [string, string])];
  }, [bankMeta.data?.levels, items]);

  const addExtra = useMutation({
    mutationFn: (ids: string[]) => api('/api/wrong-questions/extra-pool', 'POST', { ids }),
    onSuccess: (res: any, ids: string[]) => {
      if (res?.learning) {
        client.setQueryData(['state', sessionId], (old: any) =>
          old ? { ...old, learning: res.learning } : old,
        );
      }
      client.invalidateQueries({ queryKey: ['wrong-questions', sessionId] });
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      const addedSet = new Set(ids);
      setSelected(prev => prev.filter(id => !addedSet.has(id)));
      const added = Number(res?.added ?? 0);
      const nextPool = Number(res?.extraPool ?? extraPool);
      if (added > 0) {
        toast.success(`已加入加练池（新增 ${added} 题，当前共 ${nextPool} 题）`);
      } else {
        toast.message(`所选题目已在加练池中（当前共 ${nextPool} 题）`);
      }
    },
    onError: (error: any) => toast.error(error?.message || '加入加练池失败'),
  });

  const markMastered = useMutation({
    mutationFn: (ids: string[]) => api('/api/wrong-questions/master', 'POST', { ids }),
    onSuccess: (res: any, ids: string[]) => {
      if (res?.study) {
        client.setQueryData(['state', sessionId], (old: any) =>
          old ? { ...old, study: res.study } : old,
        );
      }
      client.invalidateQueries({ queryKey: ['wrong-questions', sessionId] });
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      const doneSet = new Set(ids);
      setSelected(prev => prev.filter(id => !doneSet.has(id)));
      const updated = Number(res?.updated ?? ids.length);
      toast.success(`已将 ${updated} 道错题标为已掌握`);
    },
    onError: (error: any) => toast.error(error?.message || '标记已掌握失败'),
  });

  const allSelected = items.length > 0 && selected.length === items.length;
  const toggleAll = () => setSelected(allSelected ? [] : items.map(it => it.id));
  const toggleOne = (id: string) =>
    setSelected(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]));

  const hasFilter = subject !== '__all__' || level !== '__all__' || !!debouncedKeyword;
  const resetFilters = () => {
    setSubject('__all__');
    setLevel('__all__');
    setKeyword('');
    setDebouncedKeyword('');
  };

  const unqueuedVisibleIds = items.filter(it => !extraQueueSet.has(it.id)).map(it => it.id);
  const busy = addExtra.isPending || markMastered.isPending;

  return (
    <div className="space-y-4 pb-6">
      {/* 顶部汇总与快捷操作 */}
      <div className="grid gap-3 sm:grid-cols-2">
        <Card className="gap-0 py-4 shadow-xs">
          <CardContent className="flex items-center justify-between gap-3 px-4 sm:px-5">
            <div className="min-w-0">
              <p className="text-xs text-muted-foreground">当前错题</p>
              <div className="mt-1 flex items-baseline gap-1.5">
                <span className="text-2xl font-semibold tabular-nums">{total}</span>
                <span className="text-xs text-muted-foreground">道{hasFilter ? '（已筛选）' : ''}</span>
              </div>
            </div>
            <Button
              type="button"
              size="sm"
              disabled={busy || items.length === 0 || unqueuedVisibleIds.length === 0}
              onClick={() => addExtra.mutate(unqueuedVisibleIds)}
            >
              {addExtra.isPending ? <LoaderCircle className="animate-spin" /> : <Sparkles />}
              {unqueuedVisibleIds.length === 0 && items.length > 0
                ? '已全部在加练池'
                : '全部加入加练池'}
            </Button>
          </CardContent>
        </Card>

        <Card className="gap-0 py-4 shadow-xs">
          <CardContent className="flex items-center justify-between gap-3 px-4 sm:px-5">
            <div className="min-w-0">
              <p className="text-xs text-muted-foreground">当前加练池剩余</p>
              <div className="mt-1 flex items-baseline gap-1.5">
                <span className="text-2xl font-semibold tabular-nums">{extraPool}</span>
                <span className="text-xs text-muted-foreground">
                  题{extraQueueSet.size > 0 ? `（含指定错题 ${extraQueueSet.size} 题）` : ''}
                </span>
              </div>
            </div>
            <ClearExtraPoolButton sessionId={sessionId} count={extraPool} />
          </CardContent>
        </Card>
      </div>

      {/* 筛选栏 */}
      <Card className="gap-0 py-3 shadow-xs">
        <CardContent className="flex flex-col gap-2.5 px-4 sm:flex-row sm:items-center sm:px-5">
          <div className="grid grid-cols-2 gap-2 sm:flex sm:w-auto sm:shrink-0">
            <div className="sm:w-32">
              <FilterSelect
                label="按科目筛选"
                value={subject}
                onChange={setSubject}
                options={subjectOptions}
              />
            </div>
            <div className="sm:w-32">
              <FilterSelect
                label="按年级筛选"
                value={level}
                onChange={setLevel}
                options={levelOptions}
              />
            </div>
          </div>
          <InputGroup className="h-8 flex-1">
            <InputGroupAddon>
              <Search />
            </InputGroupAddon>
            <InputGroupInput
              aria-label="搜索错题关键词"
              placeholder="搜索题干、解析关键词…"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
            />
            {keyword && (
              <InputGroupAddon align="inline-end">
                <InputGroupButton
                  size="icon-xs"
                  aria-label="清空搜索"
                  onClick={() => {
                    setKeyword('');
                    setDebouncedKeyword('');
                  }}
                >
                  <X />
                </InputGroupButton>
              </InputGroupAddon>
            )}
          </InputGroup>
          {hasFilter && (
            <Button type="button" size="sm" variant="ghost" onClick={resetFilters}>
              重置筛选
            </Button>
          )}
        </CardContent>
      </Card>

      {/* 批量操作栏 */}
      {items.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/70 bg-muted/30 px-3.5 py-2">
          <label className="flex cursor-pointer items-center gap-2 text-xs text-muted-foreground">
            <Checkbox checked={allSelected} onCheckedChange={toggleAll} aria-label="全选当前列表" />
            <span>
              {selected.length > 0 ? `已选 ${selected.length} / ${items.length} 题` : `全选（共 ${items.length} 题）`}
            </span>
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={busy || selected.length === 0}
              onClick={() => addExtra.mutate(selected)}
            >
              <PlusCircle />
              加入加练池{selected.length > 0 ? ` (${selected.length})` : ''}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={busy || selected.length === 0}
              onClick={() => markMastered.mutate(selected)}
            >
              <CheckCircle2 />
              标为已掌握{selected.length > 0 ? ` (${selected.length})` : ''}
            </Button>
          </div>
        </div>
      )}

      {/* 列表内容 */}
      {wrongQuery.isLoading ? (
        <div className="flex items-center justify-center gap-2 py-16 text-sm text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" />
          正在加载错题库…
        </div>
      ) : wrongQuery.isError ? (
        <Card className="py-10 text-center shadow-xs">
          <CardContent className="space-y-2">
            <p className="text-sm font-medium text-destructive">加载错题库失败</p>
            <p className="text-xs text-muted-foreground">
              {(wrongQuery.error as any)?.message || '请确认设备处于同一局域网并重试'}
            </p>
            <Button type="button" size="sm" variant="outline" onClick={() => wrongQuery.refetch()}>
              重试
            </Button>
          </CardContent>
        </Card>
      ) : items.length === 0 ? (
        <Card className="py-12 text-center shadow-xs">
          <CardContent className="flex flex-col items-center gap-2.5">
            {hasFilter ? (
              <BookX className="size-8 text-muted-foreground/60" />
            ) : (
              <BookCheck className="size-8 text-emerald-500/80" />
            )}
            <p className="text-sm font-medium">
              {hasFilter ? '没有符合筛选条件的错题' : '当前没有待攻克的错题'}
            </p>
            <p className="max-w-sm text-xs text-muted-foreground">
              {hasFilter
                ? '可以切换其他科目、年级或清空关键词后再看。'
                : '孩子答错的题目会自动沉淀到这里，方便随时加入加练池或查看解析。'}
            </p>
            {hasFilter && (
              <Button type="button" size="sm" variant="outline" className="mt-1" onClick={resetFilters}>
                清空筛选
              </Button>
            )}
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {items.map(item => {
            const inQueue = extraQueueSet.has(item.id);
            const checked = selected.includes(item.id);
            return (
              <Card
                key={item.id}
                className={`gap-0 py-0 shadow-xs transition-colors ${
                  checked ? 'border-primary/50 bg-primary/[0.02]' : ''
                }`}
              >
                <CardContent className="p-4 sm:px-5">
                  <div className="flex items-start gap-3">
                    <Checkbox
                      className="mt-1 shrink-0"
                      checked={checked}
                      onCheckedChange={() => toggleOne(item.id)}
                      aria-label={`选择错题 ${item.id}`}
                    />
                    <div className="min-w-0 flex-1 space-y-2.5">
                      {/* 标签与错题统计 */}
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Badge variant="outline" className="text-[11px]">
                          {labels[item.type] || item.type || '题目'}
                        </Badge>
                        {item.subject && (
                          <Badge variant="secondary" className="text-[11px]">
                            {item.subject}
                          </Badge>
                        )}
                        {item.level && (
                          <Badge variant="secondary" className="text-[11px]">
                            {item.level}
                          </Badge>
                        )}
                        {item.bank && item.bank !== '默认题库' && (
                          <Badge variant="outline" className="text-[11px] text-muted-foreground">
                            {item.bank}
                          </Badge>
                        )}
                        {inQueue && (
                          <Badge className="bg-primary/15 text-primary hover:bg-primary/20 text-[11px]">
                            <Sparkles className="me-1 size-3" />
                            已在加练池
                          </Badge>
                        )}
                        <span className="ms-auto flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                          <span className="inline-flex items-center gap-1 font-medium text-rose-600 dark:text-rose-400">
                            <Flame className="size-3.5" />
                            错 {item.wrongCount} 次
                          </span>
                          <span>共答 {item.answered} 次</span>
                          <span className="hidden sm:inline">上次：{formatLastAt(item.lastAt)}</span>
                        </span>
                      </div>

                      {/* 题干（支持 LaTeX 公式） */}
                      <MathText
                        text={item.q || '（无题干）'}
                        className="text-sm font-medium leading-relaxed text-foreground break-words"
                      />

                      <div className="text-[11px] text-muted-foreground sm:hidden">
                        上次作答：{formatLastAt(item.lastAt)}
                      </div>

                      {/* 折叠答案解析 + 单题操作 */}
                      <Collapsible>
                        <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
                          <CollapsibleTrigger
                            type="button"
                            className="group inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
                          >
                            <ChevronDown className="size-3.5 transition-transform group-data-[state=open]:rotate-180" />
                            查看答案与解析
                          </CollapsibleTrigger>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <Button
                              type="button"
                              size="sm"
                              variant={inQueue ? 'secondary' : 'outline'}
                              className="h-7 px-2 text-xs"
                              disabled={busy || inQueue}
                              onClick={() => addExtra.mutate([item.id])}
                            >
                              <PlusCircle className="size-3.5" />
                              {inQueue ? '已在加练池' : '加入加练池'}
                            </Button>
                            <Button
                              type="button"
                              size="sm"
                              variant="ghost"
                              className="h-7 px-2 text-xs"
                              disabled={busy}
                              onClick={() => markMastered.mutate([item.id])}
                            >
                              <CheckCircle2 className="size-3.5" />
                              标为已掌握
                            </Button>
                          </div>
                        </div>

                        <CollapsibleContent className="mt-3 space-y-2 rounded-lg border border-border/60 bg-muted/35 p-3 text-xs">
                          {Array.isArray(item.options) && item.options.length > 0 && (
                            <div className="space-y-1">
                              <div className="font-medium text-muted-foreground">选项</div>
                              <div className="grid gap-1 sm:grid-cols-2">
                                {item.options.map((opt, idx) => (
                                  <div key={idx} className="flex items-start gap-1.5">
                                    <span className="shrink-0 font-mono text-muted-foreground">
                                      {String.fromCharCode(65 + idx)}.
                                    </span>
                                    <MathText text={String(opt || '')} className="min-w-0 break-words" />
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                          <div className="space-y-1">
                            <div className="font-medium text-emerald-700 dark:text-emerald-400">参考答案</div>
                            <MathText
                              text={item.answer || '暂无参考答案'}
                              className="leading-relaxed text-foreground break-words"
                            />
                          </div>
                          {item.explain && (
                            <div className="space-y-1 border-t border-border/50 pt-2">
                              <div className="font-medium text-muted-foreground">解析</div>
                              <MathText
                                text={item.explain}
                                className="leading-relaxed text-muted-foreground break-words"
                              />
                            </div>
                          )}
                        </CollapsibleContent>
                      </Collapsible>
                    </div>
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
