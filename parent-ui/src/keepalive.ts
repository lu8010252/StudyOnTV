export type CheckLevel='ok'|'warn'|'unknown';

export interface KeepAliveCheck{
 id:string;
 title:string;
 level:CheckLevel;
 detail:string;
}

export interface KeepAliveSummary{
 level:CheckLevel;
 text:string;
 problems:number;
 pending:number;
}

export interface DebugStatus{
 usb:boolean;
 developer:boolean;
}

const VIA:Record<string,string>={boot:'开机广播',accessibility:'无障碍服务',job:'系统定时任务',update:'应用更新',app:'手动打开应用'};

function item(id:string,title:string,level:CheckLevel,detail:string):KeepAliveCheck{return{id,title,level,detail};}

function startedText(a:any):string{
 const via=VIA[a?.via]||a?.via||'';
 const seconds=Number(a?.startedAfterSeconds);
 const when=Number.isFinite(seconds)&&seconds>=0?(seconds<60?`${seconds} 秒`:`${Math.round(seconds/60)} 分钟`):'';
 if(when)return `本次开机后 ${when}${via?`（${via}）`:''}启动`;
 return via?`本次由${via}启动`:'本次开机后已启动';
}

export function keepAliveChecks(device:any):KeepAliveCheck[]{
 if(!device)return[];
 const monitor=device.monitor||{},keepalive=device.keepalive||{},autostart=device.autostart||{};
 const tv=device.flavor==='tv';
 const checks:KeepAliveCheck[]=[];

 const lastTick=Number(monitor.lastTickSeconds);
 if(!monitor.service){
  checks.push(item('service','管控服务','warn','服务没有运行。重新打开应用；如果经常掉线，请按下面的品牌步骤允许自启动和后台运行。'));
 }else if(Number.isFinite(lastTick)&&lastTick>10){
  checks.push(item('service','管控服务','warn',`服务在运行，但心跳已经停了 ${lastTick} 秒，可能被系统冻结。请重新打开应用或重启设备。`));
 }else{
  checks.push(item('service','管控服务','ok',Number.isFinite(lastTick)&&lastTick>=0?`正在运行，${lastTick} 秒前刚检查过`:'正在运行'));
 }

 checks.push(item('overlay','悬浮窗',monitor.overlay?'ok':'warn',
  monitor.overlay?'可以显示学习提醒':'没有悬浮窗权限，到时间可能无法弹出学习页'));

 if(monitor.usage){
  checks.push(item('usage','使用情况访问','ok','可以统计其他应用的使用时长'));
 }else if(monitor.accessibility){
  checks.push(item('usage','使用情况访问','ok','使用情况访问未开启，已用无障碍服务替代'));
 }else{
  checks.push(item('usage','使用情况访问','warn','没有使用情况访问或无障碍，无法准确统计其他应用的使用时长'));
 }

 if(monitor.accessibility){
  checks.push(item('accessibility','无障碍服务','ok','已连接，切换应用时能及时响应'));
 }else if(monitor.accessibilityEnabled){
  checks.push(item('accessibility','无障碍服务','unknown','已在系统中开启，等待服务连接'));
 }else if(tv){
  // #141：小米等电视的设置里根本没有无障碍入口，报「需要处理」家长也无从下手
  checks.push(item('accessibility','无障碍服务','unknown','未开启，可以忽略。不少电视没有无障碍设置入口；悬浮窗 + 使用情况访问已经能计时，无障碍只是让切换应用时响应更快'));
 }else{
  checks.push(item('accessibility','无障碍服务','warn','未开启。它是最可靠的后台拉起方式，建议到系统设置里开启'));
 }

 if(monitor.notificationListener){
  checks.push(item('listener','通知监听复活','ok',
   monitor.notificationListenerConnected?'已连接，被系统强停后会由它把管控服务拉回来':'已授权，等待系统连接'));
 }else{
  checks.push(item('listener','通知监听复活','unknown',
   '未开启（可选）。被系统强停后能由通知服务拉起，不读取通知内容；电视上没有入口时可用 ADB 开启'));
 }

 if(keepalive.ignoringBattery){
  checks.push(item('battery','电池优化','ok','已忽略电池优化，后台不容易被系统清理'));
 }else if(tv){
  checks.push(item('battery','电池优化','unknown','电视通常没有电池优化选项；如果系统里有，请设为不限制'));
 }else{
  checks.push(item('battery','电池优化','warn','仍受电池优化限制，长时间不用可能被系统清理'));
 }

 if(!tv&&Number(keepalive.sdk)>=33){
  checks.push(item('notifications','通知',keepalive.notifications?'ok':'warn',
   keepalive.notifications?'可以显示“使用时间管理中”的常驻通知':'通知被关闭，前台服务异常时不容易被家长发现'));
 }

 if(!autostart.known){
  checks.push(item('autostart','开机自启','unknown','这台设备不提供开机次数，无法自动判断；建议开启无障碍服务'));
 }else if(autostart.pending){
  checks.push(item('autostart','开机自启','unknown','刚安装或更新，重启一次设备后才能判断是否生效'));
 }else if(autostart.ok){
  checks.push(item('autostart','开机自启','ok',startedText(autostart)+(autostart.bootBroadcast?'，开机广播已收到':'')));
 }else{
  checks.push(item('autostart','开机自启','warn',`${startedText(autostart)}。系统可能拦截了开机自启，请按下面的品牌步骤放行。`));
 }

 return checks;
}

export function keepAliveSummary(device:any):KeepAliveSummary{
 if(!device)return{level:'unknown',text:'正在读取设备状态',problems:0,pending:0};
 const checks=keepAliveChecks(device);
 const problems=checks.filter(c=>c.level==='warn').length;
 const pending=checks.filter(c=>c.level==='unknown').length;
 if(problems>0)return{level:'warn',text:`${problems} 项需要处理`,problems,pending};
 if(pending>0)return{level:'unknown',text:`基本正常，${pending} 项待确认`,problems,pending};
 return{level:'ok',text:'全部正常',problems:0,pending:0};
}

export function brandTip(device:any):string{
 if(device?.keepalive?.brandTip)return device.keepalive.brandTip;
 return device?.flavor==='tv'
  ?'在电视的「设置 → 应用管理」里找到「题了个题」，允许自启动，再允许后台运行。不同品牌的入口名称略有不同。'
  :'在手机的「设置 → 应用管理」里找到「题了个题」，允许自启动和后台活动，并把电池策略设为「不限制」。';
}

export function debugStatus(device:any):DebugStatus{
 const keepalive=device?.keepalive||{};
 return{usb:!!keepalive.usbDebugging,developer:!!keepalive.developerOptions};
}

export function debugText(device:any):string{
 const status=debugStatus(device);
 return `- 开发者选项：${status.developer?'已开启':'未开启'}\n- USB 调试：${status.usb?'已开启':'未开启'}`;
}