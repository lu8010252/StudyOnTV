import {useEffect,useRef,useState} from 'react';
import {CalendarDays,RefreshCw} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Field,FieldContent,FieldDescription,FieldLabel,FieldLegend,FieldSet,FieldTitle} from '@/components/ui/field';
import {Switch} from '@/components/ui/switch';
import {Toolbar,ToolbarGroup} from '@/components/ui/toolbar';
import {api} from './api';

const DAYS=['周一','周二','周三','周四','周五','周六','周日'];
const EMPTY='0'.repeat(48);

function range(start:string,end:string){
 const chars=Array(48).fill('0');
 const min=(v:string)=>{const[h,m]=v.split(':').map(Number);return h*60+m;};
 const a=min(start),b=min(end);
 for(let i=0;i<48;i++){const t=i*30;if((a<b&&t>=a&&t<b)||(a>b&&(t>=a||t<b)))chars[i]='1';}
 return chars.join('');
}
function build(weekday:string|null,weekend:string|null){
 return DAYS.map((_,i)=>(i<5?weekday:weekend)||EMPTY);
}
const TEMPLATES:[string,()=>string[]][]=[
 ['上学日',()=>build(range('18:30','20:30'),range('09:00','20:00'))],
 ['每天',()=>build(range('18:30','20:30'),range('18:30','20:30'))],
 ['只在周末',()=>build(null,range('09:00','20:00'))],
 ['全天',()=>DAYS.map(()=> '1'.repeat(48))],
 ['清空',()=>DAYS.map(()=>EMPTY)]
];

export default function WeekSchedule({value,onChange,holidayMode,onHolidayMode}:{value:string[];onChange:(v:string[])=>void;holidayMode:string;onHolidayMode:(v:string)=>void}){
 const paint=useRef<string|null>(null);
 const [info,setInfo]=useState<any>(null);
 const [busy,setBusy]=useState(false);
 const slots=value&&value.length===7?value:DAYS.map(()=>EMPTY);
 const hours=slots.reduce((sum,day)=>sum+day.split('').filter(c=>c==='1').length,0)*0.5;

 useEffect(()=>{api('/api/holidays').then(setInfo).catch(()=>{});},[]);
 useEffect(()=>{const up=()=>{paint.current=null};window.addEventListener('pointerup',up);window.addEventListener('pointercancel',up);return()=>{window.removeEventListener('pointerup',up);window.removeEventListener('pointercancel',up);};},[]);

 function set(day:number,slot:number,on:boolean){const next=slots.slice();const chars=next[day].split('');chars[slot]=on?'1':'0';next[day]=chars.join('');onChange(next);}
 function down(day:number,slot:number){const on=slots[day][slot]!=='1';paint.current=on?'1':'0';set(day,slot,on);}
 function move(e:any){if(!paint.current)return;const el=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-day]') as HTMLElement|null;if(!el)return;const day=Number(el.dataset.day),slot=Number(el.dataset.slot);if(slots[day]?.[slot]!==paint.current)set(day,slot,paint.current==='1');}
 function wholeDay(day:number){const has=slots[day].includes('1');const next=slots.slice();next[day]=has?EMPTY:range('18:30','20:00');onChange(next);}
 async function sync(){setBusy(true);try{const result=await api('/api/holidays/sync?year='+new Date().getFullYear(),'POST',{},20000);setInfo(result);toast.success(`已同步 ${result.count} 天法定节假日`);}catch(e:any){toast.error(e.message);}finally{setBusy(false);}}

 return <FieldSet className="mt-0 min-w-0 gap-3 border-t border-border/60 pt-5">
  <FieldLegend variant="label" className="mb-0">可用时段</FieldLegend>
  <Toolbar aria-label="时段快捷模板" className="min-w-0 flex-wrap">
   <ToolbarGroup className="min-w-0 flex-wrap">
    {TEMPLATES.map(([label,make])=><Button key={label} type="button" size="sm" variant="outline" onClick={()=>onChange(make())}>{label}</Button>)}
   </ToolbarGroup>
  </Toolbar>
  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
   <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm" style={{background:'var(--primary)'}}/>蓝色：可以看（到点答题解锁）</span>
   <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm" style={{background:'var(--secondary)'}}/>灰色：不开放</span>
  </div>
  <div className="week-wrap min-w-0">
   <div className="week-scale">{[0,6,12,18,24].map(h=><span key={h}>{String(h).padStart(2,'0')}:00</span>)}</div>
   <div onPointerMove={move} onPointerLeave={()=>{paint.current=null;}}>
    {DAYS.map((name,day)=><div className="week-row" key={name}>
     <Button type="button" variant="ghost" size="sm" className="h-6 w-10 shrink-0 justify-start px-0 text-xs font-normal text-muted-foreground hover:bg-transparent hover:text-foreground" onClick={()=>wholeDay(day)} title={`切换${name}全部时段`}>{name}</Button>
     <div className="week-cells">{Array.from({length:48},(_,slot)=>{const time=`${String(Math.floor(slot/2)).padStart(2,'0')}:${slot%2?'30':'00'}`,on=slots[day][slot]==='1';return <Button key={slot} type="button" variant="ghost" size="sm" tabIndex={-1} aria-label={`${name} ${time}`} aria-pressed={on} data-day={day} data-slot={slot} onPointerDown={()=>down(day,slot)} className={'week-cell p-0'+(on?' on':'')} title={`${name} ${time}`}/>;})}</div>
    </div>)}
   </div>
  </div>
  <FieldDescription className="text-xs">拖动设置，每格 30 分钟；点星期整行切换；每周共 {hours} 小时。</FieldDescription>
  <Field orientation="responsive" className="border-t border-border/60 pt-4">
   <FieldContent>
    <FieldTitle className="items-center"><CalendarDays className="size-4 text-muted-foreground"/>节假日</FieldTitle>
    <FieldDescription className="text-xs">{info?.count?`已同步 ${info.year} 年 ${info.count} 天假期${info.source?' · '+info.source:''}`:'还没同步，节假日先按周日时间算'}</FieldDescription>
   </FieldContent>
   <div className="flex flex-wrap items-center gap-3">
    <Field orientation="horizontal" className="w-auto gap-2"><FieldLabel htmlFor="holiday-sunday" className="font-normal">节假日按周日时间</FieldLabel><Switch id="holiday-sunday" checked={holidayMode!=='normal'} onCheckedChange={v=>onHolidayMode(v?'sunday':'normal')}/></Field>
    <Button type="button" size="sm" variant="outline" onClick={sync} disabled={busy} title="从公开节假日数据拉取今年的放假安排（调休上班日不放假），之后节假日按周日时间算"><RefreshCw className={busy?'animate-spin':''}/>{busy?'同步中…':'同步今年节假日'}</Button>
   </div>
  </Field>
 </FieldSet>;
}
