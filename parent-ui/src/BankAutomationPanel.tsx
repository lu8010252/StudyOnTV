import {useEffect,useRef,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {toast} from 'sonner';
import {api} from './api';
import {Accordion,AccordionContent,AccordionItem,AccordionTrigger} from '@/components/ui/accordion';
import {SaveStatus,type SaveState} from '@/components/ui/save-status';
import {Button} from '@/components/ui/button';
import {Card,CardContent} from '@/components/ui/card';
import {Checkbox} from '@/components/ui/checkbox';
import {Field,FieldContent,FieldDescription,FieldError,FieldGroup,FieldLabel,FieldLegend,FieldSet,FieldTitle} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Separator} from '@/components/ui/separator';
import {Textarea} from '@/components/ui/textarea';
import {Switch} from '@/components/ui/switch';

const typeOptions:[string,string][]=[['single','单选'],['multiple','多选'],['boolean','判断'],['text','填空'],['subjective','主观题']];
const inIntRange=(v:unknown,min:number,max:number)=>{const n=Number(v);return v!==''&&v!==null&&v!==undefined&&Number.isInteger(n)&&n>=min&&n<=max;};

export default function BankAutomationPanel({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['bankAutomation',sessionId],queryFn:()=>api('/api/bank-automation'),refetchInterval:5000});
 const [dirty,setDirty]=useState(false),[form,setForm]=useState<any>({sources:[],autoAi:false,minimum:30,batch:10,masteryAttempts:5,masteryAccuracy:90,topic:'',types:['single','text']});
 const [saveError,setSaveError]=useState(''),[failedSignature,setFailedSignature]=useState('');
 const signature=JSON.stringify(form);
 const latestSignature=useRef(signature);
 latestSignature.current=signature;
 useEffect(()=>{if(query.data&&!dirty)setForm((old:any)=>({...old,...query.data}));},[query.data,dirty]);
 const field=(key:string,value:any)=>{setDirty(true);setSaveError('');setForm((f:any)=>({...f,[key]:value}));};
 const save=useMutation({
  mutationFn:(body:any)=>api('/api/bank-automation','PUT',body),
  onMutate:()=>setSaveError(''),
  onSuccess:(r,body)=>{
   client.setQueryData(['bankAutomation',sessionId],r);
   if(latestSignature.current===JSON.stringify(body)){
    setForm((old:any)=>({...old,...r}));
    setDirty(false);
    setFailedSignature('');
   }
  },
  onError:(e:any,body)=>{
   setSaveError(e?.message||'自动保存失败，请重试');
   setFailedSignature(JSON.stringify(body));
   toast.error(e.message);
  },
 });
 const types:string[]=form.types||[];
 const valid=form.sources.every((source:any)=>(!source.name.trim()&&!source.url.trim())||(!!source.name.trim()&&/^https?:\/\//.test(source.url.trim())))&&inIntRange(form.minimum,1,500)&&inIntRange(form.batch,1,20)&&inIntRange(form.masteryAttempts,1,100)&&inIntRange(form.masteryAccuracy,50,100)&&(!form.autoAi||types.length>0);
 useEffect(()=>{if(!dirty||!valid||save.isPending||failedSignature===signature)return;const timer=window.setTimeout(()=>save.mutate(form),800);return()=>window.clearTimeout(timer);},[signature,dirty,valid,save.isPending,failedSignature]);
 const sync=useMutation({
  mutationFn:()=>api('/api/bank-automation/sync','POST',{}),
  onSuccess:()=>{
   client.invalidateQueries({queryKey:['bankAutomation',sessionId]});
   client.invalidateQueries({queryKey:['bank']});
   client.invalidateQueries({queryKey:['bank-meta']});
   toast.message('已开始同步，完成后可刷新题库');
  },
  onError:(e:any)=>toast.error(e.message),
 });
 const status=query.data?.status;
 const saveState:SaveState=save.isPending?'saving':(saveError||!valid)?'error':dirty?'pending':'saved';
 return <Card className="mt-5 gap-0 overflow-hidden py-0 shadow-xs">
  <Accordion type="single" collapsible>
   <AccordionItem value="automation" className="border-0">
   <AccordionTrigger className="px-5 py-4 hover:no-underline">
    <span className="me-auto text-start text-sm font-semibold">远程题库与自动补题</span>
    <SaveStatus state={saveState}/>
   </AccordionTrigger>
   <AccordionContent className="border-t p-0">
   <CardContent className="p-4 sm:p-5">
    <FieldGroup className="gap-5">
    <FieldSet>
     <FieldLegend>远程 JSON 题库</FieldLegend>
     <FieldDescription>每 15 分钟同步；只替换该来源，失败保留本地。</FieldDescription>
     <div className="space-y-2">
      {form.sources.map((source:any,i:number)=><div key={i} className="grid gap-2 rounded-xl border border-border/60 bg-muted/20 p-3 lg:grid-cols-[9rem_minmax(0,1fr)_auto_auto] lg:items-center">
       <Input aria-label="远程题库名称" placeholder="名称" value={source.name} onChange={e=>field('sources',form.sources.map((s:any,j:number)=>i===j?{...s,name:e.target.value}:s))}/>
       <Input aria-label="远程 JSON 地址" placeholder="https://…/questions.json" value={source.url} onChange={e=>field('sources',form.sources.map((s:any,j:number)=>i===j?{...s,url:e.target.value}:s))}/>
       <Field orientation="horizontal" className="w-auto gap-2"><Checkbox id={`source-enabled-${i}`} checked={source.enabled!==false} onCheckedChange={checked=>field('sources',form.sources.map((s:any,j:number)=>i===j?{...s,enabled:checked===true}:s))}/><FieldLabel htmlFor={`source-enabled-${i}`} className="font-normal">启用</FieldLabel></Field>
       <Button type="button" variant="ghost" size="sm" onClick={()=>field('sources',form.sources.filter((_:any,j:number)=>i!==j))}>移除来源</Button>
      </div>)}
     </div>
     <div className="flex flex-wrap items-center gap-3">
      <Button type="button" variant="outline" size="sm" disabled={form.sources.length>=20} onClick={()=>field('sources',[...form.sources,{name:'',url:'',enabled:true}])}>添加来源</Button>
      <FieldDescription className="text-xs">移除后停止同步；已下载的题库需单独删除。</FieldDescription>
     </div>
    </FieldSet>

    <Separator/>

    <FieldSet>
     <FieldLegend>题目掌握后自动补题</FieldLegend>
     <FieldDescription>可能消耗 AI 额度；掌握题暂停保留，题量不足自动补题。</FieldDescription>
     <Field orientation="responsive" className="border-b border-border/60 py-4">
      <FieldContent><FieldTitle>启用 AI 自动补题</FieldTitle></FieldContent>
      <div><Switch aria-label="启用 AI 自动补题" checked={!!form.autoAi} onCheckedChange={v=>field('autoAi',v)}/></div>
     </Field>
     {form.autoAi&&<>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>最少启用题数</FieldTitle></FieldContent><Input aria-label="最少启用题数" type="number" min={1} max={500} className="w-24" value={form.minimum} onChange={e=>field('minimum',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>每批补充题数</FieldTitle></FieldContent><Input aria-label="每批补充题数" type="number" min={1} max={20} className="w-24" value={form.batch} onChange={e=>field('batch',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>至少作答次数</FieldTitle></FieldContent><Input aria-label="至少作答次数" type="number" min={1} max={100} className="w-24" value={form.masteryAttempts} onChange={e=>field('masteryAttempts',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>掌握正确率 · %</FieldTitle></FieldContent><Input aria-label="掌握正确率" type="number" min={50} max={100} className="w-24" value={form.masteryAccuracy} onChange={e=>field('masteryAccuracy',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4">
       <FieldContent><FieldTitle>补题题型</FieldTitle><FieldDescription className="text-xs">至少选一种题型。</FieldDescription>{form.autoAi&&types.length===0&&<FieldError>请至少选择一种题型</FieldError>}</FieldContent>
       <div className="flex flex-wrap gap-x-4 gap-y-2">
        {typeOptions.map(([value,label])=><Field key={value} orientation="horizontal" className="w-auto gap-2">
         <Checkbox id={`auto-type-${value}`} checked={types.includes(value)} onCheckedChange={checked=>field('types',checked?[...types,value]:types.filter((t:string)=>t!==value))}/>
         <FieldLabel htmlFor={`auto-type-${value}`} className="font-normal">{label}</FieldLabel>
        </Field>)}
       </div>
      </Field>
      <div className="pt-3"><Textarea aria-label="自动补题范围" placeholder="例如：四年级上册数学，分数基础，附解析" value={form.topic} onChange={e=>field('topic',e.target.value)}/></div>
     </>}
    </FieldSet>

    {status?.at&&<FieldDescription className="space-y-1 rounded-xl bg-muted/40 p-3 text-xs"><p>上次执行：{new Date(status.at).toLocaleString()} {status.message||''}</p>{(status.sources||[]).map((s:any)=><p key={s.name}>{s.name}：{s.ok?`同步 ${s.imported} 题`:s.message}</p>)}{status.generated!==undefined&&<p>自动补题 {status.generated} 题；暂停已掌握 {status.pausedMastered||0} 题</p>}</FieldDescription>}
    <div className="flex flex-wrap items-center justify-end gap-2"><Button type="button" variant="outline" onClick={()=>{client.invalidateQueries({queryKey:['bank']});client.invalidateQueries({queryKey:['bank-meta']});}}>刷新题库</Button><Button type="button" variant="outline" disabled={dirty||sync.isPending||query.data?.running} onClick={()=>sync.mutate()}>{query.data?.running?'同步中…':'立即同步 / 补题'}</Button></div>
    </FieldGroup>
   </CardContent>
   </AccordionContent>
   </AccordionItem>
  </Accordion>
 </Card>;
}
