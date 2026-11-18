import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bot, Settings2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
} from '@/components/ui/card';
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemTitle,
} from '@/components/ui/item';
import { SectionHeading } from '@/components/ui/section-heading';
import { api } from './api';
import { builtinProviders } from './ai-providers';
import AiConfig from './AiConfig';

export default function AiSettings({ sessionId }: { sessionId: number }) {
  const [open, setOpen] = useState(false);
  const settings = useQuery({ queryKey: ['grading', sessionId], queryFn: () => api('/api/grading') });
  const data = settings.data;
  const ready = !!(
    data?.endpoint &&
    data?.model &&
    (data?.hasKey || data?.provider === 'ollama' || data?.provider === 'custom')
  );

  return (
    <>
      <section className="space-y-2">
        <SectionHeading
          icon={Bot}
          title="模型 API"
          description="批改与出题共用"
          action={<Badge variant={ready ? 'secondary' : 'outline'}>{ready ? '已配置' : '未配置'}</Badge>}
        />
      <Card className="gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="px-4 sm:px-5">
          <ItemGroup className="divide-y divide-border/60">
            <Item size="sm" className="rounded-none px-0 py-4">
              <ItemContent>
                <ItemTitle>当前状态</ItemTitle>
                <ItemDescription className="text-xs">
                  {data?.hasKey
                    ? 'API Key 已保存在本机。'
                    : data?.provider === 'ollama'
                      ? '本地局域网模型（无需 API Key）。'
                      : '还没有保存 API Key。'}
                </ItemDescription>
              </ItemContent>
            </Item>
            <Item size="sm" className="rounded-none px-0 py-4">
              <ItemContent>
                <ItemTitle>服务商 / 模型</ItemTitle>
                <ItemDescription className="line-clamp-none text-xs">
                  {data?.model
                    ? `${builtinProviders.find(p => p.id === data.provider)?.name || data.provider || '自定义'} · ${data.model}${data.vision ? ' · 支持手写图像' : ''}`
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
      </section>
      {open && <AiConfig sessionId={sessionId} onClose={() => setOpen(false)} />}
    </>
  );
}
