import { useRef, useState } from 'react';
import { Bold, Code, Eye, Highlighter, ImagePlus, Italic, List, Pencil, Sigma, Underline } from 'lucide-react';
import { toast } from 'sonner';
import { resolveMediaUrl } from '@/api';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { MathText } from './shared';

type Props = {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  rows?: number;
  images?: string[];
  onPasteImages?: (files: File[]) => void;
  onPickImages?: () => void;
  canAddImage?: boolean;
};

/** 富文本编辑框：支持加粗/斜体/下划线/高亮/列表/公式工具栏、快捷键、粘贴截图与实时预览 */
export default function RichStemEditor({
  value,
  onChange,
  placeholder,
  rows = 4,
  images = [],
  onPasteImages,
  onPickImages,
  canAddImage = false,
}: Props) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const [preview, setPreview] = useState(false);
  const [showLatexBar, setShowLatexBar] = useState(false);
  const hasLatex = /\$|\\[\[(]|\\begin\{/.test(value);

  const latexSnippets: Array<{ label: string; before: string; after: string; fallback: string; title: string }> = [
    { label: '行内 $…$', before: '$', after: '$', fallback: 'x^2 + y^2 = z^2', title: '行内 LaTeX 公式' },
    { label: '块级 $$…$$', before: '\n$$\n', after: '\n$$\n', fallback: '\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}', title: '独立居中公式块' },
    { label: '分数', before: '$\\frac{', after: '}{2}$', fallback: '1', title: '分数 \\frac{a}{b}' },
    { label: '根号', before: '$\\sqrt{', after: '}$', fallback: 'x', title: '根号 \\sqrt{x}' },
    { label: '上标', before: '$x^{', after: '}$', fallback: '2', title: '幂/上标' },
    { label: '下标', before: '$a_{', after: '}$', fallback: 'n', title: '下标' },
    { label: '×', before: '$\\times$', after: '', fallback: '', title: '乘号 \\times' },
    { label: '÷', before: '$\\div$', after: '', fallback: '', title: '除号 \\div' },
    { label: '°', before: '$90^{\\circ}$', after: '', fallback: '', title: '角度符号' },
    { label: '方程组', before: '$$\n\\begin{cases}\n', after: '\n\\end{cases}\n$$', fallback: 'x + y = 5 \\\\\nx - y = 1', title: '联立方程组' },
  ];

  function replaceRange(
    el: HTMLTextAreaElement,
    from: number,
    to: number,
    replacement: string,
    selectStart: number,
    selectEnd: number,
  ) {
    el.focus();
    try {
      if (typeof el.setRangeText === 'function') {
        el.setRangeText(replacement, from, to, 'end');
        el.setSelectionRange(selectStart, selectEnd);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        if (el.value !== value) onChange(el.value);
        requestAnimationFrame(() => {
          if (document.activeElement === el) el.setSelectionRange(selectStart, selectEnd);
        });
        return;
      }
    } catch {}
    const next = value.slice(0, from) + replacement + value.slice(to);
    onChange(next);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(selectStart, selectEnd);
    });
  }

  function wrap(before: string, after: string, fallback: string) {
    const el = ref.current;
    if (!el) return;
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? value.length;
    const selected = value.slice(start, end) || fallback;
    const replacement = before + selected + after;
    const s = start + before.length;
    replaceRange(el, start, end, replacement, s, s + selected.length);
  }

  function insertLinePrefix(prefix: string) {
    const el = ref.current;
    if (!el) return;
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? value.length;
    const lineStart = start > 0 ? value.lastIndexOf('\n', start - 1) + 1 : 0;
    const lineEndIdx = value.indexOf('\n', end);
    const lineEnd = lineEndIdx < 0 ? value.length : lineEndIdx;
    const block = value.slice(lineStart, lineEnd);
    const lines = (block || '列表项').split('\n').map(l => (l.startsWith(prefix) ? l : prefix + l));
    const replaced = lines.join('\n');
    replaceRange(el, lineStart, lineEnd, replaced, lineStart, lineStart + replaced.length);
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (!(e.ctrlKey || e.metaKey)) return;
    const k = e.key.toLowerCase();
    if (k === 'b') {
      e.preventDefault();
      wrap('**', '**', '加粗文字');
    } else if (k === 'i') {
      e.preventDefault();
      wrap('*', '*', '斜体文字');
    } else if (k === 'u') {
      e.preventDefault();
      wrap('__', '__', '下划线文字');
    }
  }

  function handlePaste(e: React.ClipboardEvent<HTMLTextAreaElement>) {
    if (!onPasteImages) return;
    const files: File[] = [];
    for (const item of Array.from(e.clipboardData?.items || [])) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file) files.push(file);
      }
    }
    if (!files.length) return;
    if (!canAddImage) {
      if (!e.clipboardData?.getData('text/plain')) {
        e.preventDefault();
        toast.info('配图数量已达上限或正在上传中');
      }
      return;
    }
    e.preventDefault();
    onPasteImages(files);
  }

  return (
    <div className="overflow-hidden rounded-md border bg-background">
      <div className="flex flex-wrap items-center gap-0.5 border-b bg-muted/40 px-1.5 py-1">
        {!preview && (
          <>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="加粗 (Ctrl+B)" aria-label="加粗" onClick={() => wrap('**', '**', '加粗文字')}>
              <Bold className="size-3.5" />
            </Button>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="斜体 (Ctrl+I)" aria-label="斜体" onClick={() => wrap('*', '*', '斜体文字')}>
              <Italic className="size-3.5" />
            </Button>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="下划线 (Ctrl+U)" aria-label="下划线" onClick={() => wrap('__', '__', '下划线文字')}>
              <Underline className="size-3.5" />
            </Button>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="高亮" aria-label="高亮" onClick={() => wrap('==', '==', '重点')}>
              <Highlighter className="size-3.5" />
            </Button>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="行内代码" aria-label="行内代码" onClick={() => wrap('`', '`', 'code')}>
              <Code className="size-3.5" />
            </Button>
            <Button type="button" size="icon" variant="ghost" className="size-7" title="无序列表" aria-label="无序列表" onClick={() => insertLinePrefix('- ')}>
              <List className="size-3.5" />
            </Button>
            <Button
              type="button"
              size="sm"
              variant={showLatexBar ? 'secondary' : 'ghost'}
              className="h-7 gap-1 px-2 text-xs"
              title="LaTeX 数学公式模板（支持 $...$ / $$...$$）"
              aria-label="数学公式"
              onClick={() => setShowLatexBar(v => !v)}
            >
              <Sigma className="size-3.5" />
              LaTeX
            </Button>
            {onPickImages && (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                className="h-7 gap-1 px-2 text-xs"
                disabled={!canAddImage}
                title="上传或粘贴配图"
                onClick={onPickImages}
              >
                <ImagePlus className="size-3.5" />
                配图
              </Button>
            )}
          </>
        )}
        <Button
          type="button"
          size="sm"
          variant={preview ? 'secondary' : 'ghost'}
          className="ms-auto h-7 gap-1 px-2 text-xs"
          onClick={() => setPreview(v => !v)}
        >
          {preview ? <><Pencil className="size-3.5" />编辑</> : <><Eye className="size-3.5" />预览</>}
        </Button>
      </div>
      {!preview && showLatexBar && (
        <div className="flex flex-wrap items-center gap-1 border-b bg-muted/20 px-2 py-1.5">
          {latexSnippets.map(s => (
            <Button
              key={s.label}
              type="button"
              size="sm"
              variant="outline"
              className="h-6 px-2 text-[11px]"
              title={s.title}
              onClick={() => wrap(s.before, s.after, s.fallback)}
            >
              {s.label}
            </Button>
          ))}
        </div>
      )}
      {preview ? (
        <div className="min-h-24 space-y-3 p-3 text-sm leading-6">
          {value.trim() ? (
            <MathText text={value} className="whitespace-pre-wrap" />
          ) : (
            <p className="text-muted-foreground">暂无内容</p>
          )}
          {images.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {images.map((src, i) => (
                <img key={i} src={resolveMediaUrl(src)} alt={`配图 ${i + 1}`} className="max-h-36 rounded-md border object-contain" />
              ))}
            </div>
          )}
        </div>
      ) : (
        <>
          <Textarea
            ref={ref}
            rows={rows}
            value={value}
            placeholder={placeholder}
            onChange={e => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            onPaste={handlePaste}
            className="resize-y rounded-none border-0 shadow-none focus-visible:ring-0"
          />
          {hasLatex && (
            <div className="border-t bg-muted/20 px-3 py-2 text-xs">
              <div className="mb-1 text-[11px] font-medium text-muted-foreground">LaTeX 实时预览</div>
              <MathText text={value} className="whitespace-pre-wrap text-sm leading-6 text-foreground" />
            </div>
          )}
        </>
      )}
    </div>
  );
}
