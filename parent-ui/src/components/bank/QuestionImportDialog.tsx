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
 const file=useRef<HTMLInputElement>(null);
 // 每次打开都从空白开始，避免上次粘的 JSON 被重复导入
 useEffect(()=>{if(open){setText('');setFileName('');}},[open]);
 // 导入成功后补一句「跳过了哪几题」，所以跳过原因要跨一次请求保留
 const pendingIssues=useRef<{index:number,reason:string}[]>([]);
 const importing=useMutation({
  mutationFn:(items:any[])=>api('/api/questions','POST',{items}),
  onSuccess:(r:any)=>{
   client.invalidateQueries({queryKey:['bank']});client.invalidateQueries({queryKey:['bank-meta']});
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
 async function pick(e:any){
  const f=e.target.files?.[0];
  if(!f)return;
  if(f.size>20000000)return toast.error('文件超过 20 MB，请拆分导入');
  setDocumentBusy(true);setFileName(f.name);
  try{setText(await extractDocument(f));}
  catch(error:any){toast.error(error.message||'文档读取失败');}
  finally{setDocumentBusy(false);}
 }
 function importNow(){
  try{
   const raw=text.replace(/^\uFEFF/,'').trim();
   if(!raw)return toast.error('请先粘贴题库 JSON 或选择文件');
   if(!raw.startsWith('{')&&!raw.startsWith('[')){
    const items=parseQuestionText(raw);
    setText(JSON.stringify({items},null,2));
    toast.message(`已解析 ${items.length} 题，请检查预览 JSON，确认后再次点击导入`);
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
 return <Dialog open={open} onOpenChange={onOpenChange}>
  <DialogContent className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
   <DialogHeader className="border-b px-5 py-4">
    <DialogTitle>导入题库</DialogTitle>
    <DialogDescription>支持 JSON、Word、PDF 或文本；其他题库字段会自动转换。</DialogDescription>
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
     <datalist id="bank-subjects">{meta.subjects.map(v=><option key={v} value={v}/>)}</datalist>
     <datalist id="bank-levels">{meta.levels.map(v=><option key={v} value={v}/>)}</datalist>
    </FieldGroup>
   </div>
   <DialogFooter className="border-t px-5 py-3"><Button variant="outline" onClick={()=>onOpenChange(false)}>取消</Button><Button onClick={importNow} disabled={importing.isPending||documentBusy}>导入题库</Button></DialogFooter>
  </DialogContent>
 </Dialog>;
}
