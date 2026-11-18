import {useQuery} from '@tanstack/react-query';
import {ShieldCheck,RefreshCw,Copy,CircleCheck,CircleAlert,CircleHelp,Settings} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {api} from './api';
import {copyText} from './lib/clipboard';
import {Panel,Row} from './RulesPage';
import {keepAliveChecks,keepAliveSummary,brandTip,debugStatus,debugText,type CheckLevel} from './keepalive';

const VIA:Record<string,string>={boot:'开机广播',accessibility:'无障碍服务',job:'系统定时任务',update:'应用更新',app:'打开应用'};
const LEVEL:Record<CheckLevel,{icon:any,label:string,className:string}> = {
 ok:{icon:CircleCheck,label:'正常',className:'text-green-600 dark:text-green-400'},
 warn:{icon:CircleAlert,label:'需要处理',className:'text-amber-600 dark:text-amber-400'},
 unknown:{icon:CircleHelp,label:'待确认',className:'text-muted-foreground'}
};
const BANNER:Record<CheckLevel,string> = {
 ok:'border-green-600/30 bg-green-600/10 text-green-700 dark:text-green-400',
 warn:'border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400',
 unknown:'border-border bg-secondary/50 text-muted-foreground'
};

function uptime(minutes:number){
 if(!Number.isFinite(minutes)||minutes<0)return '未知';
 const hours=Math.floor(minutes/60),rest=Math.round(minutes%60);
 return hours?`${hours} 小时 ${rest} 分钟`:`${rest} 分钟`;
}

function selfCheckText(device:any){
 const lines=[`题了个题 · 后台保活自检`,`设备：${device.name||'未命名'}（${device.flavor==='tv'?'电视版':'手机 / 平板版'} v${device.version}）`];
 if(device.keepalive)lines.push(`型号：${[device.keepalive.manufacturer,device.keepalive.model].filter(Boolean).join(' ')||'未知'} · Android ${device.keepalive.android||'未知'}`);
 lines.push(`检查结果：${keepAliveSummary(device).text}`);
 for(const check of keepAliveChecks(device))lines.push(`- ${check.title}：${check.detail}`);
 lines.push(debugText(device));
 if(device.monitor?.lastError)lines.push(`最近一次弹窗失败：${device.monitor.lastError}`);
 return lines.join('\n');
}

export default function KeepAlivePanel({sessionId}:{sessionId:number}){
 const device=useQuery({queryKey:['device',sessionId],queryFn:()=>api('/api/device'),refetchInterval:5000});
 const data=device.data;
 const checks=keepAliveChecks(data);
 const summary=keepAliveSummary(data);
 const debug=debugStatus(data);
 const LevelIcon=LEVEL[summary.level].icon;
 const autostart=data?.autostart;
 const openSystemSettings=async()=>{
  try{
   const result=await api('/api/system-settings','POST',{});
   if(result?.ok===false)toast.error('这台设备没有可调起的系统设置页面');
   else toast.success('已在设备上打开系统设置');
  }catch(e:any){toast.error(e.message||'无法打开系统设置');}
 };
 const copy=async()=>{
  if(!data)return;
  const result=await copyText(selfCheckText(data));
  if(result==='ok')toast.success('自检信息已复制，可以直接发给开发者');
  else toast.error('复制失败，请手动选中文字复制');
 };

 return <Panel title="后台保活自检" icon={ShieldCheck} description="检查后台运行与重启恢复；每 5 秒刷新" hint={device.isFetching?'正在检测…':undefined}>
  {device.error&&<p role="alert" className="rounded-xl border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">无法读取设备状态：{(device.error as any).message||'请确认设备仍在同一局域网'}</p>}
  {!data&&device.isLoading&&<p className="text-sm text-muted-foreground py-3">正在读取设备状态…</p>}
  {data&&!data.keepalive&&<p className="rounded-xl border bg-secondary/40 p-3 text-sm text-muted-foreground">当前设备上的应用版本还不支持这项自检。请先更新被控设备上的应用。</p>}

  {data?.keepalive&&<>
   <div role="status" className={`flex items-start gap-3 rounded-xl border p-4 mb-2 ${BANNER[summary.level]}`}>
    <LevelIcon className="size-5 shrink-0 mt-0.5"/>
    <div className="min-w-0">
     <div className="font-semibold text-sm">{summary.text}</div>
     <p className="mt-1 text-xs leading-5 opacity-90">{summary.level==='ok'?'各项检查和重启后的自启都正常，不需要额外设置。':summary.level==='warn'?'按下面的提示处理后，回到这里点「重新检测」。':'刚安装或更新后需要重启一次设备，才能判断开机自启是否生效。'}</p>
    </div>
   </div>

   {checks.map(check=>{
    const style=LEVEL[check.level],Icon=style.icon;
    return <Row key={check.id} title={check.title} note={check.detail}>
     <span className={`inline-flex items-center gap-1.5 text-sm ${style.className}`}><Icon className="size-4"/>{style.label}</span>
    </Row>;
   })}

   <div className="rounded-xl border bg-secondary/30 p-4 mt-4">
    <h4 className="text-sm font-medium">调试状态</h4>
    <p className="mt-1 text-xs leading-5 text-muted-foreground">系统调试开关，不影响学习管控。需要在被控设备的「权限设置」里调整；应用不能代为开启 USB 调试。</p>
    <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">开发者选项</dt><dd>{debug.developer?'已开启':'未开启'}</dd></div>
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">USB 调试</dt><dd>{debug.usb?'已开启':'未开启'}</dd></div>
    </dl>
   </div>

   {autostart&&<div className="rounded-xl border bg-secondary/30 p-4 mt-4">
    <h4 className="text-sm font-medium">重启后的存活结果</h4>
    <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">开机广播</dt><dd>{!autostart.known?'无法判断':autostart.bootBroadcast?'已收到':'未收到'}</dd></div>
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">启动方式</dt><dd>{autostart.via?(VIA[autostart.via]||autostart.via):'尚未记录'}</dd></div>
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">开机后启动耗时</dt><dd>{Number(autostart.startedAfterSeconds)>=0?`${Math.round(Number(autostart.startedAfterSeconds))} 秒`:'尚未记录'}</dd></div>
     <div className="flex justify-between gap-3"><dt className="text-muted-foreground">本次已运行</dt><dd>{uptime(Number(autostart.uptimeMinutes))}</dd></div>
    </dl>
    {autostart.pending&&<p className="mt-3 rounded-lg bg-amber-600/10 px-3 py-2 text-xs leading-5 text-amber-700 dark:text-amber-400">刚安装或更新过应用。请重启一次设备，开机后回到这里，就能看到是否被系统拦截。</p>}
    {!autostart.known&&<p className="mt-3 text-xs leading-5 text-muted-foreground">这台设备不提供开机次数。可以在重启后打开本页看「管控服务」是否自行恢复，或者直接开启无障碍服务作为兜底。</p>}
   </div>}

   <div className="rounded-xl border border-amber-600/25 bg-amber-600/5 p-4 mt-4">
    <h4 className="text-sm font-medium">按品牌设置自启动</h4>
    <p className="mt-2 text-sm leading-6 text-muted-foreground">{brandTip(data)}</p>
    <p className="mt-2 text-xs leading-5 text-muted-foreground">设置完成后回到这里点「重新检测」；刚改完权限时结果可能延迟几秒。</p>
   </div>

   {!!data.monitor?.lastError&&<Row title="最近一次弹窗失败" note={data.monitor.lastError}><span className="text-xs text-muted-foreground">可随自检信息一起反馈</span></Row>}

   <div className="flex flex-wrap gap-2 pt-4">
    <Button variant="outline" onClick={()=>device.refetch()} disabled={device.isFetching}><RefreshCw className={device.isFetching?'animate-spin':''}/>{device.isFetching?'检测中…':'重新检测'}</Button>
    <Button variant="ghost" onClick={copy}><Copy/>复制自检信息</Button>
    <Button variant="ghost" onClick={openSystemSettings}><Settings/>打开系统设置</Button>
   </div>
   {data.keepalive&&<p className="mt-3 text-xs leading-5 text-muted-foreground">设备信息：{[data.keepalive.manufacturer,data.keepalive.model].filter(Boolean).join(' ')||'未知型号'} · Android {data.keepalive.android||'未知'} · 应用 v{data.version}</p>}
  </>}
 </Panel>;
}