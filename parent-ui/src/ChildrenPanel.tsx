import {useState} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Users,Plus,Check,Pencil,Trash2} from 'lucide-react';
import {toast} from 'sonner';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {api} from './api';
import {Panel} from './RulesPage';

/**
 * 多孩子档案（#132）。
 *
 * 每个孩子各自一套：学习规则、学习进度 / 复习进度、奖励与免费时长、使用时长与成长记录。
 * 题库、朗读设置、AI / 批改配置是全家共用的。
 * 电视端在「开始答题」那一屏会列出所有孩子，孩子自己点名字开始，不必每次来找家长切换。
 */
export default function ChildrenPanel({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['children',sessionId],queryFn:()=>api('/api/children')});
 const [adding,setAdding]=useState('');
 const [rename,setRename]=useState<{id:string,name:string}|null>(null);
 const [busy,setBusy]=useState('');
 const items:any[]=query.data?.items||[];
 const refresh=()=>{
  client.invalidateQueries({queryKey:['children',sessionId]});
  client.invalidateQueries({queryKey:['state',sessionId]});
  client.invalidateQueries({queryKey:['records',sessionId]});
 };
 const run=async(key:string,path:string,method:'POST'|'PUT',body:any,message:string)=>{
  setBusy(key);
  try{await api(path,method,body);refresh();toast.success(message);return true;}
  catch(e:any){toast.error(e.message||'操作失败，请重试');return false;}
  finally{setBusy('');}
 };

 return <Panel title="孩子档案" icon={Users} description="每个孩子单独记录学习进度、使用时长和奖励额度；题库、朗读与批改设置全家共用" hint={query.isFetching?'正在读取…':undefined}>
  {query.error&&<p role="alert" className="rounded-xl border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">无法读取孩子档案：{(query.error as any).message||'请确认设备仍在同一局域网'}</p>}
  <div className="divide-y divide-border/60">
   {items.map((child:any)=><div key={child.id} className="flex flex-wrap items-center gap-2 py-3 first:pt-0 last:pb-0">
    {rename?.id===child.id
     ? <>
        <Input aria-label="孩子名字" className="h-9 w-40" maxLength={12} value={rename.name} onChange={e=>setRename({id:child.id,name:e.target.value})}/>
        <Button size="sm" disabled={busy==='rename'} onClick={async()=>{if(await run('rename','/api/children','PUT',{id:rename.id,name:rename.name},'已改名'))setRename(null);}}><Check/>保存</Button>
        <Button size="sm" variant="ghost" onClick={()=>setRename(null)}>取消</Button>
       </>
     : <>
        <span className="text-sm font-medium">{child.name}</span>
        {child.active&&<Badge variant="secondary">正在使用</Badge>}
        <div className="ms-auto flex items-center gap-1">
         {!child.active&&<Button size="sm" variant="outline" disabled={busy==='switch'} onClick={()=>run('switch','/api/children/switch','POST',{id:child.id},`已切换到 ${child.name}`)}>切换到 TA</Button>}
         <Button size="sm" variant="ghost" aria-label={`改名 ${child.name}`} onClick={()=>setRename({id:child.id,name:child.name})}><Pencil/></Button>
         <Button size="sm" variant="ghost" aria-label={`删除 ${child.name}`} disabled={busy==='delete'||child.id===''}
          onClick={()=>{if(confirm(`删除「${child.name}」？TA 的学习记录、使用时长和复习进度会一起删除，题目本身保留。`))run('delete','/api/children/delete','POST',{id:child.id},'已删除');}}><Trash2/></Button>
        </div>
       </>}
   </div>)}
  </div>
  {items.length>0&&<p className="mt-3 text-xs leading-5 text-muted-foreground">第一个孩子不能删除（可以改名）。电视上开始答题时会列出所有孩子，孩子点自己的名字就能开始。</p>}
  <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-4">
   <Input aria-label="新孩子名字" className="h-9 w-40" maxLength={12} placeholder="例如：小明" value={adding} onChange={e=>setAdding(e.target.value)}/>
   <Button size="sm" disabled={busy==='add'||items.length>=8||!adding.trim()}
    onClick={async()=>{if(await run('add','/api/children','POST',{name:adding},'已添加'))setAdding('');}}><Plus/>添加孩子</Button>
   <span className="text-xs text-muted-foreground">最多 8 个；新孩子默认沿用当前的规则，之后可以单独调整。</span>
  </div>
 </Panel>;
}
