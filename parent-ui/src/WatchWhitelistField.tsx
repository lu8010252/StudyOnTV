import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AppWindow, Plus, Search, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { api } from './api';

type AppItem = { package: string; name: string };

const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

/**
 * 不计时应用白名单（P1）：系统屏保、网课 App 在前台时不扣观看时长、不弹题。
 * 名单最终由设备端 WatchWhitelist 校验；这里只负责选择和展示。
 */
export default function WatchWhitelistField({
  value,
  onChange,
}: {
  value: string[];
  onChange: (next: string[]) => void;
}) {
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  // 已安装应用列表只用于显示名称 / 选择；读不到时仍可手动输入包名
  const appsQuery = useQuery({
    queryKey: ['apps'],
    queryFn: () => api('/api/apps'),
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
  const apps: AppItem[] = (appsQuery.data?.items || []) as AppItem[];
  const nameOf = (pkg: string) => apps.find(app => app.package === pkg)?.name || pkg;

  const add = (raw: string): boolean => {
    const pkg = raw.trim();
    if (!pkg) return false;
    if (!PACKAGE.test(pkg) || pkg.length > 200) {
      setError('包名格式不正确，例如 com.example.app');
      return false;
    }
    if (value.includes(pkg)) {
      setError('这个应用已经在名单里了');
      return false;
    }
    if (value.length >= 100) {
      setError('最多添加 100 个应用');
      return false;
    }
    setError('');
    onChange([...value, pkg]);
    return true;
  };

  const choices = useMemo(
    () =>
      apps
        .filter(app => !value.includes(app.package))
        .filter(
          app =>
            !search.trim() ||
            app.name.toLowerCase().includes(search.trim().toLowerCase()) ||
            app.package.toLowerCase().includes(search.trim().toLowerCase()),
        )
        .slice(0, 300),
    [apps, value, search],
  );

  return (
    <div className="w-full space-y-3">
      <div className="flex flex-wrap gap-2">
        {value.length === 0 && <p className="text-xs text-muted-foreground">还没有添加应用。</p>}
        {value.map(pkg => (
          <span
            key={pkg}
            className="inline-flex max-w-full items-center gap-1.5 rounded-lg border border-border/70 bg-muted/30 px-2.5 py-1.5 text-xs"
          >
            <AppWindow className="size-3.5 shrink-0 text-muted-foreground" />
            <span className="max-w-56 truncate" title={pkg}>
              {nameOf(pkg)}
            </span>
            <button
              type="button"
              className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
              aria-label={`移除 ${pkg}`}
              onClick={() => onChange(value.filter(item => item !== pkg))}
            >
              <X className="size-3.5" />
            </button>
          </span>
        ))}
      </div>
      <div className="flex flex-wrap gap-2">
        <Input
          aria-label="应用包名"
          className="h-8 min-w-52 flex-1"
          placeholder="输入包名，例如 com.example.app"
          value={text}
          onChange={event => {
            setText(event.target.value);
            setError('');
          }}
          onKeyDown={event => {
            if (event.key === 'Enter') {
              event.preventDefault();
              if (add(text)) setText('');
            }
          }}
        />
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            if (add(text)) setText('');
          }}
        >
          <Plus />
          添加
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={() => setOpen(true)}>
          <AppWindow />
          从已安装应用选择
        </Button>
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="flex max-h-[75vh] flex-col sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>选择不计时的应用</DialogTitle>
            <DialogDescription>
              只列出这台设备上可启动的应用。找不到时可以直接输入包名。
            </DialogDescription>
          </DialogHeader>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              aria-label="搜索应用"
              className="h-9 pl-8"
              placeholder="搜索应用名称或包名"
              value={search}
              onChange={event => setSearch(event.target.value)}
            />
          </div>
          <div className="min-h-24 flex-1 divide-y divide-border/60 overflow-y-auto rounded-lg border border-border/70">
            {appsQuery.isLoading && <p className="p-3 text-xs text-muted-foreground">正在读取应用列表…</p>}
            {!appsQuery.isLoading && appsQuery.isError && (
              <p className="p-3 text-xs text-muted-foreground">
                读取应用列表失败，请确认手机与设备在同一 WiFi；也可以直接输入包名。
              </p>
            )}
            {!appsQuery.isLoading && !appsQuery.isError && choices.length === 0 && (
              <p className="p-3 text-xs text-muted-foreground">
                {apps.length === 0 ? '这台设备没有返回可启动的应用。' : '没有匹配的应用。'}
              </p>
            )}
            {choices.map(app => (
              <button
                key={app.package}
                type="button"
                className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-muted/50"
                onClick={() => {
                  if (add(app.package)) setOpen(false);
                }}
              >
                <AppWindow className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">{app.name}</span>
                  <span className="block truncate text-xs text-muted-foreground">{app.package}</span>
                </span>
                <Plus className="size-4 shrink-0 text-muted-foreground" />
              </button>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
