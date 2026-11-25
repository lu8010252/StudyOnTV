import {Check,Copy,ChevronLeft,ChevronRight,Pencil,Trash2} from 'lucide-react';
import {resolveMediaUrl} from '@/api';
import {Button} from '@/components/ui/button';
import {Badge} from '@/components/ui/badge';
import {Field as UiField,FieldDescription,FieldGroup,FieldTitle} from '@/components/ui/field';
import {Sheet,SheetContent,SheetDescription,SheetFooter,SheetHeader,SheetTitle} from '@/components/ui/sheet';
import {Toolbar,ToolbarGroup} from '@/components/ui/toolbar';
import {MathText,Stars,answerOf,labels,questionImages} from './shared';

/** 题目详情抽屉：上一题 / 下一题只在当前这一页里翻。 */
export default function QuestionDetailSheet({current,items,onNavigate,onClose,onEdit,onCopy,onRemove}:{
 current:any;
 items:any[];
 onNavigate:(id:string|null)=>void;
 onClose:()=>void;
 onEdit:(q:any,copy?:boolean)=>void;
 onCopy:(q:any)=>void;
 onRemove:(q:any)=>void;
}){
 const index=current?items.findIndex((q:any)=>q.id===current.id):-1;
 return <Sheet open={!!current} onOpenChange={open=>{if(!open)onClose();}}>
  <SheetContent className="w-full gap-0 p-0 sm:max-w-[520px]">
   {current&&<>
   <SheetHeader className="border-b px-5 py-4 pe-12">
    <SheetTitle>题目详情</SheetTitle>
    <SheetDescription className="font-mono"># {current.id.slice(0,8)}</SheetDescription>
   </SheetHeader>
   <div className="flex-1 overflow-y-auto px-5 py-4">
    <Toolbar className="mb-4 flex-wrap"><ToolbarGroup><Badge variant="secondary">{labels[current.type]||'单选'}</Badge><Stars n={current.difficulty||2}/></ToolbarGroup><ToolbarGroup className="text-xs text-muted-foreground">{current.subject&&<span>{current.subject}</span>}{current.level&&<span>· {current.level}</span>}</ToolbarGroup></Toolbar>
    <MathText text={current.q} className="mb-4 whitespace-pre-wrap text-sm font-medium leading-6"/>
    {questionImages(current).length>0&&<div className="mb-5 flex flex-wrap gap-2">{questionImages(current).map((src,i)=><img key={i} src={resolveMediaUrl(src)} alt={`题目配图 ${i+1}`} className="max-h-52 rounded-md border bg-muted/20 object-contain"/>)}</div>}
    {['single','multiple','boolean'].includes(current.type)&&<FieldGroup className="mb-5 gap-2">{(current.type==='boolean'?['正确','错误']:current.a||[]).map((opt:string,i:number)=>{const on=current.type==='multiple'?(current.correctIndices||[]).includes(i):i===current.k;const optImg=Array.isArray(current.optionImages)?current.optionImages[i]:'';return <UiField key={i} orientation="horizontal" className={'gap-2.5 rounded-md border p-2.5 text-sm '+(on?'border-primary/40 bg-primary/5':'')}><span className={'flex size-5 shrink-0 items-center justify-center rounded-full border text-xs '+(on?'border-primary bg-primary text-primary-foreground':'text-muted-foreground')}>{on?<Check className="size-3"/>:String.fromCharCode(65+i)}</span><div className="min-w-0 flex-1 space-y-1.5">{optImg&&<img src={resolveMediaUrl(optImg)} alt={`选项 ${String.fromCharCode(65+i)} 配图`} className="max-h-28 rounded border bg-muted/20 object-contain"/>}{String(opt||'').trim()&&<MathText text={opt} className="whitespace-pre-wrap"/>}</div></UiField>;})}</FieldGroup>}
    <FieldGroup className="mb-5 gap-3"><UiField><FieldTitle>{current.type==='text'?'可接受答案':current.type==='subjective'?'参考答案':'答案'}</FieldTitle><MathText text={current.type==='text'?(current.answers||[]).join('\n'):answerOf(current)} className="whitespace-pre-wrap text-sm leading-6 text-foreground"/></UiField>{current.type==='subjective'&&current.rubric&&<UiField><FieldTitle>评分标准</FieldTitle><MathText text={current.rubric} className="whitespace-pre-wrap text-sm leading-6 text-foreground"/></UiField>}{current.tip&&<UiField><FieldTitle>解析</FieldTitle><MathText text={current.tip} className="whitespace-pre-wrap text-sm leading-6 text-muted-foreground"/></UiField>}</FieldGroup>
    <FieldGroup className="gap-2"><UiField><FieldTitle>题目信息</FieldTitle><FieldDescription>题库：{current.bank||'默认题库'} · 出题 {current.stats?.presented||0} 次 · 作答 {current.stats?.answered||0} 次 · 正确率 {current.stats?.answered?Math.round(current.stats.correct/current.stats.answered*100):0}% · 最近一次：{current.stats?.lastCorrect===false?'答错':current.stats?.lastCorrect===true?'答对':'未作答'}</FieldDescription></UiField><UiField><FieldTitle>题目编号</FieldTitle><FieldDescription className="break-all font-mono">{current.id}</FieldDescription></UiField></FieldGroup>
   </div>
   <SheetFooter className="gap-0 border-t p-3">
    <Toolbar className="flex-wrap gap-2">
     <ToolbarGroup><Button size="sm" variant="outline" disabled={index<=0} onClick={()=>onNavigate(items[index-1]?.id||null)}><ChevronLeft/>上一题</Button><Button size="sm" variant="outline" disabled={index<0||index>=items.length-1} onClick={()=>onNavigate(items[index+1]?.id||null)}>下一题<ChevronRight/></Button></ToolbarGroup>
     <ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>onCopy(current)}><Copy/>复制</Button><Button size="sm" onClick={()=>onEdit(current)}><Pencil/>编辑</Button><Button size="sm" variant="ghost" className="text-destructive" onClick={()=>onRemove(current)}><Trash2/>删除</Button></ToolbarGroup>
    </Toolbar>
   </SheetFooter>
   </>}
  </SheetContent>
 </Sheet>;
}
