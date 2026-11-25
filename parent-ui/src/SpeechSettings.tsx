import {useEffect,useRef,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Volume2} from 'lucide-react';
import {api} from './api';
import {Button} from '@/components/ui/button';
import {Card,CardContent} from '@/components/ui/card';
import {SectionHeading} from '@/components/ui/section-heading';
import {SaveStatus,type SaveState} from '@/components/ui/save-status';
import {Field,FieldDescription,FieldError,FieldGroup,FieldLabel} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Item,ItemActions,ItemContent,ItemDescription,ItemGroup,ItemTitle} from '@/components/ui/item';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {Switch} from '@/components/ui/switch';

// deepseek：DeepSeek 语音合成。老电视（华为智慧屏、TCL 等）常常没有内置朗读引擎，选这个才有声音
const modes=['system','edge','auto','deepseek','azure','aliyun'];
const modeLabels:Record<string,string>={system:'本地系统语音',edge:'微软 Edge 语音（免费）',auto:'自动',deepseek:'DeepSeek 语音（推荐）',azure:'Azure',aliyun:'阿里云'};
// 每种通路一句话说清区别和前提，家长不用猜为什么点朗读没声音（#128）
const modeHints:Record<string,string>={
 system:'不需要网络，但电视可能没装中文语音包；读不出声音时点下面的「检测本地语音」',
 edge:'免费在线朗读（无需 API Key），失败会自动回落系统语音；该通路依赖微软在线接口，部分网络可能 403，建议同时安装本地中文语音包或配置 DeepSeek 语音',
 auto:'有云端 Key 就用云端；没有 Key 时优先用本地语音，无本地语音则自动用免费 Edge 语音',
 deepseek:'效果最好，需要 DeepSeek Key；电视没有本地语音时选这个',
 azure:'需要 Azure 语音服务的区域和 Key',
 aliyun:'需要阿里云的 AppKey 和 AccessKey',
};
const speechRates:[string,string][]=[['0.8','0.8x（较慢）'],['1','1.0x（正常）'],['1.2','1.2x（较快）'],['1.4','1.4x（快速）']];
const edgeVoices:[string,string][]=[['auto','按题目语言自动选择'],['zh-CN-XiaoxiaoNeural','晓晓（普通话女声）'],['zh-CN-YunxiNeural','云希（普通话男声）'],['zh-CN-YunjianNeural','云健（普通话解说）'],['zh-CN-XiaoyiNeural','晓伊（普通话童声/女声）'],['zh-HK-HiuGaaiNeural','晓佳（粤语女声）'],['zh-TW-HsiaoChenNeural','晓臻（台湾女声）'],['en-US-JennyNeural','Jenny（美式英语）'],['en-GB-SoniaNeural','Sonia（英式英语）']];
const deepseekVoices:[string,string][]=[['stella','stella（女声）'],['tide','tide（男声）']];
const recognizeModes:[string,string][]=[['system','设备系统识别'],['auto','自动（填了 Key 用 DeepSeek）'],['deepseek','DeepSeek 转写']];
/** #126：设备级朗读语言。空 = 跟随每道题的标注，"system" = 跟随系统语言 */
const readLanguages:[string,string][]=[['auto','跟随每道题的标注'],['system','跟随设备系统语言'],['zh-CN','普通话'],['zh-HK','粤语'],['zh-TW','台湾国语'],['en-US','英语（美式）'],['en-GB','英语（英式）']];
const readLanguageValue=(value:any)=>(readLanguages.some(([v])=>v===value)?value:'auto');
/** 朗读内容：只读题干、题干加选项、再加正确答案（答案在作答之后再读，作答前不剧透） */
const readScopes:[string,string][]=[['question','只读题目'],['questionOptions','题目和选项'],['questionOptionsAnswer','题目、选项和答案']];
const readScopeValue=(value:any)=>(readScopes.some(([v])=>v===value)?value:'question');
const labels:Record<string,string>={azureRegion:'Azure 区域',azureVoice:'Azure 音色',azureKey:'Azure Key',aliyunAppKey:'阿里云 AppKey',aliyunAccessKeyId:'阿里云 AccessKey ID',aliyunSecret:'阿里云 AccessKey Secret',aliyunVoice:'阿里云音色',deepseekKey:'DeepSeek API Key',deepseekVoice:'DeepSeek 音色',recognizeMode:'语音识别通路'};
const azureFields=['azureRegion','azureVoice','azureKey'],aliyunFields=['aliyunAppKey','aliyunAccessKeyId','aliyunSecret','aliyunVoice'],deepseekFields=['deepseekKey','deepseekVoice'];

function validate(form:any){
 const errors:Record<string,string>={};
 if(!modes.includes(form.mode))errors.mode='请选择有效的朗读通路';
 if(String(form.readLanguage??'')!=='' && readLanguageValue(form.readLanguage)==='auto')errors.readLanguage='请选择有效的朗读语言';
 if(form.recognizeMode&&!recognizeModes.some(([value])=>value===form.recognizeMode))errors.recognizeMode='请选择有效的语音识别通路';
 if(form.deepseekVoice&&!deepseekVoices.some(([value])=>value===form.deepseekVoice))errors.deepseekVoice='请选择有效的 DeepSeek 音色';
 if(form.readScope&&!readScopes.some(([value])=>value===form.readScope))errors.readScope='请选择有效的朗读内容';
 for(const key of [...azureFields,...aliyunFields,...deepseekFields]){
  const limit=key==='azureKey'||key==='aliyunSecret'?500:200;
  if(String(form[key]??'').length>limit)errors[key]=`${labels[key]}不能超过 ${limit} 个字符`;
 }
 if(!/^[a-z0-9-]*$/.test(String(form.azureRegion??'').trim()))errors.azureRegion='Azure 区域只能包含小写字母、数字和连字符';
 return errors;
}

export default function SpeechSettings({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['speech',sessionId],queryFn:()=>api('/api/speech')});
 const [form,setForm]=useState<any>({mode:'auto',autoRead:false}),[dirty,setDirty]=useState(false);
 const [saved,setSaved]=useState(false),[saveError,setSaveError]=useState(''),[failedSignature,setFailedSignature]=useState('');
 const [customDeepseekKey,setCustomDeepseekKey]=useState(false);
 // #128 本地语音自检：探测会阻塞设备端几百毫秒到 3 秒，只在点按钮时跑，不进自动保存
 const [probe,setProbe]=useState<any>(null),[probing,setProbing]=useState(false),[testing,setTesting]=useState(false);
 const errors=validate(form),validationError=Object.values(errors)[0]||'';
 const signature=JSON.stringify(form),latestSignature=useRef(signature);
 latestSignature.current=signature;
 const dirtyRef=useRef(dirty);
 dirtyRef.current=dirty;
 // 刷新设置时保留输入框里已经填的 Key：自动保存成功后如果把它擦空，输入慢一点就会被擦掉一半，看起来像「保存不了」
 useEffect(()=>{if(query.data&&!dirty)setForm((old:any)=>({...query.data,azureKey:old.azureKey,aliyunSecret:old.aliyunSecret,deepseekKey:old.deepseekKey}));},[query.data,dirty]);
 const change=(key:string,value:any)=>{setDirty(true);setSaved(false);setSaveError('');setForm((old:any)=>({...old,[key]:value}));};
 const checkTts=async()=>{
  setProbing(true);
  try{
   const probeUrl=`/api/speech/probe?language=${encodeURIComponent(form.readLanguage||'')}`;
   const [result,engines]=await Promise.all([api(probeUrl),api('/api/speech/engines')]);
   setProbe({...result,engines:engines?.engines||[]});
  }catch(e:any){setProbe({ok:false,hint:'检测失败：'+(e?.message||'请重试')});}
  finally{setProbing(false);}
 };
 const testSpeak=async()=>{
  setTesting(true);
  try{
   const result=await api('/api/speech/test','POST',{text:'今天天气不错'});
   const ok=result?.ok!==false&&result?.reason!=='engine-unavailable';
   setProbe((old:any)=>({...old,testOk:ok,testHint:ok?'已让电视读一句：听到声音就说明这条通路能用':`朗读失败：${result?.message||result?.reason||'未知原因'}`}));
  }catch(e:any){setProbe((old:any)=>({...old,testOk:false,testHint:'测试失败：'+(e?.message||'请重试')}));}
  finally{setTesting(false);}
 };
 const installVoice=async()=>{
  try{
   const result=await api('/api/speech/install-voice','POST',{});
   if(!result?.ok)setProbe((old:any)=>({...old,hint:'这台电视没有「安装语音数据」入口，请到 设置 → 无障碍 → 文字转语音 里安装中文语音包'}));
  }catch(e:any){setProbe((old:any)=>({...old,hint:'打开安装页面失败：'+(e?.message||'请到系统设置里安装')}));}
 };
 const save=useMutation({
  mutationFn:(body:any)=>{const {clearKeys:_c,...payload}=body||{};return api('/api/speech','PUT',payload);},
  onMutate:()=>setSaveError(''),
  onSuccess:(r,body)=>{
   client.setQueryData(['speech',sessionId],r);
   if(latestSignature.current===JSON.stringify(body)){setForm((old:any)=>({...r,azureKey:old.azureKey,aliyunSecret:old.aliyunSecret,deepseekKey:old.deepseekKey}));setDirty(false);setSaved(true);setFailedSignature('');}
  },
  onError:(e:any,body)=>{setSaveError(e.message||'自动保存失败，请重试');setFailedSignature(JSON.stringify(body));}
 });
 const clearKeys=useMutation({
  mutationFn:()=>api('/api/speech','PUT',{clearKeys:true}),
  onMutate:()=>{
   setSaveError('');setSaved(false);setFailedSignature('');setDirty(false);
   setForm((old:any)=>{const {clearKeys:_c,...rest}=old||{};return {...rest,azureKey:'',aliyunSecret:'',deepseekKey:''};});
  },
  onSuccess:r=>{
   client.setQueryData(['speech',sessionId],r);
   if(!dirtyRef.current){
    setForm((old:any)=>({...r,azureKey:old.azureKey,aliyunSecret:old.aliyunSecret,deepseekKey:old.deepseekKey}));
    setSaved(true);
   }else{
    setForm((old:any)=>({...old,azureKeySet:r.azureKeySet,aliyunSecretSet:r.aliyunSecretSet,deepseekKeySet:r.deepseekKeySet,deepseekInherited:r.deepseekInherited}));
   }
  },
  onError:(e:any)=>setSaveError(e?.message||'清除密钥失败，请重试'),
 });
 const busy=save.isPending||clearKeys.isPending;
 useEffect(()=>{
  // 只有「从没读到过设置」时才禁止保存：读到过之后哪怕某次刷新失败，也已经有可信的表单值
  if(!dirty||validationError||!query.data||busy||failedSignature===signature)return;
  const timer=window.setTimeout(()=>save.mutate(form),700);
  return()=>window.clearTimeout(timer);
 },[dirty,validationError,query.data,busy,failedSignature,signature]);
 const saveState:SaveState=busy?'saving':saveError?'error':dirty?(validationError?'error':'pending'):saved?'saved':'idle';
 const edgeVisible=form.mode==='edge',azureVisible=form.mode==='azure',aliyunVisible=form.mode==='aliyun';
 const deepseekVisible=form.mode==='deepseek';
 const hiddenErrors=Object.entries(errors).filter(([key])=>!((azureFields.includes(key)&&azureVisible)||(aliyunFields.includes(key)&&aliyunVisible)||(deepseekFields.includes(key)&&deepseekVisible)||key==='mode'||key==='recognizeMode'));
 const input=(key:string,label:string,password=false)=>{
  const error=errors[key],id=`speech-${key}`;
  return <Field key={key} data-invalid={!!error}>
   <FieldLabel htmlFor={id}>{label}</FieldLabel>
   <Input id={id} aria-label={label} aria-invalid={!!error} type={password?'password':'text'} autoComplete="off" value={form[key]||''} placeholder={password&&form[key+'Set']?'留空保留原值':''} onChange={e=>change(key,e.target.value)}/>
   {password&&form[key+'Set']&&<FieldDescription className="text-xs">密钥已保存，留空会保留原值。</FieldDescription>}
   <FieldError>{error}</FieldError>
  </Field>;
 };

 return <section className="space-y-2">
  <SectionHeading icon={Volume2} title="题目朗读" description="支持本地系统语音、免费 Edge 语音与云端语音；无内置语音的电视选「自动」或「DeepSeek 语音」即可" action={<SaveStatus state={saveState}/>}/>
  <Card className="gap-0 overflow-hidden py-0 shadow-xs">
  <CardContent className="px-4 sm:px-5">
   <ItemGroup className="divide-y divide-border/60">
    <Item size="sm" className="rounded-none px-0 py-4" id="setting-auto-read">
     <ItemContent><ItemTitle>自动朗读题目</ItemTitle></ItemContent>
     <ItemActions><Switch aria-label="自动朗读题目" checked={!!form.autoRead} onCheckedChange={value=>change('autoRead',value)}/></ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-0 py-4" id="setting-read-scope" data-invalid={!!errors.readScope}>
     <ItemContent><ItemTitle>朗读内容</ItemTitle><ItemDescription className="text-xs">选「和答案」时，答案在作答之后再读，作答前不会剧透。</ItemDescription><FieldError>{errors.readScope}</FieldError></ItemContent>
     <ItemActions>
      <Select value={readScopeValue(form.readScope)} onValueChange={value=>change('readScope',value)}>
       <SelectTrigger aria-label="朗读内容" aria-invalid={!!errors.readScope} className="w-full sm:w-44"><SelectValue>{readScopes.find(([value])=>value===readScopeValue(form.readScope))?.[1]}</SelectValue></SelectTrigger>
       <SelectContent>{readScopes.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
     </ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-0 py-4" data-invalid={!!errors.readLanguage}>
     <ItemContent><ItemTitle>朗读语言</ItemTitle><ItemDescription className="text-xs">粤语 / 繁体系统选「跟随设备系统语言」。</ItemDescription><FieldError>{errors.readLanguage}</FieldError></ItemContent>
     <ItemActions>
      <Select value={readLanguageValue(form.readLanguage)} onValueChange={value=>change('readLanguage',value==='auto'?'':value)}>
       <SelectTrigger aria-label="朗读语言" aria-invalid={!!errors.readLanguage} className="w-full sm:w-44"><SelectValue>{readLanguages.find(([value])=>value===readLanguageValue(form.readLanguage))?.[1]}</SelectValue></SelectTrigger>
       <SelectContent>{readLanguages.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
     </ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-0 py-4" data-invalid={!!errors.mode}>
     <ItemContent><ItemTitle>朗读通路</ItemTitle><ItemDescription className="text-xs">{modeHints[form.mode]||'自动优先使用已配置的云端密钥。'}</ItemDescription><FieldError>{errors.mode}</FieldError></ItemContent>
     <ItemActions>
     <Select value={form.mode} onValueChange={value=>change('mode',value)}>
      <SelectTrigger aria-label="朗读通路" aria-invalid={!!errors.mode} className="w-full sm:w-44"><SelectValue>{modeLabels[form.mode]||'请选择'}</SelectValue></SelectTrigger>
      <SelectContent>{modes.map(value=><SelectItem key={value} value={value}>{modeLabels[value]}</SelectItem>)}</SelectContent>
     </Select>
     </ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-0 py-4">
     <ItemContent><ItemTitle>朗读语速</ItemTitle><ItemDescription className="text-xs">适用于本地系统语音与免费 Edge 语音。</ItemDescription></ItemContent>
     <ItemActions>
      <Select value={String(form.speechRate??1)} onValueChange={value=>change('speechRate',Number(value))}>
       <SelectTrigger aria-label="朗读语速" className="w-full sm:w-44"><SelectValue>{speechRates.find(([v])=>Number(v)===(Number(form.speechRate)||1))?.[1]||'1.0x（正常）'}</SelectValue></SelectTrigger>
       <SelectContent>{speechRates.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
     </ItemActions>
    </Item>
    <div className="space-y-2 py-4">
     <div className="flex flex-wrap items-center gap-2">
      <span className="text-sm font-medium">本地语音自检</span>
      <Button type="button" variant="outline" size="sm" disabled={probing} onClick={checkTts}>{probing?'检测中…':'检测本地语音'}</Button>
      <Button type="button" variant="outline" size="sm" disabled={testing} onClick={testSpeak}>{testing?'朗读中…':'测试朗读'}</Button>
      {probe?.langStatus===-1&&<Button type="button" variant="outline" size="sm" onClick={installVoice}>去安装语言包</Button>}
     </div>
     <p className="text-xs leading-5 text-muted-foreground">
      电视上点了朗读没声音时先点这里：能分清是没装语音引擎、缺中文语言包，还是引擎不支持这种语言。
     </p>
     {probe?.hint&&<p className={`text-xs leading-5 ${probe.ok?'text-emerald-600':'text-amber-600'}`}>{probe.ok?'✓ ':'⚠ '}{probe.hint}</p>}
     {probe?.testHint&&<p className={`text-xs leading-5 ${probe.testOk?'text-emerald-600':'text-amber-600'}`}>{probe.testHint}</p>}
     {probe?.engines?.length>0&&<details className="text-xs text-muted-foreground">
      <summary>已装的语音引擎（{probe.engines.length} 个）</summary>
      <ul className="mt-1 space-y-0.5">
       {probe.engines.map((engine:any)=><li key={engine.name}>{engine.label}{engine.default?'（默认）':''}<span className="ml-1 opacity-60">{engine.name}</span></li>)}
      </ul>
     </details>}
    </div>
    <Item size="sm" className="rounded-none px-0 py-4" data-invalid={!!errors.recognizeMode}>
     <ItemContent><ItemTitle>语音识别通路</ItemTitle><ItemDescription className="text-xs">电视上答口语题时用。华为、TCL 这类电视常常没有系统识别，填了 DeepSeek Key 会自动用它。</ItemDescription><FieldError>{errors.recognizeMode}</FieldError></ItemContent>
     <ItemActions>
     <Select value={form.recognizeMode||'system'} onValueChange={value=>change('recognizeMode',value)}>
      <SelectTrigger aria-label="语音识别通路" aria-invalid={!!errors.recognizeMode} className="w-full sm:w-44"><SelectValue>{recognizeModes.find(([value])=>value===(form.recognizeMode||'system'))?.[1]}</SelectValue></SelectTrigger>
      <SelectContent>{recognizeModes.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
     </Select>
     </ItemActions>
    </Item>
    {form.mode==='auto'&&<div className="py-3 text-xs leading-5 text-muted-foreground">
     自动模式：已配置 DeepSeek Key 时自动使用 DeepSeek 语音，否则优先本地系统语音，无本地语音时自动回落免费 Edge 语音。如需单独配置云端密钥，请切换上方朗读通路。
    </div>}
    {edgeVisible&&<div className="py-4">
     <Field>
      <FieldLabel htmlFor="speech-edgeVoice">Edge 音色</FieldLabel>
      <Select value={form.edgeVoice||'auto'} onValueChange={value=>change('edgeVoice',value==='auto'?'':value)}>
       <SelectTrigger id="speech-edgeVoice" aria-label="Edge 音色" className="w-full sm:w-64"><SelectValue>{edgeVoices.find(([value])=>value===(form.edgeVoice||'auto'))?.[1]||'按题目语言自动选择'}</SelectValue></SelectTrigger>
       <SelectContent>{edgeVoices.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
      <FieldDescription className="text-xs">直接连接微软 Edge 免费朗读服务，无需配置密钥；网络异常时自动回落本地语音。</FieldDescription>
     </Field>
    </div>}
    {deepseekVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">
     {form.deepseekInherited&&!customDeepseekKey?(
      <Field>
       <FieldLabel>DeepSeek API Key</FieldLabel>
       <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-xs text-emerald-700 dark:text-emerald-400">
        <span>✓ 已自动共用「模型 API」中的 DeepSeek Key，无需重复填写</span>
        <button type="button" className="underline underline-offset-2 opacity-80 hover:opacity-100" onClick={()=>setCustomDeepseekKey(true)}>单独覆盖</button>
       </div>
       <FieldDescription className="text-xs">需使用 DeepSeek 官方 Key（platform.deepseek.com）且账户有余额；第三方中转站不支持语音接口。</FieldDescription>
      </Field>
     ):input('deepseekKey','DeepSeek API Key（与「模型 API」共用）',true)}
     <Field data-invalid={!!errors.deepseekVoice}>
      <FieldLabel htmlFor="speech-deepseekVoice">DeepSeek 音色</FieldLabel>
      <Select value={form.deepseekVoice||'stella'} onValueChange={value=>change('deepseekVoice',value)}>
       <SelectTrigger id="speech-deepseekVoice" aria-label="DeepSeek 音色" className="w-full"><SelectValue>{deepseekVoices.find(([value])=>value===(form.deepseekVoice||'stella'))?.[1]}</SelectValue></SelectTrigger>
       <SelectContent>{deepseekVoices.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
      <FieldDescription className="text-xs">已与「模型 API」统一共用同一份 DeepSeek Key（只填一次即可）；按字符计费，1 万字约 1.5 元。</FieldDescription>
      <FieldError>{errors.deepseekVoice}</FieldError>
     </Field>
    </div>}
    {azureVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">{input('azureRegion','Azure 区域，例如 eastasia')}{input('azureVoice','Azure 音色（可留空）')}{input('azureKey','Azure Key',true)}</div>}
    {aliyunVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">{input('aliyunAppKey','阿里云 AppKey')}{input('aliyunAccessKeyId','阿里云 AccessKey ID')}{input('aliyunSecret','阿里云 AccessKey Secret',true)}{input('aliyunVoice','阿里云音色，例如 xiaoyun')}</div>}
    {hiddenErrors.length>0&&<div className="py-3"><FieldError>切回对应朗读通路后修正：{hiddenErrors.map(([key,message])=>`${labels[key]||key}：${message}`).join('；')}</FieldError></div>}
    {((!query.data&&query.isError)||saveError)&&<div className="flex flex-wrap items-center justify-between gap-2 py-3">
     <FieldError>{!query.data?'无法读取朗读设置，暂时不能修改。':saveError}</FieldError>
     {/* 读不到设置时不能拿默认值去覆盖设备，只能重新拉；存失败则原样重试一次 */}
     <Button type="button" variant="outline" size="sm" disabled={busy} onClick={()=>{setSaveError('');setFailedSignature('');if(!query.data)query.refetch();else if(clearKeys.isError&&!dirty)clearKeys.mutate();else save.mutate(form);}}>重试</Button>
    </div>}
   </ItemGroup>
   <div className="flex justify-end gap-2 border-t py-3">
    <Button type="button" variant="outline" size="sm" disabled={busy||!query.data} onClick={()=>{if(confirm('清除已保存的朗读服务密钥？'))clearKeys.mutate();}}>{clearKeys.isPending?'清除中…':'清除密钥'}</Button>
   </div>
  </CardContent>
 </Card>
 </section>;
}
