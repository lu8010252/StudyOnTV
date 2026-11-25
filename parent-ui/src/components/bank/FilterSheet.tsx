import { useId, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Field, FieldLabel } from '@/components/ui/field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';

export type BankFilterState = {
  bank: string;
  type: string;
  star: string;
  subject: string;
  level: string;
  wrong: string;
  sort: string;
  showAll: boolean;
};

interface FilterSheetProps {
  open: boolean;
  onClose: () => void;
  banks: string[];
  subjects: string[];
  levels: string[];
  filters: BankFilterState;
  onChange: (key: keyof BankFilterState, value: string | boolean) => void;
  onReset: () => void;
}

function FilterSelect({
  label,
  value,
  onChange,
  children,
  className,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
  className?: string;
}) {
  const id = useId();

  return (
    <Field className={cn('min-w-0 gap-1.5', className)}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger
          id={id}
          className="w-full min-w-0 [&>[data-slot=select-value]]:block [&>[data-slot=select-value]]:truncate"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent className="w-(--radix-select-trigger-width) min-w-0 wrap-anywhere">
          {children}
        </SelectContent>
      </Select>
    </Field>
  );
}

export function FilterSheet({
  open,
  onClose,
  banks,
  subjects,
  levels,
  filters,
  onChange,
  onReset,
}: FilterSheetProps) {
  const showAllId = useId();

  return (
    <Sheet open={open} onOpenChange={onClose}>
      <SheetContent side="right" data-question-filter-sheet className="w-full gap-0">
        <SheetHeader className="shrink-0 border-b px-5 py-4 pe-12">
          <SheetTitle>筛选题目</SheetTitle>
          <SheetDescription>选择后立即更新题目列表</SheetDescription>
        </SheetHeader>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4">
          <div className="grid grid-cols-1 gap-x-3 gap-y-4 min-[360px]:grid-cols-2">
            <FilterSelect label="题库" value={filters.bank} onChange={v => onChange('bank', v)} className="col-span-full">
              <SelectItem value="all">全部题库</SelectItem>
              {banks.map(bank => <SelectItem key={bank} value={bank}>{bank}</SelectItem>)}
            </FilterSelect>

            <FilterSelect label="题型" value={filters.type} onChange={v => onChange('type', v)}>
              <SelectItem value="all">全部题型</SelectItem>
              <SelectItem value="single">单选</SelectItem>
              <SelectItem value="multiple">多选</SelectItem>
              <SelectItem value="boolean">判断</SelectItem>
              <SelectItem value="text">填空</SelectItem>
              <SelectItem value="subjective">主观题</SelectItem>
            </FilterSelect>

            <FilterSelect label="难度" value={filters.star} onChange={v => onChange('star', v)}>
              <SelectItem value="all">全部难度</SelectItem>
              {[1, 2, 3, 4, 5].map(n => <SelectItem key={n} value={String(n)}>{'★'.repeat(n)}</SelectItem>)}
            </FilterSelect>

            {subjects.length > 0 && (
              <FilterSelect label="科目" value={filters.subject} onChange={v => onChange('subject', v)}>
                <SelectItem value="all">全部科目</SelectItem>
                {subjects.map(subject => <SelectItem key={subject} value={subject}>{subject}</SelectItem>)}
              </FilterSelect>
            )}

            {levels.length > 0 && (
              <FilterSelect label="年级" value={filters.level} onChange={v => onChange('level', v)}>
                <SelectItem value="all">全部年级</SelectItem>
                {levels.map(level => <SelectItem key={level} value={level}>{level}</SelectItem>)}
              </FilterSelect>
            )}

            <FilterSelect label="错题" value={filters.wrong} onChange={v => onChange('wrong', v)}>
              <SelectItem value="all">全部题目</SelectItem>
              <SelectItem value="wrong">只看错题</SelectItem>
            </FilterSelect>

            <FilterSelect label="排序" value={filters.sort} onChange={v => onChange('sort', v)}>
              <SelectItem value="default">默认</SelectItem>
              <SelectItem value="diff">按难度</SelectItem>
              <SelectItem value="type">按题型</SelectItem>
            </FilterSelect>

            <Field orientation="horizontal" className="col-span-full border-t pt-4">
              <FieldLabel htmlFor={showAllId}>显示已暂停题目</FieldLabel>
              <Switch id={showAllId} checked={filters.showAll} onCheckedChange={v => onChange('showAll', v)} />
            </Field>
          </div>
        </div>

        <SheetFooter className="shrink-0 flex-row border-t px-5 py-3">
          <Button variant="outline" className="flex-1" onClick={onReset}>
            清除筛选
          </Button>
          <Button className="flex-1" onClick={onClose}>
            完成
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
