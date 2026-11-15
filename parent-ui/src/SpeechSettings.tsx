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
const modes=['system','auto','deepseek','azure','aliyun'];
const modeLabels:Record<string,string>={system:'本地系统语音',auto:'自动',deepseek:'DeepSeek 语音（推荐）',azure:'Azure',aliyun:'阿里云'};
// 每种通路一句话说清区别和前提，家长不用猜为什么点朗读没声音（#128）
const modeHints:Record<string,string>={
 system:'不需要网络，但电视可能没装中文语音包；读不出声音时点下面的「检测本地语音」',
 auto:'有云端 Key 就用云端，没有就用本地系统语音',
 deepseek:'效果最好，需要 DeepSeek Key；电视没有本地语音时选这个',
 azure:'需要 Azure 语音服务的区域和 Key',
 aliyun:'需要阿里云的 AppKey 和 AccessKey',
};
const deepseekVoices:[string,string][]=[['stella','stella（女声）'],['tide','tide（男声）']];
const recognizeModes:[string,string][]=[['system','设备系统识别'],['auto','自动（填了 Key 用 DeepSeek）'],['deepseek','DeepSeek 转写']];
/** #126：设备级朗读语言。空 = 跟随每道题的标注，"system" = 跟随系统语言 */
const readLanguages:[string,string][]=[['auto','跟随每道题的标注'],['system','跟随设备系统语言'],['zh-CN','普通话'],['zh-HK','粤语'],['zh-TW','台湾国语'],['en-US','英语（美式）'],['en-GB','英语（英式）']];
const readLanguageValue=(value:any)=>(readLanguages.some(([v])=>v===value)?value:'auto');
const labels:Record<string,string>={azureRegion:'Azure 区域',azureVoice:'Azure 音色',azureKey:'Azure Key',aliyunAppKey:'阿里云 AppKey',aliyunAccessKeyId:'阿里云 AccessKey ID',aliyunSecret:'阿里云 AccessKey Secret',aliyunVoice:'阿里云音色',deepseekKey:'DeepSeek API Key',deepseekVoice:'DeepSeek 音色',recognizeMode:'语音识别通路'};
const azureFields=['azureRegion','azureVoice','azureKey'],aliyunFields=['aliyunAppKey','aliyunAccessKeyId','aliyunSecret','aliyunVoice'],deepseekFields=['deepseekKey','deepseekVoice'];

function validate(form:any){
 const errors:Record<string,string>={};
 if(!modes.includes(form.mode))errors.mode='请选择有效的朗读通路';
 if(String(form.readLanguage??'')!=='' && readLanguageValue(form.readLanguage)==='auto')errors.readLanguage='请选择有效的朗读语言';
 if(form.recognizeMode&&!recognizeModes.some(([value])=>value===form.recognizeMode))errors.recognizeMode='请选择有效的语音识别通路';
 if(form.deepseekVoice&&!deepseekVoices.some(([value])=>value===form.deepseekVoice))errors.deepseekVoice='请选择有效的 DeepSeek 音色';
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
 const [form,setForm]=useState<any>({mode:'system',autoRead:false}),[dirty,setDirty]=useState(false);
 const [saved,setSaved]=useState(false),[saveError,setSaveError]=useState(''),[failedSignature,setFailedSignature]=useState('');
 // #128 本地语音自检：探测会阻塞设备端几百毫秒到 3 秒，只在点按钮时跑，不进自动保存
 const [probe,setProbe]=useState<any>(null),[probing,setProbing]=useState(false),[testing,setTesting]=useState(false);
 const errors=validate(form),validationError=Object.values(errors)[0]||'';
 const signature=JSON.stringify(form),latestSignature=useRef(signature);
 latestSignature.current=signature;
 // 刷新设置时保留输入框里已经填的 Key：自动保存成功后如果把它擦空，输入慢一点就会被擦掉一半，看起来像「保存不了」
 useEffect(()=>{if(query.data&&!dirty)setForm((old:any)=>({...query.data,azureKey:old.azureKey,aliyunSecret:old.aliyunSecret,deepseekKey:old.deepseekKey}));},[query.data,dirty]);
 const change=(key:string,value:any)=>{setDirty(true);setSaved(false);setSaveError('');setForm((old:any)=>({...old,[key]:value}));};
 const checkTts=async()=>{
  setProbing(true);
  try{
   const [result,engines]=await Promise.all([api('/api/speech/probe'),api('/api/speech/engines')]);
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
  mutationFn:(body:any)=>api('/api/speech','PUT',body),
  onMutate:()=>setSaveError(''),
  onSuccess:(r,body)=>{
   client.setQueryData(['speech',sessionId],r);
   if(latestSignature.current===JSON.stringify(body)){setForm((old:any)=>({...r,azureKey:old.azureKey,aliyunSecret:old.aliyunSecret,deepseekKey:old.deepseekKey}));setDirty(false);setSaved(true);setFailedSignature('');}
  },
  onError:(e:any,body)=>{setSaveError(e.message||'自动保存失败，请重试');setFailedSignature(JSON.stringify(body));}
 });
 useEffect(()=>{
  // 只有「从没读到过设置」时才禁止保存：读到过之后哪怕某次刷新失败，也已经有可信的表单值
  if(!dirty||validationError||!query.data||save.isPending||failedSignature===signature)return;
  const timer=window.setTimeout(()=>save.mutate(form),700);
  return()=>window.clearTimeout(timer);
 },[dirty,validationError,query.data,save.isPending,failedSignature,signature]);
 const saveState:SaveState=save.isPending?'saving':saveError?'error':dirty?(validationError?'error':'pending'):saved?'saved':'idle';
 const azureVisible=form.mode==='auto'||form.mode==='azure',aliyunVisible=form.mode==='auto'||form.mode==='aliyun';
 const deepseekVisible=form.mode==='auto'||form.mode==='deepseek';
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
  <SectionHeading icon={Volume2} title="题目朗读" description="华为、TCL 等电视没有内置朗读，选「DeepSeek 语音」才有声音" action={<SaveStatus state={saveState}/>}/>
  <Card className="gap-0 overflow-hidden py-0 shadow-xs">
  <CardContent className="px-4 sm:px-5">
   <ItemGroup className="divide-y divide-border/60">
    <Item size="sm" className="rounded-none px-0 py-4">
     <ItemContent><ItemTitle>自动朗读题目</ItemTitle></ItemContent>
     <ItemActions><Switch aria-label="自动朗读题目" checked={!!form.autoRead} onCheckedChange={value=>change('autoRead',value)}/></ItemActions>
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
    {deepseekVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">{input('deepseekKey','DeepSeek API Key（platform.deepseek.com）',true)}
     <Field data-invalid={!!errors.deepseekVoice}>
      <FieldLabel htmlFor="speech-deepseekVoice">DeepSeek 音色</FieldLabel>
      <Select value={form.deepseekVoice||'stella'} onValueChange={value=>change('deepseekVoice',value)}>
       <SelectTrigger id="speech-deepseekVoice" aria-label="DeepSeek 音色" className="w-full"><SelectValue>{deepseekVoices.find(([value])=>value===(form.deepseekVoice||'stella'))?.[1]}</SelectValue></SelectTrigger>
       <SelectContent>{deepseekVoices.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
      <FieldDescription className="text-xs">不填 Key 时会借用「AI 批改」里同一个 DeepSeek Key；按字符计费，1 万字约 1.5 元。</FieldDescription>
      <FieldError>{errors.deepseekVoice}</FieldError>
     </Field>
    </div>}
    {azureVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">{input('azureRegion','Azure 区域，例如 eastasia')}{input('azureVoice','Azure 音色（可留空）')}{input('azureKey','Azure Key',true)}</div>}
    {aliyunVisible&&<div className="grid gap-4 py-4 sm:grid-cols-2">{input('aliyunAppKey','阿里云 AppKey')}{input('aliyunAccessKeyId','阿里云 AccessKey ID')}{input('aliyunSecret','阿里云 AccessKey Secret',true)}{input('aliyunVoice','阿里云音色，例如 xiaoyun')}</div>}
    {hiddenErrors.length>0&&<div className="py-3"><FieldError>切回对应朗读通路后修正：{hiddenErrors.map(([key,message])=>`${labels[key]||key}：${message}`).join('；')}</FieldError></div>}
    {((!query.data&&query.isError)||saveError)&&<div className="flex flex-wrap items-center justify-between gap-2 py-3">
     <FieldError>{!query.data?'无法读取朗读设置，password改。':saveError}</FieldError>
     {/* 读不到设置时不能拿默认值去覆盖设备，只能重新拉；存失败则原样重试一次 */}
     <Button type="button" variant="outline" size="sm" onClick={()=>{setSaveError('');setFailedSignature('');if(!query.data)query.refetch();else save.mutate(form);}}>重试</Button>
    </div>}
   </ItemGroup>
   <div className="flex justify-end gap-2 border-t py-3">
    <Button type="button" variant="outline" size="sm" disabled={save.isPending} onClick={()=>{if(confirm('清除已保存的朗读服务密钥？')){setDirty(true);setSaved(false);setSaveError('');setFailedSignature('');setForm((old:any)=>({...old,clearKeys:true,azureKey:'',aliyunSecret:'',deepseekKey:''}));}}}>清除密钥</Button>
   </div>
  </CardContent>
 </Card>
 </section>;
}
