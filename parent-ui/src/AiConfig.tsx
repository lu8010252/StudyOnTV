import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Bot,Check,PlugZap,RefreshCw} from 'lucide-react';
import {toast} from 'sonner';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Field,FieldDescription,FieldGroup,FieldLabel} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {Dialog,DialogContent,DialogDescription,DialogFooter,DialogHeader,DialogTitle} from '@/components/ui/dialog';
import {api} from './api';
import {builtinProviders,type Provider} from './ai-providers';

export default function AiConfig({sessionId,onClose}:{sessionId:number;onClose:()=>void}){
 const client=useQueryClient();
 const settings=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const [form,setForm]=useState({provider:'deepseek',endpoint:'',model:'',apiKey:'',hasKey:false});
 const [dirty,setDirty]=useState(false);
 const [result,setResult]=useState<{ok:boolean,message:string}|null>(null);
 const providers:Provider[]=settings.data?.providers?.length?settings.data.providers:builtinProviders;
 const provider=providers.find(p=>p.id===form.provider);

 useEffect(()=>{if(settings.data)setForm(f=>({...f,hasKey:!!settings.data.hasKey,...(!dirty?{provider:settings.data.provider||'deepseek',endpoint:settings.data.endpoint||'',model:settings.data.model||''}:{})}));},[settings.data,dirty]);

 function pick(id:string){setDirty(true);const p=providers.find(x=>x.id===id);setResult(null);setForm(f=>({...f,provider:id,...(p&&p.id!=='custom'?{endpoint:p.baseUrl,model:p.model}:{})}));}
 const test=useMutation({mutationFn:()=>api('/api/grading/test','POST',{endpoint:form.endpoint,model:form.model,apiKey:form.apiKey,vision:false}),onSuccess:(r:any)=>setResult({ok:r.ok,message:r.message+(r.ms?` · ${(r.ms/1000).toFixed(1)} 秒`:'')}),onError:(e:any)=>setResult({ok:false,message:e.message})});
 const save=useMutation({mutationFn:()=>api('/api/grading','PUT',{...settings.data,mode:'ai',provider:form.provider,endpoint:form.endpoint.trim(),model:form.model.trim(),apiKey:form.apiKey,vision:!!settings.data?.vision,passScore:settings.data?.passScore||60}),onSuccess:()=>{client.invalidateQueries({queryKey:['grading',sessionId]});toast.success('模型 API 已保存');onClose();},onError:(e:any)=>toast.error(e.message)});

 function submit(){
  try{const url=new URL(form.endpoint);if(!/^https?:$/.test(url.protocol))throw 0;}catch{return toast.error('请填写完整的接口地址，例如 https://api.deepseek.com/v1');}
  if(!form.model.trim())return toast.error('请填写模型名称');
  if(!form.hasKey&&!form.apiKey.trim()&&form.provider!=='ollama'&&form.provider!=='custom')return toast.error('请填写 API Key');
  save.mutate();
 }

 return <Dialog open onOpenChange={open=>{if(!open)onClose();}}>
  <DialogContent className="max-h-[90svh] gap-0 overflow-y-auto p-0 sm:max-w-xl">
   <DialogHeader className="border-b bg-muted/20 px-5 py-4 text-start">
    <DialogTitle className="flex items-center gap-2 text-base"><Bot className="size-4 text-primary"/>模型 API</DialogTitle>
    <DialogDescription className="text-xs leading-5">AI 出题和批改共用这个服务。选好服务商，填 API Key 即可；本地 Ollama 可以填局域网地址。</DialogDescription>
   </DialogHeader>
   <div className="px-5 py-5">
    <FieldGroup className="gap-5">
     <Field>
      <FieldLabel htmlFor="ai-provider">服务商</FieldLabel>
      <Select value={form.provider} onValueChange={pick}>
       <SelectTrigger id="ai-provider" aria-label="服务商" className="w-full"><SelectValue>{providers.find(p=>p.id===form.provider)?.name||'请选择服务商'}</SelectValue></SelectTrigger>
       <SelectContent>{providers.map(p=><SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}</SelectContent>
      </Select>
      {provider?.note&&<FieldDescription>{provider.note}</FieldDescription>}
     </Field>
     <Field>
      <FieldLabel htmlFor="ai-endpoint">接口地址</FieldLabel>
      <Input id="ai-endpoint" value={form.endpoint} onChange={e=>{setDirty(true);setForm({...form,endpoint:e.target.value});}} placeholder="https://api.deepseek.com/v1"/>
     </Field>
     <Field>
      <FieldLabel htmlFor="ai-key">API Key</FieldLabel>
      <div className="relative">
       <Input id="ai-key" type="password" autoComplete="off" className={provider?.keyUrl?'pr-14':''} value={form.apiKey} onChange={e=>{setDirty(true);setForm({...form,apiKey:e.target.value});}} placeholder={form.hasKey?'已保存，留空保持原密钥':'请输入 API Key'}/>
       {provider?.keyUrl&&<a href={provider.keyUrl} target="_blank" rel="noreferrer" className="absolute right-2 top-2 text-xs text-primary underline underline-offset-4">获取</a>}
      </div>
     </Field>
     <Field>
      <FieldLabel htmlFor="ai-model">模型名称</FieldLabel>
      <Input id="ai-model" value={form.model} onChange={e=>{setDirty(true);setForm({...form,model:e.target.value});}} placeholder={provider?.model||'例如 deepseek-flash'}/>
     </Field>
     <Field orientation="responsive" className="border-t border-border/60 pt-4">
      <FieldDescription className="text-xs leading-5">测试会发一道 1+1 的示例题，不会保存设置。启用后，题目与孩子答案会发送到您配置的服务。</FieldDescription>
      <div className="flex flex-wrap items-center justify-end gap-2">
       <Button type="button" variant="outline" disabled={test.isPending} onClick={()=>test.mutate()}>{test.isPending?<RefreshCw className="animate-spin"/>:<PlugZap/>}{test.isPending?'测试中…':'测试连接'}</Button>
       {result&&<Badge variant={result.ok?'secondary':'destructive'} role="status" className="max-w-full whitespace-normal py-1 text-left">{result.ok?'连接正常':'连接失败'} · {result.message}</Badge>}
      </div>
     </Field>
    </FieldGroup>
   </div>
   <DialogFooter className="gap-2 border-t bg-muted/20 px-5 py-4">
    <Button variant="outline" className="flex-1" onClick={onClose}>取消</Button>
    <Button className="flex-1" disabled={save.isPending} onClick={submit}><Check/>{save.isPending?'保存中…':'保存并使用'}</Button>
   </DialogFooter>
  </DialogContent>
 </Dialog>;
}
