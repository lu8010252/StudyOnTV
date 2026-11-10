import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bot, Settings2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemTitle,
} from '@/components/ui/item';
import { api } from './api';
import { builtinProviders } from './ai-providers';
import AiConfig from './AiConfig';

export default function AiSettings({ sessionId }: { sessionId: number }) {
  const [open, setOpen] = useState(false);
  const settings = useQuery({ queryKey: ['grading', sessionId], queryFn: () => api('/api/grading') });
  const data = settings.data;
  const ready = !!(data?.endpoint && data?.model && (data?.hasKey || data?.provider === 'ollama' || data?.mode === 'ai'));

  return (
    <>
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
          <CardTitle className="flex items-center gap-2 text-base">
            <span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground">
              <Bot className="size-4" />
            </span>
            模型 API
          </CardTitle>
          <CardDescription className="text-xs">自动批改和自动出题共用。</CardDescription>
          <CardAction>
            <Badge variant={ready ? 'secondary' : 'outline'}>{ready ? '已配置' : '未配置'}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t p-0">
          <ItemGroup className="divide-y divide-border/60">
            <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
              <ItemContent>
                <ItemTitle>当前状态</ItemTitle>
                <ItemDescription className="text-xs">
                  {data?.hasKey ? 'API Key 已保存在本机。' : '还没有保存 API Key。'}
                </ItemDescription>
              </ItemContent>
            </Item>
            <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
              <ItemContent>
                <ItemTitle>服务商 / 模型</ItemTitle>
                <ItemDescription className="line-clamp-none text-xs">
                  {data?.model
                    ? `${builtinProviders.find(p => p.id === data.provider)?.name || data.provider || '自定义'} · ${data.model}`
                    : 'DeepSeek、通义千问、智谱、Kimi、豆包或 Ollama'}
                </ItemDescription>
              </ItemContent>
              <ItemActions>
                <Button variant="outline" onClick={() => setOpen(true)}>
                  <Settings2 />
                  配置
                </Button>
              </ItemActions>
            </Item>
          </ItemGroup>
        </CardContent>
      </Card>
      {open && <AiConfig sessionId={sessionId} onClose={() => setOpen(false)} />}
    </>
  );
}
