import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Check,LoaderCircle} from 'lucide-react';
import {toast} from 'sonner';
import {api} from './api';
import {Accordion,AccordionContent,AccordionItem,AccordionTrigger} from '@/components/ui/accordion';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Card,CardContent} from '@/components/ui/card';
import {Checkbox} from '@/components/ui/checkbox';
import {Field,FieldContent,FieldDescription,FieldGroup,FieldLabel,FieldLegend,FieldSet,FieldTitle} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Separator} from '@/components/ui/separator';
import {Textarea} from '@/components/ui/textarea';
import {Switch} from '@/components/ui/switch';

export default function BankAutomationPanel({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['bankAutomation',sessionId],queryFn:()=>api('/api/bank-automation'),refetchInterval:5000});
 const [dirty,setDirty]=useState(false),[form,setForm]=useState<any>({sources:[],autoAi:false,minimum:30,batch:10,masteryAttempts:5,masteryAccuracy:90,topic:''});
 useEffect(()=>{if(query.data&&!dirty)setForm({...form,...query.data});},[query.data,dirty]);
 const field=(key:string,value:any)=>{setDirty(true);setForm((f:any)=>({...f,[key]:value}));};
 const save=useMutation({mutationFn:()=>api('/api/bank-automation','PUT',form),onSuccess:r=>{client.setQueryData(['bankAutomation',sessionId],r);setDirty(false);},onError:(e:any)=>toast.error(e.message)});
 const valid=form.sources.every((source:any)=>(!source.name.trim()&&!source.url.trim())||(!!source.name.trim()&&/^https?:\/\//.test(source.url.trim())))&&Number(form.minimum)>=1&&Number(form.batch)>=1&&Number(form.masteryAttempts)>=1&&Number(form.masteryAccuracy)>=50;
 useEffect(()=>{if(!dirty||!valid)return;const timer=window.setTimeout(()=>save.mutate(),800);return()=>window.clearTimeout(timer);},[form,dirty,valid]);
 const sync=useMutation({mutationFn:()=>api('/api/bank-automation/sync','POST',{}),onSuccess:()=>{client.invalidateQueries({queryKey:['bankAutomation',sessionId]});toast.message('已开始同步，完成后可刷新题库');},onError:(e:any)=>toast.error(e.message)});
 const status=query.data?.status;
 return <Card className="mt-5 gap-0 overflow-hidden py-0 shadow-xs">
  <Accordion type="single" collapsible>
   <AccordionItem value="automation" className="border-0">
   <AccordionTrigger className="px-5 py-4 hover:no-underline">
    <span className="text-start">
     <span className="block text-sm font-semibold">远程题库与自动补题</span>
     <span className="mt-1 block text-xs font-normal text-muted-foreground">按需展开，修改后自动保存</span>
    </span>
   </AccordionTrigger>
   <AccordionContent className="border-t p-0">
   <CardContent className="p-4 sm:p-5">
    <FieldGroup className="gap-5">
    <FieldSet>
     <FieldLegend>远程 JSON 题库</FieldLegend>
     <FieldDescription>设备每 15 分钟拉取启用来源。同步只替换该来源的题目，失败时保留本地内容。</FieldDescription>
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
      <FieldDescription className="text-xs">移除来源会停止同步，已下载的题库可在题库筛选后单独删除。</FieldDescription>
     </div>
    </FieldSet>

    <Separator/>

    <FieldSet>
     <FieldLegend>题目掌握后自动补题</FieldLegend>
     <FieldDescription>开启后可能消耗已配置的 AI 服务额度。达到掌握标准的题目暂停保留，题量不足时自动生成新题。</FieldDescription>
     <Field orientation="responsive" className="border-b border-border/60 py-4">
      <FieldContent><FieldTitle>启用 AI 自动补题</FieldTitle></FieldContent>
      <div><Switch aria-label="启用 AI 自动补题" checked={!!form.autoAi} onCheckedChange={v=>field('autoAi',v)}/></div>
     </Field>
     {form.autoAi&&<>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>最少启用题数</FieldTitle></FieldContent><Input aria-label="最少启用题数" type="number" min={1} max={500} className="w-24" value={form.minimum} onChange={e=>field('minimum',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>每批补充题数</FieldTitle></FieldContent><Input aria-label="每批补充题数" type="number" min={1} max={20} className="w-24" value={form.batch} onChange={e=>field('batch',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>至少作答次数</FieldTitle></FieldContent><Input aria-label="至少作答次数" type="number" min={1} max={100} className="w-24" value={form.masteryAttempts} onChange={e=>field('masteryAttempts',Number(e.target.value))}/></Field>
      <Field orientation="responsive" className="border-b border-border/60 py-4"><FieldContent><FieldTitle>掌握正确率 · %</FieldTitle></FieldContent><Input aria-label="掌握正确率" type="number" min={50} max={100} className="w-24" value={form.masteryAccuracy} onChange={e=>field('masteryAccuracy',Number(e.target.value))}/></Field>
      <div className="pt-3"><Textarea aria-label="自动补题范围" placeholder="例如：四年级上册数学，分数基础，附解析" value={form.topic} onChange={e=>field('topic',e.target.value)}/></div>
     </>}
    </FieldSet>

    {status?.at&&<FieldDescription className="space-y-1 rounded-xl bg-muted/40 p-3 text-xs"><p>上次执行：{new Date(status.at).toLocaleString()} {status.message||''}</p>{(status.sources||[]).map((s:any)=><p key={s.name}>{s.name}：{s.ok?`同步 ${s.imported} 题`:s.message}</p>)}{status.generated!==undefined&&<p>自动补题 {status.generated} 题；暂停已掌握 {status.pausedMastered||0} 题</p>}</FieldDescription>}
    <div className="flex flex-wrap items-center justify-end gap-2"><Badge variant={save.isPending?'secondary':valid?'outline':'destructive'}>{save.isPending?<><LoaderCircle className="animate-spin"/>保存中</>:valid?<><Check/>修改后自动保存</>:'请检查来源地址'}</Badge><Button type="button" variant="outline" onClick={()=>client.invalidateQueries({queryKey:['bank',sessionId]})}>刷新题库</Button><Button type="button" variant="outline" disabled={dirty||sync.isPending||query.data?.running} onClick={()=>sync.mutate()}>{query.data?.running?'同步中…':'立即同步 / 补题'}</Button></div>
    </FieldGroup>
   </CardContent>
   </AccordionContent>
   </AccordionItem>
  </Accordion>
 </Card>;
}
