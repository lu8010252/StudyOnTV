import {defaults} from './schema';
import {builtinProviders} from './ai-providers';
import math from '../../data/banks/math-100.json';

let config={...defaults,version:1};
let learning:any={usedMinutes:18,remainingSeconds:720,roundsDoneToday:1,extraPool:2,reason:'',free:false,override:null};
let questions:any[]=math.items.slice(0,12).map((q,i)=>({...q,id:'preview-'+i,type:'single',subject:i%3===0?'语文':i%3===1?'英语':'数学',level:'二年级',difficulty:1+(i%3),enabled:true,a:q.a.map(String),
 // #136 错题库：每 4 题里有 1 道是答错的，另有一道答对、一道没作答过，方便预览筛选
 stats:{presented:i%3+1,answered:i%3+1,correct:i%4===0?0:i%3+1,lastCorrect:i%4===0?false:i%4===1?true:null}}));
let device:any={name:'客厅电视 · 开发预览',flavor:'tv',version:'0.3.10',ip:'127.0.0.1',port:5173,
 crashUpload:{consent:false,endpoint:'',builtInEndpoint:'',hasToken:false,builtInToken:false,pending:false},
 desktop:{supported:true,enabled:false,isDefault:false,homePackage:'com.android.tv.settings',homeLabel:'系统桌面'},
 monitor:{service:true,overlay:true,usage:true,accessibility:true,accessibilityEnabled:true,notificationListener:true,notificationListenerConnected:true,gate:false,lastTickSeconds:1,lastError:''},
 autostart:{known:true,ok:true,pending:false,via:'boot',startedAfterSeconds:12,bootBroadcast:true,uptimeMinutes:320,accessibility:true},
 keepalive:{ignoringBattery:true,notifications:true,usbDebugging:false,developerOptions:false,sdk:34,manufacturer:'Xiaomi',brand:'Xiaomi',model:'Mi TV 6',android:'12',
  brandTip:'小米电视：设置 → 应用 → 应用管理 → 题了个题 → 自启动，开启；省电策略设为「无限制」'}};
let grading:any={mode:'manual',passScore:60,provider:'deepseek',endpoint:'https://api.deepseek.com/v1',model:'deepseek-flash',vision:false,hasKey:false,providers:builtinProviders};
let integration:any={enabled:false,read:true,control:false,hasToken:false};
let speech:any={mode:'system',autoRead:false,readLanguage:'',readScope:'question',azureKeySet:false,aliyunSecretSet:false};
let children:any[]=[{id:'',name:'孩子1',createdAt:Date.now()-86400000,active:true},{id:'c2',name:'小红',createdAt:Date.now(),active:false}];
let automation:any={sources:[],autoAi:false,minimum:30,batch:10,masteryAttempts:5,masteryAccuracy:90,topic:'',types:['single','text'],running:false,status:{}};
let bankSources:any[]=[{name:'官方题库',url:'https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV-banks@main/index.json',official:true}];
const marketBanks=[
 {name:'趣味常识 24 题',subject:'综合',level:'小学',count:24,author:'StudyOnTV',license:'CC BY 4.0',description:'语文、数学、科学混编的趣味小知识，适合饭后随口一问。',url:'https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV-banks@main/banks/open-play.json',source:'官方题库'},
 {name:'二年级口算 60 题',subject:'数学',level:'二年级',count:60,author:'小红妈妈',license:'CC BY-SA 4.0',description:'100 以内加减法口算，难度循序渐进。',url:'https://example.com/banks/math-2.json',source:'家长共享'}
];
const previewFetchedBank={name:'趣味常识',items:[
 {q:'一年有几个季节？',type:'single',a:['2 个','3 个','4 个','5 个'],k:2,difficulty:1,subject:'科学',level:'小学'},
 {q:'太阳从哪边升起？',type:'single',a:['东边','西边','南边','北边'],k:0,difficulty:1,subject:'科学',level:'小学'},
 {q:'判断：水在 0℃ 时会结冰。',type:'boolean',a:['正确','错误'],k:0,difficulty:2,subject:'科学',level:'小学'}
]};
// 和 App 端 LocalStore.stemKey 一致：忽略空白、标点和大小写
const stemKey=(stem:string)=>String(stem||'').toLowerCase().replace(/[\s，。？?！!、；;：:（）()"'“”‘’《》<>]/g,'');
let previewReviews:any[]=[
 {id:'review-graded',state:'graded',ts:Date.now()-3600000,gradedAt:Date.now()-3500000,score:85,source:'ai',
  feedback:'思路正确，最后一步单位写成了米，应为千米。',
  question:{q:'小明骑车每分钟走 250 米，12 分钟能走多少千米？',modelAnswer:'250 × 12 = 3000 米 = 3 千米'},
  input:{text:'250×12=3000 米，也就是 3 米。'},
  result:{correct:true,passed:true}},
 {id:'review-pending',state:'pending',ts:Date.now()-120000,
  question:{q:'解方程 $x^2-5x+6=0$，写出过程。',modelAnswer:'$(x-2)(x-3)=0$，所以 $x=2$ 或 $x=3$。',rubric:'列式 50 分，结论 50 分。'},
  input:{text:'x²-5x+6=0，(x-2)(x-3)=0，x=2 或 3'}}
];
const update={current:'0.3.10',version:'0.3.10',checking:false,hasUpdate:false,mirrors:[],log:[],api:''};

function computePreviewStudy(){
 const wrongCount=questions.filter(q=>q.enabled!==false&&q.stats?.lastCorrect===false).length;
 const masteredCount=questions.filter(q=>q.enabled!==false&&Number(q.stats?.correct||0)>0).length;
 return {streakDays:5,todayAnswered:18,todayCorrect:15,dueCount:4,wrongCount,masteredCount,totalQuestions:questions.length};
}

// In-memory preview data: no device requests or persistent authentication.
export async function previewApi(path:string,method:string,body:any){
 const route=path.split('?')[0];
 const study=computePreviewStudy();
 if(route==='/api/state')return structuredClone({config,learning,study,questions:questions.length,repetition:{studied:8,due:study.dueCount},children,activeChild:children.find(c=>c.active)?.id||''});
 if(route==='/api/apps')return{items:[
  {package:'com.android.systemui.screensaver',name:'系统屏保'},
  {package:'com.example.onlineclass',name:'网课 · 数学同步'},
  {package:'com.example.video',name:'云视听小电视'},
  {package:'com.example.music',name:'音乐'},
 ]};
 if(route==='/api/children'){
  if(method==='POST'){const name=String(body.name||'').trim();if(!name||children.some(c=>c.name===name))throw new Error('名字为空或重复');children.push({id:'preview-'+children.length,name,createdAt:Date.now(),active:false});}
  else if(method==='PUT'){children=children.map(c=>c.id===body.id?{...c,name:String(body.name||'').trim()}:c);}
  return structuredClone({items:children,active:children.find(c=>c.active)?.id||''});
 }
 if(route==='/api/children/switch'){children=children.map(c=>({...c,active:c.id===body.id}));return structuredClone({ok:true,items:children,active:body.id});}
 if(route==='/api/children/delete'){children=children.filter(c=>c.id!==body.id);return structuredClone({ok:true,items:children,active:children.find(c=>c.active)?.id||''});}
 if(route==='/api/system-settings')return{ok:true};
 if(route==='/api/remote'){
  if(body.action==='credit'){learning.remainingSeconds=Math.max(0,learning.remainingSeconds+body.minutes*60);learning.todayLimitMinutes=(learning.todayLimitMinutes??config.dailyMinutes)+body.minutes;return structuredClone({learning});}
  learning.override=body.action==='cancel'?null:{mode:body.action,remainingSeconds:(body.minutes||30)*60,at:Date.now()};
  return structuredClone({learning});
 }
 if(route==='/api/config'){if(method==='PUT')config={...config,...body,version:config.version+1};return structuredClone({config});}
 if(route==='/api/questions/generate'){
  const subj=body?.subject||'数学';
  return {items:[
   {type:'single',q:`【变式练习】${body?.topic||subj}：计算 $15 \\times 4 - 12 = ?$`,a:['46','48','52','60'],k:1,difficulty:2,subject:subj,level:body?.level||'三年级',bank:body?.bank||'默认题库',tip:'先算乘法 $15 \\times 4 = 60$，再算减法 $60 - 12 = 48$。'},
   {type:'boolean',q:`【巩固判断】在混合运算中，没有括号时应先算乘除后算加减。`,a:['正确','错误'],k:0,difficulty:1,subject:subj,level:body?.level||'三年级',bank:body?.bank||'默认题库',tip:'四则运算基本顺序：先乘除、后加减。'}
  ],duplicates:0};
 }
 if(route==='/api/questions'){
  if(method==='POST'){const items=body.items||[body];let added=0;for(const item of items){const index=questions.findIndex(q=>q.id===item.id);if(index>=0){questions[index]={...item};added++;continue;}if(body.skipDuplicates&&questions.some(q=>stemKey(q.q)===stemKey(item.q)))continue;questions.push({...item,id:'preview-'+crypto.randomUUID()});added++;}return{ok:true,added,skipped:items.length-added};}
  // 真机上是服务端筛选 + 分页，预览里用同一套参数在内存里模拟
  const query=new URLSearchParams(path.split('?')[1]||'');
  const keyword=(query.get('search')||'').trim().toLowerCase();
  const all=query.get('all')==='1'||query.get('enabledOnly')==='false';
  let list=questions.filter(q=>(all||q.enabled!==false)
   &&(!keyword||(q.q+' '+q.tip+' '+(q.a||[]).join(' ')+' '+(q.subject||'')+' '+(q.level||'')).toLowerCase().includes(keyword))
   &&(!query.get('bank')||(q.bank||'默认题库')===query.get('bank'))
   &&(!query.get('type')||(q.type||'single')===query.get('type'))
   &&(!query.get('subject')||(q.subject||'')===query.get('subject'))
   &&(!query.get('level')||(q.level||'')===query.get('level'))
   &&(!query.get('difficulty')||String(q.difficulty||2)===query.get('difficulty'))
   &&(query.get('wrong')!=='1'||q.stats?.lastCorrect===false));
  const sort=query.get('sort')||'random';
  if(sort==='diff')list=[...list].sort((a:any,b:any)=>(b.difficulty||2)-(a.difficulty||2));
  else if(sort==='type')list=[...list].sort((a:any,b:any)=>String(a.type).localeCompare(String(b.type))||(b.difficulty||2)-(a.difficulty||2));
  const offset=Number(query.get('offset')||0),limit=Number(query.get('limit')||500);
  return structuredClone({items:list.slice(offset,offset+limit),total:list.length,offset,limit});
 }
 if(route==='/api/questions/meta'){
  const banks=[...new Set(questions.map((q:any)=>q.bank||'默认题库'))].sort();
  const subjects=[...new Set(questions.map((q:any)=>q.subject).filter(Boolean))].sort();
  const levels=[...new Set(questions.map((q:any)=>q.level).filter(Boolean))].sort();
  return {banks,subjects,levels,total:questions.length,
   enabled:questions.filter((q:any)=>q.enabled!==false).length,
   paused:questions.filter((q:any)=>q.enabled===false).length,
   wrong:questions.filter((q:any)=>q.stats?.lastCorrect===false).length};
 }
 if(route==='/api/analytics'){
  const daily=Array.from({length:14},(_,i)=>{
   const d=new Date();d.setDate(d.getDate()-(13-i));
   const answered=10+(i%5)*4;
   const acc=i<7?74+(i%3)*4:84+(i%3)*4;
   return {label:d.toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'}),answered,correct:Math.round(answered*acc/100),accuracy:acc,watch:18+(i%4)*6};
  });
  const forecast=Array.from({length:7},(_,i)=>{
   const d=new Date();d.setDate(d.getDate()+i);
   return {dayOffset:i,label:i===0?'今天':i===1?'明天':d.toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'}),dueCount:[4,6,3,5,2,4,1][i]};
  });
  return {
   overview:{totalAnswered:186,accuracyPercent:84,recent7dAccuracy:88,prev7dAccuracy:79,streakDays:study.streakDays,dueNow:study.dueCount,wrongCount:study.wrongCount,masteredQuestions:study.masteredCount},
   maturity:{unseen:2,learning:3,young:4,mature:3},
   daily,
   bySubject:[
    {subject:'数学',total:6,answered:82,accuracy:76,mastered:4,wrong:2},
    {subject:'语文',total:3,answered:54,accuracy:91,mastered:3,wrong:0},
    {subject:'英语',total:3,answered:50,accuracy:86,mastered:2,wrong:1},
   ],
   byBank:[{bank:'默认题库',total:questions.length,covered:study.masteredCount,accuracy:84}],
   hardQuestions:questions.filter(q=>q.stats?.lastCorrect===false).map((q,idx)=>({...q,wrongTimes:3-idx,answered:4,correct:1,tip:q.tip||'注意审题与进位计算步骤。'})),
   forecast,
  };
 }
 if(route==='/api/backup'){
  const query=new URLSearchParams(path.split('?')[1]||'');
  const withQ=query.get('includeQuestions')!=='0';
  return structuredClone({format:'studyontv-backup',version:1,exportedAt:Date.now(),config,children,questions:withQ?questions:[]});
 }
 if(route==='/api/backup/restore'){
  const src=body?.backup||body||{};
  if(Array.isArray(src.children)&&src.children.length)children=src.children;
  if(Array.isArray(src.questions)&&src.questions.length){
   questions=body?.mode==='merge'?[...questions,...src.questions.filter((nq:any)=>!questions.some(oq=>oq.id===nq.id))]:src.questions;
  }
  return {ok:true,children:children.length,questions:questions.length};
 }
 if(route==='/api/password')return {ok:true,token:'preview-session-'+Date.now()};
 if(route==='/api/banks/market')return structuredClone({sources:bankSources,banks:bankSources.length>1?marketBanks.concat({...marketBanks[1],name:'家长自编 20 题',url:'https://example.com/banks/parent.json',source:'家长共享'}):marketBanks.filter(b=>b.source==='官方题库'),failed:[]});
 if(route==='/api/banks/sources'){
  if(method==='POST'){const url=String(body.url||'').trim();if(!/^https?:\/\//.test(url))throw new Error('请填写 http(s) 地址');if(bankSources.some(s=>s.url===url))throw new Error('这个地址已经添加过了');bankSources.push({name:String(body.name||'').trim()||'自定义题库源',url,official:false});}
  else if(method==='DELETE')bankSources=bankSources.filter(s=>s.official||s.url!==body.url);
  return structuredClone({sources:bankSources});
 }
 if(route==='/api/banks/fetch')return{ok:true,data:structuredClone(previewFetchedBank)};
 if(route==='/api/speech/probe')return{ok:true,engine:'com.google.android.tts',language:'zh-CN',langStatus:0,hint:'本地语音可用（com.google.android.tts）'};
 if(route==='/api/speech/engines')return{defaultEngine:'com.google.android.tts',engines:[{name:'com.google.android.tts',label:'Google 文字转语音',default:true},{name:'com.example.voice',label:'讯飞语音',default:false}]};
 if(route==='/api/speech/install-voice')return{ok:true};
 if(route==='/api/speech/test')return{ok:true,mode:'system',reason:'ok',language:'zh-CN'};
 if(route==='/api/speech'){if(method==='PUT'){speech={...speech,...body,azureKeySet:body.clearKeys?false:!!body.azureKey||speech.azureKeySet,aliyunSecretSet:body.clearKeys?false:!!body.aliyunSecret||speech.aliyunSecretSet};delete speech.azureKey;delete speech.aliyunSecret;}return structuredClone(speech);}
 if(route==='/api/bank-automation'){if(method==='PUT')automation={...automation,...body};return structuredClone(automation);}
 if(route==='/api/bank-automation/sync'){automation.status={at:Date.now(),ok:true,sources:[]};return structuredClone(automation);}
 if(route==='/api/questions/clear'){const deleted=questions.length;questions=[];return{deleted};}
 if(route==='/api/questions/bank'&&method==='DELETE'){const before=questions.length;questions=questions.filter(q=>(q.bank||'默认题库')!==body.bank);return{deleted:before-questions.length};}
 if(route==='/api/questions/pause'){questions=questions.map(q=>body.ids.includes(q.id)?{...q,enabled:body.enabled}:q);return{updated:body.ids.length};}
 if(route==='/api/questions/delete'){const count=questions.length;questions=questions.filter(q=>!body.ids.includes(q.id));return{deleted:count-questions.length};}
 if(route.startsWith('/api/questions/')&&method==='DELETE'){questions=questions.filter(q=>q.id!==decodeURIComponent(route.split('/').pop()!));return{ok:true};}
 if(route==='/api/device'){if(method==='PUT')device={...device,...body};return structuredClone(device);}
 if(route==='/api/device/launcher'){device.launcherHidden=!!body.hidden;return{ok:true,launcherHidden:device.launcherHidden};}
 if(route==='/api/device/desktop'){device.desktop={...device.desktop,enabled:!!body.enabled,isDefault:!!body.enabled&&device.desktop.isDefault};return structuredClone(device.desktop);}
 if(route==='/api/device/desktop/choose'){if(device.desktop.enabled)device.desktop.isDefault=true;return structuredClone(device.desktop);}
 if(route==='/api/device/crash'){
  const next={...device.crashUpload};
  if('consent' in body)next.consent=!!body.consent;
  if('endpoint' in body)next.endpoint=String(body.endpoint||'');
  if(body.token)next.hasToken=true;
  device.crashUpload=next;
  return{ok:true,crashUpload:structuredClone(next)};
 }
 if(route==='/api/grading'){if(method==='PUT')grading={...grading,...body};return structuredClone(grading);}
 if(route==='/api/grading/test')return{ok:true,message:'模型响应正常',ms:420};
 if(route==='/api/reviews'){
  const query=new URLSearchParams(path.split('?')[1]||'');
  const state=query.get('state')||'pending';
  const items=(state==='all'?previewReviews:previewReviews.filter(r=>r.state===state)).map(r=>structuredClone(r));
  if(query.get('view')==='summary')for(const r of items){if(r.input?.drawing){r.input.hasDrawing=true;delete r.input.drawing;}}
  return{items};
 }
 if(route.startsWith('/api/reviews/')&&method==='GET')return structuredClone(previewReviews.find(r=>r.id===route.split('/').pop())||{});
 if(route==='/api/coverage'){
  const covered=questions.filter(q=>Number(q.stats?.correct||0)>0).length;
  const pending=questions.filter(q=>Number(q.stats?.correct||0)===0);
  const percent=questions.length?Math.round(covered*100/questions.length):0;
  return{total:questions.length,covered,percent,pending:pending.length,untouched:pending.filter(q=>Number(q.stats?.presented||0)===0).length,
   banks:[{name:'默认题库',total:questions.length,covered,percent}],
   items:pending.slice(0,20).map(q=>({id:q.id,q:q.q,bank:q.bank||'默认题库',subject:q.subject,level:q.level,presented:Number(q.stats?.presented||0),answered:Number(q.stats?.answered||0)}))};
 }
 if(route==='/api/history')return{items:Array.from({length:7},(_,i)=>{const day=new Date();day.setDate(day.getDate()-i);return{label:day.toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'}),watch:[18,32,25,40,16,35,28][i],answered:15+i*2,accuracy:80+i,rounds:2};})};
 if(route==='/api/events')return{items:[{type:'quiz_pass',ts:Date.now()-600000,payload:{minutes:10}},{type:'answer',ts:Date.now()-700000,payload:{correct:true}}]};
 if(route==='/api/update'||route==='/api/update/check')return structuredClone(update);
 if(route==='/api/integration'){if(method==='PUT')integration={...integration,...body};return structuredClone(integration);}
 if(route==='/api/logs')return{lines:[]};
 if(route==='/api/holidays')return{year:new Date().getFullYear(),count:0};
 if(route==='/api/logout')return{ok:true};
 throw new Error('开发预览暂不支持此操作');
}
