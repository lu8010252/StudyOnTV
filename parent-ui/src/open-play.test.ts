// 示例题库是给家长和其他 AI 参考的模板：必须能原样导入，字段和题型不能漏。
// expected 与 LocalStore.upsertQuestion 的校验保持一致，改规则时两边一起改。
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const bank:any=JSON.parse(readFileSync(new URL('../../data/banks/open-play.json',import.meta.url),'utf8'));
const types=['single','multiple','boolean','text','subjective'];
const languages=['','zh-CN','zh-HK','zh-TW','en-US','en-GB'];

assert.equal(bank.version,2);
assert.ok(Array.isArray(bank.items)&&bank.items.length>=1&&bank.items.length<=5000);
const covered=new Set<string>(),stems=new Set<string>(),subjects=new Set<string>();
for(const item of bank.items){
 const type=item.type||'single';
 const where=`题目「${String(item.q).slice(0,20)}」`;
 assert.ok(types.includes(type),`${where}题型不支持：${type}`);
 assert.equal(typeof item.q,'string');
 assert.ok(item.q.trim().length>0&&item.q.length<=5000,`${where}题干为空或过长`);
 assert.ok(!/<[a-z\/]/i.test(item.q),`${where}题干不要写 HTML`);
 covered.add(type);
 if(type==='text'){
  assert.ok(Array.isArray(item.answers)&&item.answers.length>0,`${where}填空题缺少 answers`);
  assert.ok(item.answers.every((a:any)=>typeof a==='string'&&a.trim()),`${where}填空答案不能为空`);
 }else if(type==='subjective'){
  assert.ok(String(item.modelAnswer||'').trim(),`${where}主观题缺少 modelAnswer`);
  assert.ok(String(item.rubric||'').trim(),`${where}主观题缺少 rubric`);
 }else{
  const options=type==='boolean'?['正确','错误']:item.a;
  assert.ok(Array.isArray(options)&&options.length>=2&&options.length<=8,`${where}需要 2–8 个选项`);
  assert.ok(options.every((o:any)=>typeof o==='string'&&o.trim()),`${where}选项不能为空`);
  const indices=type==='multiple'?item.correctIndices:[item.k];
  assert.ok(Array.isArray(indices)&&indices.length>0,`${where}缺少正确答案`);
  for(const index of indices)assert.ok(Number.isInteger(index)&&index>=0&&index<options.length,`${where}答案序号越界`);
 }
 const difficulty=item.difficulty??2;
 assert.ok(Number.isInteger(difficulty)&&difficulty>=1&&difficulty<=5,`${where}难度需要为 1–5 星`);
 if(item.bank!==undefined)assert.ok(item.bank.trim().length>=1&&item.bank.trim().length<=64,`${where}题库名称长度不对`);
 if(item.language!==undefined)assert.ok(languages.includes(item.language),`${where}朗读语言不在允许列表`);
 for(const key of ['image','audio'])if(item[key])assert.match(item[key],/^https?:\/\//,`${where}${key} 需要是 http(s) 链接`);
 assert.equal(item.id,undefined,`${where}示例题库不要写 id，避免复制后互相覆盖`);
 assert.ok(!stems.has(item.q),`${where}题干重复`);
 stems.add(item.q);
 if(item.subject)subjects.add(item.subject);
}
for(const type of types)assert.ok(covered.has(type),`示例题库缺少 ${type} 题型`);
assert.ok(subjects.size>=5,`示例题库的学科太少：${[...subjects].join('、')}`);
console.log(`PASS open-play.json（${bank.items.length} 题，${types.length} 种题型，${subjects.size} 个学科）`);
