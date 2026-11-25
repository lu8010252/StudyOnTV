import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {BookOpen,ClipboardCopy,Copy,ExternalLink,FileJson,RefreshCw,Sparkles,Target} from 'lucide-react';
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
import {api,getHost} from '@/api';
import {copyText} from '@/lib/clipboard';
import {ChoiceSelect,MathText,Stars,answerOf,labels} from './shared';

export type AiGeneratePreset={
 fromWrongBook?:boolean;
 subject?:string;
 level?:string;
 bank?:string;
 topic?:string;
 extra?:string;
};

const PROMPT_TEMPLATES:{label:string;subject:string;topic:string;extra:string}[]=[
 {label:'易错计算与应用题',subject:'数学',topic:'混合运算、简便计算与生活场景应用题',extra:'结合常见粗心陷阱设计干扰项，每题附一步步解题思路。'},
 {label:'古诗词理解与默写',subject:'语文',topic:'必背古诗词名句默写、字词释义与意境理解',extra:'包含易写错别字辨析与重点字释义。'},
 {label:'英语高频词汇与语法',subject:'英语',topic:'日常高频词汇辨析、时态与固定搭配',extra:'贴近日常对话语境，解析注明语法点与例句翻译。'},
 {label:'阅读理解推理',subject:'语文',topic:'短文细节定位、主旨概括与逻辑推理判断',extra:'题干给出一小段趣味短文或情境，考察信息提取与推理。'},
 {label:'科学常识与实验',subject:'科学',topic:'自然现象原理、生活安全常识与基础实验探究',extra:'贴近生活现象，解释背后的科学原理。'},
 {label:'考前综合摸底',subject:'',topic:'本学期核心必考知识点综合摸底自测',extra:'由易到难梯度分布，重点考察高频易错点并附详细解析。'},
];

function resolveAgentBaseUrl():string{
 const raw=getHost();
 if(/^https?:\/\//i.test(raw))return raw;
 const target=raw||(typeof location!=='undefined'?location.host:'127.0.0.1:8099');
 const proto=typeof location!=='undefined'&&(!raw||raw===location.host)&&location.protocol==='https:'?'https:':'http:';
 return `${proto}//${target}`;
}

/** AI 出题：支持「错题举一反三」与「按考点模板出题」，也可交给外部 Agent；生成结果先预览再入库 */
export default function AiGenerateDialog({open,onOpenChange,sessionId,onOpenImport,initialPreset}:{
 open:boolean;
 onOpenChange:(open:boolean)=>void;
 sessionId:number;
 onOpenImport?:()=>void;
 initialPreset?:AiGeneratePreset|null;
}){
 const client=useQueryClient();
 const [aiTab,setAiTab]=useState<'api'|'agent'>(initialPreset?'api':'api');
 const [aiForm,setAiForm]=useState({
  fromWrongBook:false,
  topic:'',
  subject:'',
  level:'',
  bank:'默认题库',
  types:{single:true,multiple:true,boolean:true,text:true,subjective:false} as Record<string,boolean>,
  count:10,
  difficulty:'mixed',
  extra:'',
  withTip:true,
  skipDuplicates:true,
 });
 const [aiLoading,setAiLoading]=useState(false);
 const [aiItems,setAiItems]=useState<any[]>([]);
 const [aiError,setAiError]=useState('');
 const grading=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading'),enabled:open});
 const metaQuery=useQuery({queryKey:['bank-meta',sessionId],queryFn:()=>api('/api/questions/meta'),enabled:open,staleTime:30000});
 const meta=metaQuery.data||{banks:[],subjects:[],levels:[],wrong:0};
 const configured=!!(grading.data?.endpoint&&grading.data?.model&&(grading.data?.hasKey||grading.data?.provider==='ollama'||grading.data?.provider==='custom'));
 const aiProvider=(grading.data?.providers||[]).find((p:any)=>p.id===grading.data?.provider)?.name||'AI';

 useEffect(()=>{
  if(!open)return;
  setAiError('');
  setAiItems([]);
  if(initialPreset){
   setAiTab('api');
   setAiForm(prev=>({
    ...prev,
    fromWrongBook:initialPreset.fromWrongBook??false,
    subject:initialPreset.subject??prev.subject,
    level:initialPreset.level??prev.level,
    bank:initialPreset.bank||(initialPreset.fromWrongBook?'错题巩固题库':prev.bank),
    topic:initialPreset.topic??'',
    extra:initialPreset.extra??'',
   }));
  }
 },[open,initialPreset]);

 const saveGenerated=useMutation({
  mutationFn:(items:any[])=>api('/api/questions','POST',{items}),
  onSuccess:(r:any)=>{
   client.invalidateQueries({queryKey:['bank']});
   client.invalidateQueries({queryKey:['bank-meta']});
   client.invalidateQueries({queryKey:['analytics']});
   toast.success(`已保存 ${r.added} 题到题库`);
   setAiItems([]);onOpenChange(false);
  },
  onError:(e:any)=>toast.error(e.message),
 });

 async function generate(){
  if(!configured)return toast.error('请先点击上方「去设置」配置模型 API');
  if(!aiForm.fromWrongBook&&!aiForm.topic.trim())return toast.error('请填写出题范围，或点击上方模板一键填入');
  if(!Object.values(aiForm.types).some(Boolean))return toast.error('至少选择一种题型');
  setAiLoading(true);setAiError('');
  try{
   const fallbackSubject=aiForm.subject.trim();
   const fallbackLevel=aiForm.level.trim();
   const fallbackBank=aiForm.bank.trim()||'默认题库';
   const result=await api('/api/questions/generate','POST',{
    fromWrongBook:aiForm.fromWrongBook,
    topic:aiForm.topic.trim(),
    subject:fallbackSubject,
    level:fallbackLevel,
    bank:fallbackBank,
    types:Object.keys(aiForm.types).filter(k=>aiForm.types[k]),
    count:aiForm.count,
    difficulty:aiForm.difficulty,
    extra:aiForm.extra,
    withTip:aiForm.withTip,
    skipDuplicates:aiForm.skipDuplicates,
   },90000);
   setAiItems((result.items||[]).map((q:any)=>({
    ...q,
    subject:q.subject||fallbackSubject,
    level:q.level||fallbackLevel,
    bank:q.bank||fallbackBank,
    include:true,
   })));
   const skipped=result.duplicates?`，跳过 ${result.duplicates} 道重复题`:'';
   toast.success(`生成 ${(result.items||[]).length} 题${skipped}`);
  }catch(e:any){setAiError(e.message);}
  finally{setAiLoading(false);}
 }

 const copy=async(value:string)=>{
  const r=await copyText(value);
  if(r==='ok')toast.success('已复制');
  else if(r==='empty')toast.error('没有可复制的内容');
  else toast.error('复制失败，请手动选中文字复制');
 };

 const base=resolveAgentBaseUrl();
 const scopeDesc=[aiForm.level.trim(),aiForm.subject.trim(),aiForm.topic.trim()].filter(Boolean).join(' · ')||'（填写出题范围）';
 const agentPrompt=`请给「题了个题」出 ${aiForm.count} 道题，范围：${scopeDesc}${aiForm.fromWrongBook?'（针对孩子近期易错点做同考点变式巩固）':''}。\n只返回 JSON：{"items":[{"type":"single","q":"题干","a":["选项A","选项B"],"k":1,"difficulty":2,"subject":"${aiForm.subject.trim()||'数学'}","level":"${aiForm.level.trim()||'三年级'}","bank":"${aiForm.bank.trim()||'默认题库'}","tip":"解析"}]}\ntype 可用 single / multiple（correctIndices）/ boolean（a 固定 ["正确","错误"]，k 0 或 1）/ text（answers）/ subjective（modelAnswer、rubric）。\n难度 1-5，题干不要 HTML。完整字段和示例：https://github.com/chadyi/StudyOnTV/blob/main/docs/question-format.md`;
 const jsonPrompt=agentPrompt+`\n\n写好后再调用：POST ${base}/api/questions\n请求头：Content-Type: application/json、Authorization: Bearer <管理令牌>\n请求体：{"items":[题目数组]}`;
 const aiSelected=aiItems.filter((q:any)=>q.include).length;

 return <Dialog open={open} onOpenChange={v=>{if(!v&&!aiLoading)onOpenChange(false);}}>
  <DialogContent className="grid max-h-[90dvh] grid-rows-[auto_auto_minmax(0,1fr)_auto] overflow-hidden p-0 sm:max-w-3xl">
   <DialogHeader className="border-b px-5 py-4">
    <DialogTitle className="flex items-center gap-2"><Sparkles className="size-4 text-primary"/>AI 智能出题与错题变式</DialogTitle>
   </DialogHeader>
   <Tabs value={aiTab} onValueChange={value=>setAiTab(value as 'api'|'agent')} className="border-b px-5 py-2">
    <TabsList className="w-full justify-start sm:w-auto">
     <TabsTrigger value="api">使用模型 API 一键生成</TabsTrigger>
     <TabsTrigger value="agent">外部 Agent 出题</TabsTrigger>
    </TabsList>
   </Tabs>
   <div className="min-h-0 overflow-y-auto px-5 py-4">
    {aiTab==='api'?<>
     <Toolbar className="mb-4 flex-wrap gap-2">
      <ToolbarGroup className="min-w-0">
       <Badge variant={configured?'secondary':'outline'}>{configured?'已配置':'未配置'}</Badge>
       <span className="truncate text-xs text-muted-foreground">{configured?`${aiProvider} · ${grading.data?.model}`:'尚未配置模型 API，点击右侧去设置即可使用'}</span>
      </ToolbarGroup>
      <ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>{onOpenChange(false);location.hash='/settings/ai';}}>去设置</Button></ToolbarGroup>
     </Toolbar>
     {aiItems.length===0?<FieldGroup className="gap-4">
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
       <button
        type="button"
        onClick={()=>setAiForm(f=>({...f,fromWrongBook:true,bank:f.bank==='默认题库'?'错题巩固题库':f.bank}))}
        className={`flex flex-col items-start gap-1 rounded-xl border p-3.5 text-left transition-colors ${aiForm.fromWrongBook?'border-primary bg-primary/5 ring-1 ring-primary/30':'border-border/70 hover:bg-muted/40'}`}
       >
        <div className="flex w-full items-center justify-between gap-2">
         <span className="flex items-center gap-1.5 text-sm font-semibold"><Target className="size-4 text-primary"/>🎯 错题举一反三</span>
         <Badge variant="secondary" className="text-[11px]">提分推荐{meta.wrong?` · ${meta.wrong} 错题`:''}</Badge>
        </div>
        <span className="text-xs leading-5 text-muted-foreground">无需手写提示词，自动提取孩子近期错题生成同考点变式巩固题</span>
       </button>
       <button
        type="button"
        onClick={()=>setAiForm(f=>({...f,fromWrongBook:false}))}
        className={`flex flex-col items-start gap-1 rounded-xl border p-3.5 text-left transition-colors ${!aiForm.fromWrongBook?'border-primary bg-primary/5 ring-1 ring-primary/30':'border-border/70 hover:bg-muted/40'}`}
       >
        <div className="flex w-full items-center justify-between gap-2">
         <span className="flex items-center gap-1.5 text-sm font-semibold"><BookOpen className="size-4 text-primary"/>📚 按考点 / 模板出题</span>
         <Badge variant="outline" className="text-[11px]">10 秒上手</Badge>
        </div>
        <span className="text-xs leading-5 text-muted-foreground">点选常用学科模板或输入单元知识点，快速生成同步练习题</span>
       </button>
      </div>

      {!aiForm.fromWrongBook&&(
       <UiField>
        <FieldLabel>快捷出题模板（点击一键填入）</FieldLabel>
        <div className="flex flex-wrap gap-1.5">
         {PROMPT_TEMPLATES.map(tpl=>(
          <Button
           key={tpl.label}
           type="button"
           size="sm"
           variant="outline"
           className="h-7 rounded-full px-3 text-xs"
           onClick={()=>setAiForm(f=>({
            ...f,
            subject:tpl.subject||f.subject,
            topic:f.level?`${f.level}${tpl.subject} ${tpl.topic}`:`${tpl.subject?tpl.subject+' ':''}${tpl.topic}`,
            extra:tpl.extra,
           }))}
          >
           {tpl.label}
          </Button>
         ))}
        </div>
       </UiField>
      )}

      <FieldGroup className="grid grid-cols-1 gap-3 sm:grid-cols-3">
       <UiField>
        <FieldLabel htmlFor="ai-subject">{aiForm.fromWrongBook?'限定错题科目（可选）':'科目'}</FieldLabel>
        <Input id="ai-subject" list="ai-meta-subjects" placeholder={aiForm.fromWrongBook?'全部科目错题':'例如：数学'} value={aiForm.subject} onChange={e=>setAiForm({...aiForm,subject:e.target.value})}/>
        <datalist id="ai-meta-subjects">{(meta.subjects||[]).map((v:string)=><option key={v} value={v}/>)}</datalist>
       </UiField>
       <UiField>
        <FieldLabel htmlFor="ai-level">年级</FieldLabel>
        <Input id="ai-level" list="ai-meta-levels" placeholder="例如：五年级" value={aiForm.level} onChange={e=>setAiForm({...aiForm,level:e.target.value})}/>
        <datalist id="ai-meta-levels">{(meta.levels||[]).map((v:string)=><option key={v} value={v}/>)}</datalist>
       </UiField>
       <UiField>
        <FieldLabel htmlFor="ai-bank">存入题库</FieldLabel>
        <Input id="ai-bank" list="ai-meta-banks" placeholder="默认题库" value={aiForm.bank} onChange={e=>setAiForm({...aiForm,bank:e.target.value})}/>
        <datalist id="ai-meta-banks">{(meta.banks||[]).map((v:string)=><option key={v} value={v}/>)}</datalist>
       </UiField>
      </FieldGroup>

      <UiField>
       <FieldLabel htmlFor="ai-topic">{aiForm.fromWrongBook?'补充焦点考点（可选，留空自动按错题分析）':'出题范围 / 考点'}</FieldLabel>
       <Input id="ai-topic" placeholder={aiForm.fromWrongBook?'留空则自动读取近期错题考点，也可补充如：重点练分数应用题':'例如：五年级数学 异分母分数加减法与简便运算'} value={aiForm.topic} onChange={e=>setAiForm({...aiForm,topic:e.target.value})}/>
       <FieldDescription>{aiForm.fromWrongBook?'设备会自动挑选近期答错过的题目作为参考原型，生成换数字/换场景的同考点新题。':'可以写教材版本、单元名称或薄弱知识点。'}</FieldDescription>
      </UiField>

      <UiField><FieldLabel>题型</FieldLabel><FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">{Object.entries(labels).map(([k,v])=><UiField key={k} orientation="horizontal" className="w-auto"><Checkbox id={`ai-type-${k}`} checked={!!aiForm.types[k]} onCheckedChange={checked=>setAiForm({...aiForm,types:{...aiForm.types,[k]:checked===true}})}/><FieldLabel htmlFor={`ai-type-${k}`} className="font-normal">{v}</FieldLabel></UiField>)}</FieldGroup></UiField>
      <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
       <UiField><FieldLabel htmlFor="ai-count">数量</FieldLabel><ChoiceSelect id="ai-count" label="生成数量" value={String(aiForm.count)} onChange={value=>setAiForm({...aiForm,count:Number(value)})} options={[5,10,15,20].map(n=>[String(n),`${n} 题`])}/></UiField>
       <UiField><FieldLabel htmlFor="ai-difficulty">难度</FieldLabel><ChoiceSelect id="ai-difficulty" label="生成难度" value={aiForm.difficulty} onChange={value=>setAiForm({...aiForm,difficulty:value})} options={[['mixed','混合（由易到难）'],...[1,2,3,4,5].map(n=>[String(n),'全部 '+'★'.repeat(n)] as [string,string])]}/></UiField>
      </FieldGroup>
      <UiField><FieldLabel htmlFor="ai-extra">额外要求（可选）</FieldLabel><Textarea id="ai-extra" rows={2} placeholder="例如：结合生活场景；不要出现超纲负数；每题附通俗易懂的分步解析。" value={aiForm.extra} onChange={e=>setAiForm({...aiForm,extra:e.target.value})}/></UiField>
      <FieldGroup data-slot="checkbox-group" className="flex flex-row flex-wrap gap-4">
       <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-with-tip" checked={aiForm.withTip} onCheckedChange={checked=>setAiForm({...aiForm,withTip:checked===true})}/><FieldLabel htmlFor="ai-with-tip" className="font-normal">自动生成解析</FieldLabel></UiField>
       <UiField orientation="horizontal" className="w-auto"><Checkbox id="ai-skip-duplicates" checked={aiForm.skipDuplicates} onCheckedChange={checked=>setAiForm({...aiForm,skipDuplicates:checked===true})}/><FieldLabel htmlFor="ai-skip-duplicates" className="font-normal">跳过已有题干</FieldLabel></UiField>
      </FieldGroup>
      <Alert><RefreshCw/><AlertDescription>生成结果会先预览，一次最多 20 题。API Key 只保存在设备上。</AlertDescription></Alert>
      {aiError&&<FieldError>{aiError}</FieldError>}
     </FieldGroup>:<>
      <Toolbar className="mb-3 flex-wrap gap-2"><ToolbarGroup><span className="text-xs text-muted-foreground">{aiItems.length} 题待预览 · 将存入「{aiForm.bank.trim()||'默认题库'}」</span></ToolbarGroup><ToolbarGroup className="ms-auto"><Button size="sm" variant="ghost" onClick={()=>setAiItems([])}>重新生成</Button></ToolbarGroup></Toolbar>
      <FieldGroup className="gap-2.5">
       {aiItems.map((q:any,i:number)=>{
        const choiceOpts=['single','multiple'].includes(q.type)&&Array.isArray(q.a)?q.a:[];
        const correctSet=new Set<number>(q.type==='multiple'?(q.correctIndices||[]):typeof q.k==='number'?[q.k]:[]);
        return <UiField key={i} orientation="horizontal" className="items-start rounded-md border p-3">
         <Checkbox className="mt-1" checked={q.include} onCheckedChange={checked=>setAiItems(list=>list.map((x,j)=>j===i?{...x,include:checked===true}:x))}/>
         <div className="min-w-0 flex-1 space-y-1.5">
          <Toolbar className="flex-wrap gap-1.5">
           <ToolbarGroup>
            <Badge variant="secondary">{labels[q.type]||'单选'}</Badge>
            <Stars n={q.difficulty||2}/>
            {q.subject&&<span className="text-xs text-muted-foreground">{q.subject}</span>}
            {q.level&&<span className="text-xs text-muted-foreground">· {q.level}</span>}
           </ToolbarGroup>
          </Toolbar>
          <MathText text={q.q} className="whitespace-pre-wrap text-sm font-medium leading-6"/>
          {choiceOpts.length>0&&(
           <div className="grid grid-cols-1 gap-1 pt-0.5 sm:grid-cols-2">
            {choiceOpts.map((opt:string,oi:number)=>{
             const isRight=correctSet.has(oi);
             return <div key={oi} className={`flex items-start gap-1.5 rounded border px-2 py-1 text-xs ${isRight?'border-emerald-500/40 bg-emerald-500/10 font-medium text-foreground':'border-border/60 bg-muted/20 text-muted-foreground'}`}>
              <span className="shrink-0 font-mono">{String.fromCharCode(65+oi)}.</span>
              <MathText text={opt} className="min-w-0 flex-1 whitespace-pre-wrap"/>
             </div>;
            })}
           </div>
          )}
          <MathText text={'答案：'+answerOf(q)} className="text-xs font-medium text-emerald-700 dark:text-emerald-400"/>
          {q.tip&&<MathText text={'解析：'+q.tip} className="whitespace-pre-wrap text-xs leading-5 text-muted-foreground"/>}
         </div>
        </UiField>;
       })}
      </FieldGroup>
     </>}
    </>:<FieldGroup className="gap-5">
     <UiField>
      <FieldTitle className="flex items-center gap-2"><ClipboardCopy className="size-4 text-primary"/>方式一：让 Agent 直接写入题库</FieldTitle>
      <FieldDescription>把出题指令发给能访问局域网、会调用工具的 Agent，它完成出题后直接写入题库。</FieldDescription>
      <div className="flex flex-wrap gap-1.5">{['WorkBuddy','Codex','千问办公','豆包','Kimi','智谱清言','文心一言','腾讯元宝'].map(name=><Badge key={name} variant="outline">{name}</Badge>)}</div>
      <Alert><AlertDescription>以上指桌面客户端或命令行工具，不是网页对话版。网页版通常无法访问本机局域网或写入题库。</AlertDescription></Alert>
      <ol className="list-decimal space-y-1 ps-4 text-sm leading-6 text-muted-foreground"><li>确认 Agent 和被控设备（{base}）在同一局域网</li><li>复制出题指令，连同管理令牌一起发给 Agent</li><li>Agent 写入后回到这里刷新题库</li></ol>
      <Toolbar className="flex-wrap"><ToolbarGroup><Button size="sm" onClick={()=>copy(jsonPrompt)}><Copy/>复制出题指令</Button><Button size="sm" variant="outline" onClick={()=>copy('https://github.com/chadyi/StudyOnTV/blob/main/docs/ai-api.md')}><ExternalLink/>接口说明</Button></ToolbarGroup></Toolbar>
     </UiField>
     <UiField>
      <FieldTitle className="flex items-center gap-2"><FileJson className="size-4 text-primary"/>方式二：只返回 JSON，再由我导入</FieldTitle>
      <FieldDescription>不把管理令牌交给 Agent。让它按格式输出 JSON，再粘贴或选择文件导入。</FieldDescription>
      <Toolbar className="flex-wrap"><ToolbarGroup><Button size="sm" variant="outline" onClick={()=>copy(agentPrompt)}><Copy/>复制 JSON 指令</Button>{onOpenImport&&<Button size="sm" variant="outline" onClick={()=>{onOpenChange(false);onOpenImport();}}>打开导入</Button>}</ToolbarGroup></Toolbar>
     </UiField>
     <Alert><AlertDescription>管理令牌等同于家长权限，只发给可信的 Agent，不要发到公开群或截图里。修改管理密码后旧令牌立即失效。</AlertDescription></Alert>
    </FieldGroup>}
   </div>
   <DialogFooter className="border-t px-5 py-3">
    {aiTab==='api'&&aiItems.length===0&&<><FieldDescription className="me-auto hidden sm:block">{!configured?'请先点击上方「去设置」配置模型 API':aiLoading?'正在调用模型生成题目…':aiForm.fromWrongBook?'将自动分析近期错题生成巩固变式题':'生成结果会先预览'}</FieldDescription><Button variant="outline" onClick={()=>onOpenChange(false)} disabled={aiLoading}>取消</Button><Button onClick={generate} disabled={aiLoading||!configured}><Sparkles/>{aiLoading?'生成中…':aiForm.fromWrongBook?'一键生成错题变式':'生成题目'}</Button></>}
    {aiTab==='api'&&aiItems.length>0&&<><Button variant="outline" className="me-auto" onClick={()=>setAiItems([])} disabled={saveGenerated.isPending}>重新生成</Button><Button disabled={!aiSelected||saveGenerated.isPending} onClick={()=>{const fbBank=aiForm.bank.trim()||'默认题库',fbSubj=aiForm.subject.trim(),fbLvl=aiForm.level.trim();saveGenerated.mutate(aiItems.filter((q:any)=>q.include).map(({include,...q}:any)=>({...q,bank:q.bank||fbBank,subject:q.subject||fbSubj,level:q.level||fbLvl})));}}>{saveGenerated.isPending?'保存中…':`保存 ${aiSelected} 题到题库`}</Button></>}
    {aiTab==='agent'&&<Button variant="outline" onClick={()=>onOpenChange(false)}>关闭</Button>}
   </DialogFooter>
  </DialogContent>
 </Dialog>;
}
