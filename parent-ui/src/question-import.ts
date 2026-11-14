// 外部题库的字段名不统一（question / 题目 / stem、options / 选项、answer / 答案…），
// 而服务端只认 q / a / k / correctIndices 这一套。以前把外部 JSON 原样提交，
// 第一道题就卡在「请填写题干」，整批事务回滚，用户只看到一句没头没尾的报错。
// 这里在提交前统一转换，并按题号报告哪几道跳过、为什么跳过。

export type ImportIssue={index:number;reason:string};
export type BankImport={items:any[];issues:ImportIssue[];total:number};

// 题型别名。key 已去掉空格、下划线、连字符并转小写。
const TYPES:Record<string,string>={
 single:'single',singlechoice:'single',radio:'single',onechoice:'single','单选':'single','单选题':'single','单项选择':'single','单项选择题':'single','选择题':'single',
 multiple:'multiple',multichoice:'multiple',multiplechoice:'multiple',checkbox:'multiple',multianswer:'multiple','多选':'multiple','多选题':'multiple','多项选择':'multiple','多项选择题':'multiple','不定项选择':'multiple','不定项选择题':'multiple',
 boolean:'boolean',judge:'boolean',judgement:'boolean',truefalse:'boolean',tf:'boolean','判断':'boolean','判断题':'boolean','是非题':'boolean','对错题':'boolean','正误题':'boolean',
 text:'text',fill:'text',blank:'text',fillblank:'text',fillintheblank:'text','填空':'text','填空题':'text',
 subjective:'subjective',essay:'subjective',shortanswer:'subjective',qa:'subjective','主观':'subjective','主观题':'subjective','问答':'subjective','问答题':'subjective','简答':'subjective','简答题':'subjective','解答题':'subjective','论述题':'subjective','应用题':'subjective'
};

const STEM_KEYS=['q','stem','question','questionText','title','content','text','题干','题目','问题','题目内容','题目文字'];
const OPTION_KEYS=['a','options','choices','opts','option','items','选择项','选项','选项列表','备选项','答案选项'];
const TIP_KEYS=['tip','explanation','explain','analysis','answerAnalysis','解析','解答','说明','讲解','题目解析'];
const SUBJECT_KEYS=['subject','学科','科目'];
const LEVEL_KEYS=['level','grade','年级','学段'];
const BANK_KEYS=['bank','bankName','题库','题库名称','题库名'];
const IMAGE_KEYS=['image','imageUrl','img','图片','图片地址'];
const AUDIO_KEYS=['audio','audioUrl','音频','音频地址'];
const LANGUAGE_KEYS=['language','lang','语言'];

// 本项目的答案字段（序号从 0 开始）；其余答案字段（answer / 答案…）的序号规则要看整份题库。
const OWN_ANSWER_KEYS=['correctIndices','correctIndex','correct_index','k'];
const ANSWER_KEYS=['answers','answer','correct','correctAnswer','correctAnswers','key','solution','正确答案','答案','正确选项','参考答案'];

const TAG=/<\/?(?:p|br|div|span|b|i|u|s|strong|em|sub|sup|li|ul|ol|table|tbody|tr|td|th|h[1-6])\b[^>]*>/gi;
const OPTION_LABEL=/^\s*(?:[（(]\s*[A-Za-zＡ-Ｚ]\s*[)）]|[A-Za-zＡ-Ｚ]\s*[.、．:：)）])\s*/;

function lookup(item:any,keys:string[]):any{
 // 字段名大小写不敏感：Answer / ANSWER / answer 都算
 const lower:Record<string,string>={};
 for(const key of Object.keys(item))lower[key.toLowerCase()]=key;
 for(const key of keys){
  const hit=lower[key.toLowerCase()];
  if(hit!==undefined&&item[hit]!==undefined&&item[hit]!==null)return item[hit];
 }
 return undefined;
}

function clean(value:any):string{
 return String(value??'').replace(TAG,' ').replace(/&nbsp;/gi,' ').replace(/&lt;/gi,'<').replace(/&gt;/gi,'>').replace(/&amp;/gi,'&').replace(/\s+/g,' ').trim();
}

function textOf(value:any):string{
 if(value&&typeof value==='object')return clean(value.text??value.content??value.label??value.value??value.option??value.title??value.name??'');
 return clean(value);
}

function stemOf(item:any):string{
 // 按 STEM_KEYS 的顺序取第一个非空字段：q / stem / question 比 title 更可能是题干
 return clean(lookup(item,STEM_KEYS));
}

function finishOptions(list:any[]):string[]{
 const options=list.map(textOf).map(option=>option.replace(OPTION_LABEL,''));
 // 首尾的空选项直接丢掉；中间的空选项保留，让服务端报「选项不能为空」，能定位到是哪道题
 while(options.length&&!options[0])options.shift();
 while(options.length&&!options[options.length-1])options.pop();
 return options;
}

function optionsOf(item:any):string[]{
 let raw=lookup(item,OPTION_KEYS);
 if(raw&&typeof raw==='object'&&!Array.isArray(raw)){
  // {"A":"北京","B":"上海"} 这种按字母顺序还原
  raw=Object.keys(raw).sort().map(key=>raw[key]);
 }
 if(Array.isArray(raw))return finishOptions(raw);
 // 有些题库把选项拆成 optionA / optionB / 选项A 几个字段
 const lettered:string[]=[];
 for(const letter of 'ABCDEFGH'){
  const value=lookup(item,[`option${letter}`,`option_${letter}`,`${letter}选项`,`选项${letter}`]);
  if(value===undefined||typeof value==='object')break;
  lettered.push(value);
 }
 return lettered.length>=2?finishOptions(lettered):[];
}

function letterIndex(token:string):number{
 const s=token.trim();
 if(s.length!==1)return -1;
 const code=s.toUpperCase().charCodeAt(0);
 if(code>=0xFF21&&code<=0xFF3A)return code-0xFF21;   // 全角 Ａ-Ｚ
 if(code>=65&&code<=90)return code-65;
 return -1;
}

function boolOf(raw:any):boolean|null{
 if(typeof raw==='boolean')return raw;
 if(Array.isArray(raw))return raw.length===1?boolOf(raw[0]):null;
 const s=String(raw??'').trim().toLowerCase();
 if(['正确','对','是','真','√','✓','v','t','true','yes','y','1'].includes(s))return true;
 if(['错误','错','否','假','×','✗','x','f','false','no','n','0'].includes(s))return false;
 return null;
}

// 判断题以外的题型里，"1"/"0" 更可能是序号而不是对错
function boolWord(raw:any):boolean|null{
 if(typeof raw==='boolean')return raw;
 const s=String(raw??'').trim().toLowerCase();
 if(['1','0'].includes(s))return null;
 return boolOf(s);
}

function detectNumbering(items:any[]):'zero'|'one'{
 let sawZero=false,sawMax=false;
 for(const item of items){
  if(!item||typeof item!=='object')continue;
  if(lookup(item,OWN_ANSWER_KEYS)!==undefined)continue;   // 本项目字段一律 0 起
  const raw=lookup(item,ANSWER_KEYS);
  const length=optionsOf(item).length;
  if(!length)continue;
  for(const token of Array.isArray(raw)?raw:[raw]){
   const n=typeof token==='number'?token:/^\d+$/.test(String(token??'').trim())?Number(token):NaN;
   if(!Number.isFinite(n))continue;
   if(n===0)sawZero=true;
   if(n===length)sawMax=true;      // 等于选项个数只可能是从 1 开始数
  }
 }
 return sawMax&&!sawZero?'one':'zero';
}

function parseSelection(raw:any,options:string[],numbering:'zero'|'one'):number[]{
 let tokens:any[];
 if(Array.isArray(raw))tokens=raw;
 else if(typeof raw==='number')tokens=[raw];
 else if(typeof raw==='string'){
  const s=raw.trim();
  if(!s)return [];
  const plain=s.replace(/[（）()【】\s]/g,'');
  // "ABD" / "ac" 这种连写的字母
  if(/^[A-Za-zＡ-Ｚａ-ｚ]+$/.test(plain)&&plain.length>1)tokens=plain.split('');
  else if(/^[A-Za-zＡ-Ｚａ-ｚ]$/.test(plain))tokens=[plain];
  else tokens=plain.split(/[,，、;；/|&和及]+/).filter(Boolean);
 }else return [];
 const indices:number[]=[];
 for(const token of tokens){
  if(typeof token==='number'){
   if(Number.isInteger(token))indices.push(numbering==='one'?token-1:token);
   continue;
  }
  const s=String(token??'').trim();
  // "A" 或 "A. 北京" 都要认成选项 A
  const bare=s.replace(OPTION_LABEL,'').trim();
  const letter=letterIndex(s)>=0?letterIndex(s):letterIndex(bare);
  if(letter>=0){indices.push(letter);continue;}
  if(/^\d+$/.test(s)){const n=Number(s);indices.push(numbering==='one'?n-1:n);continue;}
  // 有些题库直接把选项原文写进 answer
  const byText=options.findIndex(option=>option===s||option===bare);
  if(byText>=0)indices.push(byText);
 }
 return [...new Set(indices.filter(index=>index>=0&&index<options.length))];
}

function answerTexts(raw:any):string[]{
 if(Array.isArray(raw))return raw.map(textOf).filter(Boolean);
 const s=clean(raw);
 if(!s)return [];
 // 多个可接受答案常用 | / 或 分隔
 return s.split(/[|｜;；]|或/).map(part=>part.trim()).filter(Boolean);
}

function languageOf(raw:any):string{
 const s=String(raw??'').trim().replace(/_/g,'-').toLowerCase();
 const map:Record<string,string>={'':'',zh:'zh-CN','zh-cn':'zh-CN','zh-hans':'zh-CN','zh-hk':'zh-HK','zh-tw':'zh-TW','zh-hant':'zh-TW','en':'en-US','en-us':'en-US','en-gb':'en-GB'};
 return map[s]??'';
}

function difficultyOf(item:any):number{
 const raw=lookup(item,['difficulty','难度','stars','star']);
 const numeric=parseFloat(String(raw??''));
 if(Number.isFinite(numeric))return Math.min(5,Math.max(1,Math.round(numeric)));
 const word=String(raw??'').trim().toLowerCase();
 if(['简单','容易','易','easy','low'].includes(word))return 2;
 if(['中等','一般','普通','medium','normal'].includes(word))return 3;
 if(['困难','难','较难','high','hard','difficult'].includes(word))return 4;
 return 2;
}

function typeOf(item:any,options:string[],answer:any,numbering:'zero'|'one'):string{
 const declared=lookup(item,['type','题型','类型','questionType']);
 const key=String(declared??'').toLowerCase().replace(/[\s_\-]/g,'');
 if(key&&TYPES[key])return TYPES[key];
 if(key){
  // 题库里常见 "choice" 这种泛指，按选项和答案再判断
  if(options.length>=2&&['choice','选择','pick'].includes(key))return parseSelection(answer,options,numbering).length>1?'multiple':'single';
  throw new Error(`不支持的题型：${declared}`);
 }
 if(options.length>=2)return parseSelection(answer,options,numbering).length>1?'multiple':'single';
 if(boolWord(answer)!==null)return 'boolean';
 if(clean(answer))return 'text';
 throw new Error('缺少选项和答案，无法判断题型');
}

function normalizeItem(item:any,numbering:'zero'|'one'):any{
 if(!item||typeof item!=='object'||Array.isArray(item))throw new Error('不是一条题目对象');
 const stem=stemOf(item);
 if(!stem)throw new Error('缺少题干（可用字段：q / question / 题目 / 题干）');
 if(stem.length>5000)throw new Error('题干超过 5000 字');
 const options=optionsOf(item);
 const ownAnswer=lookup(item,OWN_ANSWER_KEYS);
 const answer=ownAnswer!==undefined?ownAnswer:lookup(item,ANSWER_KEYS);
 const answerNumbering=ownAnswer!==undefined?'zero':numbering;
 const type=typeOf(item,options,answer,answerNumbering);
 const out:any={q:stem,type,difficulty:difficultyOf(item)};
 // 导出再导入时保留 id，重复导入同一份题库是覆盖而不是又加一份
 const id=clean(lookup(item,['id']));
 if(id)out.id=id;

 if(type==='boolean'){
  // 本项目用 k 表示选项序号：0 = 正确，1 = 错误；外部题库多用「正确 / 对 / √」
  const correct=typeof answer==='number'?answer===0:boolOf(answer);
  if(correct===null)throw new Error('判断题缺少正确答案（正确 / 错误）');
  out.a=['正确','错误'];out.k=correct?0:1;
 }else if(type==='text'){
  const answers=answerTexts(answer);
  if(!answers.length)throw new Error('填空题缺少答案');
  out.answers=answers;
 }else if(type==='subjective'){
  const model=clean(lookup(item,['modelAnswer','referenceAnswer','参考答案','标准答案','答案'])??answer);
  out.modelAnswer=model;
  const rubric=clean(lookup(item,['rubric','评分标准','评分规则']));
  if(rubric)out.rubric=rubric;
 }else{
  if(options.length<2)throw new Error('选择题至少需要 2 个选项');
  if(options.length>8)throw new Error(`选项有 ${options.length} 个，最多支持 8 个`);
  out.a=options;
  const indices=parseSelection(answer,options,answerNumbering);
  if(!indices.length)throw new Error('缺少正确答案，或答案与选项对不上（可用字段：k / correctIndices / answer / 答案）');
  if(type==='multiple')out.correctIndices=indices.sort((a,b)=>a-b);
  else out.k=indices[0];
 }

 const tip=clean(lookup(item,TIP_KEYS));
 if(tip)out.tip=tip;
 const subject=clean(lookup(item,SUBJECT_KEYS));
 if(subject)out.subject=subject;
 const level=clean(lookup(item,LEVEL_KEYS));
 if(level)out.level=level;
 const bank=clean(lookup(item,BANK_KEYS));
 if(bank)out.bank=bank.slice(0,64);
 const image=clean(lookup(item,IMAGE_KEYS));
 if(image)out.image=image;
 const audio=clean(lookup(item,AUDIO_KEYS));
 if(audio)out.audio=audio;
 const language=lookup(item,LANGUAGE_KEYS);
 if(language!==undefined)out.language=languageOf(language);
 return out;
}

function unwrap(input:any):any[]{
 if(Array.isArray(input))return input;
 if(!input||typeof input!=='object')return [];
 // {"banks":[{"name":"…","questions":[…]}…]}：把题库名带到每道题上
 if(Array.isArray(input.banks)){
  const out:any[]=[];
  for(const bank of input.banks){
   if(!bank||typeof bank!=='object')continue;
   const list=Array.isArray(bank.questions)?bank.questions:Array.isArray(bank.items)?bank.items:[];
   const name=clean(bank.name??bank.title??bank.bank??'');
   for(const question of list)out.push(name&&question&&typeof question==='object'&&!question.bank?{...question,bank:name}:question);
  }
  if(out.length)return out;
 }
 for(const key of ['items','questions','list','data','题库','题目']){
  const value=input[key];
  if(Array.isArray(value))return value;
  if(value&&typeof value==='object'){
   const nested=unwrap(value);
   if(nested.length)return nested;
  }
 }
 return [input];
}

export function parseBankJson(raw:string):any{
 const text=raw.replace(/^﻿/,'').trim();
 try{return JSON.parse(text);}
 catch(error){
  // 有些题库导出成一行一题的 JSONL
  const lines=text.split(/\r?\n/).map(line=>line.trim()).filter(Boolean);
  if(lines.length>1){
   try{return lines.map(line=>JSON.parse(line));}catch{/* 落到下面的报错 */ }
  }
  throw new Error(`JSON 格式有误：${(error as Error).message}`);
 }
}

export function normalizeBank(input:any):BankImport{
 const raw=unwrap(input);
 const numbering=detectNumbering(raw);
 const items:any[]=[];
 const issues:ImportIssue[]=[];
 raw.forEach((item,index)=>{
  try{items.push(normalizeItem(item,numbering));}
  catch(error:any){issues.push({index:index+1,reason:error?.message||'格式无法识别'});}
 });
 return {items,issues,total:raw.length};
}
