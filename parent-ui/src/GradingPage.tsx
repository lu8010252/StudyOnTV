import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Check, ClipboardCheck, History, LoaderCircle, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
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
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty';
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldTitle } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { Toolbar, ToolbarGroup } from '@/components/ui/toolbar';
import { api } from './api';
import { answerOf, MathText } from './QuestionBank';

type Provider = {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  visionModel: string;
  note: string;
  keyUrl?: string;
};

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
  const [models, setModels] = useState<string[]>([]);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const providers: Provider[] = settings.data?.providers || [];
  const provider = providers.find(item => item.id === config.provider);

  useEffect(() => {
    if (settings.data && !dirty) setConfig({ mode: 'manual', ...settings.data, apiKey: '' });
  }, [settings.data]);

  function change(key: string, value: any) {
    setDirty(true);
    setResult(null);
    setConfig((current: any) => ({ ...current, [key]: value }));
  }

  function pick(id: string) {
    const next = providers.find(item => item.id === id);
    setDirty(true);
    setResult(null);
    setModels([]);
    setConfig((current: any) => ({
      ...current,
      provider: id,
      ...(next && next.id !== 'custom'
        ? { endpoint: next.baseUrl, model: current.vision && next.visionModel ? next.visionModel : next.model }
        : {}),
    }));
  }

  const payload = () => ({
    endpoint: config.endpoint,
    model: config.model,
    vision: config.vision,
    apiKey: config.apiKey,
  });
  const save = useMutation({
    mutationFn: (value: any) => api('/api/grading', 'PUT', value),
    onSuccess: response => {
      setConfig({ ...response, apiKey: '' });
      setDirty(false);
      client.setQueryData(['grading', sessionId], response);
    },
    onError: (error: any) => toast.error(error.message),
  });
  const test = useMutation({
    mutationFn: () => api('/api/grading/test', 'POST', payload()),
    onSuccess: response =>
      setResult({
        ok: response.ok,
        message: response.message + (response.ms ? ` · ${(response.ms / 1000).toFixed(1)} 秒` : ''),
      }),
    onError: (error: any) => setResult({ ok: false, message: error.message }),
  });
  const list = useMutation({
    mutationFn: () => api('/api/grading/models', 'POST', payload()),
    onSuccess: response => {
      if (!response.ok) return toast.error(response.message);
      setModels(response.items);
      toast.success(`获取到 ${response.items.length} 个模型`);
    },
    onError: (error: any) => toast.error(error.message),
  });
  const valid =
    Number.isFinite(Number(config.passScore)) &&
    Number(config.passScore) >= 1 &&
    Number(config.passScore) <= 100 &&
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
          (!!config.hasKey || !!config.apiKey.trim() || config.provider === 'ollama')
        );
      })());

  useEffect(() => {
    if (!dirty || !valid) return;
    const timer = window.setTimeout(() => save.mutate(config), 800);
    return () => window.clearTimeout(timer);
  }, [config, dirty, valid]);

  return (
    <div className="space-y-4">
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
          <CardTitle className="flex items-center gap-2 text-base">
            <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
              <Bot className="size-4" />
            </span>
            批改设置
          </CardTitle>
          <CardDescription className="text-xs">默认家长手动批改，也可以使用模型 API。</CardDescription>
        </CardHeader>
        <CardContent className="border-t p-0">
          <ItemGroup className="divide-y divide-border/60">
            <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
              <ItemContent>
                <ItemTitle>批改方式</ItemTitle>
                <ItemDescription className="text-xs">家长评分或 AI 自动批改。</ItemDescription>
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
            <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
              <ItemContent>
                <ItemTitle>及格分</ItemTitle>
                <ItemDescription className="text-xs">低于及格分时显示未通过。</ItemDescription>
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
              <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
                <ItemContent>
                  <ItemTitle>AI 服务</ItemTitle>
                  <ItemDescription className="text-xs">
                    失败时孩子可以先跳过，家长也可以手动批改。
                  </ItemDescription>
                </ItemContent>
                <div className="flex flex-wrap items-center justify-end gap-2 max-sm:basis-full">
                  <Badge variant="secondary">
                    {settings.data?.hasKey ? '已配置' : '未配置'}
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
        <CardFooter className="justify-between gap-3 border-t px-4 py-3 sm:px-6">
          <span className="text-xs text-muted-foreground">修改后自动保存</span>
          <Badge variant={save.isPending ? 'secondary' : valid ? 'outline' : 'destructive'}>
            {save.isPending ? (
              <>
                <LoaderCircle className="animate-spin" />
                保存中
              </>
            ) : valid ? (
              <>
                <Check />
                已保存
              </>
            ) : (
              '请检查设置'
            )}
          </Badge>
        </CardFooter>
      </Card>

      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
          <CardTitle className="flex items-center gap-2 text-base">
            <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
              <ClipboardCheck className="size-4" />
            </span>
            待批作答
          </CardTitle>
          <CardDescription className="text-xs">手动评分，或通过已验证的 API 批改。</CardDescription>
          <CardAction>
            <Toolbar aria-label="待批作答工具栏" className="justify-end">
              <ToolbarGroup>
                <Badge variant="secondary">{pending.data?.items?.length || 0} 份</Badge>
                <Button variant="ghost" size="sm" onClick={() => pending.refetch()}>
                  <RefreshCw />
                  刷新
                </Button>
              </ToolbarGroup>
            </Toolbar>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t p-0">
          {pending.isLoading ? (
            <div className="flex items-center justify-center gap-2 px-4 py-12 text-sm text-muted-foreground">
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
                <EmptyDescription>孩子提交后会自动出现在这里。</EmptyDescription>
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

      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
          <CardTitle className="flex items-center gap-2 text-base">
            <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
              <History className="size-4" />
            </span>
            已批改
          </CardTitle>
          <CardDescription className="text-xs">
            批改完成后可以在这里回看分数和意见，最近 200 条。
          </CardDescription>
          <CardAction>
            <Toolbar aria-label="已批改工具栏" className="justify-end">
              <ToolbarGroup>
                <Badge variant="secondary">{gradedItems.length} 份</Badge>
                <Button variant="ghost" size="sm" onClick={() => graded.refetch()}>
                  <RefreshCw />
                  刷新
                </Button>
              </ToolbarGroup>
            </Toolbar>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t p-0">
          {graded.isLoading ? (
            <div className="flex items-center justify-center gap-2 px-4 py-12 text-sm text-muted-foreground">
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
    enabled: open && !!review.input?.hasDrawing,
    staleTime: 5 * 60 * 1000,
  });
  const drawing = image.data?.input?.drawing;
  const passed = review.result?.correct;
  return (
    <article className="border-t px-4 py-4 first:border-t-0 sm:px-6">
      <div className="flex items-start justify-between gap-3">
        <MathText
          text={review.question?.q || '题目已删除'}
          className="min-w-0 text-base font-medium leading-7"
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
      <Accordion type="single" collapsible>
        <AccordionItem value="detail">
          <AccordionTrigger>查看作答与批改意见</AccordionTrigger>
          <AccordionContent className="space-y-3">
            <div>
              <div className="text-xs text-muted-foreground">孩子的答案</div>
              <MathText
                className="mt-1 whitespace-pre-wrap rounded-lg bg-muted/55 p-3 leading-7"
                text={review.input?.text || (review.input?.hasDrawing ? '手写作答' : '（空）')}
              />
              {review.input?.hasDrawing && (
                image.isLoading ? (
                  <div className="mt-2 text-xs text-muted-foreground">正在读取手写图片…</div>
                ) : drawing ? (
                  <img
                    className="my-3 max-h-96 w-full rounded-lg border bg-white object-contain"
                    alt="孩子的手写答案"
                    src={drawing}
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
                className="mt-1 leading-7"
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

  return (
    <article className="border-t px-4 py-5 first:border-t-0 sm:px-6">
      <MathText text={review.question.q} className="text-base font-medium leading-7" />
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
              src={review.input.drawing}
            />
          )}
        </Field>
        <Accordion type="single" collapsible>
          <AccordionItem value="answer">
            <AccordionTrigger>参考答案与评分标准</AccordionTrigger>
            <AccordionContent className="space-y-2">
              <MathText
                className="leading-7"
                text={review.question.modelAnswer || answerOf(review.question)}
              />
              <MathText
                className="leading-7 text-muted-foreground"
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
