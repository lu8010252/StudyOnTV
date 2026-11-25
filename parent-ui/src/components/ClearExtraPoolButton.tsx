import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { LoaderCircle, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

export function ClearExtraPoolButton({
  sessionId,
  count,
  size = 'sm',
  variant = 'outline',
  className,
  onCleared,
}: {
  sessionId: number;
  count: number;
  size?: 'sm' | 'default' | 'lg';
  variant?: 'outline' | 'destructive' | 'ghost' | 'secondary';
  className?: string;
  onCleared?: () => void;
}) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);

  const clear = useMutation({
    mutationFn: () => api('/api/extra-pool/clear', 'POST', {}),
    onSuccess: (res: any) => {
      if (res?.learning) {
        client.setQueryData(['state', sessionId], (old: any) =>
          old ? { ...old, learning: res.learning } : old,
        );
      }
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      client.invalidateQueries({ queryKey: ['wrong-questions', sessionId] });
      setOpen(false);
      const cleared = Number(res?.cleared ?? count) || 0;
      toast.success(cleared > 0 ? `已清空加练池（移除 ${cleared} 题）` : '加练池已清空');
      onCleared?.();
    },
    onError: (error: any) => toast.error(error?.message || '清空加练池失败'),
  });

  if (count <= 0) {
    return (
      <Button
        type="button"
        size={size}
        variant={variant}
        disabled
        className={className}
      >
        <Trash2 />
        加练池已空
      </Button>
    );
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button
          type="button"
          size={size}
          variant={variant}
          disabled={clear.isPending}
          className={className}
        >
          {clear.isPending ? <LoaderCircle className="animate-spin" /> : <Trash2 />}
          清空加练池
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>确认清空加练池？</DialogTitle>
          <DialogDescription>
            当前加练池共有 {count} 道待加练题。清空后将归零待加练题数与加练队列，不会删除原题库或错题记录。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={clear.isPending}
            onClick={() => setOpen(false)}
          >
            取消
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={clear.isPending}
            onClick={() => clear.mutate()}
          >
            {clear.isPending ? <LoaderCircle className="animate-spin" /> : null}
            确认清空
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
