import { lazy, Suspense, useEffect, useReducer, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Globe, MoreHorizontal, Plus, Sparkles, Trash2, Upload } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { api } from './api';
import BankAutomationPanel from './BankAutomationPanel';
import {
  activeFilterCount,
  buildQuery,
  defaultFilter,
  filterReducer,
  hasFilters,
  PAGE_SIZE,
  type Filter,
} from './components/bank/filters';
import QuestionFilters from './components/bank/QuestionFilters';
import QuestionList from './components/bank/QuestionList';
import QuestionDetailSheet from './components/bank/QuestionDetailSheet';
import QuestionFormDialog from './components/bank/QuestionFormDialog';
const QuestionImportDialog = lazy(() => import('./components/bank/QuestionImportDialog'));
const BankMarketDialog = lazy(() => import('./components/bank/BankMarketDialog'));
const AiGenerateDialog = lazy(() => import('./components/bank/AiGenerateDialog'));
import { answerOf, blank, download, MathText, questionImages } from './components/bank/shared';

// GradingPage 等页面仍在从这里取这两个工具
export { answerOf, MathText };

/**
 * 题库管理页。题目多的时候（几千到几万道）不再一次性拉全量：
 * 搜索、筛选、排序、分页都交给设备端 SQL，一次只取一页，浏览器里不再对全库做 filter。
 */
export default function QuestionBank({ sessionId }: { sessionId: number }) {
  const client = useQueryClient();
  const [filter, dispatch] = useReducer(filterReducer, defaultFilter);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [drawer, setDrawer] = useState<string | null>(null);
  const [form, setForm] = useState<any>(null);
  const [importing, setImporting] = useState(false);
  const [marketOpen, setMarketOpen] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);
  const [remove, setRemove] = useState<any>(null);
  const [exporting, setExporting] = useState(false);

  // 输入框即时响应，请求防抖 300ms：每敲一个字就扫一遍设备端 SQL 会卡输入
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(filter.search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [filter.search]);

  const params = buildQuery(filter, page, search);
  const query = useQuery({
    queryKey: ['bank', sessionId, params],
    queryFn: () => api('/api/questions?' + params),
    placeholderData: keepPreviousData,
  });
  const metaQuery = useQuery({
    queryKey: ['bank-meta', sessionId],
    queryFn: () => api('/api/questions/meta'),
    staleTime: 30000,
  });
  const meta = metaQuery.data || { banks: [], subjects: [], levels: [], paused: 0, wrong: 0, total: 0 };
  const items: any[] = query.data?.items || [];
  const total = query.data?.total || 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const current = drawer ? items.find((q: any) => q.id === drawer) || null : null;

  // 删到最后一页空了（或者筛选后页数变少）要退回上一页，别停在空白页
  useEffect(() => {
    if (page > 0 && page >= pages) setPage(pages - 1);
  }, [page, pages]);
  // 题目被删掉 / 当前页刷新后不在列表里，详情抽屉要自己关掉
  useEffect(() => {
    if (drawer && !items.some((q: any) => q.id === drawer)) setDrawer(null);
  }, [items, drawer]);

  function invalidate() {
    client.invalidateQueries({ queryKey: ['bank'] });
    client.invalidateQueries({ queryKey: ['bank-meta'] });
  }
  function change(key: keyof Filter, value: string | boolean) {
    dispatch({ type: 'set', key, value });
    setPage(0);
  }
  function reset() {
    dispatch({ type: 'reset' });
    setPage(0);
    setSelected([]);
  }
  function startEdit(q?: any, copy = false) {
    setDrawer(null);
    setForm(
      q
        ? {
            id: copy ? '' : q.id,
            type: q.type || 'single',
            q: q.q,
            opts: q.type === 'boolean' ? ['正确', '错误'] : [...(q.a || [])],
            optionImages: q.type === 'boolean' ? [] : (q.a || []).map((_: any, i: number) => (Array.isArray(q.optionImages) ? q.optionImages[i] || '' : '')),
            correct: q.type === 'multiple' ? [...(q.correctIndices || [])] : [q.k || 0],
            accepted: (q.answers || []).join('\n'),
            difficulty: q.difficulty || 2,
            tip: q.tip || '',
            modelAnswer: q.modelAnswer || '',
            rubric: q.rubric || '',
            subject: q.subject || '',
            level: q.level || '',
            bank: q.bank || '默认题库',
            image: q.image || '',
            images: questionImages(q),
            audio: q.audio || '',
            listen: q.listen || '',
            listenTimes: q.listenTimes != null ? String(q.listenTimes) : '5',
            listenRequired: !!q.listenRequired,
            listenRate: q.listenRate != null ? String(q.listenRate) : '',
            language: q.language ?? 'zh-CN',
            enabled: copy ? true : q.enabled,
          }
        : { ...blank },
    );
  }
  const toggle = (id: string) =>
    setSelected(list => (list.includes(id) ? list.filter(x => x !== id) : [...list, id]));

  const bulkDelete = useMutation({
    mutationFn: (ids: string[]) => api('/api/questions/delete', 'POST', { ids }),
    onSuccess: (r: any) => {
      setSelected([]);
      invalidate();
      toast.success(`已删除 ${r.deleted} 题`);
    },
    onError: (e: any) => toast.error(e.message),
  });
  const bulkPause = useMutation({
    mutationFn: ({ ids, enabled }: { ids: string[]; enabled: boolean }) =>
      api('/api/questions/pause', 'POST', { ids, enabled }),
    onSuccess: (r: any, { enabled }) => {
      setSelected([]);
      invalidate();
      toast.success(enabled ? `已恢复 ${r.updated} 题` : `已暂停 ${r.updated} 题`);
    },
    onError: (e: any) => toast.error(e.message),
  });
  const deleting = useMutation({
    mutationFn: (id: string) => api('/api/questions/' + encodeURIComponent(id), 'DELETE'),
    onSuccess: () => {
      setRemove(null);
      setDrawer(null);
      invalidate();
      toast.success('已删除');
    },
    onError: (e: any) => toast.error(e.message),
  });
  const deleteBank = useMutation({
    mutationFn: (bank: string) => api('/api/questions/bank', 'DELETE', { bank, confirm: true }),
    onSuccess: (r: any) => {
      setSelected([]);
      setDrawer(null);
      dispatch({ type: 'set', key: 'bank', value: 'all' });
      setPage(0);
      invalidate();
      toast.success(`已删除题库，共 ${r.deleted} 题`);
    },
    onError: (e: any) => toast.error(e.message),
  });
  const clearBank = useMutation({
    mutationFn: () => api('/api/questions/clear', 'POST', { confirm: true }),
    onSuccess: (r: any) => {
      setSelected([]);
      setDrawer(null);
      invalidate();
      toast.success(`已清空题库，共删除 ${r.deleted} 题`);
    },
    onError: (e: any) => toast.error(e.message),
  });

  // 导出要的就是整份题库（含暂停题与内联本地配图）：单独请求全量，日常翻页仍然只取一页
  async function exportBank() {
    if (exporting) return;
    setExporting(true);
    try {
      const result = await api(
        '/api/questions?all=1&limit=50000&inlineMedia=1',
        'GET',
        undefined,
        60000,
      );
      const exported = (result.items || []).map((q: any) => {
        const item: any = { ...q };
        const imgs = questionImages(q);
        if (imgs.length) {
          item.images = imgs;
          item.image = imgs[0];
        }
        if (Array.isArray(q.optionImages) && q.optionImages.some(Boolean)) {
          item.optionImages = q.optionImages;
        }
        item.enabled = q.enabled !== false;
        return item;
      });
      download({ version: 2, items: exported }, 'studyontv-bank.json');
    } catch (e: any) {
      toast.error(e.message || '导出失败');
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <QuestionFilters
        filter={filter}
        onChange={change}
        onReset={reset}
        meta={meta}
        activeCount={activeFilterCount(filter)}
        actions={
          <>
            <Button size="sm" onClick={() => startEdit()}>
              <Plus />
              新增题目
            </Button>
            <Button variant="outline" size="sm" onClick={() => setMarketOpen(true)}>
              <Globe />
              在线题库市场
            </Button>
            <Button variant="outline" size="sm" onClick={() => setImporting(true)}>
              <Upload />
              导入 Word/PDF/JSON
            </Button>
            <Button variant="outline" size="sm" onClick={() => setAiOpen(true)}>
              <Sparkles />
              AI 出题
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon" className="size-8" aria-label="更多题库操作">
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-44">
                <DropdownMenuLabel>导出</DropdownMenuLabel>
                <DropdownMenuItem disabled={exporting} onSelect={exportBank}>
                  <Download />
                  {exporting ? '正在导出…' : '导出全部题目'}
                </DropdownMenuItem>
                {selected.length > 0 && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuLabel>已选 {selected.length} 题</DropdownMenuLabel>
                    <DropdownMenuItem
                      disabled={bulkPause.isPending}
                      onSelect={() => bulkPause.mutate({ ids: selected, enabled: false })}
                    >
                      暂停选中
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      variant="destructive"
                      disabled={bulkDelete.isPending}
                      onSelect={() => bulkDelete.mutate(selected)}
                    >
                      <Trash2 />
                      删除选中
                    </DropdownMenuItem>
                  </>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  variant="destructive"
                  disabled={query.isLoading || clearBank.isPending || !meta.total}
                  onSelect={() => {
                    if (window.confirm('确定清空全部题库（包括已暂停的题目）？此操作无法撤销，建议先导出备份。')) {
                      clearBank.mutate();
                    }
                  }}
                >
                  <Trash2 />
                  清空题库
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        }
      />

      <BankAutomationPanel sessionId={sessionId} />

      <QuestionList
        items={items}
        total={total}
        page={page}
        pages={pages}
        loading={query.isLoading}
        hasFilters={hasFilters(filter)}
        selected={selected}
        pausedCount={meta.paused}
        bankFilter={filter.bank}
        bulkPending={bulkDelete.isPending || bulkPause.isPending}
        deleteBankPending={deleteBank.isPending}
        onPage={setPage}
        onToggle={toggle}
        onSelectPage={() => setSelected(list => [...new Set([...list, ...items.map((q: any) => q.id)])])}
        onSelectAll={checked =>
          setSelected(list =>
            checked
              ? [...new Set([...list, ...items.map((q: any) => q.id)])]
              : list.filter(id => !items.some((q: any) => q.id === id)),
          )
        }
        onClearSelection={() => setSelected([])}
        onOpen={setDrawer}
        onEdit={startEdit}
        onRemove={setRemove}
        onDeleteBank={bank => deleteBank.mutate(bank)}
        onBulkPause={enabled => bulkPause.mutate({ ids: selected, enabled })}
        onBulkDelete={() => bulkDelete.mutate(selected)}
        onReset={reset}
        onCreate={() => startEdit()}
        onOpenAi={() => setAiOpen(true)}
        onOpenMarket={() => setMarketOpen(true)}
        onOpenImport={() => setImporting(true)}
      />

      <QuestionDetailSheet
        current={current}
        items={items}
        onNavigate={setDrawer}
        onClose={() => setDrawer(null)}
        onEdit={startEdit}
        onCopy={q => startEdit(q, true)}
        onRemove={setRemove}
      />

      {form && (
        <QuestionFormDialog
          initial={form}
          meta={meta}
          onClose={() => setForm(null)}
          onSaved={message => {
            setForm(null);
            toast.success(message);
          }}
        />
      )}

      {importing && (
        <Suspense fallback={null}>
          <QuestionImportDialog
            open={importing}
            onOpenChange={setImporting}
            meta={meta}
            onImported={message => toast.success(message)}
          />
        </Suspense>
      )}

      {marketOpen && (
        <Suspense fallback={null}>
          <BankMarketDialog open={marketOpen} onOpenChange={setMarketOpen} sessionId={sessionId} />
        </Suspense>
      )}

      {aiOpen && (
        <Suspense fallback={null}>
          <AiGenerateDialog
            open={aiOpen}
            onOpenChange={setAiOpen}
            sessionId={sessionId}
            onOpenImport={() => setImporting(true)}
          />
        </Suspense>
      )}

      <Dialog
        open={!!remove}
        onOpenChange={v => {
          if (!v) setRemove(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除这道题？</DialogTitle>
            <DialogDescription>{remove?.q}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRemove(null)}>
              取消
            </Button>
            <Button variant="destructive" disabled={deleting.isPending} onClick={() => deleting.mutate(remove.id)}>
              删除
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
