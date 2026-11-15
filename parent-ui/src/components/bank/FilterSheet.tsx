import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';

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

/** 题库筛选侧栏：筛选条件集中在一处，不再占用列表上方一行行铺开。 */
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
  return (
    <Sheet open={open} onOpenChange={onClose}>
      <SheetContent side="right" className="w-80">
        <SheetHeader>
          <SheetTitle>筛选题目</SheetTitle>
          <SheetDescription>按条件过滤题库内容</SheetDescription>
        </SheetHeader>

        <div className="mt-6 flex-1 space-y-5 overflow-y-auto">
          <div className="space-y-1.5">
            <label className="text-sm font-medium">题库</label>
            <Select value={filters.bank} onValueChange={v => onChange('bank', v)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部题库</SelectItem>
                {banks.map(b => (
                  <SelectItem key={b} value={b}>
                    {b}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">题型</label>
            <Select value={filters.type} onValueChange={v => onChange('type', v)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部题型</SelectItem>
                <SelectItem value="single">单选</SelectItem>
                <SelectItem value="multiple">多选</SelectItem>
                <SelectItem value="boolean">判断</SelectItem>
                <SelectItem value="text">填空</SelectItem>
                <SelectItem value="subjective">主观题</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">难度</label>
            <Select value={filters.star} onValueChange={v => onChange('star', v)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部难度</SelectItem>
                {[1, 2, 3, 4, 5].map(n => (
                  <SelectItem key={n} value={String(n)}>
                    {'★'.repeat(n)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {subjects.length > 0 && (
            <div className="space-y-1.5">
              <label className="text-sm font-medium">科目</label>
              <Select value={filters.subject} onValueChange={v => onChange('subject', v)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部科目</SelectItem>
                  {subjects.map(s => (
                    <SelectItem key={s} value={s}>
                      {s}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          {levels.length > 0 && (
            <div className="space-y-1.5">
              <label className="text-sm font-medium">年级</label>
              <Select value={filters.level} onValueChange={v => onChange('level', v)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部年级</SelectItem>
                  {levels.map(l => (
                    <SelectItem key={l} value={l}>
                      {l}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="space-y-1.5">
            <label className="text-sm font-medium">错题</label>
            <Select value={filters.wrong} onValueChange={v => onChange('wrong', v)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部题目</SelectItem>
                <SelectItem value="wrong">只看错题</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">排序</label>
            <Select value={filters.sort} onValueChange={v => onChange('sort', v)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="default">默认</SelectItem>
                <SelectItem value="diff">按难度</SelectItem>
                <SelectItem value="type">按题型</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="flex items-center justify-between">
            <label className="text-sm font-medium">显示已暂停题目</label>
            <Switch checked={filters.showAll} onCheckedChange={v => onChange('showAll', v)} />
          </div>
        </div>

        <div className="mt-6 flex gap-2">
          <Button variant="outline" className="flex-1" onClick={onReset}>
            清除筛选
          </Button>
          <Button className="flex-1" onClick={onClose}>
            完成
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
}
