import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** 卡片外的分区标题：12px 次要色，可带图标，右侧放操作或轻量状态。 */
export function SectionHeading({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: any
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-wrap items-center gap-x-2 gap-y-1', className)}>
      <div className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
        {Icon && <Icon className="size-4 shrink-0" />}
        <span className="font-medium">{title}</span>
        {description && <span className="min-w-0">{description}</span>}
      </div>
      {action && <div className="ms-auto flex items-center gap-2">{action}</div>}
    </div>
  )
}
