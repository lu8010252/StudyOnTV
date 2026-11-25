import type { ComponentType, ReactNode } from 'react';
import {
  BookOpen,
  ChevronLeft,
  ChevronRight,
  Copy,
  Globe,
  Image as ImageIcon,
  Pencil,
  Plus,
  Search,
  Sparkles,
  Trash2,
  Upload,
} from 'lucide-react';
import { resolveMediaUrl } from '@/api';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { FieldDescription } from '@/components/ui/field';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Toolbar, ToolbarGroup, ToolbarSeparator } from '@/components/ui/toolbar';
import { Count, MathText, Stars, answerOf, dot, labels, questionImages } from './shared';

type Props = {
  items: any[];
  total: number;
  page: number;
  pages: number;
  loading: boolean;
  hasFilters: boolean;
  selected: string[];
  pausedCount: number;
  bankFilter: string;
  bulkPending: boolean;
  deleteBankPending: boolean;
  onPage: (page: number) => void;
  onToggle: (id: string) => void;
  onSelectPage: () => void;
  onSelectAll: (checked: boolean) => void;
  onClearSelection: () => void;
  onOpen: (id: string) => void;
  onEdit: (q?: any, copy?: boolean) => void;
  onRemove: (q: any) => void;
  onDeleteBank: (bank: string) => void;
  onBulkPause: (enabled: boolean) => void;
  onBulkDelete: () => void;
  onReset: () => void;
  onCreate: () => void;
  onOpenAi: () => void;
  onOpenMarket: () => void;
  onOpenImport: () => void;
};

/** 空状态：没有题目、筛选没有结果时，给出下一步该做什么 */
function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: ComponentType<any>;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
      <div className="rounded-full bg-muted p-3">
        <Icon className="size-6 text-muted-foreground" />
      </div>
      <div>
        <p className="text-sm font-medium">{title}</p>
        <p className="mt-1 text-xs text-muted-foreground">{description}</p>
      </div>
      {action}
    </div>
  );
}

function firstPreviewMedia(q: any): string {
  const imgs = questionImages(q);
  if (imgs.length) return imgs[0];
  if (Array.isArray(q?.optionImages)) {
    const found = q.optionImages.find(
      (s: any) => typeof s === 'string' && /^(\/api\/media\/|https?:\/\/|data:image\/)/.test(s.trim()),
    );
    if (found) return found.trim();
  }
  return '';
}

/** 题目列表：桌面表格 / 手机卡片共用同一页数据，勾选只作用于当前页。 */
export default function QuestionList(props: Props) {
  const {
    items,
    total,
    page,
    pages,
    loading,
    hasFilters,
    selected,
    pausedCount,
    bankFilter,
    bulkPending,
    deleteBankPending,
  } = props;
  const allSelected = items.length > 0 && items.every((q: any) => selected.includes(q.id));
  return (
    <Card className="mt-4 gap-0 overflow-hidden py-0 shadow-none">
      <Toolbar aria-label="题目批量操作工具栏" className="flex-wrap gap-2 border-b px-3 py-2.5 sm:px-4">
        {selected.length > 0 ? (
          <>
            <ToolbarGroup>
              <Count value={selected.length} suffix="道已选" />
              <Button size="sm" variant="ghost" onClick={props.onSelectPage}>
                选择本页
              </Button>
              <Button size="sm" variant="ghost" onClick={props.onClearSelection}>
                取消选择
              </Button>
            </ToolbarGroup>
            <ToolbarSeparator orientation="vertical" className="max-sm:hidden" />
            <ToolbarGroup className="ms-auto">
              <Button size="sm" variant="ghost" disabled={bulkPending} onClick={() => props.onBulkPause(false)}>
                暂停
              </Button>
              <Button size="sm" variant="ghost" disabled={bulkPending} onClick={() => props.onBulkPause(true)}>
                恢复
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={bulkPending}
                onClick={() => {
                  if (window.confirm(`确定删除选中的 ${selected.length} 道题？此操作无法撤销`)) {
                    props.onBulkDelete();
                  }
                }}
              >
                <Trash2 />
                删除
              </Button>
            </ToolbarGroup>
          </>
        ) : (
          <>
            <ToolbarGroup>
              <Count value={total} suffix="道题目" />
              {pausedCount > 0 && <Count value={pausedCount} suffix="道已暂停" />}
            </ToolbarGroup>
            <ToolbarGroup className="ms-auto">
              {bankFilter !== 'all' && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={deleteBankPending}
                  onClick={() => {
                    if (confirm(`删除整个「${bankFilter}」题库（包含暂停题）？请先导出备份。`)) {
                      props.onDeleteBank(bankFilter);
                    }
                  }}
                >
                  删除此题库
                </Button>
              )}
              <Button size="sm" variant="outline" disabled={!items.length} onClick={props.onSelectPage}>
                选择本页
              </Button>
            </ToolbarGroup>
          </>
        )}
      </Toolbar>

      <div className="hidden md:block">
        <Table className="min-w-[800px] text-sm">
          <TableHeader className="bg-muted/50 text-xs text-muted-foreground">
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-10 px-3">
                <Checkbox
                  aria-label="全选当前页"
                  checked={allSelected}
                  onCheckedChange={checked => props.onSelectAll(checked === true)}
                />
              </TableHead>
              <TableHead className="px-3">题目</TableHead>
              <TableHead className="w-20 px-3">题型</TableHead>
              <TableHead className="w-20 px-3">科目</TableHead>
              <TableHead className="w-20 px-3">年级</TableHead>
              <TableHead className="w-24 px-3">难度</TableHead>
              <TableHead className="w-[18%] px-3">答案</TableHead>
              <TableHead className="w-28 px-3 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((q: any) => {
              const thumb = firstPreviewMedia(q);
              return (
                <TableRow key={q.id} className="cursor-pointer" onClick={() => props.onOpen(q.id)}>
                  <TableCell className="px-3" onClick={e => e.stopPropagation()}>
                    <Checkbox
                      aria-label="选择题目"
                      checked={selected.includes(q.id)}
                      onCheckedChange={() => props.onToggle(q.id)}
                    />
                  </TableCell>
                  <TableCell className="max-w-0 px-3">
                    <div className="flex min-w-0 items-center gap-2">
                      <span className={'size-1.5 shrink-0 rounded-full ' + (dot[q.type] || dot.single)} />
                      <MathText text={q.q} className="truncate" />
                      {q.enabled === false && (
                        <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
                          暂停
                        </Badge>
                      )}
                      {thumb && (
                        <span className="inline-flex shrink-0 items-center gap-1" title="含配图">
                          <img
                            src={resolveMediaUrl(thumb)}
                            alt=""
                            loading="lazy"
                            className="size-5 rounded border bg-muted/20 object-cover"
                          />
                          <ImageIcon className="size-3.5 text-muted-foreground sr-only" aria-label="含配图" />
                        </span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="px-3">
                    <Badge variant="secondary">{labels[q.type] || '单选'}</Badge>
                  </TableCell>
                  <TableCell className="px-3 text-muted-foreground">{q.subject || '—'}</TableCell>
                  <TableCell className="px-3 text-muted-foreground">{q.level || '—'}</TableCell>
                  <TableCell className="px-3">
                    <Stars n={q.difficulty || 2} />
                  </TableCell>
                  <TableCell className="px-3">
                    <MathText text={answerOf(q)} className="truncate text-muted-foreground" />
                  </TableCell>
                  <TableCell className="px-3" onClick={e => e.stopPropagation()}>
                    <div className="flex justify-end gap-1">
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="编辑题目"
                        onClick={() => props.onEdit(q)}
                      >
                        <Pencil />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="复制为新题"
                        onClick={() => props.onEdit(q, true)}
                      >
                        <Copy />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="size-8"
                        aria-label="删除题目"
                        onClick={() => props.onRemove(q)}
                      >
                        <Trash2 className="text-destructive/60" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>

      <div className="divide-y md:hidden">
        {items.map((q: any) => {
          const thumb = firstPreviewMedia(q);
          return (
            <div key={q.id} className="space-y-2 px-4 py-3">
              <Toolbar className="gap-2">
                <ToolbarGroup className="min-w-0">
                  <Checkbox
                    aria-label="选择题目"
                    checked={selected.includes(q.id)}
                    onCheckedChange={() => props.onToggle(q.id)}
                  />
                  <Badge variant="secondary">{labels[q.type] || '单选'}</Badge>
                  <Stars n={q.difficulty || 2} />
                  {q.enabled === false && (
                    <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                      已暂停
                    </Badge>
                  )}
                </ToolbarGroup>
                <ToolbarGroup className="ms-auto">
                  <Button size="icon" variant="ghost" aria-label="查看题目" onClick={() => props.onOpen(q.id)}>
                    <ChevronRight />
                  </Button>
                </ToolbarGroup>
              </Toolbar>
              <div className="flex items-start gap-2.5">
                <MathText text={q.q} className="min-w-0 flex-1 text-sm font-medium leading-6" />
                {thumb && (
                  <img
                    src={resolveMediaUrl(thumb)}
                    alt="题目配图"
                    loading="lazy"
                    className="size-12 shrink-0 rounded border bg-muted/20 object-contain"
                  />
                )}
              </div>
              <MathText text={'答案：' + answerOf(q)} className="truncate text-xs text-muted-foreground" />
              <Toolbar className="gap-1">
                <ToolbarGroup>
                  <span className="truncate text-xs text-muted-foreground">
                    {q.subject || '未分类'}
                    {q.level ? ' · ' + q.level : ''}
                  </span>
                </ToolbarGroup>
                <ToolbarGroup className="ms-auto">
                  <Button size="sm" variant="ghost" onClick={() => props.onEdit(q)}>
                    <Pencil />
                    编辑
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => props.onEdit(q, true)}>
                    <Copy />
                    复制
                  </Button>
                  <Button size="sm" variant="ghost" className="text-destructive" onClick={() => props.onRemove(q)}>
                    <Trash2 />
                    删除
                  </Button>
                </ToolbarGroup>
              </Toolbar>
            </div>
          );
        })}
      </div>

      {!items.length &&
        (loading ? (
          <div className="px-6 py-16 text-center">
            <FieldDescription>正在加载题库…</FieldDescription>
          </div>
        ) : total === 0 && !hasFilters ? (
          <EmptyState
            icon={BookOpen}
            title="题库还没有题目"
            description="可以从在线题库市场下载、导入 Word/PDF/JSON、AI 出题或手动新增"
            action={
              <div className="flex flex-wrap justify-center gap-2">
                <Button size="sm" onClick={props.onOpenMarket}>
                  <Globe className="size-3.5" />
                  在线题库市场
                </Button>
                <Button variant="outline" size="sm" onClick={props.onOpenImport}>
                  <Upload className="size-3.5" />
                  导入文档/JSON
                </Button>
                <Button variant="outline" size="sm" onClick={props.onOpenAi}>
                  <Sparkles className="size-3.5" />
                  AI 出题
                </Button>
                <Button variant="outline" size="sm" onClick={props.onCreate}>
                  <Plus className="size-3.5" />
                  手动新增
                </Button>
              </div>
            }
          />
        ) : (
          <EmptyState
            icon={Search}
            title="没有符合条件的题目"
            description="尝试调整筛选条件或搜索关键词"
            action={
              hasFilters ? (
                <Button variant="outline" size="sm" onClick={props.onReset}>
                  清除所有筛选
                </Button>
              ) : undefined
            }
          />
        ))}

      <Toolbar aria-label="分页工具栏" className="flex-wrap gap-2 border-t px-3 py-2.5 sm:px-4">
        <ToolbarGroup>
          <span className="text-xs text-muted-foreground">
            共 <b className="text-foreground">{total}</b> 题
          </span>
        </ToolbarGroup>
        <ToolbarGroup className="ms-auto">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => props.onPage(page - 1)}>
            <ChevronLeft />
            上一页
          </Button>
          <span className="px-1 text-xs tabular-nums text-muted-foreground">
            <b className="font-medium">{page + 1}</b> / {pages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => props.onPage(page + 1)}>
            下一页
            <ChevronRight />
          </Button>
        </ToolbarGroup>
      </Toolbar>
    </Card>
  );
}
