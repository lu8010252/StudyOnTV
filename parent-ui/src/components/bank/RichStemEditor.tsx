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
            <Button type="button" size="icon" variant="ghost" className="size-7" title="插入数学公式 $...$" aria-label="数学公式" onClick={() => wrap('$', '$', 'x^2 + y^2 = z^2')}>
              <Sigma className="size-3.5" />
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
      )}
    </div>
  );
}
