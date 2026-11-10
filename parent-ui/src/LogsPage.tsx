import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, RefreshCw, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemTitle,
} from '@/components/ui/item';
import { Switch } from '@/components/ui/switch';
import { Toolbar, ToolbarGroup } from '@/components/ui/toolbar';
import { api, downloadLogs } from './api';

export default function LogsPage({ sessionId }: { sessionId: number }) {
  const client = useQueryClient();
  const [auto, setAuto] = useState(true);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const logs = useQuery({
    queryKey: ['logs', sessionId],
    queryFn: () => api('/api/logs?limit=1200'),
    refetchInterval: auto ? 3000 : false,
  });

  useEffect(() => {
    if (auto) void logs.refetch();
  }, [auto]);

  const lines: string[] = logs.data?.lines || [];
  const shown = query.trim()
    ? lines.filter(line => line.toLowerCase().includes(query.trim().toLowerCase()))
    : lines;

  async function clear() {
    if (!window.confirm('清空本机诊断日志？导出的旧文件不受影响。')) return;
    try {
      await api('/api/logs', 'DELETE');
      client.invalidateQueries({ queryKey: ['logs', sessionId] });
      toast.success('日志已清空');
    } catch (error: any) {
      toast.error(error.message);
    }
  }

  async function exportLogs() {
    setBusy(true);
    try {
      await downloadLogs();
      toast.success('日志包已开始下载');
    } catch (error: any) {
      toast.error(error.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="gap-0 overflow-hidden py-0 shadow-xs">
      <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
            <RefreshCw className="size-4" />
          </span>
          诊断日志
        </CardTitle>
        <CardDescription className="text-xs">只保存在本机，不会自动上传。</CardDescription>
        <CardAction>
          <Button size="sm" variant="outline" onClick={() => logs.refetch()} disabled={logs.isFetching}>
            <RefreshCw className={logs.isFetching ? 'animate-spin' : ''} />
            刷新
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="border-t p-0">
        <ItemGroup className="divide-y divide-border/60">
          <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
            <ItemContent>
              <ItemTitle>自动刷新</ItemTitle>
              <ItemDescription className="text-xs">每 3 秒刷新。</ItemDescription>
            </ItemContent>
            <ItemActions>
              <Switch aria-label="自动刷新日志" checked={auto} onCheckedChange={setAuto} />
            </ItemActions>
          </Item>
          <div className="px-4 py-3 sm:px-6">
            <Toolbar aria-label="日志工具栏" className="flex-col gap-3 sm:flex-row">
              <ToolbarGroup className="flex-wrap">
                <Button variant="outline" onClick={exportLogs} disabled={busy}>
                  <Download />
                  导出日志包
                </Button>
                <Button variant="ghost" className="text-destructive" onClick={clear}>
                  <Trash2 />
                  清空
                </Button>
              </ToolbarGroup>
              <Input
                className="w-full sm:ms-auto sm:w-64"
                aria-label="筛选日志"
                placeholder="筛选关键字"
                value={query}
                onChange={event => setQuery(event.target.value)}
              />
            </Toolbar>
          </div>
        </ItemGroup>
        <pre className="m-4 max-h-[55svh] overflow-auto rounded-lg bg-muted/45 p-3 font-mono text-[11px] leading-5 whitespace-pre-wrap break-all sm:mx-6">
          {logs.isLoading ? '正在读取日志…' : shown.length ? shown.join('\n') : '暂时没有日志。'}
        </pre>
        <p className="px-4 pb-4 text-xs text-muted-foreground sm:px-6">
          共 {lines.length} 行{query ? `，筛出 ${shown.length} 行` : ''}
        </p>
      </CardContent>
    </Card>
  );
}
