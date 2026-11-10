import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {FieldDescription} from '@/components/ui/field';
import {Plus,Trash2} from 'lucide-react';
import {useEffect,type ReactNode} from 'react';
import type {ExemptWindow,TimePoint} from './schema';

const DAYS=['一','二','三','四','五','六','日'];
const HOURS=Array.from({length:24},(_,i)=>String(i).padStart(2,'0'));
const MINUTES=Array.from({length:60},(_,i)=>String(i).padStart(2,'0'));

/** 秒级 id：局域网 http 页面不是安全上下文，不能用 crypto.randomUUID。 */
function newId(prefix:string){return prefix+Date.now().toString(36)+Math.random().toString(36).slice(2,8);}

/** 分钟精度的开始 / 结束时间选择（时 + 分两个下拉，和页签里其它控件风格一致）。 */
export function TimePicker({value,onChange,label}:{value:string;onChange:(value:string)=>void;label:string}){
 const [hour,minute]=/^\d\d:\d\d$/.test(value)?value.split(':'):['00','00'];
 const set=(h:string,m:string)=>onChange(h+':'+m);
 return <div className="flex items-center gap-1" role="group" aria-label={label}>
  <Select value={hour} onValueChange={h=>set(h,minute)}>
   <SelectTrigger size="sm" aria-label={label+' 小时'} className="w-[4.5rem]"><SelectValue>{hour}</SelectValue></SelectTrigger>
   <SelectContent className="max-h-64">{HOURS.map(h=><SelectItem key={h} value={h}>{h}</SelectItem>)}</SelectContent>
  </Select>
  <span className="text-xs text-muted-foreground">:</span>
  <Select value={minute} onValueChange={m=>set(hour,m)}>
   <SelectTrigger size="sm" aria-label={label+' 分钟'} className="w-[4.5rem]"><SelectValue>{minute}</SelectValue></SelectTrigger>
   <SelectContent className="max-h-64">{MINUTES.map(m=><SelectItem key={m} value={m}>{m}</SelectItem>)}</SelectContent>
  </Select>
 </div>;
}

function DayPicker({value,onChange,label}:{value:number[];onChange:(value:number[])=>void;label:string}){
 const days=Array.isArray(value)?value:[];
 return <div className="flex flex-wrap gap-1" role="group" aria-label={label+' 星期'}>
  {DAYS.map((name,index)=>{
   const on=days.includes(index);
   return <Button key={name} type="button" size="sm" variant={on?'default':'outline'} aria-pressed={on}
    aria-label={label+' 周'+name} title={'周'+name} className="size-8 px-0"
    onClick={()=>onChange(on?days.filter(v=>v!==index):[...days,index].sort((a,b)=>a-b))}>{name}</Button>;
  })}
 </div>;
}

function Row({label,children,onRemove}:{label:string;children:ReactNode;onRemove:()=>void}){
 return <div className="space-y-3 rounded-xl border border-border/70 bg-muted/20 p-3">
  <div className="flex items-start gap-2">{children}
   <Button type="button" variant="ghost" size="icon" aria-label={'删除'+label} title="删除" className="size-8 shrink-0 text-muted-foreground" onClick={onRemove}><Trash2/></Button>
  </div>
 </div>;
}

function TimeFields({start,end,onStart,onEnd,label}:{start:string;end:string;onStart:(v:string)=>void;onEnd:(v:string)=>void;label:string}){
 return <>
  <div className="space-y-1"><span className="text-xs text-muted-foreground">开始</span><TimePicker value={start} onChange={onStart} label={label+' 开始'}/></div>
  <div className="space-y-1"><span className="text-xs text-muted-foreground">结束</span><TimePicker value={end} onChange={onEnd} label={label+' 结束'}/></div>
 </>;
}

export default function TimePoints({points,exempt,onPoints,onExempt}:{
 points:TimePoint[];exempt:ExemptWindow[];onPoints:(value:TimePoint[])=>void;onExempt:(value:ExemptWindow[])=>void;
}){
 const list=Array.isArray(points)?points:[];
 const windows=Array.isArray(exempt)?exempt:[];
 // 旧配置里「计入限额」可能没有 dailyMinutes（Android 端会拒绝保存），进入页面时补成界面显示的默认值。
 useEffect(()=>{if(list.some(item=>item.mode==='counted'&&item.dailyMinutes==null))onPoints(list.map(item=>item.mode==='counted'&&item.dailyMinutes==null?{...item,dailyMinutes:60}:item));},[JSON.stringify(points)]);
 const update=(index:number,patch:Partial<TimePoint>)=>onPoints(list.map((item,i)=>i===index?{...item,...patch}:item));
 const updateExempt=(index:number,patch:Partial<ExemptWindow>)=>onExempt(windows.map((item,i)=>i===index?{...item,...patch}:item));
 const add=()=>onPoints([...list,{id:newId('tp_'),name:'网课',days:[0,2,4],start:'19:00',end:'20:00',mode:'free' as const}]);
 const addExempt=()=>onExempt([...windows,{id:newId('ex_'),name:'大人使用',days:[5,6],start:'21:30',end:'23:00'}]);

 return <div className="space-y-4">
  <div className="flex flex-wrap items-center justify-between gap-2">
   <span className="text-sm font-medium">网课时段</span>
   <Button type="button" size="sm" variant="outline" onClick={add} disabled={list.length>=50}><Plus/>新增网课</Button>
  </div>
  {list.length===0?<FieldDescription className="text-xs">还没有网课时段。添加后可以设置「自由使用不扣时长」或「计入每日限额并使用该时段自己的上限」。</FieldDescription>
   :list.map((item,index)=><Row key={item.id||index} label={item.name||'网课'} onRemove={()=>onPoints(list.filter((_,i)=>i!==index))}>
    <div className="w-full space-y-3">
     <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-36 flex-1 space-y-1"><span className="text-xs text-muted-foreground">名称</span>
       <Input className="h-8" maxLength={20} placeholder="数学网课" aria-label={'网课时段 '+(index+1)+' 名称'} value={item.name} onChange={e=>update(index,{name:e.target.value})}/>
      </div>
      <div className="space-y-1"><span className="text-xs text-muted-foreground">星期</span>
       <DayPicker value={item.days} onChange={days=>update(index,{days})} label={'网课时段 '+(index+1)}/>
      </div>
     </div>
     <div className="flex flex-wrap items-end gap-3">
      <TimeFields start={item.start} end={item.end} label={'网课时段 '+(index+1)}
       onStart={start=>update(index,{start})} onEnd={end=>update(index,{end})}/>
      <div className="space-y-1"><span className="text-xs text-muted-foreground">用途</span>
       <Select value={item.mode||'free'} onValueChange={value=>update(index,{mode:value as TimePoint['mode'],dailyMinutes:value==='counted'?(item.dailyMinutes??60):undefined})}>
        <SelectTrigger size="sm" aria-label={'网课时段 '+(index+1)+' 用途'} className="w-32"><SelectValue>{item.mode==='counted'?'计入限额':'自由使用'}</SelectValue></SelectTrigger>
        <SelectContent>
         <SelectItem value="free">自由使用（不扣时长）</SelectItem>
         <SelectItem value="counted">计入每日限额</SelectItem>
        </SelectContent>
       </Select>
      </div>
      {item.mode==='counted'&&<div className="space-y-1"><span className="text-xs text-muted-foreground">时段每日上限 · 分钟</span>
       <Input className="h-8 w-24 text-center" type="number" min={1} max={480} aria-label={'网课时段 '+(index+1)+' 每日上限分钟'} value={item.dailyMinutes??60}
        onChange={e=>update(index,{dailyMinutes:Number(e.target.value)})}
        onBlur={e=>update(index,{dailyMinutes:Math.min(480,Math.max(1,Number(e.target.value)||1))})}/>
      </div>}
     </div>
    </div>
   </Row>)}
  <FieldDescription className="text-xs">按真实星期几生效（不受「法定节假日按周日安排」影响）；结束早于开始表示跨午夜，例如 23:30–00:30。</FieldDescription>

  <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border/60 pt-4">
   <span className="text-sm font-medium">大人豁免时段</span>
   <Button type="button" size="sm" variant="outline" onClick={addExempt} disabled={windows.length>=50}><Plus/>新增豁免</Button>
  </div>
  {windows.length===0?<FieldDescription className="text-xs">到点自动解锁给大人使用，不用去改设置；优先级高于网课时段。</FieldDescription>
   :windows.map((item,index)=><Row key={item.id||index} label={item.name||'大人豁免'} onRemove={()=>onExempt(windows.filter((_,i)=>i!==index))}>
    <div className="w-full space-y-3">
     <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-36 flex-1 space-y-1"><span className="text-xs text-muted-foreground">名称</span>
       <Input className="h-8" maxLength={20} placeholder="大人看电影" aria-label={'大人豁免 '+(index+1)+' 名称'} value={item.name} onChange={e=>updateExempt(index,{name:e.target.value})}/>
      </div>
      <div className="space-y-1"><span className="text-xs text-muted-foreground">星期</span>
       <DayPicker value={item.days} onChange={days=>updateExempt(index,{days})} label={'大人豁免 '+(index+1)}/>
      </div>
     </div>
     <div className="flex flex-wrap items-end gap-3">
      <TimeFields start={item.start} end={item.end} label={'大人豁免 '+(index+1)}
       onStart={start=>updateExempt(index,{start})} onEnd={end=>updateExempt(index,{end})}/>
     </div>
    </div>
   </Row>)}
 </div>;
}
