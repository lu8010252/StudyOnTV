import {useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import {Check,Plus,X} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Dialog,DialogContent,DialogDescription,DialogFooter,DialogHeader,DialogTitle} from '@/components/ui/dialog';
import {Field as UiField,FieldDescription,FieldGroup} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {api} from '@/api';
import {ChoiceSelect,Field,labels} from './shared';
import type {BankMeta} from './QuestionFilters';

/** 新增 / 编辑题目。表单状态跟着对话框走，关掉就丢，不再挂在题库页上。 */
export default function QuestionFormDialog({initial,meta,onClose,onSaved}:{
 initial:any;
 meta:BankMeta;
 onClose:()=>void;
 onSaved:(message:string)=>void;
}){
 const client=useQueryClient();
 const [form,setForm]=useState<any>(initial);
 const save=useMutation({
  mutationFn:(body:any)=>api('/api/questions','POST',body),
  onSuccess:()=>{client.invalidateQueries({queryKey:['bank']});client.invalidateQueries({queryKey:['bank-meta']});onSaved('题目已保存');},
  onError:(e:any)=>toast.error(e.message),
 });
 function field(key:string,value:any){setForm((f:any)=>({...f,[key]:value}));}
 function submit(){
  const f=form;
  const q:any={q:f.q.trim(),type:f.type,difficulty:Number(f.difficulty),tip:f.tip,subject:f.subject.trim(),level:f.level.trim(),bank:f.bank.trim()||'默认题库',image:f.image.trim(),audio:f.audio.trim(),language:f.language};
  if(!q.q)return toast.error('请填写题干');
  if(f.id)q.id=f.id;
  if(f.type==='subjective'){q.modelAnswer=f.modelAnswer;q.rubric=f.rubric;}
  else if(f.type==='text'){
   q.answers=f.accepted.split('\n').map((s:string)=>s.trim()).filter(Boolean);
   if(!q.answers.length)return toast.error('请填写至少一个可接受答案');
  }else{
   q.a=f.type==='boolean'?['正确','错误']:f.opts.map((o:string)=>o.trim());
   if(f.type!=='boolean'&&q.a.some((o:string)=>!o))return toast.error('选项不能为空，多余的选项请删除');
   if(q.a.length<2)return toast.error('至少需要两个选项');
   const correct=f.correct.filter((i:number)=>i<q.a.length);
   if(!correct.length)return toast.error('请点击选项左侧的圆圈标记正确答案');
   if(f.type==='multiple')q.correctIndices=[...correct].sort();
   else q.k=correct[0];
  }
  save.mutate(q);
 }
 return <Dialog open onOpenChange={open=>{if(!open)onClose();}}>
  <DialogContent className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
   <DialogHeader className="border-b px-5 py-4">
    <DialogTitle>{form.id?'编辑题目':'新增题目'}</DialogTitle>
    <DialogDescription>支持五种题型，公式可用 $...$ 表示。</DialogDescription>
   </DialogHeader>
   <div className="min-h-0 overflow-y-auto px-5 py-4">
    <FieldGroup className="gap-4">
     <Field label="题型"><ChoiceSelect label="题型" value={form.type} onChange={v=>{field('type',v);if(v!=='multiple')field('correct',[form.correct[0]??0]);}} options={Object.entries(labels)}/></Field>
     <Field label="题干"><Textarea rows={3} placeholder="请输入题目" value={form.q} onChange={e=>field('q',e.target.value)}/></Field>
     {['single','multiple','boolean'].includes(form.type)&&<Field label="选项"><FieldDescription>{form.type==='multiple'?'点击字母标记全部正确答案。':'点击字母标记正确答案。'}</FieldDescription><FieldGroup className="gap-2">{(form.type==='boolean'?['正确','错误']:form.opts).map((o:string,i:number)=>{const on=form.correct.includes(i);return <UiField key={i} orientation="horizontal" className="gap-2"><Button type="button" size="icon" variant={on?'default':'outline'} className="shrink-0 rounded-full text-xs font-semibold" aria-label={`标记选项 ${String.fromCharCode(65+i)} 为正确答案`} aria-pressed={on} onClick={()=>field('correct',form.type==='multiple'?(on?form.correct.filter((x:number)=>x!==i):[...form.correct,i]):[i])}>{on?<Check/>:String.fromCharCode(65+i)}</Button>{form.type==='boolean'?<FieldDescription className="text-foreground">{o}</FieldDescription>:<><Input aria-label={`选项 ${String.fromCharCode(65+i)}`} value={o} placeholder={`选项 ${String.fromCharCode(65+i)}`} onChange={e=>field('opts',form.opts.map((x:string,j:number)=>j===i?e.target.value:x))}/>{form.opts.length>2&&<Button type="button" size="icon" variant="ghost" aria-label="删除此选项" onClick={()=>{field('opts',form.opts.filter((_:any,j:number)=>j!==i));field('correct',form.correct.filter((x:number)=>x!==i).map((x:number)=>x>i?x-1:x));}}><X/></Button>}</>}</UiField>;})}</FieldGroup>{form.type!=='boolean'&&form.opts.length<8&&<Button type="button" variant="outline" size="sm" onClick={()=>field('opts',[...form.opts,''])}><Plus/>添加选项</Button>}</Field>}
     {form.type==='text'&&<Field label="可接受答案，每行一个"><Textarea rows={3} value={form.accepted} onChange={e=>field('accepted',e.target.value)}/></Field>}
     {form.type==='subjective'&&<><Field label="参考答案"><Textarea rows={3} value={form.modelAnswer} onChange={e=>field('modelAnswer',e.target.value)}/></Field><Field label="评分标准"><Textarea rows={2} value={form.rubric} onChange={e=>field('rubric',e.target.value)}/></Field></>}
     <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <Field label="科目"><Input list="bank-subjects" value={form.subject} onChange={e=>field('subject',e.target.value)} placeholder="例如 数学"/><datalist id="bank-subjects">{meta.subjects.map(v=><option key={v} value={v}/>)}</datalist></Field>
      <Field label="年级"><Input list="bank-levels" value={form.level} onChange={e=>field('level',e.target.value)} placeholder="例如 五年级"/><datalist id="bank-levels">{meta.levels.map(v=><option key={v} value={v}/>)}</datalist></Field>
     </FieldGroup>
     <Field label="题库名称"><Input list="bank-names" value={form.bank} onChange={e=>field('bank',e.target.value)}/><datalist id="bank-names">{meta.banks.map(v=><option key={v} value={v}/>)}</datalist></Field>
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
   </div>
   <DialogFooter className="border-t px-5 py-3"><Button variant="outline" onClick={onClose}>取消</Button><Button disabled={save.isPending} onClick={submit}>{save.isPending?'保存中…':'保存题目'}</Button></DialogFooter>
  </DialogContent>
 </Dialog>;
}
