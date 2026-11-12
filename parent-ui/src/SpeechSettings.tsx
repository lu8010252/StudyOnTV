import {useEffect,useRef,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Volume2} from 'lucide-react';
import {api} from './api';
import {Button} from '@/components/ui/button';
import {Card,CardContent,CardDescription,CardFooter,CardHeader,CardTitle} from '@/components/ui/card';
import {Field,FieldDescription,FieldError,FieldGroup,FieldLabel} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Item,ItemActions,ItemContent,ItemDescription,ItemGroup,ItemTitle} from '@/components/ui/item';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {Switch} from '@/components/ui/switch';

const modes=['system','auto','azure','aliyun'];
/** #126：设备级朗读语言。空 = 跟随每道题的标注，"system" = 跟随系统语言 */
const readLanguages:[string,string][]=[['auto','跟随每道题的标注'],['system','跟随设备系统语言'],['zh-CN','普通话'],['zh-HK','粤语'],['zh-TW','台湾国语'],['en-US','英语（美式）'],['en-GB','英语（英式）']];
const readLanguageValue=(value:any)=>(readLanguages.some(([v])=>v===value)?value:'auto');
const labels:Record<string,string>={azureRegion:'Azure 区域',azureVoice:'Azure 音色',azureKey:'Azure Key',aliyunAppKey:'阿里云 AppKey',aliyunAccessKeyId:'阿里云 AccessKey ID',aliyunSecret:'阿里云 AccessKey Secret',aliyunVoice:'阿里云音色'};
const azureFields=['azureRegion','azureVoice','azureKey'],aliyunFields=['aliyunAppKey','aliyunAccessKeyId','aliyunSecret','aliyunVoice'];

function validate(form:any){
 const errors:Record<string,string>={};
 if(!modes.includes(form.mode))errors.mode='请选择有效的朗读通路';
 if(String(form.readLanguage??'')!=='' && readLanguageValue(form.readLanguage)==='auto')errors.readLanguage='请选择有效的朗读语言';
 for(const key of [...azureFields,...aliyunFields]){
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
 const errors=validate(form),validationError=Object.values(errors)[0]||'';
 const signature=JSON.stringify(form),latestSignature=useRef(signature);
 latestSignature.current=signature;
 useEffect(()=>{if(query.data&&!dirty)setForm({...query.data,azureKey:'',aliyunSecret:''});},[query.data,dirty]);
 const change=(key:string,value:any)=>{setDirty(true);setSaved(false);setSaveError('');setForm((old:any)=>({...old,[key]:value}));};
 const save=useMutation({
  mutationFn:(body:any)=>api('/api/speech','PUT',body),
  onMutate:()=>setSaveError(''),
  onSuccess:(r,body)=>{
   client.setQueryData(['speech',sessionId],r);
   if(latestSignature.current===JSON.stringify(body)){setForm({...r,azureKey:'',aliyunSecret:''});setDirty(false);setSaved(true);setFailedSignature('');}
  },
  onError:(e:any,body)=>{setSaveError(e.message||'自动保存失败，请重试');setFailedSignature(JSON.stringify(body));}
 });
 useEffect(()=>{
  if(!dirty||validationError||query.isError||save.isPending||failedSignature===signature)return;
  const timer=window.setTimeout(()=>save.mutate(form),700);
  return()=>window.clearTimeout(timer);
 },[dirty,validationError,query.isError,save.isPending,failedSignature,signature]);
 const status=save.isPending?'正在保存…':saveError?'保存失败':dirty?(validationError?'请修正错误':'等待自动保存'):saved?'已自动保存':'更改后自动保存';
 const azureVisible=form.mode==='auto'||form.mode==='azure',aliyunVisible=form.mode==='auto'||form.mode==='aliyun';
 const hiddenErrors=Object.entries(errors).filter(([key])=>!((azureFields.includes(key)&&azureVisible)||(aliyunFields.includes(key)&&aliyunVisible)||key==='mode'));
 const input=(key:string,label:string,password=false)=>{
  const error=errors[key],id=`speech-${key}`;
  return <Field key={key} data-invalid={!!error}>
   <FieldLabel htmlFor={id}>{label}</FieldLabel>
   <Input id={id} aria-label={label} aria-invalid={!!error} type={password?'password':'text'} autoComplete="off" value={form[key]||''} placeholder={password&&form[key+'Set']?'留空保留原值':''} onChange={e=>change(key,e.target.value)}/>
   {password&&form[key+'Set']&&<FieldDescription className="text-xs">密钥已保存，留空会保留原值。</FieldDescription>}
   <FieldError>{error}</FieldError>
  </Field>;
 };

 return <Card className="gap-0 overflow-hidden py-0 shadow-xs">
  <CardHeader className="gap-1.5 px-4 py-4 sm:px-6">
   <CardTitle className="flex items-center gap-2 text-base"><span className="grid size-8 place-items-center rounded-lg bg-muted text-muted-foreground"><Volume2 className="size-4"/></span>题目朗读<span aria-live="polite" className="ms-auto text-xs font-normal text-muted-foreground">{status}</span></CardTitle>
   <CardDescription className="text-xs">自动或手动朗读；云端失败时回落到系统语音。</CardDescription>
  </CardHeader>
  <CardContent className="border-t p-0">
   <ItemGroup className="divide-y divide-border/60">
    <Item size="sm" className="rounded-none px-4 py-4 sm:px-6">
     <ItemContent><ItemTitle>自动朗读题目</ItemTitle><ItemDescription className="text-xs">进入新题目时自动播放题干。</ItemDescription></ItemContent>
     <ItemActions><Switch aria-label="自动朗读题目" checked={!!form.autoRead} onCheckedChange={value=>change('autoRead',value)}/></ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-4 py-4 sm:px-6" data-invalid={!!errors.readLanguage}>
     <ItemContent><ItemTitle>朗读语言</ItemTitle><ItemDescription className="text-xs">系统是粤语 / 繁体时选「跟随设备系统语言」，题目就不用逐条修改。</ItemDescription><FieldError>{errors.readLanguage}</FieldError></ItemContent>
     <ItemActions>
      <Select value={readLanguageValue(form.readLanguage)} onValueChange={value=>change('readLanguage',value==='auto'?'':value)}>
       <SelectTrigger aria-label="朗读语言" aria-invalid={!!errors.readLanguage} className="w-full sm:w-44"><SelectValue>{readLanguages.find(([value])=>value===readLanguageValue(form.readLanguage))?.[1]}</SelectValue></SelectTrigger>
       <SelectContent>{readLanguages.map(([value,label])=><SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
      </Select>
     </ItemActions>
    </Item>
    <Item size="sm" className="rounded-none px-4 py-4 sm:px-6" data-invalid={!!errors.mode}>
     <ItemContent><ItemTitle>朗读通路</ItemTitle><ItemDescription className="text-xs">自动选择优先使用已配置的云端密钥。</ItemDescription><FieldError>{errors.mode}</FieldError></ItemContent>
     <ItemActions>
     <Select value={form.mode} onValueChange={value=>change('mode',value)}>
      <SelectTrigger aria-label="朗读通路" aria-invalid={!!errors.mode} className="w-full sm:w-44"><SelectValue>{{system:'设备系统语音',auto:'自动选择',azure:'Azure',aliyun:'阿里云'}[form.mode as 'system'|'auto'|'azure'|'aliyun']||'请选择'}</SelectValue></SelectTrigger>
      <SelectContent><SelectItem value="system">设备系统语音</SelectItem><SelectItem value="auto">自动选择</SelectItem><SelectItem value="azure">Azure</SelectItem><SelectItem value="aliyun">阿里云</SelectItem></SelectContent>
     </Select>
     </ItemActions>
    </Item>
    {azureVisible&&<div className="grid gap-4 px-4 py-4 sm:grid-cols-2 sm:px-6">{input('azureRegion','Azure 区域，例如 eastasia')}{input('azureVoice','Azure 音色（可留空）')}{input('azureKey','Azure Key',true)}</div>}
    {aliyunVisible&&<div className="grid gap-4 px-4 py-4 sm:grid-cols-2 sm:px-6">{input('aliyunAppKey','阿里云 AppKey')}{input('aliyunAccessKeyId','阿里云 AccessKey ID')}{input('aliyunSecret','阿里云 AccessKey Secret',true)}{input('aliyunVoice','阿里云音色，例如 xiaoyun')}</div>}
    {hiddenErrors.length>0&&<div className="px-4 py-3 sm:px-6"><FieldError>切回对应朗读通路后修正：{hiddenErrors.map(([key,message])=>`${labels[key]||key}：${message}`).join('；')}</FieldError></div>}
    {(query.isError||saveError)&&<div className="px-4 py-3 sm:px-6"><FieldError>{query.isError?'无法读取朗读设置，请重试。':saveError}</FieldError></div>}
   </ItemGroup>
  </CardContent>
  <CardFooter className="justify-end gap-2 border-t px-4 py-3 sm:px-6">
   <Button type="button" variant="outline" size="sm" disabled={save.isPending} onClick={()=>{if(confirm('清除已保存的朗读服务密钥？')){setDirty(true);setSaved(false);setSaveError('');setFailedSignature('');setForm((old:any)=>({...old,clearKeys:true,azureKey:'',aliyunSecret:''}));}}}>清除密钥</Button>
  </CardFooter>
 </Card>;
}
