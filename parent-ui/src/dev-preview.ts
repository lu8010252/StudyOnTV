import {defaults} from './schema';
import {builtinProviders} from './ai-providers';
import math from '../../data/banks/math-100.json';

let config={...defaults,version:1};
let learning:any={usedMinutes:18,remainingSeconds:720,roundsDoneToday:1,extraPool:2,reason:'',free:false,override:null};
let questions:any[]=math.items.slice(0,12).map((q,i)=>({...q,id:'preview-'+i,type:'single',subject:'数学',level:'二年级',difficulty:1,enabled:true,a:q.a.map(String),
 // #136 错题库：每 4 题里有 1 道是答错的，另有一道答对、一道没作答过，方便预览筛选
 stats:{presented:i%3+1,answered:i%3,correct:i%3===0?0:Math.max(0,i%3-1),lastCorrect:i%4===0?false:i%4===1?true:null}}));
let device:any={name:'客厅电视 · 开发预览',flavor:'tv',version:'0.3.10',ip:'127.0.0.1',port:5173,buglyConsent:false,
 monitor:{service:true,overlay:true,usage:true,accessibility:true,accessibilityEnabled:true,gate:false,lastTickSeconds:1,lastError:''},
 autostart:{known:true,ok:true,pending:false,via:'boot',startedAfterSeconds:12,bootBroadcast:true,uptimeMinutes:320,accessibility:true},
 keepalive:{ignoringBattery:true,notifications:true,usbDebugging:false,developerOptions:false,sdk:34,manufacturer:'Xiaomi',brand:'Xiaomi',model:'Mi TV 6',android:'12',
  brandTip:'小米电视：设置 → 应用 → 应用管理 → 题了个题 → 自启动，开启；省电策略设为「无限制」'}};
let grading:any={mode:'parent',passScore:60,provider:'deepseek',endpoint:'https://api.deepseek.com/v1',model:'deepseek-flash',hasKey:false,providers:builtinProviders};
let integration:any={enabled:false,read:true,control:false,hasToken:false};
let speech:any={mode:'system',autoRead:false,readLanguage:'',azureKeySet:false,aliyunSecretSet:false};
let children:any[]=[{id:'',name:'孩子1',createdAt:Date.now()-86400000,active:true},{id:'c2',name:'小红',createdAt:Date.now(),active:false}];
let automation:any={sources:[],autoAi:false,minimum:30,batch:10,masteryAttempts:5,masteryAccuracy:90,topic:'',types:['single','text'],running:false,status:{}};
const update={current:'0.3.10',version:'0.3.10',checking:false,hasUpdate:false,mirrors:[],log:[],api:''};

// In-memory preview data: no device requests or persistent authentication.
export async function previewApi(path:string,method:string,body:any){
 const route=path.split('?')[0];
 if(route==='/api/state')return structuredClone({config,learning,questions:questions.length,repetition:{studied:8,due:3},children,activeChild:children.find(c=>c.active)?.id||''});
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
 if(route==='/api/questions'){
  if(method==='POST'){const items=body.items||[body];for(const item of items){const index=questions.findIndex(q=>q.id===item.id);if(index>=0)questions[index]={...item};else questions.push({...item,id:'preview-'+crypto.randomUUID()});}return{ok:true,added:items.length};}
  return structuredClone({items:questions,total:questions.length});
 }
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
 if(route==='/api/device/bugly'){device.buglyConsent=!!body.enabled;return{ok:true};}
 if(route==='/api/grading'){if(method==='PUT')grading={...grading,...body};return structuredClone(grading);}
 if(route==='/api/reviews')return{items:[]};
 if(route==='/api/history')return{items:Array.from({length:7},(_,i)=>{const day=new Date();day.setDate(day.getDate()-i);return{label:day.toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'}),watch:[18,32,25,40,16,35,28][i],answered:15+i*2,accuracy:80+i,rounds:2};})};
 if(route==='/api/events')return{items:[{type:'quiz_pass',ts:Date.now()-600000,payload:{minutes:10}},{type:'answer',ts:Date.now()-700000,payload:{correct:true}}]};
 if(route==='/api/update'||route==='/api/update/check')return structuredClone(update);
 if(route==='/api/integration'){if(method==='PUT')integration={...integration,...body};return structuredClone(integration);}
 if(route==='/api/logs')return{lines:[]};
 if(route==='/api/holidays')return{year:new Date().getFullYear(),count:0};
 if(route==='/api/logout')return{ok:true};
 throw new Error('开发预览暂不支持此操作');
}
