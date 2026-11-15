import {useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Globe,RefreshCw,X} from 'lucide-react';
import {toast} from 'sonner';
import {Alert,AlertDescription} from '@/components/ui/alert';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Dialog,DialogClose,DialogContent,DialogHeader,DialogTitle} from '@/components/ui/dialog';
import {FieldGroup} from '@/components/ui/field';
import {Input} from '@/components/ui/input';
import {api} from '@/api';
import {normalizeBank} from '@/question-import';
import {Count} from './shared';

/** 在线题库市场：读共享题库索引，点一下就下载并导入本机题库 */
export default function BankMarketDialog({open,onOpenChange,sessionId}:{
 open:boolean;
 onOpenChange:(open:boolean)=>void;
 sessionId:number;
}){
 const client=useQueryClient();
 const [sourceUrl,setSourceUrl]=useState('');
 const [addingBank,setAddingBank]=useState('');
 const [addedBanks,setAddedBanks]=useState<string[]>([]);
 // 只在打开对话框时拉一次索引，别耽误题库页加载
 const market=useQuery({queryKey:['market',sessionId],queryFn:()=>api('/api/banks/market','GET',undefined,30000),enabled:open,staleTime:5*60*1000,retry:false});
 const sourceAdd=useMutation({mutationFn:(payload:any)=>api('/api/banks/sources','POST',payload),onSuccess:()=>{setSourceUrl('');client.invalidateQueries({queryKey:['market',sessionId]});toast.success('在线题库已添加');},onError:(e:any)=>toast.error(e.message)});
 const sourceRemove=useMutation({mutationFn:(url:string)=>api('/api/banks/sources','DELETE',{url}),onSuccess:()=>{client.invalidateQueries({queryKey:['market',sessionId]});toast.success('在线题库已移除');},onError:(e:any)=>toast.error(e.message)});
 async function addBank(bank:any){
  if(addingBank)return;
  setAddingBank(bank.url);
  try{
   const fetched=await api('/api/banks/fetch','POST',{url:bank.url},30000);
   const result=normalizeBank(fetched.data);
   if(!result.items.length){
    const first=result.issues[0];
    throw new Error(first?`没有可导入的题目：第 ${first.index} 题：${first.reason}`:'这个题库是空的');
   }
   const name=String(bank.name||'').trim()||'默认题库';
   const saved=await api('/api/questions','POST',{items:result.items.map((q:any)=>({...q,bank:q.bank||name})),skipDuplicates:true},60000);
   setAddedBanks(list=>[...list,bank.url]);
   client.invalidateQueries({queryKey:['bank']});client.invalidateQueries({queryKey:['bank-meta']});
   toast.success(saved.skipped?`已添加 ${saved.added} 题，跳过 ${saved.skipped} 道重复题`:`已添加 ${saved.added} 题`);
  }catch(error:any){toast.error(error.message||'添加失败');}
  finally{setAddingBank('');}
 }
 return <Dialog open={open} onOpenChange={onOpenChange}>
  <DialogContent showCloseButton={false} className="grid max-h-[90dvh] w-[calc(100%-1.5rem)] grid-rows-[auto_minmax(0,1fr)] gap-0 overflow-hidden p-0 sm:max-w-2xl">
   <DialogHeader className="relative border-b px-5 py-4 pe-24">
    <DialogTitle className="flex items-center gap-2"><Globe className="size-4 text-primary"/>在线题库</DialogTitle>
    <div className="absolute end-2 top-2 flex items-center gap-1">
     <Button type="button" variant="ghost" size="icon" aria-label="刷新题库" disabled={market.isFetching} onClick={()=>market.refetch()}><RefreshCw className={market.isFetching?'animate-spin':''}/></Button>
     <DialogClose asChild><Button type="button" variant="ghost" size="icon" aria-label="关闭"><X/></Button></DialogClose>
    </div>
   </DialogHeader>
   <div className="min-h-0 overflow-y-auto px-5 py-4">
    <FieldGroup className="gap-4">
     {market.isPending&&<p className="text-sm text-muted-foreground">正在拉取题库列表…</p>}
     {market.isError&&<Alert variant="destructive"><AlertDescription>拉取失败：{(market.error as any)?.message||'请检查电视的网络'}</AlertDescription></Alert>}
     {(market.data?.banks||[]).map((bank:any)=>{
      const added=addedBanks.includes(bank.url),busy=addingBank===bank.url;
      return <div key={bank.url} className="flex items-start justify-between gap-3 rounded-md border p-3">
       <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
         <span className="truncate text-sm font-medium">{bank.name}</span>
         {bank.count?<Count value={bank.count} suffix="题"/>:null}
         {bank.level?<Badge variant="outline">{bank.level}</Badge>:null}
         {bank.license?<Badge variant="outline">{bank.license}</Badge>:null}
        </div>
        <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{bank.description||bank.subject||'暂无简介'}</p>
        <p className="mt-1 truncate text-xs text-muted-foreground/70">{[bank.author&&`作者：${bank.author}`,bank.source&&`来自 ${bank.source}`].filter(Boolean).join(' · ')}</p>
       </div>
       <Button size="sm" variant={added?'outline':'secondary'} disabled={added||!!addingBank} onClick={()=>addBank(bank)}>{busy?'添加中…':added?'已添加':'添加'}</Button>
      </div>;
     })}
     {market.data&&!(market.data.banks||[]).length&&!market.isFetching&&<p className="text-sm text-muted-foreground">这里还没有题库。</p>}
     {(market.data?.failed||[]).map((item:any)=><Alert key={item.source}><AlertDescription>{item.source} 拉取失败：{item.message}</AlertDescription></Alert>)}
    </FieldGroup>
    {(market.data?.sources||[]).filter((source:any)=>!source.official).length>0&&<div className="mt-4 flex flex-wrap gap-2">
     {(market.data?.sources||[]).filter((source:any)=>!source.official).map((source:any)=><Badge key={source.url} variant="secondary" className="gap-1 py-1">
      {source.name}
      <button type="button" aria-label={`移除 ${source.name}`} className="text-muted-foreground hover:text-foreground" disabled={sourceRemove.isPending} onClick={()=>sourceRemove.mutate(source.url)}><X className="size-3"/></button>
     </Badge>)}
    </div>}
    <div className="mt-4 flex items-center gap-2 border-t pt-3">
     <Input className="min-w-0 flex-1" aria-label="题库索引地址" value={sourceUrl} onChange={e=>setSourceUrl(e.target.value)} placeholder="https://…/index.json"/>
     <Button size="sm" variant="outline" disabled={sourceAdd.isPending||!sourceUrl.trim()} onClick={()=>sourceAdd.mutate({name:'',url:sourceUrl.trim()})}>{sourceAdd.isPending?'添加中…':'添加源'}</Button>
    </div>
   </div>
  </DialogContent>
 </Dialog>;
}
