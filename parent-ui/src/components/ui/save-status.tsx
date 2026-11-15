import { useEffect, useState } from 'react'
import { AlertCircle, Check, LoaderCircle } from 'lucide-react'
import { cn } from '@/lib/utils'

export type SaveState = 'idle' | 'saving' | 'saved' | 'error' | 'pending'

/** 轻量保存状态：12px，已保存用绿色 ✓，2 秒后淡出。 */
export function SaveStatus({
  state,
  className,
}: {
  state: SaveState
  className?: string
}) {
  const [hidden, setHidden] = useState(false)

  useEffect(() => {
    setHidden(false)
    if (state !== 'saved') return
    const timer = window.setTimeout(() => setHidden(true), 2000)
    return () => window.clearTimeout(timer)
  }, [state])

  if (state === 'idle' || (state === 'saved' && hidden)) return null

  let Icon: any = null
  let label = ''
  let color = 'text-muted-foreground'
  let spin = false

  if (state === 'saving') {
    Icon = LoaderCircle
    label = '正在保存…'
    spin = true
  } else if (state === 'saved') {
    Icon = Check
    label = '已保存'
    color = 'text-emerald-600 dark:text-emerald-400'
  } else if (state === 'error') {
    Icon = AlertCircle
    label = '保存失败'
    color = 'text-destructive'
  } else {
    label = '等待保存'
  }

  return (
    <span
      role="status"
      aria-live="polite"
      className={cn('inline-flex items-center gap-1 text-xs', color, className)}
    >
      {Icon && <Icon className={cn('size-3.5', spin && 'animate-spin')} />}
      {label}
    </span>
  )
}
