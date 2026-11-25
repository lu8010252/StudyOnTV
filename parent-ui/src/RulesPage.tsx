import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookOpen, Check, ChevronDown, Clock3, GraduationCap, Repeat2, Sparkles } from 'lucide-react';
import { toast } from 'sonner';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
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
import { ClearExtraPoolButton } from './components/ClearExtraPoolButton';
import { normalizeRules, ruleSchema, zoneOptions, type Rules } from './schema';
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
  supervisionEnabled: '启用屏幕时间管控',
  memorizeFirst: '先背再测',
  spacedScope: '间隔重复范围',
  videoMinutes: '每次视频学习分钟',
  dailyFreeMinutes: '每日免费时长',
  bootFreeMinutes: '每次开机或唤醒免费时长',
  bootFreeIntervalMinutes: '开机间隔',
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
  blockSettingsDuringControl: '管控期间不允许进系统设置与应用管理',
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

const parseVideoLines = (text: string) =>
  text
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean);

export default function RulesPage({ state, sessionId }: { state: any; sessionId: number }) {
  const initial = normalizeRules(state?.config);
  const form = useForm<Rules>({ resolver: zodResolver(ruleSchema), defaultValues: initial });
  const c = form.watch();
  const [videoText, setVideoText] = useState(() => (initial.educationalVideos || []).join('\n'));
  const [videoFocused, setVideoFocused] = useState(false);
  const videosKey = (c.educationalVideos || []).join('\n');
  useEffect(() => {
    if (!videoFocused) setVideoText(videosKey);
  }, [videosKey, videoFocused]);
  const client = useQueryClient();
  const [tab, setTab] = useState<'task' | 'time' | 'scope' | 'more'>('task');
  const [gradeSubjectMode, setGradeSubjectMode] = useState(
    () => Object.keys(initial.gradeSubjects || {}).length > 0,
  );
  const bankMeta = useQuery({
    queryKey: ['bank-meta', sessionId],
    queryFn: () => api('/api/questions/meta'),
  });
  const device = useQuery({ queryKey: ['device', sessionId], queryFn: () => api('/api/device') });
  // Radix 的 SelectItem 不接受空字符串，所以「跟随设备」在控件里用 device 代替
  const zoneChoices = zoneOptions.map(([value, label]) => [value || 'device', label] as [string, string]);
  const scopeSubjects: string[] = bankMeta.data?.subjects || [];
  const scopeLevels: string[] = bankMeta.data?.levels || [];
  const scopeBanks: string[] = bankMeta.data?.banks || [];
  const inScope = (key: any, value: string) => Array.isArray((c as any)[key]) && (c as any)[key].includes(value);
  const change = (key: any, value: any) => form.setValue(key, value, { shouldDirty: true, shouldValidate: true });
  const toggleScope = (key: any, value: string) => {
    const list = Array.isArray((c as any)[key]) ? (c as any)[key] : [];
    change(key, list.includes(value) ? list.filter((v: string) => v !== value) : [...list, value]);
  };
  const configSnapshot = state?.config ? JSON.stringify(state.config) : '';
  const activeChildId = state?.child?.id ?? '';
  const lastChildId = useRef(activeChildId);
  useEffect(() => {
    if (state?.config && !form.formState.isDirty) {
      const normalized = normalizeRules(state.config);
      form.reset(normalized);
      const hasRemote = Object.keys(normalized.gradeSubjects || {}).length > 0;
      if (lastChildId.current !== activeChildId) {
        lastChildId.current = activeChildId;
        setGradeSubjectMode(hasRemote);
      } else if (hasRemote) {
        setGradeSubjectMode(true);
      }
    }
  }, [activeChildId, configSnapshot]);
  // 失败后记住失败时的值，避免 isPending 变化触发同一个值反复自动重试（网络断开时每 0.8 秒弹一次错误）。
  const failedSignature = useRef('');
  const validationToast = useRef('');
  const save = useMutation({
    mutationFn: (value: Rules) => api('/api/config', 'PUT', value),
    onSuccess: (r, body) => {
      failedSignature.current = '';
      if (JSON.stringify(form.getValues()) === JSON.stringify(body)) {
        form.reset(normalizeRules(r.config));
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
        const parsed = ruleSchema.safeParse(form.getValues());
        const text = (!parsed.success ? parsed.error.issues[0]?.message : undefined)
          || collectErrorMessages(form.formState.errors)[0]?.text;
        if (text && validationToast.current !== text) {
          validationToast.current = text;
          toast.error('无法自动保存：' + text);
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
    const current = Number((c as any)[key]);
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
  const supervisionEnabled = c.supervisionEnabled !== false;

  const gradeSubjects = (c.gradeSubjects || {}) as Record<string, string[]>;
  const gradeSubjectEntries = Object.entries(gradeSubjects);
  const allScopeLevels = Array.from(new Set([...scopeLevels, ...Object.keys(gradeSubjects)]));
  const gradeSubjectSummary = gradeSubjectEntries.length
    ? `已配置 ${gradeSubjectEntries.length} 个年级：${gradeSubjectEntries
        .map(([g, subs]) => `${g}(${subs.length ? subs.join('、') : '全部科目'})`)
        .join('、')}`
    : '暂未勾选年级；请勾选下方年级，或点击「从当前年级×科目一键导入」';

  const toggleGradeInMap = (level: string) => {
    const next = { ...gradeSubjects };
    if (level in next) delete next[level];
    else next[level] = [];
    change('gradeSubjects', next);
  };

  const toggleSubjectInGrade = (level: string, subject: string) => {
    const current = Array.isArray(gradeSubjects[level]) ? gradeSubjects[level] : [];
    const nextSubs = current.includes(subject)
      ? current.filter(s => s !== subject)
      : [...current, subject];
    change('gradeSubjects', { ...gradeSubjects, [level]: nextSubs });
  };

  const importFromFlatScope = () => {
    const targetLevels = (c.quizLevels?.length ? c.quizLevels : scopeLevels).filter(Boolean);
    if (!targetLevels.length) {
      toast.error('题库里还没有年级可导入');
      return;
    }
    const targetSubjects = (c.quizSubjects || []).filter(Boolean);
    const next: Record<string, string[]> = {};
    targetLevels.forEach(lvl => {
      next[lvl] = [...targetSubjects];
    });
    change('gradeSubjects', next);
    toast.success(`已从当前设置导入 ${targetLevels.length} 个年级`);
  };

  const rewardAndQuotaFields = (
    <FieldGroup className={fieldGroup}>
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
  );

  return (
    <form
      onSubmit={form.handleSubmit(
        v => save.mutate(v),
        () => toast.error('请检查设置中的输入'),
      )}
      className="rules-form space-y-5"
    >
      {/* 顶部模式切换卡：降低上手门槛，不需要锁屏权限也能直接使用全部学习提分能力 */}
      <Card className="gap-0 border-primary/20 bg-gradient-to-br from-primary/[0.03] via-card to-card py-4 shadow-xs">
        <CardContent className="space-y-3 px-4 sm:px-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <GraduationCap className="size-4 text-primary" />
              运行模式
            </div>
            {statusNode}
          </div>
          <div className="grid gap-2.5 sm:grid-cols-2">
            <button
              type="button"
              onClick={() => change('supervisionEnabled', false)}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors ${
                !supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">📚 纯粹学习提分模式</span>
                {!supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />启用中
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                免锁屏、免复杂权限，随时刷题背诵、错题先背再测与艾宾浩斯复习
              </span>
            </button>
            <button
              type="button"
              onClick={() => change('supervisionEnabled', true)}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors ${
                supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">⏳ 先学再看管控模式</span>
                {supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />启用中
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                答题达标赢取屏幕使用时间，自动管理每日时长与可用时段
              </span>
            </button>
          </div>
        </CardContent>
      </Card>

      <Tabs value={tab} onValueChange={value => setTab(value as typeof tab)} className="gap-5">
        <TabsList className="flex w-full max-w-full flex-nowrap justify-start gap-1 overflow-x-auto sm:justify-center">
          {(
            [
              ['task', '任务与背诵'],
              ['scope', '答题范围'],
              ['more', '记忆与加练'],
              ['time', supervisionEnabled ? '时间计划' : '时间管控（未启用）'],
            ] as const
          ).map(([id, label]) => (
            <TabsTrigger
              key={id}
              type="button"
              value={id}
              className="shrink-0 whitespace-nowrap max-[420px]:px-2 max-[420px]:text-xs"
            >
              {label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="task" className="mt-0">
          <Panel title="学习任务与背诵策略" icon={Sparkles} status={tab === 'task' ? statusNode : undefined}>
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>玩法</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      按组闯关：答完一组结算；连续答对：连对 N 题达标；自由练习：不计解锁，随时刷题；答对指定题数：累计答对 N 题达标
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
                    <FieldTitle>先背再测</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      做题前先展示答案与解析卡片背诵一遍，再打乱测验，适合生词、古诗文与新题入门
                    </FieldDescription>
                  </FieldContent>
                  {toggle('memorizeFirst')}
                </Field>

                {supervisionEnabled && rewardAndQuotaFields}
              </FieldGroup>
            </FieldSet>

            {!supervisionEnabled && (
              <Collapsible className="border-t border-border/60">
                <CollapsibleTrigger
                  type="button"
                  className="group flex items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
                >
                  <ChevronDown className="size-3 transition-transform group-data-[state=open]:rotate-180" />
                  屏幕时长与奖励规则（切换到「先学再看管控模式」后生效）
                </CollapsibleTrigger>
                <CollapsibleContent>{rewardAndQuotaFields}</CollapsibleContent>
              </Collapsible>
            )}

            {/* ── 高级设置：默认收起 ── */}
            <Collapsible className="border-t border-border/60">
              <CollapsibleTrigger
                type="button"
                className="group flex items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                <ChevronDown className="size-3 transition-transform group-data-[state=open]:rotate-180" />
                更多答题与显示设置
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
                            value={videoText}
                            onFocus={() => setVideoFocused(true)}
                            onChange={e => {
                              const raw = e.target.value;
                              setVideoText(raw);
                              change('educationalVideos', parseVideoLines(raw));
                            }}
                            onBlur={e => {
                              setVideoFocused(false);
                              setVideoText(parseVideoLines(e.target.value).join('\n'));
                            }}
                          />
                        </Field>
                      </>
                    )}

                    {supervisionEnabled && (
                      <>
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
                      </>
                    )}

                    {c.bootFreeMinutes > 0 && (
                      <Field orientation="responsive" className="py-3.5">
                        <FieldContent>
                          <FieldTitle>开机间隔（分钟）</FieldTitle>
                          <FieldDescription className="text-xs leading-5">
                            开机或唤醒时，距上次发放至少这么久才会再次发放；0 为不限
                          </FieldDescription>
                        </FieldContent>
                        {number('bootFreeIntervalMinutes', 0, 1440)}
                      </Field>
                    )}

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
                          0 为不强制；期间不能提前进入下一题
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

                    {supervisionEnabled && (
                      <Field orientation="responsive" className="py-3.5">
                        <FieldContent>
                          <FieldTitle>显示剩余时间角标</FieldTitle>
                          <FieldDescription className="text-xs leading-5">
                            右上角显示剩余分钟；最后一分钟闪烁
                          </FieldDescription>
                        </FieldContent>
                        {toggle('showCountdown')}
                      </Field>
                    )}

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
                    {scopeBanks.length ? (
                      scopeBanks.map(v => (
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
                      ))
                    ) : (
                      <FieldDescription className="text-xs">题库里还没有题目</FieldDescription>
                    )}
                  </div>
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>按年级分别设置科目（主从组合）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      开启后可为不同年级指定不同科目（例如：三年级只练英语，四年级练语文/数学/英语）；关闭则恢复扁平多选
                    </FieldDescription>
                  </FieldContent>
                  <Switch
                    aria-label="按年级分别设置科目"
                    className="w-8! shrink-0"
                    checked={gradeSubjectMode}
                    onCheckedChange={checked => {
                      setGradeSubjectMode(checked);
                      if (!checked && Object.keys(gradeSubjects).length > 0) {
                        change('gradeSubjects', {});
                      }
                    }}
                  />
                </Field>
                {gradeSubjectMode ? (
                  <Field className="py-3.5">
                    <div className="space-y-3">
                      <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/70 bg-muted/35 px-3 py-2">
                        <p className="min-w-0 flex-1 text-xs leading-5 text-muted-foreground">
                          {gradeSubjectSummary}
                        </p>
                        {(scopeLevels.length > 0 || (c.quizLevels?.length || 0) > 0) && (
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            className="h-7 px-2 text-xs"
                            onClick={importFromFlatScope}
                          >
                            从当前年级×科目一键导入
                          </Button>
                        )}
                      </div>
                      {allScopeLevels.length ? (
                        <div className="space-y-2">
                          {allScopeLevels.map(lvl => {
                            const enabled = lvl in gradeSubjects;
                            const chosenSubs = Array.isArray(gradeSubjects[lvl])
                              ? gradeSubjects[lvl]
                              : [];
                            const levelSubjects = Array.from(
                              new Set([...scopeSubjects, ...chosenSubs]),
                            );
                            return (
                              <div
                                key={lvl}
                                className={`rounded-lg border p-3 transition-colors ${
                                  enabled
                                    ? 'border-primary/40 bg-primary/[0.03]'
                                    : 'border-border/70 bg-background'
                                }`}
                              >
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                  <FieldLabel className="cursor-pointer font-medium">
                                    <Checkbox
                                      checked={enabled}
                                      onCheckedChange={() => toggleGradeInMap(lvl)}
                                    />
                                    <span>{lvl}</span>
                                  </FieldLabel>
                                  {enabled && (
                                    <Badge variant="secondary" className="text-[11px] font-normal">
                                      {chosenSubs.length
                                        ? `已选 ${chosenSubs.length} 科`
                                        : '全部科目'}
                                    </Badge>
                                  )}
                                </div>
                                {enabled && (
                                  <div className="mt-2.5 space-y-1.5 border-t border-border/50 pt-2.5">
                                    <p className="text-[11px] text-muted-foreground">
                                      选择该年级要练习的科目（不勾选表示该年级全部科目）：
                                    </p>
                                    {levelSubjects.length ? (
                                      <div className="flex flex-wrap gap-2">
                                        {levelSubjects.map(sub => (
                                          <FieldLabel
                                            key={sub}
                                            className="cursor-pointer rounded-md border border-border/70 bg-background px-2.5 py-1.5 text-xs font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/10"
                                          >
                                            <Checkbox
                                              checked={chosenSubs.includes(sub)}
                                              onCheckedChange={() => toggleSubjectInGrade(lvl, sub)}
                                            />
                                            <span>{sub}</span>
                                          </FieldLabel>
                                        ))}
                                      </div>
                                    ) : (
                                      <p className="text-xs text-muted-foreground">
                                        题库里还没有科目，将练习该年级全部题目
                                      </p>
                                    )}
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      ) : (
                        <FieldDescription className="text-xs">题库里还没有年级</FieldDescription>
                      )}
                    </div>
                  </Field>
                ) : (
                  <>
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
                  </>
                )}
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
          {!supervisionEnabled && (
            <Alert>
              <Clock3 />
              <AlertDescription className="flex flex-wrap items-center gap-3">
                <span>当前处于「纯粹学习提分模式」，设备不会锁屏或限制其他应用。如需限制观看时长，请开启管控模式。</span>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="ms-auto"
                  onClick={() => change('supervisionEnabled', true)}
                >
                  开启先学再看管控
                </Button>
              </AlertDescription>
            </Alert>
          )}
          <div className={supervisionEnabled ? 'space-y-5' : 'space-y-5 opacity-65'}>
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
                      <FieldTitle>全天管控</FieldTitle>
                      <FieldDescription className="text-xs leading-5">
                        开启后全天按时间规则执行；关闭后可在下方每周时段表自定义
                      </FieldDescription>
                    </FieldContent>
                    {toggle('allDay')}
                  </Field>
                  {!c.allDay && (
                    <WeekSchedule
                      value={c.weekSlots}
                      onChange={v => change('weekSlots', v)}
                      holidayMode={c.holidayMode || 'sunday'}
                      onHolidayMode={v => change('holidayMode', v)}
                      scheduleMode={c.scheduleMode || 'allow'}
                    />
                  )}
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
                      <FieldTitle>管控期间不允许进系统设置与应用管理</FieldTitle>
                      <FieldDescription className="text-xs leading-5">
                        开启后（默认开启），管控、可用时长、临时解锁及自由时段均拦截系统设置与应用管理，防止从设置卸载；家长可在拦截页输管理密码临时进入 2 分钟。需开启无障碍或使用情况访问权限
                      </FieldDescription>
                    </FieldContent>
                    {toggle('blockSettingsDuringControl')}
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
                      {!!(c.watchWhitelist && c.watchWhitelist.length > 0) &&
                        device.data?.monitor &&
                        !device.data.monitor.accessibility &&
                        !device.data.monitor.usage && (
                          <p className="mt-1.5 text-xs leading-5 text-amber-600 dark:text-amber-400">
                            ⚠ 当前设备未开启「使用情况访问」和「无障碍服务」，系统无法识别正在运行的应用，白名单暂不会生效；请到「设置 → 设备」开启其中任意一项。
                          </p>
                        )}
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
                </FieldGroup>
                <details className="group border-t border-border/60">
                  <summary className="flex cursor-pointer list-none items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground [&::-webkit-details-marker]:hidden">
                    <ChevronDown className="size-3 transition-transform group-open:rotate-180" />
                    高级排障选项（流量判断、防遮挡、时区等）
                  </summary>
                  <FieldGroup className={fieldGroup}>
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
                        <FieldTitle>强制使用全屏学习页</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          适合悬浮窗被视频遮挡的电视（如海信 VIDAA）
                        </FieldDescription>
                      </FieldContent>
                      {toggle('forceFullscreenGate')}
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
                  </FieldGroup>
                </details>
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
          </div>
        </TabsContent>

        {/* forceMount：保持子组件状态稳定，按 data-state 隐藏非激活页签 */}
        <TabsContent value="more" forceMount className="mt-0 space-y-5 data-[state=inactive]:hidden">
          <Panel title="科学记忆与错题加练" icon={Repeat2} status={tab === 'more' ? statusNode : undefined}>
            <FieldSet className="gap-0">
              <FieldGroup className={fieldGroup}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>先背再测</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      做题前先展示答案与解析卡片背诵一遍，再打乱顺序小测检验，大幅提升初次正确率
                    </FieldDescription>
                  </FieldContent>
                  {toggle('memorizeFirst')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>按记忆曲线复习（Anki 间隔重复）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      到遗忘临界点自动优先安排复习，答对越稳下次复习间隔越长
                    </FieldDescription>
                  </FieldContent>
                  {toggle('spacedRepetitionEnabled')}
                </Field>
                {c.spacedRepetitionEnabled && (
                  <>
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>间隔重复范围</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          「只背做错过的题」适合题库量大、只想专注清零错题本的场景
                        </FieldDescription>
                      </FieldContent>
                      <Controller
                        name="spacedScope"
                        control={form.control}
                        render={({ field }) => (
                          <Choice
                            label="间隔重复范围"
                            value={field.value || 'all'}
                            onChange={field.onChange}
                            options={[
                              ['all', '全部启用题目'],
                              ['wrong_only', '只背做错过的题（错题艾宾浩斯）'],
                            ]}
                          />
                        )}
                      />
                    </Field>
                    <Field className="py-3.5">
                      <FieldDescription>
                        已纳入记忆计划 {state?.repetition?.studied || 0} 题，今日到期复习 {state?.repetition?.due || 0} 题；进度跨重启保留。
                      </FieldDescription>
                    </Field>
                  </>
                )}
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
                    <FieldTitle>当前加练池</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      当前待完成加练 {Number(state?.learning?.extraPool) || 0} 题；积压过多时可一键清空，不会删除原题库与错题记录
                    </FieldDescription>
                  </FieldContent>
                  <ClearExtraPoolButton
                    sessionId={sessionId}
                    count={Number(state?.learning?.extraPool) || 0}
                  />
                </Field>
              </FieldGroup>
            </FieldSet>
          </Panel>
          <p className="text-xs leading-5 text-muted-foreground">
            朗读怎么读、读题目还是连同选项和答案一起读，已经移到
            <a className="mx-1 underline underline-offset-4" href="#/settings/speech">设置 → 朗读</a>
            ，那里还顺着其它设备设置，找起来更顺手。
          </p>
        </TabsContent>
      </Tabs>
      {errorMessages.length > 0 && <div className="space-y-2">{errorMessages}</div>}
    </form>
  );
}
