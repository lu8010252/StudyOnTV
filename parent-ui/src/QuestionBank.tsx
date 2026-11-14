import {cloneElement,isValidElement,useEffect,useId,useMemo,useRef,useState} from 'react';
import {copyText} from './lib/clipboard';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Plus,Upload,Download,FileJson,Search,X,Pencil,Trash2,Check,Copy,Sparkles,ChevronLeft,ChevronRight,RefreshCw,ClipboardCopy,ExternalLink,SlidersHorizontal,MoreHorizontal} from 'lucide-react';
import {toast} from 'sonner';
import {Alert,AlertDescription} from '@/components/ui/alert';
import {Button} from '@/components/ui/button';
import {Card,CardAction,CardContent,CardDescription,CardHeader,CardTitle} from '@/components/ui/card';
import {Checkbox} from '@/components/ui/checkbox';
import {Input} from '@/components/ui/input';
import {InputGroup,InputGroupAddon,InputGroupButton,InputGroupInput} from '@/components/ui/input-group';
import {Field as UiField,FieldDescription,FieldError,FieldGroup,FieldLabel,FieldTitle} from '@/components/ui/field';
import {Textarea} from '@/components/ui/textarea';
import {Badge} from '@/components/ui/badge';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription,DialogFooter} from '@/components/ui/dialog';
import {DropdownMenu,DropdownMenuContent,DropdownMenuItem,DropdownMenuLabel,DropdownMenuSeparator,DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {Sheet,SheetContent,SheetDescription,SheetFooter,SheetHeader,SheetTitle} from '@/components/ui/sheet';
import {Switch} from '@/components/ui/switch';
import {Table,TableBody,TableCell,TableHead,TableHeader,TableRow} from '@/components/ui/table';
import {Tabs,TabsList,TabsTrigger} from '@/components/ui/tabs';
import {Toolbar,ToolbarGroup,ToolbarSeparator} from '@/components/ui/toolbar';
import {api} from './api';
import BankAutomationPanel from './BankAutomationPanel';
import {extractDocument,parseQuestionText} from './document-import';
import {normalizeBank,parseBankJson} from './question-import';

export function MathText({text,className=''}:{text:string,className?:string}){const ref=useRef<HTMLDivElement>(null);useEffect(()=>{if(!ref.current)return;ref.current.textContent=text||'';(window as any).SOTV_MATH?.(ref.current);},[text]);return <div ref={ref} className={className}/>;}
export function answerOf(q:any){if(q.type==='subjective')return q.modelAnswer||'由家长批改';if(q.type==='text')return(q.answers||[]).join(' / ');const a=q.type==='boolean'?['正确','错误']:(q.a||[]);return q.type==='multiple'?(q.correctIndices||[]).map((i:number)=>a[i]).join('、'):a[q.k]||'';}
const labels:{[key:string]:string}={single:'单选',multiple:'多选',boolean:'判断',text:'填空',subjective:'主观题'};
const dot:{[key:string]:string}={single:'bg-teal-600 dark:bg-teal-400',multiple:'bg-blue-600 dark:bg-blue-400',boolean:'bg-amber-600 dark:bg-amber-400',text:'bg-violet-600 dark:bg-violet-400',subjective:'bg-rose-600 dark:bg-rose-400'};
const blank={id:'',type:'single',q:'',opts:['','','',''],correct:[0],accepted:'',difficulty:2,tip:'',modelAnswer:'',rubric:'',subject:'',level:'',bank:'默认题库',image:'',audio:'',language:'zh-CN'};
export function download(data:any,name:string){const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),3000);}
const PAGE=25;

function Stars({n}:{n:number}){return <span className="whitespace-nowrap text-[11px] tracking-wide" title={n+' 星'}><span className="text-amber-600 dark:text-amber-400">{'★'.repeat(n)}</span><span className="text-muted-foreground/40">{'☆'.repeat(5-n)}</span></span>;}
function FilterSelect({label,value,onChange,options}:{label:string,value:string,onChange:(value:string)=>void,options:[string,string][]}){
 return <Select value={value} onValueChange={onChange}><SelectTrigger aria-label={label} className="w-full min-w-0"><SelectValue>{options.find(([optionValue])=>optionValue===value)?.[1]||'请选择'}</SelectValue></SelectTrigger><SelectContent>{options.map(([optionValue,optionLabel])=><SelectItem key={optionValue} value={optionValue}>{optionLabel}</SelectItem>)}</SelectContent></Select>;
}
function FilterField({label,children,className}:{label:string,children:any,className?:string}){
 return <UiField className={className}><FieldLabel className="text-xs font-medium text-muted-foreground">{label}</FieldLabel>{children}</UiField>;
}
function ChoiceSelect({value,onChange,options,label,id}:{value:string,onChange:(value:string)=>void,options:[string,string][],label:string,id?:string}){
 return <Select value={value} onValueChange={onChange}><SelectTrigger id={id} aria-label={label} className="w-full"><SelectValue>{options.find(([optionValue])=>optionValue===value)?.[1]||'请选择'}</SelectValue></SelectTrigger><SelectContent>{options.map(([optionValue,optionLabel])=><SelectItem key={optionValue} value={optionValue}>{optionLabel}</SelectItem>)}</SelectContent></Select>;
}

export default function QuestionBank({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['bank',sessionId],queryFn:()=>api('/api/questions?limit=5000&enabledOnly=false')});
 const questions=query.data?.items||[];
 const subjects:any[]=[...new Set(questions.map((q:any)=>q.subject).filter(Boolean))].sort();
 const levels:any[]=[...new Set(questions.map((q:any)=>q.level).filter(Boolean))].sort();
 const banks:any[]=[...new Set(questions.map((q:any)=>q.bank||'默认题库'))].sort();
 const [bankFilter,setBankFilter]=useState('all'),[importBank,setImportBank]=useState('默认题库'),[documentBusy,setDocumentBusy]=useState(false);
 const [search,setSearch]=useState(''),[typeFilter,setTypeFilter]=useState('all'),[starFilter,setStarFilter]=useState('all'),[subjectFilter,setSubjectFilter]=useState('all'),[levelFilter,setLevelFilter]=useState('all'),[wrongFilter,setWrongFilter]=useState('all'),[sort,setSort]=useState('default');
 const [page,setPage]=useState(0),[selected,setSelected]=useState<string[]>([]),[drawer,setDrawer]=useState<string|null>(null);
 const [filtersOpen,setFiltersOpen]=useState(false);
 const [showAll,setShowAll]=useState(false);
 const [form,setForm]=useState<any>(null),[importing,setImporting]=useState(false),[text,setText]=useState(''),[fileName,setFileName]=useState(''),[remove,setRemove]=useState<any>(null),[importSubject,setImportSubject]=useState(''),[importLevel,setImportLevel]=useState('');
 const file=useRef<HTMLInputElement>(null);
 // 导入成功后补一句「跳过了哪几题」，所以跳过原因要跨一次请求保留
 const pendingIssues=useRef<{index:number,reason:string}[]>([]);
 const [aiOpen,setAiOpen]=useState(false),[aiTab,setAiTab]=useState<'api'|'agent'>('agent');
 const [aiForm,setAiForm]=useState({topic:'',types:{single:true,multiple:true,boolean:true,text:true,subjective:false} as any,count:10,difficulty:'mixed',extra:'',withTip:true,skipDuplicates:true});
 const [aiLoading,setAiLoading]=useState(false),[aiItems,setAiItems]=useState<any[]>([]),[aiError,setAiError]=useState('');
 const grading=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const configured=!!(grading.data?.endpoint&&grading.data?.model);
 const aiProvider=(grading.data?.providers||[]).find((p:any)=>p.id===grading.data?.provider)?.name||'AI';

 const pausedCount=questions.filter((q:any)=>q.enabled===false).length;
 // 错题库（#136）：最近一次答错的题
 const wrongCount=questions.filter((q:any)=>q.stats?.lastCorrect===false).length;
 const filtered=useMemo(()=>{const k=search.trim().toLowerCase();const list=questions.filter((q:any)=>(showAll||q.enabled!==false)&&(bankFilter==='all'||(q.bank||'默认题库')===bankFilter)&&(typeFilter==='all'||(q.type||'single')===typeFilter)&&(starFilter==='all'||String(q.difficulty||2)===starFilter)&&(subjectFilter==='all'||(q.subject||'')===subjectFilter)&&(levelFilter==='all'||(q.level||'')===levelFilter)&&(wrongFilter==='all'||q.stats?.lastCorrect===false)&&(!k||(q.q+' '+answerOf(q)+' '+q.tip+' '+(q.subject||'')+' '+(q.level||'')).toLowerCase().includes(k)));
  if(sort==='diff')return [...list].sort((a:any,b:any)=>(b.difficulty||2)-(a.difficulty||2));
  if(sort==='type')return [...list].sort((a:any,b:any)=>String(a.type).localeCompare(String(b.type))||(b.difficulty||2)-(a.difficulty||2));
  return list;
 },[questions,search,typeFilter,starFilter,subjectFilter,levelFilter,wrongFilter,sort,showAll,bankFilter]);
 const shown=filtered.slice(page*PAGE,page*PAGE+PAGE);
 const current=drawer?questions.find((q:any)=>q.id===drawer):null;
 const drawerIndex=current?filtered.findIndex((q:any)=>q.id===current.id):-1;

 useEffect(()=>{if(page*PAGE>=filtered.length)setPage(0);},[filtered.length,page]);
 useEffect(()=>{if(drawer&&!questions.some((q:any)=>q.id===drawer))setDrawer(null);},[questions,drawer]);

 const deleteBank=useMutation({mutationFn:(bank:string)=>api('/api/questions/bank','DELETE',{bank,confirm:true}),onSuccess:(r:any)=>{setSelected([]);setDrawer(null);setBankFilter('all');client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已删除题库，共 ${r.deleted} 题`);},onError:(e:any)=>toast.error(e.message)});
 const clearBank=useMutation({mutationFn:()=>api('/api/questions/clear','POST',{confirm:true}),onSuccess:(r:any)=>{setSelected([]);setDrawer(null);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已清空题库，共删除 ${r.deleted} 题`);},onError:(e:any)=>toast.error(e.message)});
 const bulkDelete=useMutation({mutationFn:(ids:string[])=>api('/api/questions/delete','POST',{ids}),onSuccess:(r:any)=>{setSelected([]);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已删除 ${r.deleted} 题`);},onError:(e:any)=>toast.error(e.message)});
 const bulkPause=useMutation({mutationFn:({ids,enabled}:{ids:string[],enabled:boolean})=>api('/api/questions/pause','POST',{ids,enabled}),onSuccess:(r:any,{enabled})=>{setSelected([]);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(enabled?`已恢复 ${r.updated} 题`:`已暂停 ${r.updated} 题`);},onError:(e:any)=>toast.error(e.message)});
 const save=useMutation({mutationFn:(body:any)=>api('/api/questions','POST',body),onSuccess:()=>{client.invalidateQueries({queryKey:['bank',sessionId]});setForm(null);toast.success('题目已保存');},onError:(e:any)=>toast.error(e.message)});
 const importingMutation=useMutation({mutationFn:(items:any[])=>api('/api/questions','POST',{items}),onSuccess:(r:any)=>{client.invalidateQueries({queryKey:['bank',sessionId]});setImporting(false);toast.success(`已导入 ${r.added} 题`);const skipped=pendingIssues.current;pendingIssues.current=[];if(skipped.length){const shown=skipped.slice(0,3).map(i=>`第 ${i.index} 题：${i.reason}`).join('；');toast.warning(`跳过 ${skipped.length} 题（${shown}${skipped.length>3?' 等':''}）`,{duration:8000});}},onError:(e:any)=>{pendingIssues.current=[];toast.error(e.message);}});
 const deleting=useMutation({mutationFn:(id:string)=>api('/api/questions/'+encodeURIComponent(id),'DELETE'),onSuccess:()=>{setRemove(null);setDrawer(null);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success('已删除');},onError:(e:any)=>toast.error(e.message)});
 const saveGenerated=useMutation({mutationFn:(items:any[])=>api('/api/questions','POST',{items}),onSuccess:(r:any)=>{client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已保存 ${r.added} 题到题库`);setAiItems([]);setAiOpen(false);},onError:(e:any)=>toast.error(e.message)});

 const toggle=(id:string)=>setSelected(list=>list.includes(id)?list.filter(x=>x!==id):[...list,id]);
 function edit(q?:any,copy=false){setDrawer(null);setForm(q?{id:copy?'':q.id,type:q.type||'single',q:q.q,opts:q.type==='boolean'?['正确','错误']:[...(q.a||[])],correct:q.type==='multiple'?[...(q.correctIndices||[])]:[q.k||0],accepted:(q.answers||[]).join('\n'),difficulty:q.difficulty||2,tip:q.tip||'',modelAnswer:q.modelAnswer||'',rubric:q.rubric||'',subject:q.subject||'',level:q.level||'',bank:q.bank||'默认题库',image:q.image||'',audio:q.audio||'',language:q.language??'zh-CN'}:{...blank});}
 function field(key:string,value:any){setForm((f:any)=>({...f,[key]:value}));}
 function submit(){const f=form;const q:any={q:f.q.trim(),type:f.type,difficulty:Number(f.difficulty),tip:f.tip,subject:f.subject.trim(),level:f.level.trim(),bank:f.bank.trim()||'默认题库',image:f.image.trim(),audio:f.audio.trim(),language:f.language};if(!q.q)return toast.error('请填写题干');if(f.id)q.id=f.id;if(f.type==='subjective'){q.modelAnswer=f.modelAnswer;q.rubric=f.rubric;}else if(f.type==='text'){q.answers=f.accepted.split('\n').map((s:string)=>s.trim()).filter(Boolean);if(!q.answers.length)return toast.error('请填写至少一个可接受答案');}else{q.a=f.type==='boolean'?['正确','错误']:f.opts.map((o:string)=>o.trim());if(f.type!=='boolean'&&q.a.some((o:string)=>!o))return toast.error('选项不能为空，多余的选项请删除');if(q.a.length<2)return toast.error('至少需要两个选项');const correct=f.correct.filter((i:number)=>i<q.a.length);if(!correct.length)return toast.error('请点击选项左侧的圆圈标记正确答案');if(f.type==='multiple')q.correctIndices=[...correct].sort();else q.k=correct[0];}save.mutate(q);}
 async function pick(e:any){const f=e.target.files?.[0];if(!f)return;if(f.size>20000000)return toast.error('文件超过 20 MB，请拆分导入');setDocumentBusy(true);setFileName(f.name);try{setText(await extractDocument(f));}catch(error:any){toast.error(error.message||'文档读取失败');}finally{setDocumentBusy(false);}}
 function importNow(){try{const raw=text.replace(/^\uFEFF/,'').trim();if(!raw)return toast.error('请先粘贴题库 JSON 或选择文件');if(!raw.startsWith('{')&&!raw.startsWith('[')){const items=parseQuestionText(raw);setText(JSON.stringify({items},null,2));toast.message(`已解析 ${items.length} 题，请检查预览 JSON，确认后再次点击导入`);return;}const result=normalizeBank(parseBankJson(raw));if(!result.items.length){const first=result.issues[0];return toast.error(first?`没有可导入的题目：第 ${first.index} 题：${first.reason}`:'题库不能为空');}pendingIssues.current=result.issues;const bank=importBank.trim()||'默认题库',subject=importSubject.trim(),level=importLevel.trim();importingMutation.mutate(result.items.map((q:any)=>({...q,bank:q.bank||bank,subject:q.subject||subject,level:q.level||level})));}catch(error:any){toast.error(error.message||'题库格式有误');}}
 function openAi(){setAiTab('agent');setAiError('');setAiOpen(true);}
 async function generate(){if(!aiForm.topic.trim())return toast.error('请填写出题范围');if(!Object.values(aiForm.types).some(Boolean))return toast.error('至少选择一种题型');setAiLoading(true);setAiError('');try{const result=await api('/api/questions/generate','POST',{topic:aiForm.topic,types:Object.keys(aiForm.types).filter(k=>aiForm.types[k]),count:aiForm.count,difficulty:aiForm.difficulty,extra:aiForm.extra,withTip:aiForm.withTip,skipDuplicates:aiForm.skipDuplicates},90000);setAiItems((result.items||[]).map((q:any)=>({...q,include:true})));const skipped=result.duplicates?`，跳过 ${result.duplicates} 道重复题`:'';toast.success(`生成 ${result.items.length} 题${skipped}`);}catch(e:any){setAiError(e.message);}finally{setAiLoading(false);}}
 const copy=async(value:string)=>{const r=await copyText(value);if(r==='ok')toast.success('已复制');else if(r==='empty')toast.error('没有可复制的内容');else toast.error('复制失败，请手动选中文字复制');};
 const base=location.origin;
 const agentPrompt=`请给「题了个题」出 ${aiForm.count} 道题，范围：${aiForm.topic||'（填写出题范围）'}。\n只返回 JSON：{"items":[{"type":"single","q":"题干","a":["选项A","选项B"],"k":1,"difficulty":2,"tip":"解析"}]}\ntype 可用 single / multiple（correctIndices）/ boolean（a 固定 ["正确","错误"]，k 0 或 1）/ text（answers）/ subjective（modelAnswer、rubric）。\n难度 1-5，题干不要 HTML。完整字段和示例：https://github.com/chadyi/StudyOnTV/blob/main/docs/question-format.md`;
 const jsonPrompt=agentPrompt+`\n\n写好后再调用：POST ${base}/api/questions\n请求头：Content-Type: application/json、Authorization: Bearer <管理令牌>\n请求体：{"items":[题目数组]}`;
 const aiSelected=aiItems.filter((q:any)=>q.include).length;
 const extraFilterCount=[subjectFilter,levelFilter,wrongFilter].filter(value=>value!=='all').length+(sort!=='default'?1:0);
 const hasFilters=!!(search||bankFilter!=='all'||typeFilter!=='all'||starFilter!=='all'||subjectFilter!=='all'||levelFilter!=='all'||wrongFilter!=='all'||showAll||sort!=='default');
 function resetFilters(){setSearch('');setBankFilter('all');setTypeFilter('all');setStarFilter('all');setSubjectFilter('all');setLevelFilter('all');setWrongFilter('all');setShowAll(false);setSort('default');setPage(0);}

 return <>
  <Toolbar aria-label="题库页工具栏" className="flex-col items-stretch gap-3 sm:flex-row sm:items-center">
   <ToolbarGroup className="min-w-0 items-start">
    <div className="min-w-0">
     <div className="flex flex-wrap items-center gap-2">
      <Badge variant="secondary">{questions.length} 道题</Badge>
      <Badge variant="outline">{banks.length} 个题库</Badge>
      {pausedCount>0&&<Badge variant="outline">{pausedCount} 道已暂停</Badge>}
     </div>
    </div>
   </ToolbarGroup>
   <ToolbarGroup className="flex-wrap sm:ms-auto">
    <Button size="sm" onClick={()=>edit()}><Plus/>新增题目</Button>
    <Button size="sm" variant="secondary" onClick={openAi}><Sparkles/>AI 出题</Button>
    <Button size="sm" variant="outline" onClick={()=>{setText('');setFileName('');setImporting(true);}}><Upload/>导入</Button>
    <DropdownMenu>
     <DropdownMenuTrigger asChild><Button variant="outline" size="icon" aria-label="更多题库操作"><MoreHorizontal/></Button></DropdownMenuTrigger>
     <DropdownMenuContent align="end" className="min-w-44">
      <DropdownMenuLabel>题库文件</DropdownMenuLabel>
      <DropdownMenuItem onSelect={()=>download({version:2,items:questions},'studyontv-bank.json')}><Download/>导出 JSON</DropdownMenuItem>
      <DropdownMenuSeparator/>
      <DropdownMenuItem variant="destructive" disabled={query.isLoading||clearBank.isPending||!questions.length} onSelect={()=>{if(window.confirm('确定清空全部题库（包括已暂停的题目）？此操作无法撤销，建议先导出备份。'))clearBank.mutate();}}><Trash2/>清空题库</DropdownMenuItem>
     </DropdownMenuContent>
    </DropdownMenu>
   </ToolbarGroup>
  </Toolbar>

  <Card className="mt-4 gap-0 py-0 shadow-none">
   <CardHeader className="border-b px-4 py-3 sm:px-5">
    <CardTitle className="text-sm">查找与筛选</CardTitle>
    <CardDescription>共 {questions.length} 道题{wrongCount>0?`，其中 ${wrongCount} 道错题`:''}，当前显示 {filtered.length} 道</CardDescription>
    <CardAction className="col-auto row-auto">
     <Button type="button" size="sm" variant={filtersOpen||extraFilterCount?'secondary':'outline'} aria-expanded={filtersOpen} onClick={()=>setFiltersOpen(v=>!v)}>
      <SlidersHorizontal/>更多筛选{extraFilterCount>0&&<Badge variant="secondary">{extraFilterCount}</Badge>}
     </Button>
    </CardAction>
   </CardHeader>
   <CardContent className="px-4 py-4 sm:px-5">
    <FieldGroup className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-12">
     <FilterField label="搜索" className="sm:col-span-2 xl:col-span-6">
      <InputGroup>
       <InputGroupAddon><Search/></InputGroupAddon>
       <InputGroupInput placeholder="搜索题干、答案或解析" value={search} onChange={e=>{setSearch(e.target.value);setPage(0);}}/>
       {search&&<InputGroupAddon align="inline-end"><InputGroupButton size="icon-xs" aria-label="清空搜索" onClick={()=>{setSearch('');setPage(0);}}><X/></InputGroupButton></InputGroupAddon>}
      </InputGroup>
     </FilterField>
     <FilterField label="题库" className="xl:col-span-2"><FilterSelect label="题库筛选" value={bankFilter} onChange={value=>{setBankFilter(value);setPage(0);}} options={[['all','全部题库'],...banks.map(v=>[v,v] as [string,string])]}/></FilterField>
     <FilterField label="题型" className="xl:col-span-2"><FilterSelect label="题型筛选" value={typeFilter} onChange={value=>{setTypeFilter(value);setPage(0);}} options={[['all','全部题型'],...Object.entries(labels)]}/></FilterField>
     <FilterField label="难度" className="xl:col-span-2"><FilterSelect label="难度筛选" value={starFilter} onChange={value=>{setStarFilter(value);setPage(0);}} options={[['all','全部难度'],...[5,4,3,2,1].map(n=>[String(n),'★'.repeat(n)] as [string,string])]}/></FilterField>
    </FieldGroup>
    {filtersOpen&&<FieldGroup className="mt-4 grid grid-cols-1 gap-3 border-t pt-4 sm:grid-cols-2 lg:grid-cols-4">
     <FilterField label="科目"><FilterSelect label="科目筛选" value={subjectFilter} onChange={value=>{setSubjectFilter(value);setPage(0);}} options={[['all','全部科目'],...subjects.map(v=>[v,v] as [string,string])]}/></FilterField>
     <FilterField label="年级"><FilterSelect label="年级筛选" value={levelFilter} onChange={value=>{setLevelFilter(value);setPage(0);}} options={[['all','全部年级'],...levels.map(v=>[v,v] as [string,string])]}/></FilterField>
     <FilterField label="掌握"><FilterSelect label="掌握情况筛选" value={wrongFilter} onChange={value=>{setWrongFilter(value);setPage(0);}} options={[['all','全部题目'],['wrong',`只看错题${wrongCount>0?`（${wrongCount}）`:''}`]]}/></FilterField>
     <FilterField label="排序"><FilterSelect label="排序方式" value={sort} onChange={value=>{setSort(value);setPage(0);}} options={[['default','默认顺序'],['diff','难度从高到低'],['type','按题型']]}/></FilterField>
     <UiField orientation="horizontal" className="items-center self-end pb-2"><FieldLabel className="text-sm font-normal">包含已暂停</FieldLabel><Switch className="ms-auto" checked={showAll} onCheckedChange={setShowAll} aria-label="包含已暂停题目"/></UiField>
    </FieldGroup>}
    <Toolbar aria-label="筛选结果工具栏" className="mt-4 flex-wrap gap-x-3 gap-y-2">
     <ToolbarGroup>
      <span className="text-xs text-muted-foreground">显示 <b className="font-medium text-foreground">{filtered.length}</b> 道{pausedCount>0?` · ${pausedCount} 道已暂停`:''}</span>
     </ToolbarGroup>
     <ToolbarGroup className="ms-auto">
      {bankFilter!=='all'&&<Button size="sm" variant="ghost" disabled={deleteBank.isPending} onClick={()=>{if(confirm(`删除整个「${bankFilter}」题库（包含暂停题）？请先导出备份。`))deleteBank.mutate(bankFilter);}}>删除此题库</Button>}
      {hasFilters&&<Button type="button" size="sm" variant="ghost" onClick={resetFilters}>清除筛选</Button>}
     </ToolbarGroup>
    </Toolbar>
   </CardContent>
  </Card>

  <BankAutomationPanel sessionId={sessionId}/>

  <Card className="mt-4 gap-0 overflow-hidden py-0 shadow-none">
   <Toolbar aria-label="题目批量操作工具栏" className="flex-wrap gap-2 border-b px-3 py-2.5 sm:px-4">
    {selected.length>0?<>
     <ToolbarGroup>
      <Badge>{selected.length} 道已选</Badge>
      <Button size="sm" variant="ghost" onClick={()=>setSelected(filtered.map((q:any)=>q.id))}>选择全部结果</Button>
      <Button size="sm" variant="ghost" onClick={()=>setSelected([])}>取消选择</Button>
     </ToolbarGroup>
     <ToolbarSeparator orientation="vertical" className="max-sm:hidden"/>
     <ToolbarGroup className="ms-auto">
      <Button size="sm" variant="ghost" disabled={bulkPause.isPending} onClick={()=>bulkPause.mutate({ids:selected,enabled:false})}>暂停</Button>
      <Button size="sm" variant="ghost" disabled={bulkPause.isPending} onClick={()=>bulkPause.mutate({ids:selected,enabled:true})}>恢复</Button>
      <Button size="sm" variant="destructive" disabled={bulkDelete.isPending} onClick={()=>{if(window.confirm(`确定删除选中的 ${selected.length} 道题？此操作无法撤销`))bulkDelete.mutate(selected);}}><Trash2/>删除</Button>
     </ToolbarGroup>
    </>:<>
     <ToolbarGroup><Badge variant="outline">{filtered.length} 道题目</Badge><span className="text-xs text-muted-foreground">支持跨页批量操作</span></ToolbarGroup>
     <ToolbarGroup className="ms-auto"><Button size="sm" variant="outline" disabled={!filtered.length} onClick={()=>setSelected(filtered.map((q:any)=>q.id))}>选择全部结果</Button></ToolbarGroup>
    </>}
   </Toolbar>

   <div className="hidden md:block">
    <Table className="min-w-[800px] text-sm">
     <TableHeader className="bg-muted/50 text-xs text-muted-foreground">
      <TableRow className="hover:bg-transparent">
       <TableHead className="w-10 px-3"><Checkbox aria-label="全选当前页" checked={shown.length>0&&shown.every((q:any)=>selected.includes(q.id))} onCheckedChange={checked=>{const ids=shown.map((q:any)=>q.id);setSelected(list=>checked?[...new Set([...list,...ids])]:list.filter(id=>!ids.includes(id)));}}/></TableHead>
       <TableHead className="px-3">题目</TableHead>
       <TableHead className="w-20 px-3">题型</TableHead>
       <TableHead className="w-20 px-3">科目</TableHead>
       <TableHead className="w-20 px-3">年级</TableHead>
       <TableHead className="w-24 px-3">难度</TableHead>
       <TableHead className="w-[18%] px-3">答案</TableHead>
       <TableHead className="w-28 px-3 text-right">操作</TableHead>
      </TableRow>
     </TableHeader>
    <TableBody>{shown.map((q:any)=><TableRow key={q.id} className="cursor-pointer" onClick={()=>setDrawer(q.id)}>
      <TableCell className="px-3" onClick={e=>e.stopPropagation()}><Checkbox aria-label="选择题目" checked={selected.includes(q.id)} onCheckedChange={()=>toggle(q.id)}/></TableCell>
      <TableCell className="max-w-0 px-3"><div className="flex min-w-0 items-center gap-2"><span className={'size-1.5 shrink-0 rounded-full '+(dot[q.type]||dot.single)}/><MathText text={q.q} className="truncate"/></div></TableCell>
      <TableCell className="px-3"><Badge variant="secondary">{labels[q.type]||'单选'}</Badge></TableCell>
      <TableCell className="px-3 text-muted-foreground">{q.subject||'—'}</TableCell>
      <TableCell className="px-3 text-muted-foreground">{q.level||'—'}</TableCell>
      <TableCell className="px-3"><Stars n={q.difficulty||2}/></TableCell>
      <TableCell className="px-3"><MathText text={answerOf(q)} className="truncate text-muted-foreground"/></TableCell>
      <TableCell className="px-3" onClick={e=>e.stopPropagation()}><div className="flex justify-end gap-1">
       <Button size="icon" variant="ghost" className="size-8" aria-label="编辑题目" onClick={()=>edit(q)}><Pencil/></Button>
       <Button size="icon" variant="ghost" className="size-8" aria-label="复制为新题" onClick={()=>edit(q,true)}><Copy/></Button>
       <Button size="icon" variant="ghost" className="size-8" aria-label="删除题目" onClick={()=>setRemove(q)}><Trash2 className="text-destructive/60"/></Button>
      </div></TableCell>
     </TableRow>)}</TableBody>
    </Table>
   </div>

  <div className="divide-y md:hidden">{shown.map((q:any)=><div key={q.id} className="space-y-2 px-4 py-3">
    <Toolbar className="gap-2">
     <ToolbarGroup className="min-w-0">
      <Checkbox aria-label="选择题目" checked={selected.includes(q.id)} onCheckedChange={()=>toggle(q.id)}/>
      <Badge variant="secondary">{labels[q.type]||'单选'}</Badge>
      <Stars n={q.difficulty||2}/>
     </ToolbarGroup>
     <ToolbarGroup className="ms-auto"><Button size="icon" variant="ghost" aria-label="查看题目" onClick={()=>setDrawer(q.id)}><ChevronRight/></Button></ToolbarGroup>
    </Toolbar>
    <MathText text={q.q} className="text-sm font-medium leading-6"/>
    <MathText text={"答案："+answerOf(q)} className="truncate text-xs text-muted-foreground"/>
    <Toolbar className="gap-1">
     <ToolbarGroup><span className="truncate text-xs text-muted-foreground">{q.subject||'未分类'}{q.level?' · '+q.level:''}</span></ToolbarGroup>
     <ToolbarGroup className="ms-auto">
      <Button size="sm" variant="ghost" onClick={()=>edit(q)}><Pencil/>编辑</Button>
      <Button size="sm" variant="ghost" onClick={()=>edit(q,true)}><Copy/>复制</Button>
      <Button size="sm" variant="ghost" className="text-destructive" onClick={()=>setRemove(q)}><Trash2/>删除</Button>
     </ToolbarGroup>
    </Toolbar>
   </div>)}</div>

    {!shown.length&&<div className="px-6 py-16 text-center">{query.isLoading?<FieldDescription>正在加载题库…</FieldDescription>:<><FieldDescription>没有符合条件的题目。</FieldDescription>{hasFilters&&<Button type="button" size="sm" variant="outline" className="mt-3" onClick={resetFilters}>清除筛选</Button>}</>}</div>}

  <Toolbar aria-label="分页工具栏" className="flex-wrap gap-2 border-t px-3 py-2.5 sm:px-4">
   <ToolbarGroup><span className="text-xs text-muted-foreground">共 <b className="text-foreground">{filtered.length}</b> 题</span></ToolbarGroup>
   <ToolbarGroup className="ms-auto"><Button size="sm" variant="outline" disabled={page===0} onClick={()=>setPage(p=>p-1)}><ChevronLeft/>上一页</Button><Badge variant="outline">{page+1} / {Math.max(1,Math.ceil(filtered.length/PAGE))}</Badge><Button size="sm" variant="outline" disabled={(page+1)*PAGE>=filtered.length} onClick={()=>setPage(p=>p+1)}>下一页<ChevronRight/></Button></ToolbarGroup>
  </Toolbar>
  </Card>

  <Sheet open={!!current} onOpenChange={open=>{if(!open)setDrawer(null);}}>
   <SheetContent className="w-full gap-0 p-0 sm:max-w-[520px]">
    {current&&<>
    <SheetHeader className="border-b px-5 py-4 pe-12">
     <SheetTitle>题目详情</SheetTitle>
     <SheetDescription className="font-mono"># {current.id.slice(0,8)}</SheetDescription>
    </SheetHeader>
    <div className="flex-1 overflow-y-auto px-5 py-4">
     <Toolbar className="mb-4 flex-wrap"><ToolbarGroup><Badge variant="secondary">{labels[current.type]||'单选'}</Badge><Stars n={current.difficulty||2}/></ToolbarGroup><ToolbarGroup className="text-xs text-muted-foreground">{current.subject&&<span>{current.subject}</span>}{current.level&&<span>· {current.level}</span>}</ToolbarGroup></Toolbar>
     <MathText text={current.q} className="mb-5 whitespace-pre-wrap text-[16px] font-medium leading-7"/>
     {['single','multiple','boolean'].includes(current.type)&&<FieldGroup className="mb-5 gap-2">{(current.type==='boolean'?['正确','错误']:current.a||[]).map((opt:string,i:number)=>{const on=current.type==='multiple'?(current.correctIndices||[]).includes(i):i===current.k;return <UiField key={i} orientation="horizontal" className={'gap-2.5 rounded-md border p-2.5 text-sm '+(on?'border-primary/40 bg-primary/5':'')}><span className={'flex size-5 shrink-0 items-center justify-center rounded-full border text-[10px] '+(on?'border-primary bg-primary text-primary-foreground':'text-muted-foreground')}>{on?<Check className="size-3"/>:String.fromCharCode(65+i)}</span><span>{opt}</span></UiField>;})}</FieldGroup>}
     <FieldGroup className="mb-5 gap-3"><UiField><FieldTitle>{current.type==='text'?'可接受答案':current.type==='subjective'?'参考答案':'答案'}</FieldTitle><FieldDescription className="whitespace-pre-wrap text-foreground">{current.type==='text'?(current.answers||[]).join('\n'):answerOf(current)}</FieldDescription></UiField>{current.type==='subjective'&&current.rubric&&<UiField><FieldTitle>评分标准</FieldTitle><FieldDescription className="whitespace-pre-wrap text-foreground">{current.rubric}</FieldDescription></UiField>}{current.tip&&<UiField><FieldTitle>解析</FieldTitle><MathText text={current.tip} className="whitespace-pre-wrap text-sm leading-6 text-muted-foreground"/></UiField>}</FieldGroup>
     <FieldGroup className="gap-2"><UiField><FieldTitle>题目信息</FieldTitle><FieldDescription>题库：{current.bank||'默认题库'} · 出题 {current.stats?.presented||0} 次 · 作答 {current.stats?.answered||0} 次 · 正确率 {current.stats?.answered?Math.round(current.stats.correct/current.stats.answered*100):0}% · 最近一次：{current.stats?.lastCorrect===false?'答错':current.stats?.lastCorrect===true?'答对':'未作答'}</FieldDescription></UiField><UiField><FieldTitle>题目编号</FieldTitle><FieldDescription className="break-all font-mono">{current.id}</FieldDescription></UiField></FieldGroup>
    </div>
    <SheetFooter className="gap-0 border-t p-3">
     <Toolbar className="flex-wrap gap-2">
      <ToolbarGroup><Button size="sm" variant="outline" disabled={drawerIndex<=0} onClick={()=>setDrawer(filtered[drawerIndex-1]?.id||null)}><ChevronLeft/>上一题</Button><Button size="sm" variant="outline" disabled={drawerIndex<0||drawerIndex>=filtered.length-1} onClick={()=>setDrawer(filtered[drawerIndex+1]?.id||null)}>下一题<ChevronRight/></Button></ToolbarGroup>
      <ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>edit(current,true)}><Copy/>复制</Button><Button size="sm" onClick={()=>edit(current)}><Pencil/>编辑</Button><Button size="sm" variant="ghost" className="text-destructive" onClick={()=>setRemove(current)}><Trash2/>删除</Button></ToolbarGroup>
     </Toolbar>
    </SheetFooter>
    </>}
   </SheetContent>
  </Sheet>

  <Dialog open={!!form} onOpenChange={v=>{if(!v)setForm(null);}}>
   <DialogContent className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
    <DialogHeader className="border-b px-5 py-4">
     <DialogTitle>{form?.id?'编辑题目':'新增题目'}</DialogTitle>
     <DialogDescription>支持五种题型，公式可用 $...$ 表示。</DialogDescription>
    </DialogHeader>
    {form&&<div className="min-h-0 overflow-y-auto px-5 py-4">
     <FieldGroup className="gap-4">
      <Field label="题型"><ChoiceSelect label="题型" value={form.type} onChange={v=>{field('type',v);if(v!=='multiple')field('correct',[form.correct[0]??0]);}} options={Object.entries(labels)}/></Field>
      <Field label="题干"><Textarea rows={3} placeholder="请输入题目" value={form.q} onChange={e=>field('q',e.target.value)}/></Field>
      {['single','multiple','boolean'].includes(form.type)&&<Field label="选项"><FieldDescription>{form.type==='multiple'?'点击左侧字母标记所有正确答案。':'点击左侧字母标记正确答案。'}</FieldDescription><FieldGroup className="gap-2">{(form.type==='boolean'?['正确','错误']:form.opts).map((o:string,i:number)=>{const on=form.correct.includes(i);return <UiField key={i} orientation="horizontal" className="gap-2"><Button type="button" size="icon" variant={on?'default':'outline'} className="shrink-0 rounded-full text-xs font-semibold" aria-label={`标记选项 ${String.fromCharCode(65+i)} 为正确答案`} aria-pressed={on} onClick={()=>field('correct',form.type==='multiple'?(on?form.correct.filter((x:number)=>x!==i):[...form.correct,i]):[i])}>{on?<Check/>:String.fromCharCode(65+i)}</Button>{form.type==='boolean'?<FieldDescription className="text-foreground">{o}</FieldDescription>:<><Input aria-label={`选项 ${String.fromCharCode(65+i)}`} value={o} placeholder={`选项 ${String.fromCharCode(65+i)}`} onChange={e=>field('opts',form.opts.map((x:string,j:number)=>j===i?e.target.value:x))}/>{form.opts.length>2&&<Button type="button" size="icon" variant="ghost" aria-label="删除此选项" onClick={()=>{field('opts',form.opts.filter((_:any,j:number)=>j!==i));field('correct',form.correct.filter((x:number)=>x!==i).map((x:number)=>x>i?x-1:x));}}><X/></Button>}</>}</UiField>;})}</FieldGroup>{form.type!=='boolean'&&form.opts.length<8&&<Button type="button" variant="outline" size="sm" onClick={()=>field('opts',[...form.opts,''])}><Plus/>添加选项</Button>}</Field>}
      {form.type==='text'&&<Field label="可接受答案，每行一个"><Textarea rows={3} value={form.accepted} onChange={e=>field('accepted',e.target.value)}/></Field>}
      {form.type==='subjective'&&<><Field label="参考答案"><Textarea rows={3} value={form.modelAnswer} onChange={e=>field('modelAnswer',e.target.value)}/></Field><Field label="评分标准"><Textarea rows={2} value={form.rubric} onChange={e=>field('rubric',e.target.value)}/></Field></>}
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <Field label="科目"><Input list="bank-subjects" value={form.subject} onChange={e=>field('subject',e.target.value)} placeholder="例如 数学"/><datalist id="bank-subjects">{subjects.map(v=><option key={v} value={v}/>)}</datalist></Field>
       <Field label="年级"><Input list="bank-levels" value={form.level} onChange={e=>field('level',e.target.value)} placeholder="例如 五年级"/><datalist id="bank-levels">{levels.map(v=><option key={v} value={v}/>)}</datalist></Field>
      </FieldGroup>
      <Field label="题库名称"><Input list="bank-names" value={form.bank} onChange={e=>field('bank',e.target.value)}/><datalist id="bank-names">{banks.map(v=><option key={v} value={v}/>)}</datalist></Field>
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <Field label="图片地址（可选）"><Input value={form.image} onChange={e=>field('image',e.target.value)} placeholder="https://"/></Field>
       <Field label="音频地址（可选）"><Input value={form.audio} onChange={e=>field('audio',e.target.value)} placeholder="https://"/></Field>
      </FieldGroup>
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <Field label="朗读 / 语音作答语言"><ChoiceSelect label="朗读 / 语音作答语言" value={form.language||'system'} onChange={v=>field('language',v==='system'?'':v)} options={[['zh-CN','中文'],['zh-HK','粤语（香港）'],['zh-TW','台湾国语'],['en-US','英语（美式）'],['en-GB','英语（英式）'],['system','跟随设备系统语言']]}/></Field>
       <Field label="星级难度"><ChoiceSelect label="星级难度" value={String(form.difficulty)} onChange={v=>field('difficulty',Number(v))} options={[1,2,3,4,5].map(s=>[String(s),'★'.repeat(s)])}/></Field>
      </FieldGroup>
      <Field label="解析"><Textarea rows={2} value={form.tip} onChange={e=>field('tip',e.target.value)}/></Field>
     </FieldGroup>
    </div>}
    <DialogFooter className="border-t px-5 py-3"><Button variant="outline" onClick={()=>setForm(null)}>取消</Button><Button disabled={save.isPending} onClick={submit}>{save.isPending?'保存中…':'保存题目'}</Button></DialogFooter>
   </DialogContent>
  </Dialog>

  <Dialog open={importing} onOpenChange={setImporting}>
   <DialogContent className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
    <DialogHeader className="border-b px-5 py-4">
     <DialogTitle>导入题库</DialogTitle>
     <DialogDescription>支持 JSON、Word、文字版 PDF 或文本文件，先解析预览再确认导入。其他题库的 question / 题目、options / 选项、answer / 答案 等字段会自动转换。</DialogDescription>
    </DialogHeader>
    <div className="min-h-0 overflow-y-auto px-5 py-4">
     <FieldGroup className="gap-4">
      <Field label="导入到题库"><Input value={importBank} onChange={e=>setImportBank(e.target.value)} placeholder="默认题库"/></Field>
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <Field label="默认科目（可选）"><Input list="bank-subjects" value={importSubject} onChange={e=>setImportSubject(e.target.value)} placeholder="例如 数学"/></Field>
       <Field label="默认年级（可选）"><Input list="bank-levels" value={importLevel} onChange={e=>setImportLevel(e.target.value)} placeholder="例如 五年级"/></Field>
      </FieldGroup>
      <Field label="题库 JSON"><FieldDescription>只给没有 subject / level 的题目补上，JSON 里已有的分类会保留。</FieldDescription><Textarea aria-label="题库 JSON" className="min-h-56 font-mono text-xs" value={text} onChange={e=>setText(e.target.value)}/></Field>
      <input hidden type="file" ref={file} accept=".json,.docx,.pdf,.txt,application/json" onChange={pick}/>
      <Toolbar className="flex-wrap gap-2">
       <ToolbarGroup><Button variant="outline" onClick={()=>{if(file.current){file.current.value='';file.current.click();}}}><Upload/>{documentBusy?'读取文档中…':'选择 JSON / Word / PDF'}</Button><span className="max-w-64 truncate text-xs text-muted-foreground">{fileName}</span></ToolbarGroup>
      </Toolbar>
     </FieldGroup>
    </div>
    <DialogFooter className="border-t px-5 py-3"><Button variant="outline" onClick={()=>setImporting(false)}>取消</Button><Button onClick={importNow} disabled={importingMutation.isPending||documentBusy}>导入题库</Button></DialogFooter>
   </DialogContent>
  </Dialog>

  <Dialog open={!!remove} onOpenChange={v=>{if(!v)setRemove(null);}}><DialogContent><DialogHeader><DialogTitle>删除这道题？</DialogTitle><DialogDescription>{remove?.q}</DialogDescription></DialogHeader><DialogFooter><Button variant="outline" onClick={()=>setRemove(null)}>取消</Button><Button variant="destructive" disabled={deleting.isPending} onClick={()=>deleting.mutate(remove.id)}>删除</Button></DialogFooter></DialogContent></Dialog>

  <Dialog open={aiOpen} onOpenChange={v=>{if(!v&&!aiLoading)setAiOpen(false);}}>
   <DialogContent className="grid max-h-[90dvh] grid-rows-[auto_auto_minmax(0,1fr)_auto] overflow-hidden p-0 sm:max-w-3xl">
    <DialogHeader className="border-b px-5 py-4">
     <DialogTitle className="flex items-center gap-2"><Sparkles className="size-4 text-primary"/>AI 出题</DialogTitle>
    </DialogHeader>
    <Tabs value={aiTab} onValueChange={value=>setAiTab(value as 'api'|'agent')} className="border-b px-5 py-2">
     <TabsList className="w-full justify-start sm:w-auto">
      <TabsTrigger value="agent">外部 Agent 出题</TabsTrigger>
      <TabsTrigger value="api">使用模型 API</TabsTrigger>
     </TabsList>
    </Tabs>
    <div className="min-h-0 overflow-y-auto px-5 py-4">
     {aiTab==='api'?<>
      <Toolbar className="mb-4 flex-wrap gap-2">
       <ToolbarGroup className="min-w-0"><Badge variant={configured?'secondary':'outline'}>{configured?'已配置':'未配置'}</Badge><span className="truncate text-xs text-muted-foreground">{configured?`${aiProvider} · ${grading.data?.model}`:'尚未配置模型 API'}</span></ToolbarGroup>
       <ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>{setAiOpen(false);location.hash='/settings/ai';}}>去设置</Button></ToolbarGroup>
      </Toolbar>
      {aiItems.length===0?<FieldGroup className="gap-4">
       <Field label="出题范围"><Input id="ai-topic" placeholder="例如：五年级数学 分数加减法" value={aiForm.topic} onChange={e=>setAiForm({...aiForm,topic:e.target.value})}/><FieldDescription>可以写教材版本、单元或薄弱点。</FieldDescription></Field>
       <UiField><FieldLabel>题型</FieldLabel><FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">{Object.entries(labels).map(([k,v])=><UiField key={k} orientation="horizontal" className="w-auto"><Checkbox id={`ai-type-${k}`} checked={!!aiForm.types[k]} onCheckedChange={checked=>setAiForm({...aiForm,types:{...aiForm.types,[k]:checked===true}})}/><FieldLabel htmlFor={`ai-type-${k}`} className="font-normal">{v}</FieldLabel></UiField>)}</FieldGroup></UiField>
       <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="数量"><ChoiceSelect label="生成数量" value={String(aiForm.count)} onChange={value=>setAiForm({...aiForm,count:Number(value)})} options={[5,10,15,20].map(n=>[String(n),`${n} 题`])}/></Field>
        <Field label="难度"><ChoiceSelect label="生成难度" value={aiForm.difficulty} onChange={value=>setAiForm({...aiForm,difficulty:value})} options={[['mixed','混合（由易到难）'],...[1,2,3,4,5].map(n=>[String(n),'全部 '+'★'.repeat(n)] as [string,string])]}/></Field>
       </FieldGroup>
       <Field label="额外要求（可选）"><Textarea id="ai-extra" rows={2} placeholder="例如：结合生活场景；不要出现负数；每题附一句解析。" value={aiForm.extra} onChange={e=>setAiForm({...aiForm,extra:e.target.value})}/></Field>
       <FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">
        <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-with-tip" checked={aiForm.withTip} onCheckedChange={checked=>setAiForm({...aiForm,withTip:checked===true})}/><FieldLabel htmlFor="ai-with-tip" className="font-normal">自动生成解析</FieldLabel></UiField>
        <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-skip-duplicates" checked={aiForm.skipDuplicates} onCheckedChange={checked=>setAiForm({...aiForm,skipDuplicates:checked===true})}/><FieldLabel htmlFor="ai-skip-duplicates" className="font-normal">跳过已有题干</FieldLabel></UiField>
       </FieldGroup>
       <Alert><RefreshCw/><AlertDescription>生成结果会先预览，一次最多 20 题。API Key 只保存在设备上。</AlertDescription></Alert>
       {aiError&&<FieldError>{aiError}</FieldError>}
      </FieldGroup>:<>
       <Toolbar className="mb-3 flex-wrap gap-2"><ToolbarGroup><Badge variant="outline">{aiItems.length} 题待预览</Badge><span className="text-xs text-muted-foreground">勾选要保存的题目</span></ToolbarGroup><ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>setAiItems([])}>重新生成</Button></ToolbarGroup></Toolbar>
       <FieldGroup className="gap-2">{aiItems.map((q:any,i:number)=><UiField key={i} orientation="horizontal" className="items-start rounded-md border p-3"><Checkbox className="mt-1" checked={q.include} onCheckedChange={checked=>setAiItems(list=>list.map((x,j)=>j===i?{...x,include:checked===true}:x))}/><div className="min-w-0 flex-1"><Toolbar className="mb-1"><ToolbarGroup><Badge variant="secondary">{labels[q.type]||'单选'}</Badge><Stars n={q.difficulty||2}/></ToolbarGroup></Toolbar><MathText text={q.q} className="whitespace-pre-wrap text-sm font-medium leading-6"/><MathText text={"答案："+answerOf(q)} className="mt-1 truncate text-xs text-muted-foreground"/></div></UiField>)}</FieldGroup>
      </>}
     </>:<FieldGroup className="gap-5">
      <UiField>
       <FieldTitle className="flex items-center gap-2"><ClipboardCopy className="size-4 text-primary"/>方式一：让 Agent 直接写入题库</FieldTitle>
       <FieldDescription>把出题指令发给能访问局域网、会调用工具的 Agent，它完成出题后直接写入题库。</FieldDescription>
       <div className="flex flex-wrap gap-1.5">{['WorkBuddy','Codex','千问办公','豆包','Kimi','智谱清言','文心一言','腾讯元宝'].map(name=><Badge key={name} variant="outline">{name}</Badge>)}</div>
       <Alert><AlertDescription>以上指桌面客户端或命令行工具，不是网页对话版。网页版通常无法访问本机局域网或写入题库。</AlertDescription></Alert>
       <ol className="list-decimal space-y-1 ps-4 text-sm leading-6 text-muted-foreground"><li>确认 Agent 和被控设备在同一局域网</li><li>复制出题指令，连同管理令牌一起发给 Agent</li><li>Agent 写入后回到这里刷新题库</li></ol>
       <Toolbar className="flex-wrap"><ToolbarGroup><Button size="sm" onClick={()=>copy(jsonPrompt)}><Copy/>复制出题指令</Button><Button size="sm" variant="outline" onClick={()=>copy('https://github.com/chadyi/StudyOnTV/blob/main/docs/ai-api.md')}><ExternalLink/>接口说明</Button></ToolbarGroup></Toolbar>
      </UiField>
      <UiField>
       <FieldTitle className="flex items-center gap-2"><FileJson className="size-4 text-primary"/>方式二：只返回 JSON，再由我导入</FieldTitle>
       <FieldDescription>不把管理令牌交给 Agent。让它按格式输出 JSON，再粘贴或选择文件导入。</FieldDescription>
       <Toolbar className="flex-wrap"><ToolbarGroup><Button size="sm" variant="outline" onClick={()=>copy(agentPrompt)}><Copy/>复制 JSON 指令</Button><Button size="sm" variant="outline" onClick={()=>{setAiOpen(false);setText('');setFileName('');setImporting(true);}}>打开导入</Button></ToolbarGroup></Toolbar>
      </UiField>
      <Alert><AlertDescription>管理令牌等同于家长权限，只发给可信的 Agent，不要发到公开群或截图里。修改管理密码后旧令牌立即失效。</AlertDescription></Alert>
     </FieldGroup>}
    </div>
    <DialogFooter className="border-t px-5 py-3">
     {aiTab==='api'&&aiItems.length===0&&<><FieldDescription className="me-auto hidden sm:block">{aiLoading?'正在调用模型…':'生成结果会先预览'}</FieldDescription><Button variant="outline" onClick={()=>setAiOpen(false)} disabled={aiLoading}>取消</Button><Button onClick={generate} disabled={aiLoading}><Sparkles/>{aiLoading?'生成中…':'生成题目'}</Button></>}
     {aiTab==='api'&&aiItems.length>0&&<><Button variant="outline" className="me-auto" onClick={()=>setAiItems([])} disabled={saveGenerated.isPending}>重新生成</Button><Button disabled={!aiSelected||saveGenerated.isPending} onClick={()=>saveGenerated.mutate(aiItems.filter((q:any)=>q.include).map(({include,...q}:any)=>q))}>{saveGenerated.isPending?'保存中…':`保存 ${aiSelected} 题到题库`}</Button></>}
     {aiTab==='agent'&&<Button variant="outline" onClick={()=>setAiOpen(false)}>关闭</Button>}
    </DialogFooter>
   </DialogContent>
  </Dialog>
 </>;
}
export function Field({label,children}:{label:string,children:any}){
 const autoId=useId();
 let target:string|undefined;
 const link=(child:any)=>{
  if(isValidElement(child)&&(child.type===Input||child.type===Textarea||child.type===ChoiceSelect)){
   const own=(child.props as any).id as string|undefined;
   const id=own||autoId;
   target=id;
   return own?child:cloneElement(child as any,{id});
  }
  return child;
 };
 const content=Array.isArray(children)?children.map(link):link(children);
 return <UiField><FieldLabel htmlFor={target}>{label}</FieldLabel>{content}</UiField>;
}
