import {z} from 'zod';
const count=(min:number,max:number)=>z.coerce.number({invalid_type_error:'请输入有效数字'}).int('请输入整数').min(min,`最少 ${min}`).max(max,`最多 ${max}`);
export const timePattern=/^([01]\d|2[0-3]):[0-5]\d$/;
const minute=z.string().regex(timePattern,'请选择有效时间（精确到分钟）');
const weekDays=z.array(z.coerce.number().int().min(0).max(6)).min(1,'请至少选择一天').max(7);
const sameTime=(v:{start?:string,end?:string},ctx:z.RefinementCtx)=>{
 if(v.start!==undefined&&v.start===v.end)ctx.addIssue({code:'custom',path:['end'],message:'开始与结束不能相同'});
};
/** 网课时段：days 用真实星期几（0=周一 … 6=周日），不受节假日按周日规则影响。 */
export const timePointSchema=z.object({id:z.string().default(''),name:z.string().trim().min(1,'请输入名称').max(20,'名称最多 20 个字'),days:weekDays,start:minute,end:minute,mode:z.enum(['free','counted']).default('free'),dailyMinutes:count(1,480).optional()}).superRefine((v,ctx)=>{
 sameTime(v,ctx);
 if(v.mode==='counted'&&v.dailyMinutes===undefined)ctx.addIssue({code:'custom',path:['dailyMinutes'],message:'计入限额需要填写 1–480 分钟'});
});
export const exemptWindowSchema=z.object({id:z.string().default(''),name:z.string().trim().min(1,'请输入名称').max(20,'名称最多 20 个字'),days:weekDays,start:minute,end:minute}).superRefine(sameTime);
export type TimePoint=z.infer<typeof timePointSchema>;
export type ExemptWindow=z.infer<typeof exemptWindowSchema>;
/** 时区可选项（值 = IANA 名称），与 Android 端 DeviceClock.ZONES 保持一致；空 = 跟随设备 */
export const zoneOptions:[string,string][]=[['','跟随设备'],['UTC','UTC'],['Asia/Shanghai','中国 · 北京时间'],['Asia/Hong_Kong','香港'],['Asia/Taipei','台北'],['Asia/Tokyo','日本'],['Asia/Seoul','韩国'],['Asia/Singapore','新加坡'],['Asia/Dubai','迪拜'],['Europe/London','英国'],['Europe/Paris','法国 / 中欧'],['Europe/Berlin','德国'],['Europe/Moscow','莫斯科'],['America/New_York','美东'],['America/Chicago','美国中部'],['America/Denver','美国山地'],['America/Los_Angeles','美西'],['America/Sao_Paulo','圣保罗'],['Australia/Sydney','悉尼'],['Pacific/Auckland','奥克兰']];
const zoneIds=zoneOptions.map(([value])=>value);
export const ruleSchema=z.object({
 supervisionEnabled:z.boolean().default(true),
 memorizeFirst:z.boolean().default(false),
 spacedScope:z.enum(['all','wrong_only']).default('all'),
 dailyMinutes:count(1,480),sessionMinutes:count(1,120),questionsPerRound:count(1,100),roundsPerDay:count(1,100),passRatio:z.coerce.number().gt(0).max(1),playMode:z.enum(['round','streak','practice','count']),timezone:z.string().default('').refine(v=>zoneIds.includes(v),{message:'不支持的时区'}),allDay:z.boolean(),windowStart:z.string(),windowEnd:z.string(),extraEnabled:z.boolean(),wrongThreshold:count(1,100),extraQuestions:count(1,100),difficultyRewardEnabled:z.boolean(),difficultyRewards:z.record(z.coerce.number().min(0.1).max(120)),spacedRepetitionEnabled:z.boolean(),coverageFirst:z.boolean().default(true),newFirst:z.boolean().default(false),wrongFirst:z.boolean().default(false),scheduleMode:z.enum(['allow','control']),blockSettingsDuringControl:z.boolean().default(true),shuffleOptions:z.boolean().default(false),showCountdown:z.boolean().default(true),weekSlots:z.array(z.string().regex(/^[01]{48}$/)).length(7),holidayMode:z.enum(['sunday','normal']).default('sunday'),quizSubjects:z.array(z.string()).default([]),quizLevels:z.array(z.string()).default([]),gradeSubjects:z.record(z.string(),z.array(z.string())).default({}),dailyFreeMinutes:count(0,120).default(0),bootFreeMinutes:count(0,120).default(0),bootFreeIntervalMinutes:count(0,1440).default(0),dailyLimitEnabled:z.boolean().default(true),roundsLimitEnabled:z.boolean().default(true),restEnabled:z.boolean().default(false),restWatchMinutes:count(1,480).default(40),restMinutes:count(1,120).default(10),wrongReviewSeconds:count(0,300).default(0),fontScale:count(75,200).default(100),childSelectLevel:z.boolean().default(false),trafficAssistEnabled:z.boolean().default(false),backgroundAnchorEnabled:z.boolean().default(true),forceFullscreenGate:z.boolean().default(false),allowQuizUnlock:z.boolean().default(false),watchWhitelist:z.array(z.string().regex(/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/,'应用包名格式不正确')).max(100,'最多 100 个应用').default([]),quizBanks:z.array(z.string()).default([]),learningContent:z.enum(['quiz','video','alternate']).default('quiz'),educationalVideos:z.array(z.string().url()).max(50).default([]),videoMinutes:count(1,30).default(1),timePoints:z.array(timePointSchema).max(50,'最多 50 条网课时段').default([]),exemptWindows:z.array(exemptWindowSchema).max(50,'最多 50 条大人豁免').default([])
}).superRefine((c,ctx)=>{if(c.learningContent!=='quiz'&&!c.educationalVideos.length)ctx.addIssue({code:'custom',path:['educationalVideos'],message:'视频模式需要至少一个视频直链'});if(c.educationalVideos.some(url=>!/^https?:\/\//.test(url)))ctx.addIssue({code:'custom',path:['educationalVideos'],message:'请使用 HTTP/HTTPS 视频地址'});if(!c.allDay){if(!timePattern.test(c.windowStart))ctx.addIssue({code:'custom',path:['windowStart'],message:'请选择有效开始时间'});if(!timePattern.test(c.windowEnd))ctx.addIssue({code:'custom',path:['windowEnd'],message:'请选择有效结束时间'});if(timePattern.test(c.windowStart)&&c.windowStart===c.windowEnd)ctx.addIssue({code:'custom',path:['windowEnd'],message:'开始与结束不能相同，全天请开启「全天管控」'});}});
export type Rules=z.infer<typeof ruleSchema>;
export const defaults:Rules={supervisionEnabled:true,memorizeFirst:false,spacedScope:'all',dailyMinutes:40,sessionMinutes:10,questionsPerRound:5,roundsPerDay:3,passRatio:.6,playMode:'round',timezone:'',allDay:true,windowStart:'18:30',windowEnd:'20:00',extraEnabled:true,wrongThreshold:3,extraQuestions:2,difficultyRewardEnabled:false,difficultyRewards:{1:1,2:2,3:3,4:4,5:5},spacedRepetitionEnabled:false,coverageFirst:true,newFirst:false,wrongFirst:false,scheduleMode:'allow',blockSettingsDuringControl:true,shuffleOptions:false,showCountdown:true,weekSlots:Array(7).fill('1'.repeat(48)),holidayMode:'sunday',quizSubjects:[],quizLevels:[],gradeSubjects:{} as Record<string,string[]>,dailyFreeMinutes:0,bootFreeMinutes:0,bootFreeIntervalMinutes:0,dailyLimitEnabled:true,roundsLimitEnabled:true,restEnabled:false,restWatchMinutes:40,restMinutes:10,wrongReviewSeconds:0,fontScale:100,childSelectLevel:false,trafficAssistEnabled:false,backgroundAnchorEnabled:true,forceFullscreenGate:false,allowQuizUnlock:false,watchWhitelist:[],quizBanks:[],learningContent:'quiz',educationalVideos:[],videoMinutes:1,timePoints:[],exemptWindows:[]};
/** 归一化按年级设置的科目映射（ST-604）：去除空年级键、去重并清洗空白科目名。 */
export function normalizeGradeSubjects(raw:unknown):Record<string,string[]>{
 if(!raw||typeof raw!=='object'||Array.isArray(raw))return {};
 const out:Record<string,string[]>={};
 for(const [rawKey,rawVal] of Object.entries(raw as Record<string,unknown>)){
  const key=rawKey.trim();
  if(!key)continue;
  const list=Array.isArray(rawVal)?rawVal:[];
  const seen=new Set<string>();
  const cleaned:string[]=[];
  for(const item of list){
   if(typeof item!=='string')continue;
   const subject=item.trim();
   if(!subject||seen.has(subject))continue;
   seen.add(subject);
   cleaned.push(subject);
  }
  out[key]=cleaned;
 }
 return out;
}
/** 归一化旧配置中的网课时段：mode === 'counted' 时补齐默认 60 分钟并夹紧到 1–480。 */
export function normalizeTimePoints(points:unknown):TimePoint[]{
 if(!Array.isArray(points))return [];
 return points.map(raw=>{
  const item=raw as TimePoint;
  if(item?.mode!=='counted')return item;
  const n=item.dailyMinutes===undefined||item.dailyMinutes===null||(item.dailyMinutes as unknown)===''?NaN:Number(item.dailyMinutes);
  const dailyMinutes=Number.isFinite(n)?Math.min(480,Math.max(1,Math.round(n))):60;
  return {...item,dailyMinutes};
 });
}
export function normalizeRules(config?:Partial<Rules>|null):Rules{
 const merged={...defaults,...(config||{})};
 return {...merged,timePoints:normalizeTimePoints(merged.timePoints),gradeSubjects:normalizeGradeSubjects(merged.gradeSubjects)};
}
export function weekFrom(start:string,end:string){const chars=Array(48).fill('0');const min=(s:string)=>{const[h,m]=s.split(':').map(Number);return h*60+m;};const a=min(start),b=min(end);for(let i=0;i<48;i++){const t=i*30;if((a<b&&t>=a&&t<b)||(a>b&&(t>=a||t<b)))chars[i]='1';}return Array(7).fill(chars.join(''));}
export function segments(allDay:boolean,start:string,end:string){if(allDay)return[{left:0,width:100}];if(!timePattern.test(start)||!timePattern.test(end)||start===end)return[];const min=(s:string)=>{const [h,m]=s.split(':').map(Number);return h*60+m;};const s=min(start)/14.4,e=min(end)/14.4;return s<e?[{left:s,width:e-s}]:[{left:s,width:100-s},{left:0,width:e}];}
