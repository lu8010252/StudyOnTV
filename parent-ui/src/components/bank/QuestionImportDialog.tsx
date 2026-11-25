import {useEffect,useRef,useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import {Upload} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Dialog,DialogContent,DialogDescription,DialogFooter,DialogHeader,DialogTitle} from '@/components/ui/dialog';
import {FieldDescription,FieldGroup} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {Toolbar,ToolbarGroup} from '@/components/ui/toolbar';
import {api} from '@/api';
import {extractDocument,parseQuestionText} from '@/document-import';
import {normalizeBank,parseBankJson} from '@/question-import';
import {Field} from './shared';
import type {BankMeta} from './QuestionFilters';

/** 导入题库：JSON / Word / PDF / 纯文本，导入成功后刷新题库页 */
export default function QuestionImportDialog({open,onOpenChange,meta,onImported}:{
 open:boolean;
 onOpenChange:(open:boolean)=>void;
 meta:BankMeta;
 onImported:(message:string)=>void;
}){
 const client=useQueryClient();
 const [importBank,setImportBank]=useState('默认题库');
 const [importSubject,setImportSubject]=useState('');
 const [importLevel,setImportLevel]=useState('');
 const [text,setText]=useState('');
 const [fileName,setFileName]=useState('');
 const [documentBusy,setDocumentBusy]=useState(false);
 const [parsedCount,setParsedCount]=useState<number|null>(null);
 const file=useRef<HTMLInputElement>(null);
 // 每次打开都从空白开始，避免上次粘的 JSON 被重复导入
 useEffect(()=>{if(open){setText('');setFileName('');setParsedCount(null);}},[open]);
 const trimmed=text.replace(/^\uFEFF/,'').trim();
 const isJson=trimmed.startsWith('{')||trimmed.startsWith('[');
 const needsParse=!!trimmed&&!isJson;
 // 导入成功后补一句「跳过了哪几题」，所以跳过原因要跨一次请求保留
 const pendingIssues=useRef<{index:number,reason:string}[]>([]);
 const importing=useMutation({
  mutationFn:(items:any[])=>api('/api/questions','POST',{items},60000),
  onSuccess:(r:any)=>{
   client.invalidateQueries({queryKey:['bank']});
   client.invalidateQueries({queryKey:['bank-meta']});
   client.invalidateQueries({queryKey:['analytics']});
   onOpenChange(false);
   onImported(`已导入 ${r.added} 题`);
   const skipped=pendingIssues.current;pendingIssues.current=[];
   if(skipped.length){
    const shown=skipped.slice(0,3).map(i=>`第 ${i.index} 题：${i.reason}`).join('；');
    toast.warning(`跳过 ${skipped.length} 题（${shown}${skipped.length>3?' 等':''}）`,{duration:8000});
   }
  },
  onError:(e:any)=>{pendingIssues.current=[];toast.error(e.message);},
 });
 const busy=importing.isPending||documentBusy;
 async function pick(e:any){
  const f=e.target.files?.[0];
  if(!f)return;
  if(f.size>20000000)return toast.error('文件超过 20 MB，请拆分导入');
  setDocumentBusy(true);setFileName(f.name);setParsedCount(null);
  try{setText(await extractDocument(f));}
  catch(error:any){toast.error(error.message||'文档读取失败');}
  finally{setDocumentBusy(false);}
 }
 function importNow(){
  try{
   const raw=text.replace(/^\uFEFF/,'').trim();
   if(!raw)return toast.error('请先粘贴题库内容或选择文件');
   if(!raw.startsWith('{')&&!raw.startsWith('[')){
    const items=parseQuestionText(raw);
    setText(JSON.stringify({items},null,2));
    setParsedCount(items.length);
    toast.success(`已解析出 ${items.length} 道题，请点击下方「确认导入题库」完成入库`);
    return;
   }
   const result=normalizeBank(parseBankJson(raw));
   if(!result.items.length){
    const first=result.issues[0];
    return toast.error(first?`没有可导入的题目：第 ${first.index} 题：${first.reason}`:'题库不能为空');
   }
   pendingIssues.current=result.issues;
   const bank=importBank.trim()||'默认题库',subject=importSubject.trim(),level=importLevel.trim();
   importing.mutate(result.items.map((q:any)=>({...q,bank:q.bank||bank,subject:q.subject||subject,level:q.level||level})));
  }catch(error:any){toast.error(error.message||'题库格式有误');}
 }
 return <Dialog open={open} onOpenChange={next=>{if(!next&&busy)return;onOpenChange(next);}}>
  <DialogContent
   className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl"
   onInteractOutside={e=>{if(busy)e.preventDefault();}}
   onEscapeKeyDown={e=>{if(busy)e.preventDefault();}}
  >
   <DialogHeader className="border-b px-5 py-4">
    <DialogTitle>导入题库</DialogTitle>
    <DialogDescription>支持 Word（.docx）、PDF、纯文本或 JSON 文件；选好文档后按两步解析并确认入库。</DialogDescription>
   </DialogHeader>
   <div className="min-h-0 overflow-y-auto px-5 py-4">
    <FieldGroup className="gap-4">
     <Field label="导入到题库"><Input value={importBank} onChange={e=>setImportBank(e.target.value)} placeholder="默认题库"/></Field>
     <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <Field label="默认科目（可选）"><Input list="bank-subjects" value={importSubject} onChange={e=>setImportSubject(e.target.value)} placeholder="例如 数学"/></Field>
      <Field label="默认年级（可选）"><Input list="bank-levels" value={importLevel} onChange={e=>setImportLevel(e.target.value)} placeholder="例如 五年级"/></Field>
     </FieldGroup>
     <input hidden type="file" ref={file} accept=".json,.docx,.pdf,.txt,application/json" onChange={pick}/>
     <div className="space-y-2 rounded-lg border border-border/70 bg-muted/25 p-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
       <span className={!parsedCount?'font-medium text-foreground':''}>1. 选择 Word / PDF / JSON 或粘贴文本</span>
       <span>→</span>
       <span className={parsedCount!==null?'font-medium text-primary':''}>2. 预览并确认导入</span>
      </div>
      <Toolbar className="flex-wrap gap-2">
       <ToolbarGroup><Button type="button" variant="outline" size="sm" disabled={busy} onClick={()=>{if(file.current){file.current.value='';file.current.click();}}}><Upload/>{documentBusy?'读取文档中…':'选择 Word / PDF / JSON 文件'}</Button><span className="max-w-64 truncate text-xs text-muted-foreground">{fileName||'未选择文件'}</span></ToolbarGroup>
      </Toolbar>
     </div>
     {parsedCount!==null&&isJson&&(
      <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-xs leading-5 text-emerald-700 dark:text-emerald-400">
       ✓ 已成功解析出 <b>{parsedCount}</b> 道题并转换为标准题库格式，确认无误后点击下方「确认导入题库」即可入库。
      </div>
     )}
     <Field label={isJson?'标准题库 JSON（已就绪，可直接导入）':'文档原文 / 题目文本（支持粘贴 Word/PDF 文字或 JSON）'}>
      <FieldDescription>
       {needsParse
        ?'当前为文档纯文本，点击底部「解析文档题目」会自动识别题干、选项与答案，再确认导入。'
        :'只给没有 subject / level 的题目补上默认分类，已有分类会保留。'}
      </FieldDescription>
      <Textarea
       aria-label="题库 JSON"
       className="min-h-52 font-mono text-xs"
       placeholder="点上方选择 Word / PDF / JSON 文件，或直接粘贴题目文本 / 题库 JSON…"
       value={text}
       onChange={e=>{setText(e.target.value);if(parsedCount!==null)setParsedCount(null);}}
      />
     </Field>
     <datalist id="bank-subjects">{meta.subjects.map(v=><option key={v} value={v}/>)}</datalist>
     <datalist id="bank-levels">{meta.levels.map(v=><option key={v} value={v}/>)}</datalist>
    </FieldGroup>
   </div>
   <DialogFooter className="border-t px-5 py-3">
    <Button variant="outline" disabled={busy} onClick={()=>onOpenChange(false)}>取消</Button>
    <Button onClick={importNow} disabled={busy||!trimmed}>
     {importing.isPending
      ?'正在导入…'
      :needsParse
       ?'解析文档题目（第 1 步）'
       :parsedCount!==null
        ?`确认导入 ${parsedCount} 题`
        :'导入题库'}
    </Button>
   </DialogFooter>
  </DialogContent>
 </Dialog>;
}
