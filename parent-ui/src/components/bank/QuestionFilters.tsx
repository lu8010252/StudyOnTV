import { useState, type ReactNode } from 'react';
import { Search, SlidersHorizontal, X } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { Toolbar } from '@/components/ui/toolbar';
import { FilterSheet } from './FilterSheet';
import type { Filter } from './filters';

export type BankMeta = {
  banks: string[];
  subjects: string[];
  levels: string[];
  paused: number;
  wrong: number;
  total: number;
};

/** 题库工具栏：搜索 + 筛选（侧栏）+ 右侧的主操作。条件变化交给上层重新分页请求，这里只管输入。 */
export default function QuestionFilters({
  filter,
  onChange,
  onReset,
  meta,
  activeCount,
  actions,
}: {
  filter: Filter;
  onChange: (key: keyof Filter, value: string | boolean) => void;
  onReset: () => void;
  meta: BankMeta;
  activeCount: number;
  actions?: ReactNode;
}) {
  const [filtersOpen, setFiltersOpen] = useState(false);
  return (
    <>
      <Toolbar aria-label="题库工具栏" className="flex-wrap items-center justify-between gap-2 px-0">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <InputGroup className="h-8 max-w-xs min-w-0 flex-1">
            <InputGroupAddon>
              <Search />
            </InputGroupAddon>
            <InputGroupInput
              aria-label="搜索题目"
              placeholder="搜索题干、答案或解析"
              value={filter.search}
              onChange={e => onChange('search', e.target.value)}
            />
            {filter.search && (
              <InputGroupAddon align="inline-end">
                <InputGroupButton size="icon-xs" aria-label="清空搜索" onClick={() => onChange('search', '')}>
                  <X />
                </InputGroupButton>
              </InputGroupAddon>
            )}
          </InputGroup>

          <Button
            type="button"
            variant={activeCount > 0 ? 'secondary' : 'outline'}
            size="sm"
            aria-label="筛选"
            onClick={() => setFiltersOpen(true)}
          >
            <SlidersHorizontal />
            筛选
            {activeCount > 0 && (
              <Badge variant="secondary" className="ml-1 size-4 justify-center p-0 text-[10px]">
                {activeCount}
              </Badge>
            )}
          </Button>

          {activeCount > 0 && (
            <Button type="button" variant="ghost" size="sm" onClick={onReset}>
              清除
            </Button>
          )}
        </div>

        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </Toolbar>

      <FilterSheet
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        banks={meta.banks}
        subjects={meta.subjects}
        levels={meta.levels}
        filters={{
          bank: filter.bank,
          type: filter.type,
          star: filter.star,
          subject: filter.subject,
          level: filter.level,
          wrong: filter.wrong,
          sort: filter.sort,
          showAll: filter.showAll,
        }}
        onChange={(key, value) => onChange(key as keyof Filter, value)}
        onReset={onReset}
      />
    </>
  );
}
