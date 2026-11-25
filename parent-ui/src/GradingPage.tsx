import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, ClipboardCheck, History, LoaderCircle, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
} from '@/components/ui/card';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty';
import { Field, FieldDescription, FieldGroup, FieldLabel } from '@/components/ui/field';
import { SectionHeading } from '@/components/ui/section-heading';
import { SaveStatus, type SaveState } from '@/components/ui/save-status';
import { Input } from '@/components/ui/input';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { Toolbar, ToolbarGroup } from '@/components/ui/toolbar';
import { api, resolveMediaUrl } from './api';
import { answerOf, MathText, questionImages } from './components/bank/shared';

export default function GradingPage({ sessionId }: { sessionId: number }) {
  const settings = useQuery({ queryKey: ['grading', sessionId], queryFn: () => api('/api/grading') });
  const pending = useQuery({
    queryKey: ['reviews', sessionId],
    queryFn: () => api('/api/reviews'),
    refetchInterval: 5000,
  });
  // 批改完的作答从待批列表消失了，家长要能回看分数和批改意见；列表用 summary 模式，手写图按需再取
  const graded = useQuery({
    queryKey: ['reviews-graded', sessionId],
    queryFn: () => api('/api/reviews?state=all&view=summary'),
    refetchInterval: 15000,
  });
  const gradedItems: any[] = (graded.data?.items || []).filter(
    (review: any) => review.state === 'graded',
  );
  const client = useQueryClient();
  const [config, setConfig] = useState<any>({
    mode: 'manual',
    provider: 'deepseek',
    endpoint: 'https://api.deepseek.com/v1',
    model: 'deepseek-flash',
    vision: false,
    passScore: 60,
    apiKey: '',
    hasKey: false,
  });
  const [dirty, setDirty] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [failedSignature, setFailedSignature] = useState('');

  useEffect(() => {
    if (settings.data && !dirty) setConfig({ mode: 'manual', ...settings.data, apiKey: '' });
  }, [settings.data, dirty]);

  function change(key: string, value: any) {
    setDirty(true);
    setSaveError('');
    setConfig((current: any) => ({ ...current, [key]: value }));
  }

  const passScoreNum = Number(config.passScore);
  const payload = { ...config, passScore: passScoreNum };
  const signature = JSON.stringify(payload);
  const latestSignature = useRef(signature);
  latestSignature.current = signature;

  const save = useMutation({
    mutationFn: (value: any) => api('/api/grading', 'PUT', value),
    onMutate: () => setSaveError(''),
    onSuccess: (response, body) => {
      client.setQueryData(['grading', sessionId], (old: any) =>
        old ? { ...old, ...response } : response,
      );
      if (latestSignature.current === JSON.stringify(body)) {
        setConfig((old: any) => ({ ...old, ...response, apiKey: '' }));
        setDirty(false);
        setFailedSignature('');
      }
    },
    onError: (error: any, body) => {
      setSaveError(error?.message || '自动保存失败，请重试');
      setFailedSignature(JSON.stringify(body));
      toast.error(error.message);
    },
  });

  const aiConfigured = !!(
    settings.data?.endpoint &&
    settings.data?.model &&
    (settings.data?.hasKey ||
      settings.data?.provider === 'ollama' ||
      settings.data?.provider === 'custom')
  );

  const valid =
    String(config.passScore).trim() !== '' &&
    Number.isInteger(passScoreNum) &&
    passScoreNum >= 1 &&
    passScoreNum <= 100 &&
    (config.mode !== 'ai' ||
      (() => {
        try {
          const url = new URL(config.endpoint);
          if (!/^https?:$/.test(url.protocol)) return false;
        } catch {
          return false;
        }
        return (
          !!config.model.trim() &&
          (!!config.hasKey ||
            !!config.apiKey.trim() ||
            config.provider === 'ollama' ||
            config.provider === 'custom')
        );
      })());

  useEffect(() => {
    if (!dirty || !valid || save.isPending || failedSignature === signature) return;
    const timer = window.setTimeout(() => save.mutate(payload), 800);
    return () => window.clearTimeout(timer);
  }, [signature, dirty, valid, save.isPending, failedSignature]);

  return (
    <div className="space-y-4">
      <section className="space-y-2">
      <SectionHeading
        icon={Bot}
        title="批改设置"
        action={
          <SaveStatus
            state={
              (save.isPending
                ? 'saving'
                : saveError
                  ? 'error'
                  : dirty
                    ? valid
                      ? 'pending'
                      : 'error'
                    : 'saved') as SaveState
            }
          />
        }
      />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          <ItemGroup className="divide-y divide-border/60">
            <Item size="sm" className="rounded-none px-0 py-4">
              <ItemContent>
                <ItemTitle>批改方式</ItemTitle>
              </ItemContent>
              <Select value={config.mode || 'manual'} onValueChange={value => change('mode', value)}>
                <SelectTrigger aria-label="批改方式" className="w-full sm:w-44">
                  <SelectValue>
                    {config.mode === 'ai' ? 'AI 自动批改' : '家长手动批改'}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="manual">家长手动批改</SelectItem>
                  <SelectItem value="ai">AI 自动批改</SelectItem>
                </SelectContent>
              </Select>
            </Item>
            <Item size="sm" className="rounded-none px-0 py-4">
              <ItemContent>
                <ItemTitle>及格分</ItemTitle>
              </ItemContent>
              <div className="flex items-center gap-2">
                <Input
                  className="w-24 text-center"
                  aria-label="及格分"
                  type="number"
                  min={1}
                  max={100}
                  value={config.passScore}
                  onChange={event => change('passScore', event.target.value)}
                />
                <span className="text-sm text-muted-foreground">分</span>
              </div>
            </Item>
            {config.mode === 'ai' && (
              <Item size="sm" className="rounded-none px-0 py-4">
                <ItemContent>
                  <ItemTitle>AI 服务</ItemTitle>
                  <ItemDescription className="text-xs">
                    失败时孩子可以先跳过，家长也可以手动批改。
                  </ItemDescription>
                  {!aiConfigured && (
                    <p className="mt-1.5 text-xs leading-5 text-amber-600 dark:text-amber-400">
                      尚未配置模型 API，当前「AI 自动批改」暂时无法保存。请先点击右侧「去设置」填写模型与密钥后再切换回来。
                    </p>
                  )}
                </ItemContent>
                <div className="flex flex-wrap items-center justify-end gap-2 max-sm:basis-full">
                  <Badge variant={aiConfigured ? 'secondary' : 'outline'}>
                    {aiConfigured ? '已配置' : '未配置'}
                  </Badge>
                  <span className="text-xs text-muted-foreground">
                    {settings.data?.model || '未选择模型'}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      location.hash = '/settings/ai';
                    }}
                  >
                    去设置
                  </Button>
                </div>
              </Item>
            )}
          </ItemGroup>
        </CardContent>
      </Card>
      </section>

      <section className="space-y-2">
      <SectionHeading
        icon={ClipboardCheck}
        title="待批作答"
        action={
          <Toolbar aria-label="待批作答工具栏" className="justify-end">
            <ToolbarGroup>
              <span className="inline-flex items-baseline gap-1 text-xs text-muted-foreground"><b className="font-medium tabular-nums">{pending.data?.items?.length || 0}</b>份</span>
              <Button variant="ghost" size="sm" onClick={() => pending.refetch()}>
                <RefreshCw />
                刷新
              </Button>
            </ToolbarGroup>
          </Toolbar>
        }
      />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          {pending.isLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <LoaderCircle className="animate-spin" />
              正在读取作答
            </div>
          ) : !pending.data?.items?.length ? (
            <Empty className="rounded-none border-0 py-12">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <ClipboardCheck />
                </EmptyMedia>
                <EmptyTitle>暂无待批作答</EmptyTitle>
                <EmptyDescription>仅主观题与手写作答需要批改，孩子提交后会自动出现在这里（客观题自动判分）。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            pending.data.items.map((review: any) => (
              <Review
                key={review.id}
                review={review}
                sessionId={sessionId}
                ai={config.mode === 'ai'}
              />
            ))
          )}
        </CardContent>
      </Card>
      </section>

      <section className="space-y-2">
      <SectionHeading
        icon={History}
        title="已批改"
        description="最近 200 条"
        action={
          <Toolbar aria-label="已批改工具栏" className="justify-end">
            <ToolbarGroup>
              <span className="inline-flex items-baseline gap-1 text-xs text-muted-foreground"><b className="font-medium tabular-nums">{gradedItems.length}</b>份</span>
              <Button variant="ghost" size="sm" onClick={() => graded.refetch()}>
                <RefreshCw />
                刷新
              </Button>
            </ToolbarGroup>
          </Toolbar>
        }
      />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          {graded.isLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <LoaderCircle className="animate-spin" />
              正在读取批改记录
            </div>
          ) : !gradedItems.length ? (
            <Empty className="rounded-none border-0 py-12">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <History />
                </EmptyMedia>
                <EmptyTitle>还没有已批改的作答</EmptyTitle>
                <EmptyDescription>批改完成后会保留在这里，可以随时回看。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            gradedItems.map((review: any) => <Graded key={review.id} review={review} />)
          )}
        </CardContent>
      </Card>
      </section>
    </div>
  );
}

function formatGradedTime(value: unknown) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '';
  return new Date(timestamp).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function Graded({ review }: { review: any }) {
  const [open, setOpen] = useState(false);
  const image = useQuery({
    queryKey: ['review', review.id],
    queryFn: () => api('/api/reviews/' + review.id),
    enabled: open && !!review.input?.hasDrawing && !review.input?.drawing,
    staleTime: 5 * 60 * 1000,
  });
  const drawing = review.input?.drawing || image.data?.input?.drawing;
  const qImgs = questionImages(review.question);
  const passed = review.result?.correct;
  return (
    <article className="border-t py-4 first:border-t-0">
      <div className="flex items-start justify-between gap-3">
        <MathText
          text={review.question?.q || '题目已删除'}
          className="min-w-0 whitespace-pre-wrap text-sm font-medium leading-7"
        />
        <Badge variant={passed === false ? 'destructive' : 'default'}>
          {Number(review.score) || 0} 分
        </Badge>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span>{review.source === 'ai' ? 'AI 批改' : '家长 / Agent 批改'}</span>
        {passed === true && <span>计为答对</span>}
        {passed === false && <span>计为答错</span>}
        {review.gradedAt ? <span>{formatGradedTime(review.gradedAt)}</span> : null}
      </div>
      <Accordion type="single" collapsible onValueChange={v => setOpen(v === 'detail')}>
        <AccordionItem value="detail">
          <AccordionTrigger>查看作答与批改意见</AccordionTrigger>
          <AccordionContent className="space-y-3">
            {qImgs.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {qImgs.map((src, i) => (
                  <img
                    key={i}
                    src={resolveMediaUrl(src)}
                    alt={`题目配图 ${i + 1}`}
                    className="max-h-40 rounded border bg-muted/20 object-contain"
                  />
                ))}
              </div>
            )}
            <div>
              <div className="text-xs text-muted-foreground">孩子的答案</div>
              <MathText
                className="mt-1 whitespace-pre-wrap rounded-lg bg-muted/55 p-3 leading-7"
                text={review.input?.text || ((review.input?.hasDrawing || drawing) ? '手写作答' : '（空）')}
              />
              {(review.input?.hasDrawing || drawing) && (
                image.isLoading ? (
                  <div className="mt-2 text-xs text-muted-foreground">正在读取手写图片…</div>
                ) : drawing ? (
                  <img
                    className="my-3 max-h-96 w-full rounded-lg border bg-white object-contain"
                    alt="孩子的手写答案"
                    src={resolveMediaUrl(drawing)}
                  />
                ) : null
              )}
            </div>
            {review.feedback && (
              <div>
                <div className="text-xs text-muted-foreground">批改意见</div>
                <p className="mt-1 whitespace-pre-wrap leading-7">{review.feedback}</p>
              </div>
            )}
            <div>
              <div className="text-xs text-muted-foreground">参考答案</div>
              <MathText
                className="mt-1 whitespace-pre-wrap leading-7"
                text={review.question?.modelAnswer || answerOf(review.question || {})}
              />
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </article>
  );
}

function Review({
  review,
  sessionId,
  ai,
}: {
  review: any;
  sessionId: number;
  ai: boolean;
}) {
  const [score, setScore] = useState('60');
  const [feedback, setFeedback] = useState('');
  const client = useQueryClient();
  const submit = useMutation({
    mutationFn: (body: any) => api('/api/reviews/' + review.id, 'POST', body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['reviews', sessionId] });
      client.invalidateQueries({ queryKey: ['reviews-graded', sessionId] });
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      toast.success('评分已提交');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const retry = useMutation({
    mutationFn: () => api('/api/reviews/' + review.id + '/retry', 'POST', {}),
    onSuccess: () => toast.success('已提交 AI 重试'),
    onError: (error: any) => toast.error(error.message),
  });
  const qImgs = questionImages(review.question);

  return (
    <article className="border-t py-4 first:border-t-0">
      <MathText text={review.question.q} className="whitespace-pre-wrap text-sm font-medium leading-7" />
      {qImgs.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {qImgs.map((src, i) => (
            <img
              key={i}
              src={resolveMediaUrl(src)}
              alt={`题目配图 ${i + 1}`}
              className="max-h-48 rounded border bg-muted/20 object-contain"
            />
          ))}
        </div>
      )}
      <FieldGroup className="mt-4 gap-4">
        <Field>
          <FieldLabel>孩子的答案</FieldLabel>
          <MathText
            className="whitespace-pre-wrap rounded-lg bg-muted/55 p-3 leading-7"
            text={review.input.text || '手写作答'}
          />
          {review.input.drawing && (
            <img
              className="my-3 max-h-96 w-full rounded-lg border bg-white object-contain"
              alt="孩子的手写答案"
              src={resolveMediaUrl(review.input.drawing)}
            />
          )}
        </Field>
        <Accordion type="single" collapsible>
          <AccordionItem value="answer">
            <AccordionTrigger>参考答案与评分标准</AccordionTrigger>
            <AccordionContent className="space-y-2">
              <MathText
                className="whitespace-pre-wrap leading-7"
                text={review.question.modelAnswer || answerOf(review.question)}
              />
              <MathText
                className="whitespace-pre-wrap leading-7 text-muted-foreground"
                text={review.question.rubric || ''}
              />
            </AccordionContent>
          </AccordionItem>
        </Accordion>
        {review.feedback && <FieldDescription>{review.feedback}</FieldDescription>}
        <FieldGroup className="grid gap-4 sm:grid-cols-[120px_minmax(0,1fr)]">
          <Field>
            <FieldLabel htmlFor={`score-${review.id}`}>分数</FieldLabel>
            <Input
              id={`score-${review.id}`}
              aria-label="分数"
              className="w-full text-center"
              type="number"
              min={0}
              max={100}
              value={score}
              onChange={event => setScore(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor={`feedback-${review.id}`}>批改意见</FieldLabel>
            <Textarea
              id={`feedback-${review.id}`}
              value={feedback}
              onChange={event => setFeedback(event.target.value)}
              rows={3}
            />
          </Field>
        </FieldGroup>
      </FieldGroup>
      <div className="mt-4 flex justify-end gap-2">
        {ai && (
          <Button variant="outline" disabled={retry.isPending} onClick={() => retry.mutate()}>
            AI 重试
          </Button>
        )}
        <Button
          disabled={submit.isPending}
          onClick={() => {
            const value = Number(score);
            if (score === '' || !Number.isFinite(value) || value < 0 || value > 100) {
              return toast.error('请输入 0–100 分');
            }
            submit.mutate({ score: value, feedback });
          }}
        >
          提交评分
        </Button>
      </div>
    </article>
  );
}
