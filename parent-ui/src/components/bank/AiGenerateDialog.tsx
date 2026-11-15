import {useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {ClipboardCopy,Copy,ExternalLink,FileJson,RefreshCw,Sparkles} from 'lucide-react';
import {toast} from 'sonner';
import {Alert,AlertDescription} from '@/components/ui/alert';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Checkbox} from '@/components/ui/checkbox';
import {Dialog,DialogContent,DialogFooter,DialogHeader,DialogTitle} from '@/components/ui/dialog';
import {Field as UiField,FieldDescription,FieldError,FieldGroup,FieldLabel,FieldTitle} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Tabs,TabsList,TabsTrigger} from '@/components/ui/tabs';
import {Textarea} from '@/components/ui/textarea';
import {Toolbar,ToolbarGroup} from '@/components/ui/toolbar';
import {api} from '@/api';
import {copyText} from '@/lib/clipboard';
import {ChoiceSelect,MathText,Stars,answerOf,labels} from './shared';

/** AI 出题：模型 API 直接生成，或把指令交给外部 Agent；生成结果先预览再入库 */
export default function AiGenerateDialog({open,onOpenChange,sessionId,onOpenImport}:{
 open:boolean;
 onOpenChange:(open:boolean)=>void;
 sessionId:number;
 onOpenImport:()=>void;
}){
 const client=useQueryClient();
 const [aiTab,setAiTab]=useState<'api'|'agent'>('agent');
 const [aiForm,setAiForm]=useState({topic:'',types:{single:true,multiple:true,boolean:true,text:true,subjective:false} as any,count:10,difficulty:'mixed',extra:'',withTip:true,skipDuplicates:true});
 const [aiLoading,setAiLoading]=useState(false);
 const [aiItems,setAiItems]=useState<any[]>([]);
 const [aiError,setAiError]=useState('');
 const grading=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading'),enabled:open});
 const configured=!!(grading.data?.endpoint&&grading.data?.model);
 const aiProvider=(grading.data?.providers||[]).find((p:any)=>p.id===grading.data?.provider)?.name||'AI';
 const saveGenerated=useMutation({
  mutationFn:(items:any[])=>api('/api/questions','POST',{items}),
  onSuccess:(r:any)=>{
   client.invalidateQueries({queryKey:['bank']});client.invalidateQueries({queryKey:['bank-meta']});
   toast.success(`已保存 ${r.added} 题到题库`);
   setAiItems([]);onOpenChange(false);
  },
  onError:(e:any)=>toast.error(e.message),
 });
 async function generate(){
  if(!aiForm.topic.trim())return toast.error('请填写出题范围');
  if(!Object.values(aiForm.types).some(Boolean))return toast.error('至少选择一种题型');
  setAiLoading(true);setAiError('');
  try{
   const result=await api('/api/questions/generate','POST',{topic:aiForm.topic,types:Object.keys(aiForm.types).filter(k=>aiForm.types[k]),count:aiForm.count,difficulty:aiForm.difficulty,extra:aiForm.extra,withTip:aiForm.withTip,skipDuplicates:aiForm.skipDuplicates},90000);
   setAiItems((result.items||[]).map((q:any)=>({...q,include:true})));
   const skipped=result.duplicates?`，跳过 ${result.duplicates} 道重复题`:'';
   toast.success(`生成 ${result.items.length} 题${skipped}`);
  }catch(e:any){setAiError(e.message);}
  finally{setAiLoading(false);}
 }
 const copy=async(value:string)=>{
  const r=await copyText(value);
  if(r==='ok')toast.success('已复制');
  else if(r==='empty')toast.error('没有可复制的内容');
  else toast.error('复制失败，请手动选中文字复制');
 };
 const base=location.origin;
 const agentPrompt=`请给「题了个题」出 ${aiForm.count} 道题，范围：${aiForm.topic||'（填写出题范围）'}。\n只返回 JSON：{"items":[{"type":"single","q":"题干","a":["选项A","选项B"],"k":1,"difficulty":2,"tip":"解析"}]}\ntype 可用 single / multiple（correctIndices）/ boolean（a 固定 ["正确","错误"]，k 0 或 1）/ text（answers）/ subjective（modelAnswer、rubric）。\n难度 1-5，题干不要 HTML。完整字段和示例：https://github.com/chadyi/StudyOnTV/blob/main/docs/question-format.md`;
 const jsonPrompt=agentPrompt+`\n\n写好后再调用：POST ${base}/api/questions\n请求头：Content-Type: application/json、Authorization: Bearer <管理令牌>\n请求体：{"items":[题目数组]}`;
 const aiSelected=aiItems.filter((q:any)=>q.include).length;
 return <Dialog open={open} onOpenChange={v=>{if(!v&&!aiLoading)onOpenChange(false);}}>
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
      <ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>{onOpenChange(false);location.hash='/settings/ai';}}>去设置</Button></ToolbarGroup>
     </Toolbar>
     {aiItems.length===0?<FieldGroup className="gap-4">
      <UiField><FieldLabel htmlFor="ai-topic">出题范围</FieldLabel><Input id="ai-topic" placeholder="例如：五年级数学 分数加减法" value={aiForm.topic} onChange={e=>setAiForm({...aiForm,topic:e.target.value})}/><FieldDescription>可以写教材版本、单元或薄弱点。</FieldDescription></UiField>
      <UiField><FieldLabel>题型</FieldLabel><FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">{Object.entries(labels).map(([k,v])=><UiField key={k} orientation="horizontal" className="w-auto"><Checkbox id={`ai-type-${k}`} checked={!!aiForm.types[k]} onCheckedChange={checked=>setAiForm({...aiForm,types:{...aiForm.types,[k]:checked===true}})}/><FieldLabel htmlFor={`ai-type-${k}`} className="font-normal">{v}</FieldLabel></UiField>)}</FieldGroup></UiField>
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <UiField><FieldLabel htmlFor="ai-count">数量</FieldLabel><ChoiceSelect id="ai-count" label="生成数量" value={String(aiForm.count)} onChange={value=>setAiForm({...aiForm,count:Number(value)})} options={[5,10,15,20].map(n=>[String(n),`${n} 题`])}/></UiField>
       <UiField><FieldLabel htmlFor="ai-difficulty">难度</FieldLabel><ChoiceSelect id="ai-difficulty" label="生成难度" value={aiForm.difficulty} onChange={value=>setAiForm({...aiForm,difficulty:value})} options={[['mixed','混合（由易到难）'],...[1,2,3,4,5].map(n=>[String(n),'全部 '+'★'.repeat(n)] as [string,string])]}/></UiField>
      </FieldGroup>
      <UiField><FieldLabel htmlFor="ai-extra">额外要求（可选）</FieldLabel><Textarea id="ai-extra" rows={2} placeholder="例如：结合生活场景；不要出现负数；每题附一句解析。" value={aiForm.extra} onChange={e=>setAiForm({...aiForm,extra:e.target.value})}/></UiField>
      <FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">
       <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-with-tip" checked={aiForm.withTip} onCheckedChange={checked=>setAiForm({...aiForm,withTip:checked===true})}/><FieldLabel htmlFor="ai-with-tip" className="font-normal">自动生成解析</FieldLabel></UiField>
       <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-skip-duplicates" checked={aiForm.skipDuplicates} onCheckedChange={checked=>setAiForm({...aiForm,skipDuplicates:checked===true})}/><FieldLabel htmlFor="ai-skip-duplicates" className="font-normal">跳过已有题干</FieldLabel></UiField>
      </FieldGroup>
      <Alert><RefreshCw/><AlertDescription>生成结果会先预览，一次最多 20 题。API Key 只保存在设备上。</AlertDescription></Alert>
      {aiError&&<FieldError>{aiError}</FieldError>}
     </FieldGroup>:<>
      <Toolbar className="mb-3 flex-wrap gap-2"><ToolbarGroup><span className="text-xs text-muted-foreground">{aiItems.length} 题待预览</span></ToolbarGroup><ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>setAiItems([])}>重新生成</Button></ToolbarGroup></Toolbar>
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
      <Toolbar className="flex-wrap"><ToolbarGroup><Button size="sm" variant="outline" onClick={()=>copy(agentPrompt)}><Copy/>复制 JSON 指令</Button><Button size="sm" variant="outline" onClick={()=>{onOpenChange(false);onOpenImport();}}>打开导入</Button></ToolbarGroup></Toolbar>
     </UiField>
     <Alert><AlertDescription>管理令牌等同于家长权限，只发给可信的 Agent，不要发到公开群或截图里。修改管理密码后旧令牌立即失效。</AlertDescription></Alert>
    </FieldGroup>}
   </div>
   <DialogFooter className="border-t px-5 py-3">
    {aiTab==='api'&&aiItems.length===0&&<><FieldDescription className="me-auto hidden sm:block">{aiLoading?'正在调用模型…':'生成结果会先预览'}</FieldDescription><Button variant="outline" onClick={()=>onOpenChange(false)} disabled={aiLoading}>取消</Button><Button onClick={generate} disabled={aiLoading}><Sparkles/>{aiLoading?'生成中…':'生成题目'}</Button></>}
    {aiTab==='api'&&aiItems.length>0&&<><Button variant="outline" className="me-auto" onClick={()=>setAiItems([])} disabled={saveGenerated.isPending}>重新生成</Button><Button disabled={!aiSelected||saveGenerated.isPending} onClick={()=>saveGenerated.mutate(aiItems.filter((q:any)=>q.include).map(({include,...q}:any)=>q))}>{saveGenerated.isPending?'保存中…':`保存 ${aiSelected} 题到题库`}</Button></>}
    {aiTab==='agent'&&<Button variant="outline" onClick={()=>onOpenChange(false)}>关闭</Button>}
   </DialogFooter>
  </DialogContent>
 </Dialog>;
}
