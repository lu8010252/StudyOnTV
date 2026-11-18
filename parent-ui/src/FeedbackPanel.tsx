import {useEffect,useRef,useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import {MessageCircleHeart} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Card,CardContent} from '@/components/ui/card';
import {SectionHeading} from '@/components/ui/section-heading';
import {SaveStatus,type SaveState} from '@/components/ui/save-status';
import {Field,FieldDescription,FieldError,FieldGroup,FieldLabel,FieldLegend,FieldSet} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {Item,ItemActions,ItemContent,ItemDescription,ItemGroup,ItemTitle} from '@/components/ui/item';
import {Textarea} from '@/components/ui/textarea';
import {api} from './api';

const lines=(v:string)=>v.split('\n').map(s=>s.trim()).filter(Boolean);
const phraseError=(items:string[])=>{
 const long=items.findIndex(s=>s.length>40);
 if(long>=0)return `第 ${long+1} 句超过 40 个字`;
 return items.length>30?'最多填写 30 句':'';
};
const themes=[
 {id:'dark',name:'深色',note:'推荐 · 适合晚上和客厅',bg:'#141414',card:'#222222',text:'#E8E8E8',focus:'#2A2A2A',ring:'rgba(229,229,229,.72)'},
 {id:'light',name:'浅色',note:'适合白天明亮的房间',bg:'#F4F4F4',card:'#FFFFFF',text:'#1E1E1E',focus:'#FFFFFF',ring:'rgba(30,30,30,.5)'},
 {id:'system',name:'跟随系统',note:'跟随电视的深色模式设置',bg:'linear-gradient(135deg,#141414 50%,#F4F4F4 50%)',card:'#999999',text:'#999999',focus:'#B3B3B3',ring:'rgba(120,120,120,.7)'}];

/** 答题反馈（参考多邻国）：答完在题目下方显示结果和鼓励语，孩子按“继续”进入下一题 */
export default function FeedbackPanel({state,sessionId}:{state:any,sessionId:number}){
 const client=useQueryClient();
 const config=state?.config;
 const [praise,setPraise]=useState(''),[encourage,setEncourage]=useState(''),[auto,setAuto]=useState('0'),[dirty,setDirty]=useState(false);
 const [saved,setSaved]=useState(false),[saveError,setSaveError]=useState(''),[failedSignature,setFailedSignature]=useState('');
 const praiseItems=lines(praise),encourageItems=lines(encourage);
 const seconds=Number(auto);
 const autoError=auto.trim()===''||!Number.isInteger(seconds)||seconds<0||seconds>30?'请输入 0–30 的整数':'';
 const praiseError=phraseError(praiseItems),encourageError=phraseError(encourageItems);
 const valid=!autoError&&!praiseError&&!encourageError;
 const payload={praisePhrases:praiseItems,encouragePhrases:encourageItems,autoNextSeconds:seconds};
 const signature=JSON.stringify(payload);
 const latestSignature=useRef(signature);
 latestSignature.current=signature;
 useEffect(()=>{if(config&&!dirty){setPraise((config.praisePhrases||[]).join('\n'));setEncourage((config.encouragePhrases||[]).join('\n'));setAuto(String(config.autoNextSeconds??0));}},[config]);
 const theme=useMutation({mutationFn:(id:string)=>api('/api/config','PUT',{theme:id}),onSuccess:()=>{client.invalidateQueries({queryKey:['state',sessionId]});toast.success('外观已应用，电视画面几秒内会更新');},onError:(e:any)=>toast.error(e.message)});
 const save=useMutation({
  mutationFn:(body:typeof payload)=>api('/api/config','PUT',body),
  onMutate:()=>setSaveError(''),
  onSuccess:(_,body)=>{
   if(latestSignature.current===JSON.stringify(body)){setDirty(false);setSaved(true);setFailedSignature('');}
   client.invalidateQueries({queryKey:['state',sessionId]});
  },
  onError:(e:any,body)=>{setSaveError(e.message||'自动保存失败，请重试');setFailedSignature(JSON.stringify(body));}
 });
 useEffect(()=>{
  if(!dirty||!valid||save.isPending||failedSignature===signature)return;
  const timer=window.setTimeout(()=>save.mutate(payload),700);
  return()=>window.clearTimeout(timer);
 },[dirty,valid,save.isPending,failedSignature,signature]);
 const change=(setter:(value:string)=>void,value:string)=>{setDirty(true);setSaved(false);setSaveError('');setter(value);};
 function restoreDefaults(){setDirty(true);setSaved(false);setSaveError('');setPraise('');setEncourage('');}
 const saveState:SaveState=save.isPending?'saving':saveError?'error':dirty?(valid?'pending':'error'):saved?'saved':'idle';
 const current=['dark','light','system'].includes(config?.theme)?config.theme:'dark';

 return <section className="space-y-2">
  <SectionHeading icon={MessageCircleHeart} title="界面与反馈" action={<SaveStatus state={saveState}/>}/>
  <Card className="gap-0 overflow-hidden py-0 shadow-xs">
  <CardContent className="px-4 sm:px-5">
   <ItemGroup className="divide-y divide-border/60">
    <Item size="sm" className="flex-col items-stretch rounded-none px-0 py-4">
     <FieldSet className="gap-3">
     <FieldLegend variant="label" className="mb-0">电视学习页外观</FieldLegend>
     <div className="grid grid-cols-3 gap-2">
      {themes.map(t=>{
       const on=current===t.id;
       return <Button key={t.id} type="button" variant={on?'secondary':'outline'} aria-pressed={on} disabled={theme.isPending} onClick={()=>theme.mutate(t.id)} className="h-auto min-w-0 flex-col items-stretch justify-start gap-1.5 whitespace-normal rounded-lg p-1.5 text-left">
        <div className="flex h-14 w-full flex-col justify-between rounded-md p-2 sm:h-16" style={{background:t.bg}}>
         <div className="h-1.5 w-8 rounded-full" style={{background:t.text,opacity:.85}}/>
         <div className="flex gap-1"><div className="h-4 flex-1 rounded-sm sm:h-5" style={{background:t.focus,boxShadow:`inset 0 0 0 2px ${t.ring}`}}/><div className="h-4 flex-1 rounded-sm sm:h-5" style={{background:t.card}}/></div>
        </div>
        <div className="w-full px-0.5 pb-0.5"><div className="text-xs font-medium">{t.name}{on&&' ✓'}</div><div className="line-clamp-1 text-xs leading-4 text-muted-foreground">{t.note}</div></div>
       </Button>;
      })}
     </div>
    </FieldSet>
    </Item>
    <Item size="sm" className="rounded-none px-0 py-4" data-invalid={!!autoError}>
     <ItemContent>
      <ItemTitle>进入下一题</ItemTitle>
      <ItemDescription className="text-xs">0 表示由孩子手动选择“继续”。</ItemDescription>
      <FieldError>{autoError}</FieldError>
     </ItemContent>
     <div className="flex items-center gap-2"><Input aria-label="自动下一题秒数" aria-invalid={!!autoError} className="h-8 w-16 text-center" type="number" min={0} max={30} value={auto} onChange={e=>change(setAuto,e.target.value)}/><span className="text-xs text-muted-foreground">秒后自动</span></div>
    </Item>
    <div className="grid gap-4 py-4 sm:grid-cols-2">
      <Field data-invalid={!!praiseError}>
       <FieldLabel htmlFor="feedback-praise">答对时（每行一句，随机显示）</FieldLabel>
       <Textarea id="feedback-praise" aria-invalid={!!praiseError} className="min-h-24 resize-y" rows={4} value={praise} onChange={e=>change(setPraise,e.target.value)}/>
       <FieldError>{praiseError}</FieldError>
      </Field>
      <Field data-invalid={!!encourageError}>
       <FieldLabel htmlFor="feedback-encourage">答错时（之后会显示正确答案）</FieldLabel>
       <Textarea id="feedback-encourage" aria-invalid={!!encourageError} className="min-h-24 resize-y" rows={4} value={encourage} onChange={e=>change(setEncourage,e.target.value)}/>
       <FieldError>{encourageError}</FieldError>
      </Field>
    </div>
    {saveError&&<div className="py-3"><FieldError>{saveError}</FieldError></div>}
   </ItemGroup>
   <div className="flex justify-end gap-2 border-t py-3">
    <Button type="button" variant="ghost" size="sm" disabled={save.isPending} onClick={restoreDefaults}>恢复默认</Button>
   </div>
  </CardContent>
 </Card>
 </section>;
}
