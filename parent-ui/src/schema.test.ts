import assert from 'node:assert/strict';
import {defaults,normalizeGradeSubjects,normalizeRules,normalizeTimePoints,ruleSchema,segments} from './schema.ts';
assert.equal(ruleSchema.safeParse({...defaults,allDay:false,windowStart:'',windowEnd:''}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,allDay:false,windowStart:'18:30',windowEnd:'18:30'}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,allDay:true,windowStart:'18:30',windowEnd:'18:30'}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,allDay:false,windowStart:'25:00'}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,allDay:false,windowStart:'22:00',windowEnd:'06:00'}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,allDay:true,windowStart:'',windowEnd:''}).success,true);
assert.deepEqual(segments(false,'',''),[]);
assert.deepEqual(segments(true,'18:30','20:00'),[{left:0,width:100}]);
const overnight=segments(false,'22:00','06:00');
assert.equal(overnight.length,2);
assert.ok(Math.abs(overnight.reduce((sum,s)=>sum+s.width,0)-100/3)<1e-8);
assert.equal(ruleSchema.safeParse({...defaults,questionsPerRound:NaN}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,dailyFreeMinutes:1.5}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,dailyFreeMinutes:NaN}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,dailyFreeMinutes:0}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,dailyFreeMinutes:120}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,dailyFreeMinutes:121}).success,false);
for (const bootFreeIntervalMinutes of [0, 120, 1440]) {
  assert.equal(ruleSchema.parse({...defaults,bootFreeIntervalMinutes}).bootFreeIntervalMinutes,bootFreeIntervalMinutes);
}
for (const bootFreeIntervalMinutes of [-1, 1.5, 1441]) {
  assert.equal(ruleSchema.safeParse({...defaults,bootFreeIntervalMinutes}).success,false);
}
const legacy={...defaults} as Record<string,unknown>;
delete legacy.shuffleOptions;delete legacy.showCountdown;delete legacy.bootFreeIntervalMinutes;
const migrated=ruleSchema.parse(legacy);
assert.equal(migrated.shuffleOptions,false);
assert.equal(migrated.showCountdown,true);
assert.equal(migrated.bootFreeIntervalMinutes,0);
// #136：旧版本没有「错题优先」，读出来默认关闭
delete legacy.wrongFirst;
assert.equal(ruleSchema.parse(legacy).wrongFirst,false);
assert.equal(ruleSchema.parse({...defaults,wrongFirst:true}).wrongFirst,true);
// 覆盖优先是默认开启的，旧配置里没有这个键也读成 true
delete legacy.coverageFirst;
assert.equal(ruleSchema.parse(legacy).coverageFirst,true);
assert.equal(ruleSchema.parse({...defaults,coverageFirst:false}).coverageFirst,false);
const configured=ruleSchema.parse({...defaults,shuffleOptions:true,showCountdown:false,dailyFreeMinutes:15});
assert.equal(configured.shuffleOptions,true);
assert.equal(configured.showCountdown,false);
assert.equal(configured.dailyFreeMinutes,15);
assert.equal(ruleSchema.safeParse({...defaults,playMode:'count',dailyLimitEnabled:false,roundsLimitEnabled:false}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,restEnabled:true,restWatchMinutes:0}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,wrongReviewSeconds:301}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,fontScale:74}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,learningContent:'video',educationalVideos:[]}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,learningContent:'video',educationalVideos:['https://example.test/video.mp4']}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,learningContent:'video',educationalVideos:['ftp://example.test/video.mp4']}).success,false);
// #148 / P1：不计时白名单与答题页密码解锁。旧配置没有这两个键时按关闭处理。
const oldPolicy={...defaults} as Record<string,unknown>;
delete oldPolicy.allowQuizUnlock;delete oldPolicy.watchWhitelist;
const oldParsed=ruleSchema.parse(oldPolicy);
assert.equal(oldParsed.allowQuizUnlock,false);
assert.deepEqual(oldParsed.watchWhitelist,[]);
assert.equal(ruleSchema.safeParse({...defaults,allowQuizUnlock:true}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,watchWhitelist:['com.example.app']}).success,true);
assert.equal(ruleSchema.safeParse({...defaults,watchWhitelist:['not a package']}).success,false);
assert.equal(ruleSchema.safeParse({...defaults,watchWhitelist:Array.from({length:101},(_,i)=>`com.example.app${i}`)}).success,false);
// #230：出题顺序「没做过的题先出」。旧配置没有这个键时按关闭处理。
const orderConfig={...defaults} as Record<string,unknown>;
delete orderConfig.newFirst;
assert.equal(ruleSchema.parse(orderConfig).newFirst,false);
assert.equal(ruleSchema.parse({...defaults,newFirst:true}).newFirst,true);
// Phase 5：纯粹学习模式开关、先背再测、只背错题
const prePhase5={...defaults} as Record<string,unknown>;
delete prePhase5.supervisionEnabled;
delete prePhase5.memorizeFirst;
delete prePhase5.spacedScope;
const migratedPhase5=ruleSchema.parse(prePhase5);
assert.equal(migratedPhase5.supervisionEnabled,true);
assert.equal(migratedPhase5.memorizeFirst,false);
assert.equal(migratedPhase5.spacedScope,'all');
assert.equal(ruleSchema.parse({...defaults,supervisionEnabled:false,memorizeFirst:true,spacedScope:'wrong_only'}).supervisionEnabled,false);
assert.equal(ruleSchema.parse({...defaults,memorizeFirst:true}).memorizeFirst,true);
assert.equal(ruleSchema.parse({...defaults,spacedScope:'wrong_only'}).spacedScope,'wrong_only');
assert.equal(ruleSchema.safeParse({...defaults,spacedScope:'invalid'}).success,false);
// ST-501：旧配置 timePoints 归一化（counted 缺省/null/越界）
const normalizedPoints=normalizeTimePoints([
 {id:'a',name:'网课1',days:[0],start:'19:00',end:'20:00',mode:'counted'},
 {id:'b',name:'网课2',days:[1],start:'19:00',end:'20:00',mode:'counted',dailyMinutes:null as unknown as number},
 {id:'c',name:'网课3',days:[2],start:'19:00',end:'20:00',mode:'counted',dailyMinutes:0},
 {id:'d',name:'网课4',days:[3],start:'19:00',end:'20:00',mode:'counted',dailyMinutes:999},
 {id:'e',name:'网课5',days:[4],start:'19:00',end:'20:00',mode:'counted',dailyMinutes:45},
 {id:'f',name:'网课6',days:[5],start:'19:00',end:'20:00',mode:'free'},
]);
assert.equal(normalizedPoints[0].dailyMinutes,60);
assert.equal(normalizedPoints[1].dailyMinutes,60);
assert.equal(normalizedPoints[2].dailyMinutes,1);
assert.equal(normalizedPoints[3].dailyMinutes,480);
assert.equal(normalizedPoints[4].dailyMinutes,45);
assert.equal(normalizedPoints[5].dailyMinutes,undefined);
assert.deepEqual(normalizeTimePoints(undefined),[]);
assert.equal(normalizeRules({timePoints:[{id:'x',name:'数学',days:[0],start:'19:00',end:'20:00',mode:'counted'}]}).timePoints[0].dailyMinutes,60);
// ST-604：科目与年级主从组合（gradeSubjects）默认值、校验与归一化
const preGradeSubjects={...defaults} as Record<string,unknown>;
delete preGradeSubjects.gradeSubjects;
assert.deepEqual(ruleSchema.parse(preGradeSubjects).gradeSubjects,{});
assert.deepEqual(
 ruleSchema.parse({...defaults,gradeSubjects:{'三年级':['英语'],'四年级':['语文','数学','英语'],'五年级':[]}}).gradeSubjects,
 {'三年级':['英语'],'四年级':['语文','数学','英语'],'五年级':[]}
);
assert.equal(ruleSchema.safeParse({...defaults,gradeSubjects:{'三年级':'英语'} as unknown as Record<string,string[]>}).success,false);
assert.deepEqual(
 normalizeGradeSubjects({' 三年级 ':[' 英语 ','英语',' ','数学'],'  ':['语文'],'四年级':[],'五年级':null}),
 {'三年级':['英语','数学'],'四年级':[],'五年级':[]}
);
assert.deepEqual(normalizeGradeSubjects(null),{});
assert.deepEqual(normalizeGradeSubjects(['三年级']),{});
assert.deepEqual(
 normalizeRules({gradeSubjects:{'三年级':['英语','英语','']}}).gradeSubjects,
 {'三年级':['英语']}
);
console.log('PASS schedule validation, quotas, rest/review/font bounds, video settings, watch whitelist, question order, study modes and backward-compatible defaults');
