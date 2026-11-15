import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookOpen, ChevronDown, Clock3, Repeat2, Sparkles } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { SectionHeading } from '@/components/ui/section-heading';
import { SaveStatus, type SaveState } from '@/components/ui/save-status';
import { Checkbox } from '@/components/ui/checkbox';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldSet,
  FieldTitle,
} from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent } from '@/components/ui/card';
import { api } from './api';
import { defaults, ruleSchema, zoneOptions, type Rules } from './schema';
import SpeechSettings from './SpeechSettings';
import TimePoints from './TimePoints';
import WatchWhitelistField from './WatchWhitelistField';
import WeekSchedule from './WeekSchedule';

/** 数字步进器：输入过程中先保留原始文本，失焦时再夹到 min–max。
 *  直接在 onChange 里夹紧会把「8」立刻改成 75，再输入「0」就变成 750 → 200。 */
function NumberStepper({
  value,
  onChange,
  min,
  max,
  label,
}: {
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  label: string;
}) {
  const clamp = (n: number) => (Number.isNaN(n) ? min : Math.min(max, Math.max(min, n)));
  const [text, setText] = useState(String(value));
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    if (!focused) setText(String(value));
  }, [value, focused]);
  const commit = (raw: string) => {
    const next = clamp(raw.trim() === '' ? min : Number(raw));
    setText(String(next));
    if (next !== value) onChange(next);
  };
  const bump = (delta: number) => {
    const base = focused ? (text.trim() === '' ? min : Number(text)) : value;
    const next = clamp(base + delta);
    setText(String(next));
    onChange(next);
  };
  return (
    <div className="flex items-center gap-1">
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="size-8"
        aria-label={'减少' + label}
        onClick={() => bump(-1)}
      >
        −
      </Button>
      <Input
        aria-label={label}
        className="h-8 w-14 text-center"
        type="number"
        inputMode="numeric"
        min={min}
        max={max}
        value={text}
        onFocus={() => setFocused(true)}
        onChange={e => {
          const raw = e.target.value;
          setText(raw);
          const n = Number(raw);
          if (raw.trim() !== '' && !Number.isNaN(n) && n >= min && n <= max) onChange(n);
        }}
        onBlur={e => {
          setFocused(false);
          commit(e.target.value);
        }}
        onKeyDown={e => {
          if (e.key === 'Enter') {
            e.preventDefault();
            e.currentTarget.blur();
          }
        }}
      />
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="size-8"
        aria-label={'增加' + label}
        onClick={() => bump(1)}
      >
        +
      </Button>
    </div>
  );
}

const fieldLabels: Record<string, string> = {
  videoMinutes: '每次视频学习分钟',
  dailyFreeMinutes: '每日免费时长',
  bootFreeMinutes: '每次开机或唤醒免费时长',
  dailyMinutes: '每日使用上限',
  questionsPerRound: '题量',
  roundsPerDay: '每天最多解锁组数',
  wrongReviewSeconds: '答错后查看解析秒数',
  fontScale: '学习页字号',
  restWatchMinutes: '累计观看分钟',
  restMinutes: '强制休息分钟',
  sessionMinutes: '通过一组解锁分钟',
  wrongThreshold: '累计答错题数',
  extraQuestions: '每次增加加练题数',
  dailyLimitEnabled: '启用每日时长上限',
  shuffleOptions: '随机打乱选项',
  showCountdown: '显示剩余时间角标',
  roundsLimitEnabled: '启用每日组数上限',
  restEnabled: '强制休息',
  trafficAssistEnabled: '按流量判断是否在播放',
  backgroundAnchorEnabled: '保留后台空窗口',
  blockSettingsDuringControl: '锁定时不允许进系统设置',
  allDay: '全天管控',
  childSelectLevel: '孩子选择年级',
  difficultyRewardEnabled: '按题目星级奖励',
  extraEnabled: '答错累计加练',
  spacedRepetitionEnabled: '按记忆曲线复习',
  newFirst: '没做过的题先出',
  wrongFirst: '答错的题先出',
  coverageFirst: '没答对的题先出',
  allowQuizUnlock: '答题页输密码解锁',
};

const errorNames: Record<string, string> = { timePoints: '网课时段', exemptWindows: '大人豁免', watchWhitelist: '不计时应用' };

const localizeMessage = (message: string) =>
  message
    .replace(/^Number must be greater than or equal to (.+)$/, '不能小于 $1')
    .replace(/^Number must be less than or equal to (.+)$/, '不能大于 $1')
    .replace(/^Number must be greater than (.+)$/, '必须大于 $1')
    .replace(/^Expected number, received nan$/, '请输入有效数字');

/** 把 zod 的嵌套错误拍平成「位置：中文提示」，供页面底部列表和自动保存失败提示共用。 */
function collectErrorMessages(errors: any) {
  const out: { key: string; text: string }[] = [];
  const walk = (path: (string | number)[], error: any) => {
    if (!error) return;
    if (typeof error.message === 'string' && error.message) {
      const label = path
        .map((part, index) => {
          if (path[0] === 'difficultyRewards' && index === 1) return `${Number(part)} 星奖励`;
          return typeof part === 'number' ? `第 ${part + 1} 条` : errorNames[part] || '';
        })
        .filter(Boolean)
        .join(' ');
      const message = localizeMessage(error.message);
      out.push({ key: path.join('.'), text: label ? label + '：' + message : message });
      return;
    }
    if (Array.isArray(error)) {
      error.forEach((item, index) => walk([...path, index], item));
      return;
    }
    if (typeof error === 'object') {
      const entries = Object.entries(error);
      if (!entries.length) {
        out.push({ key: path.join('.'), text: '请检查 ' + (path.join('.') || '设置') });
        return;
      }
      entries.forEach(([key, value]) => walk([...path, key], value));
      return;
    }
    out.push({ key: path.join('.'), text: '请检查 ' + path.join('.') });
  };
  Object.entries(errors || {}).forEach(([key, error]) => walk([key], error));
  return out;
}

export function Choice({
  value,
  onChange,
  options,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  options: [string, string][];
  label?: string;
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger aria-label={label} className="min-w-32">
        <SelectValue>{options.find(([v]) => v === value)?.[1] || '请选择'}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        {options.map(([v, t]) => (
          <SelectItem key={v} value={v}>
            {t}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** 后台保活等旧面板使用的行布局；保留与上游相同的 title/note/children 接口。 */
export function Row({
  title,
  note,
  children,
}: {
  title: string;
  note?: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border/60 py-3.5 last:border-0">
      <div className="min-w-0">
        <div className="text-sm font-medium">{title}</div>
        {note && <div className="mt-1 text-xs leading-5 text-muted-foreground">{note}</div>}
      </div>
      {children != null && <div className="min-w-0">{children}</div>}
    </div>
  );
}

export function Panel({
  title,
  description,
  hint,
  children,
  footer,
  icon: Icon,
  status,
}: {
  title: string;
  description?: string;
  hint?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  icon?: any;
  status?: ReactNode;
}) {
  return (
    <section className="space-y-2">
      <SectionHeading
        icon={Icon}
        title={title}
        description={description}
        action={
          <>
            {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
            {status}
          </>
        }
      />
      <Card className="gap-0 overflow-hidden border-border/60 py-0 shadow-sm">
        <CardContent className="px-4 sm:px-5">{children}</CardContent>
        {footer && <div className="flex justify-end gap-2 border-t border-border/60 py-3">{footer}</div>}
      </Card>
    </section>
  );
}

export default function RulesPage({ state, sessionId }: { state: any; sessionId: number }) {
  const initial = { ...defaults, ...(state?.config || {}) };
  const form = useForm<Rules>({ resolver: zodResolver(ruleSchema), defaultValues: initial });
  const c = form.watch();
  const client = useQueryClient();
  const [tab, setTab] = useState<'task' | 'time' | 'scope' | 'more'>('task');
  // 必须和 QuestionBank 用完全相同的 URL：两处共用 ['bank',…] 这个 queryKey，
  // URL 不一致时先挂载的一方会把不完整的数据写进缓存（暂停的题在题库页凭空消失）。
  const bank = useQuery({
    queryKey: ['bank', sessionId],
    queryFn: () => api('/api/questions?limit=50000&enabledOnly=false'),
  });
  const device = useQuery({ queryKey: ['device', sessionId], queryFn: () => api('/api/device') });
  // Radix 的 SelectItem 不接受空字符串，所以「跟随设备」在控件里用 device 代替
  const zoneChoices = zoneOptions.map(([value, label]) => [value || 'device', label] as [string, string]);
  const scopeSubjects: any[] = [
    ...new Set((bank.data?.items || []).map((q: any) => q.subject).filter(Boolean)),
  ].sort();
  const scopeLevels: any[] = [
    ...new Set((bank.data?.items || []).map((q: any) => q.level).filter(Boolean)),
  ].sort();
  const scopeBanks: any[] = [
    ...new Set((bank.data?.items || []).map((q: any) => q.bank || '默认题库')),
  ].sort();
  const inScope = (key: any, value: string) => Array.isArray(c[key]) && c[key].includes(value);
  const change = (key: any, value: any) => form.setValue(key, value, { shouldDirty: true, shouldValidate: true });
  const toggleScope = (key: any, value: string) => {
    const list = Array.isArray(c[key]) ? c[key] : [];
    change(key, list.includes(value) ? list.filter((v: string) => v !== value) : [...list, value]);
  };
  useEffect(() => {
    if (state?.config && !form.formState.isDirty) form.reset({ ...defaults, ...state.config });
  }, [state?.config?.version]);
  // 失败后记住失败时的值，避免 isPending 变化触发同一个值反复自动重试（网络断开时每 0.8 秒弹一次错误）。
  const failedSignature = useRef('');
  const validationToast = useRef('');
  const save = useMutation({
    mutationFn: (value: Rules) => api('/api/config', 'PUT', value),
    onSuccess: (r, body) => {
      failedSignature.current = '';
      if (JSON.stringify(form.getValues()) === JSON.stringify(body)) {
        form.reset({ ...defaults, ...r.config });
      }
      client.invalidateQueries({ queryKey: ['state', sessionId] });
    },
    onError: (e: any, body) => {
      failedSignature.current = JSON.stringify(body);
      toast.error(e.message);
    },
  });
  useEffect(() => {
    if (!form.formState.isDirty || save.isPending) return;
    const signature = JSON.stringify(c);
    if (failedSignature.current === signature) return;
    const timer = window.setTimeout(async () => {
      if (await form.trigger()) {
        validationToast.current = '';
        save.mutate(form.getValues());
      } else {
        const first = collectErrorMessages(form.formState.errors)[0];
        if (first && validationToast.current !== first.text) {
          validationToast.current = first.text;
          toast.error('无法自动保存：' + first.text);
        }
      }
    }, 800);
    return () => window.clearTimeout(timer);
  }, [JSON.stringify(c), form.formState.isDirty, save.isPending]);
  const toggle = (key: any) => (
    <Controller
      name={key}
      control={form.control}
      render={({ field }) => (
        <Switch
          aria-label={fieldLabels[key] || key}
          className="w-8! shrink-0"
          checked={!!field.value}
          onCheckedChange={field.onChange}
        />
      )}
    />
  );
  const number = (key: any, min: number, max: number) => {
    const current = Number(c[key]);
    return (
      <NumberStepper
        key={key}
        value={Number.isFinite(current) ? current : min}
        min={min}
        max={max}
        label={fieldLabels[key] || key}
        onChange={value => change(key, value)}
      />
    );
  };
  const errorMessages = collectErrorMessages(form.formState.errors).map(item => (
    <FieldError key={item.key}>{item.text}</FieldError>
  ));
  const fieldGroup =
    'gap-0 [&>[data-slot=field]+[data-slot=field]]:border-t [&>[data-slot=field]+[data-slot=field]]:border-border/60';
  const saveState: SaveState = save.isPending
    ? 'saving'
    : save.isError
      ? 'error'
      : form.formState.isDirty
        ? 'pending'
        : 'saved';
  const statusNode = <SaveStatus state={saveState} />;
  return (
    <form
      onSubmit={form.handleSubmit(
        v => save.mutate(v),
        () => toast.error('请检查设置中的输入'),
      )}
      className="rules-form space-y-5"
    >
      <Tabs value={tab} onValueChange={value => setTab(value as typeof tab)} className="gap-5">
        <TabsList className="w-full gap-1">
          {(
            [
              ['task', '任务与解锁'],
              ['time', '时间计划'],
              ['scope', '答题范围'],
              ['more', '加练与复习'],
            ] as const
          ).map(([id, label]) => (
            <TabsTrigger key={id} type="button" value={id}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="task" className="mt-0">
          <Panel title="学习任务" icon={Sparkles} status={tab === 'task' ? statusNode : undefined}>
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                {/* ── 核心设置：默认展示 ── */}

                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>玩法</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      按组闯关：答对一组解锁时长；连续答对：连对 N 题解锁；自由练习：不管控，随时答题
                    </FieldDescription>
                  </FieldContent>
                  <Controller
                    name="playMode"
                    control={form.control}
                    render={({ field }) => (
                      <Choice
                        value={field.value}
                        onChange={field.onChange}
                        label="玩法"
                        options={[
                          ['round', '按组闯关'],
                          ['streak', '连续答对'],
                          ['practice', '自由练习'],
                          ['count', '答对指定题数'],
                        ]}
                      />
                    )}
                  />
                </Field>

                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>
                      {c.playMode === 'streak'
                        ? '连续答对目标（题）'
                        : c.playMode === 'count'
                          ? '累计答对目标（题）'
                          : '每组题量'}
                    </FieldTitle>
                  </FieldContent>
                  {number('questionsPerRound', 1, 100)}
                </Field>

                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>按题目星级奖励</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      关闭时：通过一组固定解锁 N 分钟；开启时：按题目难度星级分别设置奖励时长
                    </FieldDescription>
                  </FieldContent>
                  {toggle('difficultyRewardEnabled')}
                </Field>

                {c.difficultyRewardEnabled ? (
                  [1, 2, 3, 4, 5].map(star => (
                    <Field key={star} orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>{'★'.repeat(star)} 每题奖励（分钟）</FieldTitle>
                      </FieldContent>
                      <Input
                        className="w-24 text-center"
                        aria-label={star + '星奖励'}
                        type="number"
                        min={0.1}
                        max={120}
                        step={0.1}
                        {...form.register(`difficultyRewards.${star}`, { valueAsNumber: true })}
                      />
                    </Field>
                  ))
                ) : (
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>通过一组解锁（分钟）</FieldTitle>
                    </FieldContent>
                    {number('sessionMinutes', 1, 120)}
                  </Field>
                )}

                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>每日使用上限（分钟）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      超出后当天锁死；0 点重置
                    </FieldDescription>
                  </FieldContent>
                  <div className="flex items-center gap-3">
                    {toggle('dailyLimitEnabled')}
                    {c.dailyLimitEnabled && number('dailyMinutes', 1, 480)}
                  </div>
                </Field>

                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>每天最多解锁（组）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">答完上限即锁定</FieldDescription>
                  </FieldContent>
                  <div className="flex items-center gap-3">
                    {toggle('roundsLimitEnabled')}
                    {c.roundsLimitEnabled && number('roundsPerDay', 1, 100)}
                  </div>
                </Field>
              </FieldGroup>
            </FieldSet>

            {/* ── 高级设置：默认收起 ── */}
            <Collapsible className="border-t border-border/60">
              <CollapsibleTrigger
                type="button"
                className="group flex items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                <ChevronDown className="size-3 transition-transform group-data-[state=open]:rotate-180" />
                高级设置
              </CollapsibleTrigger>
              <CollapsibleContent>
                <FieldSet className="gap-0">
                  <FieldGroup className={fieldGroup}>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>学习内容</FieldTitle>
                      </FieldContent>
                      <Controller
                        name="learningContent"
                        control={form.control}
                        render={({ field }) => (
                          <Choice
                            label="学习内容"
                            value={field.value}
                            onChange={field.onChange}
                            options={[
                              ['quiz', '答题'],
                              ['video', '公益视频'],
                              ['alternate', '视频与答题交替'],
                            ]}
                          />
                        )}
                      />
                    </Field>

                    {c.learningContent !== 'quiz' && (
                      <>
                        <Field orientation="responsive" className="py-3.5">
                          <FieldContent>
                            <FieldTitle>每次视频学习（分钟）</FieldTitle>
                          </FieldContent>
                          {number('videoMinutes', 1, 30)}
                        </Field>
                        <Field className="py-3.5">
                          <FieldContent>
                            <FieldTitle>公益视频直链</FieldTitle>
                            <FieldDescription className="text-xs leading-5">
                              每行一个 HTTP/HTTPS 地址
                            </FieldDescription>
                          </FieldContent>
                          <Textarea
                            aria-label="公益视频直链"
                            rows={4}
                            value={c.educationalVideos.join('\n')}
                            onChange={e =>
                              change(
                                'educationalVideos',
                                e.target.value
                                  .split('\n')
                                  .map(s => s.trim())
                                  .filter(Boolean),
                              )
                            }
                          />
                        </Field>
                      </>
                    )}

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>每日免费时长（分钟）</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          到期前无需答题；0 为关闭
                        </FieldDescription>
                      </FieldContent>
                      {number('dailyFreeMinutes', 0, 120)}
                    </Field>

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>每次开机免费（分钟）</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          真实开机或唤醒时发放，重开应用不重复；0 为关闭
                        </FieldDescription>
                      </FieldContent>
                      {number('bootFreeMinutes', 0, 120)}
                    </Field>

                    {c.playMode === 'round' && (
                      <Field orientation="responsive" className="py-3.5">
                        <FieldContent>
                          <FieldTitle>通过正确率（%）</FieldTitle>
                        </FieldContent>
                        <Controller
                          name="passRatio"
                          control={form.control}
                          render={({ field }) => (
                            <Input
                              className="w-24 text-center"
                              type="number"
                              min={1}
                              max={100}
                              aria-label="通过正确率"
                              value={Number.isFinite(field.value) ? Math.round(Number(field.value) * 100) : ''}
                              onChange={e =>
                                field.onChange(e.target.value === '' ? NaN : Number(e.target.value) / 100)
                              }
                            />
                          )}
                        />
                      </Field>
                    )}

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>答错后强制查看解析（秒）</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          0 为不强制；期间不能提前下一题或使用奖励时长
                        </FieldDescription>
                      </FieldContent>
                      {number('wrongReviewSeconds', 0, 300)}
                    </Field>

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>随机打乱选项</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          打乱单选、多选选项；判断题不变
                        </FieldDescription>
                      </FieldContent>
                      {toggle('shuffleOptions')}
                    </Field>

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>显示剩余时间角标</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          右上角显示剩余分钟；最后一分钟闪烁
                        </FieldDescription>
                      </FieldContent>
                      {toggle('showCountdown')}
                    </Field>

                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>学习页字号（%）</FieldTitle>
                        <FieldDescription className="text-xs leading-5">75–200</FieldDescription>
                      </FieldContent>
                      {number('fontScale', 75, 200)}
                    </Field>
                  </FieldGroup>
                </FieldSet>
              </CollapsibleContent>
            </Collapsible>
          </Panel>
        </TabsContent>

        <TabsContent value="scope" className="mt-0">
          <Panel
            title="答题范围"
            icon={BookOpen}
            description="不选即全部题目"
            status={tab === 'scope' ? statusNode : undefined}
          >
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>孩子选择年级</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      从允许且有题目的年级中选择；进行中不能切换
                    </FieldDescription>
                  </FieldContent>
                  {toggle('childSelectLevel')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>题库</FieldTitle>
                    <FieldDescription className="text-xs leading-5">只从选中的题库抽题</FieldDescription>
                  </FieldContent>
                  <div className="flex min-w-0 flex-wrap gap-2">
                    {scopeBanks.map(v => (
                      <FieldLabel
                        key={v}
                        className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                      >
                        <Checkbox
                          checked={inScope('quizBanks', v)}
                          onCheckedChange={() => toggleScope('quizBanks', v)}
                        />
                        <span>{v}</span>
                      </FieldLabel>
                    ))}
                  </div>
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>科目</FieldTitle>
                    <FieldDescription className="text-xs leading-5">只从选中的科目抽题</FieldDescription>
                  </FieldContent>
                  <div className="flex min-w-0 flex-wrap gap-2">
                    {scopeSubjects.length ? (
                      scopeSubjects.map(v => (
                        <FieldLabel
                          key={v}
                          className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                        >
                          <Checkbox
                            checked={inScope('quizSubjects', v)}
                            onCheckedChange={() => toggleScope('quizSubjects', v)}
                          />
                          <span>{v}</span>
                        </FieldLabel>
                      ))
                    ) : (
                      <FieldDescription className="text-xs">题库里还没有科目</FieldDescription>
                    )}
                  </div>
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>年级</FieldTitle>
                  </FieldContent>
                  <div className="flex min-w-0 flex-wrap gap-2">
                    {scopeLevels.length ? (
                      scopeLevels.map(v => (
                        <FieldLabel
                          key={v}
                          className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                        >
                          <Checkbox
                            checked={inScope('quizLevels', v)}
                            onCheckedChange={() => toggleScope('quizLevels', v)}
                          />
                          <span>{v}</span>
                        </FieldLabel>
                      ))
                    ) : (
                      <FieldDescription className="text-xs">题库里还没有年级</FieldDescription>
                    )}
                  </div>
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>没做过的题先出</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      孩子一次都没见过的题排在前面；见过的题按正常顺序。和下面「答错的题先出」同时打开时，答错的题仍然最前。
                    </FieldDescription>
                  </FieldContent>
                  {toggle('newFirst')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>答错的题先出</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      答错的题下一轮先回来重练，答对一次后恢复正常顺序
                    </FieldDescription>
                  </FieldContent>
                  {toggle('wrongFirst')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>没答对的题先出</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      还没答对过的题排在前面，保证整本题库都能过一遍
                    </FieldDescription>
                  </FieldContent>
                  {toggle('coverageFirst')}
                </Field>
              </FieldGroup>
            </FieldSet>
          </Panel>
        </TabsContent>

        <TabsContent value="time" className="mt-0 space-y-5">
          <Panel title="时段" icon={Clock3} status={tab === 'time' ? statusNode : undefined}>
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>时间规则</FieldTitle>
                  </FieldContent>
                  <Controller
                    control={form.control}
                    name="scheduleMode"
                    render={({ field }) => (
                      <Choice
                        label="时间规则"
                        value={field.value || 'allow'}
                        onChange={v => field.onChange(v)}
                        options={[
                          ['allow', '时段内答题解锁，时段外锁定'],
                          ['control', '时段内答题解锁，时段外自由看'],
                        ]}
                      />
                    )}
                  />
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>强制休息</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      关屏、答题不计时，重启不能跳过
                    </FieldDescription>
                  </FieldContent>
                  {toggle('restEnabled')}
                </Field>
                {c.restEnabled && (
                  <>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>累计观看（分钟）</FieldTitle>
                      </FieldContent>
                      {number('restWatchMinutes', 1, 480)}
                    </Field>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>强制休息（分钟）</FieldTitle>
                      </FieldContent>
                      {number('restMinutes', 1, 120)}
                    </Field>
                  </>
                )}
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>按流量判断是否在播放</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      有的电视识别不到正在播放的视频。打开后，只要有声音、同时网络有明显下载，就当作在看视频
                    </FieldDescription>
                  </FieldContent>
                  {toggle('trafficAssistEnabled')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>锁定时不允许进系统设置</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      锁定期间打不开系统设置；需要开启无障碍权限
                    </FieldDescription>
                  </FieldContent>
                  {toggle('blockSettingsDuringControl')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>强制使用全屏学习页</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      适合悬浮窗被视频遮挡的电视（如海信 VIDAA）
                    </FieldDescription>
                  </FieldContent>
                  {toggle('forceFullscreenGate')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>答题页输密码解锁</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      锁定页显示「家长验证解锁」，输对管理密码可临时解锁 30 分钟；默认关闭
                    </FieldDescription>
                  </FieldContent>
                  {toggle('allowQuizUnlock')}
                </Field>
                <Field className="py-3.5">
                  <FieldContent>
                    <FieldTitle>不计时的应用</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      这些应用在前台时不扣观看时长、不弹题，适合系统屏保、网课 App。最多 100 个。
                    </FieldDescription>
                  </FieldContent>
                  <Controller
                    name="watchWhitelist"
                    control={form.control}
                    render={({ field }) => (
                      <WatchWhitelistField
                        value={field.value || []}
                        onChange={field.onChange}
                      />
                    )}
                  />
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>时区</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      可用时段、全天可用和「今天」的重置都按这个时区算。
                      {!c.timezone && device.data?.timezone
                        ? `设备当前：${device.data.timezone}`
                        : '人在国外或设备时间固定在国内时区时改这里。'}
                    </FieldDescription>
                  </FieldContent>
                  <Choice
                    value={c.timezone || 'device'}
                    onChange={v => change('timezone', v === 'device' ? '' : v)}
                    options={zoneChoices}
                    label="时区"
                  />
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>全天管控</FieldTitle>
                    <FieldDescription className="text-xs leading-5">忽略上面的时段设置</FieldDescription>
                  </FieldContent>
                  {toggle('allDay')}
                </Field>
                {!c.allDay && (
                  <WeekSchedule
                    value={c.weekSlots}
                    onChange={v => change('weekSlots', v)}
                    holidayMode={c.holidayMode || 'sunday'}
                    onHolidayMode={v => change('holidayMode', v)}
                  />
                )}
              </FieldGroup>
            </FieldSet>
          </Panel>
          <Panel
            title="网课时段与大人豁免"
            icon={Clock3}
            description="可跨午夜；优先级：解锁/暂停 > 大人豁免 > 网课 > 可用时段"
          >
            <TimePoints
              points={c.timePoints}
              exempt={c.exemptWindows}
              onPoints={v => change('timePoints', v)}
              onExempt={v => change('exemptWindows', v)}
            />
          </Panel>
        </TabsContent>

        {/* forceMount：朗读设置有自己的本地草稿和 700ms 自动保存，卸载会丢掉刚输入但还没保存的内容。
             forceMount 的内容不会自动加 hidden，需要自己按 data-state 隐藏。 */}
        <TabsContent value="more" forceMount className="mt-0 space-y-5 data-[state=inactive]:hidden">
          <Panel title="加练与复习" icon={Repeat2} status={tab === 'more' ? statusNode : undefined}>
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>答错累计加练</FieldTitle>
                  </FieldContent>
                  {toggle('extraEnabled')}
                </Field>
                {c.extraEnabled && (
                  <>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>累计答错（题）</FieldTitle>
                      </FieldContent>
                      {number('wrongThreshold', 1, 100)}
                    </Field>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>每次增加加练（题）</FieldTitle>
                      </FieldContent>
                      {number('extraQuestions', 1, 100)}
                    </Field>
                  </>
                )}
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>按记忆曲线复习</FieldTitle>
                    <FieldDescription className="text-xs leading-5">到该复习的时候自动先出，答对后间隔会拉长</FieldDescription>
                  </FieldContent>
                  {toggle('spacedRepetitionEnabled')}
                </Field>
                {c.spacedRepetitionEnabled && (
                  <Field className="py-3.5">
                    <FieldDescription>
                      已学习 {state?.repetition?.studied || 0} 题，待复习 {state?.repetition?.due || 0} 题；跨重启保留。
                    </FieldDescription>
                  </Field>
                )}
              </FieldGroup>
            </FieldSet>
          </Panel>
          <SpeechSettings sessionId={sessionId} />
        </TabsContent>
      </Tabs>
      {errorMessages.length > 0 && <div className="space-y-2">{errorMessages}</div>}
    </form>
  );
}
