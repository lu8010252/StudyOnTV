import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, ImagePlus, Link2, Plus, Upload, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Field as UiField, FieldDescription, FieldGroup } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { api, resolveMediaUrl } from '@/api';
import { ChoiceSelect, Field, labels, questionImages } from './shared';
import { compressImageFile, formatImageHint } from './imageUtils';
import RichStemEditor from './RichStemEditor';
import type { BankMeta } from './QuestionFilters';

const MAX_IMAGES = 4;

/** 新增 / 编辑题目。支持富文本格式、公式与本地图片上传 / 剪贴板截图粘贴。 */
export default function QuestionFormDialog({ initial, meta, onClose, onSaved }: {
  initial: any;
  meta: BankMeta;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const client = useQueryClient();
  const [form, setForm] = useState<any>(() => ({
    ...initial,
    images: questionImages(initial),
    optionImages: Array.isArray(initial.optionImages)
      ? initial.optionImages
      : (initial.opts || []).map(() => ''),
  }));
  const [urlInput, setUrlInput] = useState('');
  const [showUrlInput, setShowUrlInput] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const optFileRef = useRef<HTMLInputElement>(null);
  const optTargetRef = useRef<number>(0);

  const save = useMutation({
    mutationFn: (body: any) => api('/api/questions', 'POST', body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['bank'] });
      client.invalidateQueries({ queryKey: ['bank-meta'] });
      onSaved('题目已保存');
    },
    onError: (e: any) => toast.error(e.message),
  });

  function field(key: string, value: any) {
    setForm((f: any) => ({ ...f, [key]: value }));
  }

  async function addImageFiles(files: File[]) {
    const current: string[] = form.images || [];
    const room = MAX_IMAGES - current.length;
    if (room <= 0) {
      toast.info(`每道题最多支持 ${MAX_IMAGES} 张配图`);
      return;
    }
    if (files.length > room) {
      toast.info(`最多支持 ${MAX_IMAGES} 张配图，本次仅添加前 ${room} 张`);
    }
    const batch = files.slice(0, room);
    setUploading(true);
    try {
      const added: string[] = [];
      for (const file of batch) {
        added.push(await compressImageFile(file));
      }
      setForm((f: any) => {
        const next = [...(f.images || []), ...added].slice(0, MAX_IMAGES);
        return { ...f, images: next, image: next[0] || '' };
      });
      toast.success(`已添加 ${added.length} 张配图`);
    } catch (e: any) {
      toast.error(e?.message || '配图处理失败');
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  function addImageUrl() {
    const u = urlInput.trim();
    if (!u) return;
    if (!/^(\/api\/media\/.+|https?:\/\/.+)/.test(u)) {
      toast.error('请输入 http://、https:// 或 /api/media/ 开头的图片地址');
      return;
    }
    const current: string[] = form.images || [];
    if (current.length >= MAX_IMAGES) {
      toast.error(`每道题最多支持 ${MAX_IMAGES} 张配图`);
      return;
    }
    if (current.includes(u)) {
      toast.info('这张图片已在列表中');
      return;
    }
    const next = [...current, u];
    setForm((f: any) => ({ ...f, images: next, image: next[0] || '' }));
    setUrlInput('');
    setShowUrlInput(false);
  }

  function removeImage(index: number) {
    setForm((f: any) => {
      const next = (f.images || []).filter((_: string, i: number) => i !== index);
      return { ...f, images: next, image: next[0] || '' };
    });
  }

  async function setOptionImage(index: number, file: File) {
    setUploading(true);
    try {
      const dataUrl = await compressImageFile(file);
      setForm((f: any) => {
        const next = (f.opts || []).map((_: any, i: number) => (f.optionImages || [])[i] || '');
        next[index] = dataUrl;
        return { ...f, optionImages: next };
      });
      toast.success(`已添加选项 ${String.fromCharCode(65 + index)} 配图`);
    } catch (e: any) {
      toast.error(e?.message || '选项配图处理失败');
    } finally {
      setUploading(false);
      if (optFileRef.current) optFileRef.current.value = '';
    }
  }

  function clearOptionImage(index: number) {
    setForm((f: any) => {
      const next = [...(f.optionImages || [])];
      next[index] = '';
      return { ...f, optionImages: next };
    });
  }

  function handleDropImages(e: React.DragEvent) {
    e.preventDefault();
    if (uploading) return;
    const list = Array.from(e.dataTransfer.files || []).filter(
      f => f.type.startsWith('image/') || /\.(heic|heif)$/i.test(f.name || ''),
    );
    if (list.length) void addImageFiles(list);
  }

  function submit() {
    const f = form;
    const images: string[] = (f.images || []).filter(Boolean);
    const q: any = {
      q: f.q.trim(),
      type: f.type,
      difficulty: Number(f.difficulty),
      tip: f.tip,
      subject: f.subject.trim(),
      level: f.level.trim(),
      bank: f.bank.trim() || '默认题库',
      image: images[0] || '',
      images,
      audio: f.audio.trim(),
      listen: String(f.listen || '').trim(),
      language: f.language,
    };
    if (f.enabled === false) q.enabled = false;
    if (!q.q) return toast.error('请填写题干');
    if (q.listen && q.audio) return toast.error('听力文本和音频地址只能二选一');
    if (q.listen.length > 1000) return toast.error('听力文本不能超过 1000 字');
    if (q.listen) {
      q.listenTimes = Math.min(5, Math.max(1, Number(f.listenTimes) || 5));
      q.listenRequired = !!f.listenRequired;
      const listenRate = Number(f.listenRate);
      if (listenRate >= 0.7 && listenRate <= 1.5) q.listenRate = listenRate;
    }
    if (f.id) q.id = f.id;
    if (f.type === 'subjective') {
      q.modelAnswer = f.modelAnswer;
      q.rubric = f.rubric;
    } else if (f.type === 'text') {
      q.answers = f.accepted.split('\n').map((s: string) => s.trim()).filter(Boolean);
      if (!q.answers.length) return toast.error('请填写至少一个可接受答案');
    } else {
      q.a = f.type === 'boolean' ? ['正确', '错误'] : f.opts.map((o: string) => o.trim());
      const optImgs: string[] = f.type === 'boolean' ? [] : q.a.map((_: string, i: number) => String((f.optionImages || [])[i] || '').trim());
      if (f.type !== 'boolean' && q.a.some((o: string, i: number) => !o && !optImgs[i])) {
        return toast.error('选项文字和配图不能同时为空，多余的选项请删除');
      }
      if (q.a.length < 2) return toast.error('至少需要两个选项');
      if (optImgs.some(Boolean)) q.optionImages = optImgs;
      const correct = f.correct.filter((i: number) => i < q.a.length);
      if (!correct.length) return toast.error('请点击选项左侧的圆圈标记正确答案');
      if (f.type === 'multiple') q.correctIndices = [...correct].sort((a, b) => a - b);
      else q.k = correct[0];
    }
    save.mutate(q);
  }

  const images: string[] = form.images || [];

  return (
    <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
      <DialogContent className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="border-b px-5 py-4">
          <DialogTitle>{form.id ? '编辑题目' : '新增题目'}</DialogTitle>
          <DialogDescription>支持富文本排版、$...$ 数学公式，以及本地上传或 Ctrl+V 粘贴截图配图。</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 overflow-y-auto px-5 py-4">
          <FieldGroup className="gap-4">
            <Field label="题型">
              <ChoiceSelect
                label="题型"
                value={form.type}
                onChange={v => {
                  field('type', v);
                  if (v !== 'multiple') field('correct', [form.correct[0] ?? 0]);
                }}
                options={Object.entries(labels)}
              />
            </Field>

            <UiField>
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium">题干</label>
                <span className="text-xs text-muted-foreground">可直接粘贴截图（Ctrl+V）</span>
              </div>
              <RichStemEditor
                value={form.q}
                onChange={v => field('q', v)}
                placeholder="请输入题目，支持加粗/斜体/下划线/高亮、$公式$，可直接粘贴截图"
                rows={4}
                images={images}
                canAddImage={images.length < MAX_IMAGES && !uploading}
                onPickImages={() => fileRef.current?.click()}
                onPasteImages={files => void addImageFiles(files)}
              />
            </UiField>

            <UiField onDragOver={e => e.preventDefault()} onDrop={handleDropImages}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <label className="text-sm font-medium">题目配图（可选，最多 {MAX_IMAGES} 张）</label>
                <div className="flex items-center gap-1.5">
                  <input
                    ref={fileRef}
                    type="file"
                    accept="image/*"
                    multiple
                    className="hidden"
                    onChange={e => {
                      const list = Array.from(e.target.files || []);
                      if (list.length) void addImageFiles(list);
                    }}
                  />
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={uploading || images.length >= MAX_IMAGES}
                    onClick={() => fileRef.current?.click()}
                  >
                    <Upload className="size-3.5" />
                    {uploading ? '压缩中…' : '上传图片'}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={images.length >= MAX_IMAGES}
                    onClick={() => setShowUrlInput(v => !v)}
                  >
                    <Link2 className="size-3.5" />
                    图片链接
                  </Button>
                </div>
              </div>
              {showUrlInput && (
                <div className="mt-2 flex gap-2">
                  <Input
                    value={urlInput}
                    onChange={e => setUrlInput(e.target.value)}
                    placeholder="https://example.com/diagram.png"
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addImageUrl(); } }}
                  />
                  <Button type="button" size="sm" onClick={addImageUrl}>添加</Button>
                </div>
              )}
              {images.length > 0 ? (
                <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {images.map((src, i) => (
                    <div key={i} className="group relative overflow-hidden rounded-md border bg-muted/30">
                      <img src={resolveMediaUrl(src)} alt={`配图 ${i + 1}`} className="h-24 w-full object-contain p-1" />
                      <div className="flex items-center justify-between border-t bg-background/90 px-2 py-0.5 text-[11px] text-muted-foreground">
                        <span>{formatImageHint(src)}</span>
                        <button
                          type="button"
                          aria-label={`移除第 ${i + 1} 张配图`}
                          className="rounded p-0.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                          onClick={() => removeImage(i)}
                        >
                          <X className="size-3.5" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => fileRef.current?.click()}
                  className="mt-1 flex w-full items-center justify-center gap-2 rounded-md border border-dashed py-3 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
                >
                  <ImagePlus className="size-4" />
                  点击上传、拖拽图片到此处，或在题干框按 Ctrl+V 粘贴截图（自动压缩）
                </button>
              )}
            </UiField>

            {['single', 'multiple', 'boolean'].includes(form.type) && (
              <Field label="选项">
                <FieldDescription>{form.type === 'multiple' ? '点击字母标记全部正确答案，支持为选项单独上传或粘贴配图。' : '点击字母标记正确答案，支持为选项单独上传或粘贴配图。'}</FieldDescription>
                <input
                  ref={optFileRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={e => {
                    const file = e.target.files?.[0];
                    if (file) void setOptionImage(optTargetRef.current, file);
                  }}
                />
                <FieldGroup className="gap-2">
                  {(form.type === 'boolean' ? ['正确', '错误'] : form.opts).map((o: string, i: number) => {
                    const on = form.correct.includes(i);
                    const optImg = form.type === 'boolean' ? '' : ((form.optionImages || [])[i] || '');
                    return (
                      <div key={i} className="space-y-1.5">
                        <UiField orientation="horizontal" className="gap-2">
                          <Button
                            type="button"
                            size="icon"
                            variant={on ? 'default' : 'outline'}
                            className="shrink-0 rounded-full text-xs font-semibold"
                            aria-label={`标记选项 ${String.fromCharCode(65 + i)} 为正确答案`}
                            aria-pressed={on}
                            onClick={() => field('correct', form.type === 'multiple' ? (on ? form.correct.filter((x: number) => x !== i) : [...form.correct, i]) : [i])}
                          >
                            {on ? <Check /> : String.fromCharCode(65 + i)}
                          </Button>
                          {form.type === 'boolean' ? (
                            <FieldDescription className="text-foreground">{o}</FieldDescription>
                          ) : (
                            <>
                              <Input
                                aria-label={`选项 ${String.fromCharCode(65 + i)}`}
                                value={o}
                                placeholder={`选项 ${String.fromCharCode(65 + i)}（可直接粘贴截图）`}
                                onChange={e => field('opts', form.opts.map((x: string, j: number) => (j === i ? e.target.value : x)))}
                                onPaste={e => {
                                  for (const item of Array.from(e.clipboardData?.items || [])) {
                                    if (item.kind === 'file' && item.type.startsWith('image/')) {
                                      const file = item.getAsFile();
                                      if (file) {
                                        e.preventDefault();
                                        void setOptionImage(i, file);
                                        return;
                                      }
                                    }
                                  }
                                }}
                              />
                              <Button
                                type="button"
                                size="icon"
                                variant="ghost"
                                title={`上传选项 ${String.fromCharCode(65 + i)} 配图`}
                                aria-label={`上传选项 ${String.fromCharCode(65 + i)} 配图`}
                                disabled={uploading}
                                onClick={() => { optTargetRef.current = i; optFileRef.current?.click(); }}
                              >
                                <ImagePlus className="size-4" />
                              </Button>
                              {form.opts.length > 2 && (
                                <Button
                                  type="button"
                                  size="icon"
                                  variant="ghost"
                                  aria-label="删除此选项"
                                  onClick={() => {
                                    field('opts', form.opts.filter((_: any, j: number) => j !== i));
                                    field('optionImages', (form.optionImages || []).filter((_: any, j: number) => j !== i));
                                    field('correct', form.correct.filter((x: number) => x !== i).map((x: number) => (x > i ? x - 1 : x)));
                                  }}
                                >
                                  <X />
                                </Button>
                              )}
                            </>
                          )}
                        </UiField>
                        {optImg && (
                          <div className="ms-11 flex items-center gap-2">
                            <img src={resolveMediaUrl(optImg)} alt={`选项 ${String.fromCharCode(65 + i)} 配图`} className="h-16 rounded border bg-muted/20 object-contain p-0.5" />
                            <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs text-muted-foreground hover:text-destructive" onClick={() => clearOptionImage(i)}>
                              <X className="size-3.5" />移除配图
                            </Button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </FieldGroup>
                {form.type !== 'boolean' && form.opts.length < 8 && (
                  <Button type="button" variant="outline" size="sm" onClick={() => {
                    field('opts', [...form.opts, '']);
                    field('optionImages', [...(form.optionImages || []), '']);
                  }}>
                    <Plus />添加选项
                  </Button>
                )}
              </Field>
            )}

            {form.type === 'text' && (
              <Field label="可接受答案，每行一个">
                <Textarea rows={3} value={form.accepted} onChange={e => field('accepted', e.target.value)} />
              </Field>
            )}
            {form.type === 'subjective' && (
              <>
                <Field label="参考答案">
                  <Textarea rows={3} value={form.modelAnswer} onChange={e => field('modelAnswer', e.target.value)} />
                </Field>
                <Field label="评分标准">
                  <Textarea rows={2} value={form.rubric} onChange={e => field('rubric', e.target.value)} />
                </Field>
              </>
            )}

            <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="科目">
                <Input list="bank-subjects" value={form.subject} onChange={e => field('subject', e.target.value)} placeholder="例如 数学" />
                <datalist id="bank-subjects">{meta.subjects.map(v => <option key={v} value={v} />)}</datalist>
              </Field>
              <Field label="年级">
                <Input list="bank-levels" value={form.level} onChange={e => field('level', e.target.value)} placeholder="例如 五年级" />
                <datalist id="bank-levels">{meta.levels.map(v => <option key={v} value={v} />)}</datalist>
              </Field>
            </FieldGroup>

            <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="题库名称">
                <Input list="bank-names" value={form.bank} onChange={e => field('bank', e.target.value)} />
                <datalist id="bank-names">{meta.banks.map(v => <option key={v} value={v} />)}</datalist>
              </Field>
              <Field label="音频地址（可选）">
                <Input value={form.audio} onChange={e => field('audio', e.target.value)} placeholder="https://" />
              </Field>
            </FieldGroup>

            {/* 听力题：这段文字只交给朗读，屏幕上不显示，所以听力和文字可以脱钩，不需要音频文件 */}
            <FieldGroup className="gap-4 rounded-lg border p-4">
              <Field label="听力文本（可选，只朗读不显示）">
                <Textarea
                  rows={2}
                  value={form.listen || ''}
                  onChange={e => field('listen', e.target.value)}
                  placeholder="例如 The school bus leaves at a quarter past seven."
                />
              </Field>
              <FieldDescription>
                填了听力文本就不要再填音频地址（两者只能二选一）。播放时用设备朗读这段文字，屏幕上看不到它——题干照常写「听录音，选出正确答案。」，选项照常写答案候选。英语听力题记得把上面的「朗读 / 语音作答语言」选成英语。
              </FieldDescription>
              {form.listen ? (
                <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <Field label="允许播放次数">
                    <ChoiceSelect
                      label="允许播放次数"
                      value={String(form.listenTimes || '5')}
                      onChange={v => field('listenTimes', v)}
                      options={[['1', '1 次'], ['2', '2 次'], ['3', '3 次'], ['4', '4 次'], ['5', '5 次（默认）']]}
                    />
                  </Field>
                  <Field label="作答前必须播放">
                    <ChoiceSelect
                      label="作答前必须播放"
                      value={form.listenRequired ? 'yes' : 'no'}
                      onChange={v => field('listenRequired', v === 'yes')}
                      options={[['no', '不限制'], ['yes', '必须听过才能提交']]}
                    />
                  </Field>
                  <Field label="单题语速">
                    <ChoiceSelect
                      label="单题语速"
                      value={String(form.listenRate ?? '')}
                      onChange={v => field('listenRate', v)}
                      options={[['', '跟随朗读设置'], ['0.8', '0.8× 慢'], ['0.9', '0.9×'], ['1', '1× 正常'], ['1.1', '1.1×'], ['1.2', '1.2× 快']]}
                    />
                  </Field>
                </FieldGroup>
              ) : null}
            </FieldGroup>

            <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="朗读 / 语音作答语言">
                <ChoiceSelect
                  label="朗读 / 语音作答语言"
                  value={form.language || 'system'}
                  onChange={v => field('language', v === 'system' ? '' : v)}
                  options={[['zh-CN', '中文'], ['zh-HK', '粤语（香港）'], ['zh-TW', '台湾国语'], ['en-US', '英语（美式）'], ['en-GB', '英语（英式）'], ['system', '跟随设备系统语言']]}
                />
              </Field>
              <Field label="星级难度">
                <ChoiceSelect
                  label="星级难度"
                  value={String(form.difficulty)}
                  onChange={v => field('difficulty', Number(v))}
                  options={[1, 2, 3, 4, 5].map(s => [String(s), '★'.repeat(s)])}
                />
              </Field>
            </FieldGroup>

            <UiField>
              <label className="text-sm font-medium">解析（支持富文本与公式）</label>
              <RichStemEditor
                value={form.tip}
                onChange={v => field('tip', v)}
                placeholder="可选，答对或答错后展示给孩子"
                rows={2}
              />
            </UiField>
          </FieldGroup>
        </div>
        <DialogFooter className="border-t px-5 py-3">
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button disabled={save.isPending || uploading} onClick={submit}>
            {save.isPending ? '保存中…' : '保存题目'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
