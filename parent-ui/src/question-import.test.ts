// 外部题库导入：字段名、答案写法、选项写法各不相同，都要能转成本项目的 q / a / k。
// 坏题只跳过自己并带题号，不能因为一道题让整批导入报「请填写题干」。
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {normalizeBank,parseBankJson} from './question-import.ts';

// 1. 本项目自己的示例题库原样通过（导出的 JSON 再导入不能被改坏）
const bank:any=JSON.parse(readFileSync(new URL('../../data/banks/open-play.json',import.meta.url),'utf8'));
const own=normalizeBank(bank);
assert.equal(own.total,bank.items.length);
assert.deepEqual(own.issues,[]);
for(let i=0;i<bank.items.length;i++){
 const before=bank.items[i],after=own.items[i];
 assert.equal(after.q,before.q);
 assert.equal(after.type,before.type);
 assert.equal(after.difficulty,before.difficulty??2);
 // 判断题的选项固定为「正确 / 错误」，题库里可以省略
 assert.deepEqual(after.a,before.a??(before.type==='boolean'?['正确','错误']:undefined));
 assert.equal(after.k,before.k);
 assert.deepEqual(after.correctIndices,before.correctIndices);
 assert.deepEqual(after.answers,before.answers);
 assert.equal(after.modelAnswer,before.modelAnswer);
 assert.equal(after.subject,before.subject);
 assert.equal(after.bank,before.bank);
 assert.equal(after.tip,before.tip);
 assert.equal(after.language,before.language??undefined);
}

// 2. 中文字段名
assert.deepEqual(normalizeBank([{题目:'中国的首都是？',选项:['北京','上海','广州'],答案:'A',题型:'单选题',解析:'首都是北京',难度:1}]).items[0],
 {q:'中国的首都是？',type:'single',difficulty:1,a:['北京','上海','广州'],k:0,tip:'首都是北京'});

// 3. 英文字段名 + 对象选项，没写题型时按选项和答案判断
assert.equal(normalizeBank([{question:'2 + 2 = ?',options:[{text:'3'},{text:'4'}],answer:'B'}]).items[0].k,1);

// 4. 序号从 1 开始（有答案等于选项个数，只可能是 1 起）
const oneBased=normalizeBank([{question:'a',options:['x','y','z'],answer:3},{question:'b',options:['x','y'],answer:1}]).items;
assert.deepEqual(oneBased.map(q=>q.k),[2,0]);

// 5. 序号从 0 开始（出现 0 就按 0 起）
const zeroBased=normalizeBank([{question:'a',options:['x','y'],answer:0},{question:'b',options:['x','y'],answer:1}]).items;
assert.deepEqual(zeroBased.map(q=>q.k),[0,1]);

// 6. 多选：字母、顿号、连写都认
for(const answer of ['A、C','AC','A,C','a c'])assert.deepEqual(normalizeBank([{question:'选出偶数',options:['1','2','3','4'],answer}]).items[0].correctIndices,[0,2],`答案写法：${answer}`);

// 7. 判断题
assert.equal(normalizeBank([{题目:'地球是圆的',答案:'正确'}]).items[0].k,0);
assert.equal(normalizeBank([{题目:'地球是方的',答案:'×'}]).items[0].k,1);
assert.equal(normalizeBank([{题目:'太阳从东边升起',答案:true}]).items[0].type,'boolean');

// 8. 填空题：多个可接受答案
assert.deepEqual(normalizeBank([{题目:'长江又称',答案:'扬子江|大江'}]).items[0].answers,['扬子江','大江']);

// 9. 选项自带 "A." 前缀、题干带 HTML
const messy=normalizeBank([{题目:'<p>1 + 1 = ?</p>',选项:['A. 1','B. 2'],答案:'B'}]).items[0];
assert.equal(messy.q,'1 + 1 = ?');
assert.deepEqual(messy.a,['1','2']);

// 10. 坏题只跳过自己，并按题号报告
const mixed=normalizeBank([
 {question:'能导入的题',options:['对','错'],answer:'A'},
 {name:'没有题干'},
 {question:'既没有选项也没有答案'},
 {题目:'也能导入',答案:'填空答案'}
]);
assert.equal(mixed.items.length,2);
assert.deepEqual(mixed.issues.map(i=>i.index),[2,3]);
assert.match(mixed.issues[0].reason,/题干/);
assert.match(mixed.issues[1].reason,/选项和答案/);

// 11. 常见外层包装
assert.equal(normalizeBank({version:2,items:[{question:'a',options:['x','y'],answer:'A'}]}).items.length,1);
assert.equal(normalizeBank({data:{questions:[{question:'a',options:['x','y'],answer:'A'}]}}).items.length,1);
const banks=normalizeBank({banks:[{name:'三年级数学',questions:[{question:'a',options:['x','y'],answer:'A'},{question:'b',options:['x','y'],answer:'B'}]}]});
assert.deepEqual(banks.items.map(q=>q.bank),['三年级数学','三年级数学']);

// 12. 只给一个对象（不是数组）也能导入
assert.equal(normalizeBank({question:'a',options:['x','y'],answer:'A'}).items.length,1);

// 13. 解析：JSON、JSONL、错误提示
assert.equal(parseBankJson('[{"q":"a"}]').length,1);
assert.equal(parseBankJson('{"q":"a"}\n{"q":"b"}').length,2);
assert.throws(()=>parseBankJson('{oops'),/JSON/);

// 14. 题型写错时要说明，而不是悄悄当成单选
assert.match(normalizeBank([{question:'a',options:['x','y'],answer:'A',type:'排序题'}]).issues[0].reason,/排序题/);

// 15. 选项拆成 optionA / optionB 字段，答案写成选项原文
const lettered=normalizeBank([{question:'中国的首都',optionA:'北京',optionB:'上海',optionC:'广州',answer:'A. 北京'}]).items[0];
assert.deepEqual(lettered.a,['北京','上海','广州']);
assert.equal(lettered.k,0);

// 16. 语言字段：合法值保留，奇怪的值退回跟随系统
assert.equal(normalizeBank([{question:'a',options:['x','y'],answer:'A',language:'zh_TW'}]).items[0].language,'zh-TW');
assert.equal(normalizeBank([{question:'a',options:['x','y'],answer:'A',language:'klingon'}]).items[0].language,'');

// 17. 导出 -> 再导入往返一致性：保留多行富文本列表、最多 4 张配图、纯图片选项与暂停状态 enabled: false
const roundTripInput={
 version:2,
 items:[
  {
   id:'q-rich-1',
   type:'single',
   q:'阅读下面条件：\r\n- **甲** 比 **乙** 大 2 岁\r\n- ==两人年龄之和== 为 $18$',
   a:['','',''],
   optionImages:['data:image/webp;base64,AAA','data:image/webp;base64,BBB','/api/media/opt-c.webp'],
   k:1,
   difficulty:3,
   tip:'解题步骤：\n- 设乙为 $x$\n- 则 $2x+2=18$',
   images:['/api/media/1.webp','https://example.com/2.png','data:image/jpeg;base64,333','https://example.com/4.png','https://example.com/5-ignored.png'],
   enabled:false
  },
  {
   id:'q-subj-1',
   type:'subjective',
   q:'证明勾股定理：\n- 画出直角三角形\n- 写出推导过程',
   modelAnswer:'第一步：构造正方形\r\n第二步：面积恒等式 $a^2+b^2=c^2$',
   rubric:'图形正确 40 分\n推导严密 60 分',
   difficulty:4,
   enabled:true
  }
 ]
};
const roundTrip=normalizeBank(parseBankJson(JSON.stringify(roundTripInput)));
assert.deepEqual(roundTrip.issues,[]);
assert.equal(roundTrip.items.length,2);
const rt0=roundTrip.items[0];
assert.equal(rt0.q,'阅读下面条件：\n- **甲** 比 **乙** 大 2 岁\n- ==两人年龄之和== 为 $18$');
assert.deepEqual(rt0.a,['','','']);
assert.deepEqual(rt0.optionImages,['data:image/webp;base64,AAA','data:image/webp;base64,BBB','/api/media/opt-c.webp']);
assert.equal(rt0.k,1);
assert.equal(rt0.tip,'解题步骤：\n- 设乙为 $x$\n- 则 $2x+2=18$');
assert.equal(rt0.images.length,4);
assert.equal(rt0.image,'/api/media/1.webp');
assert.equal(rt0.enabled,false);

const rt1=roundTrip.items[1];
assert.equal(rt1.q,'证明勾股定理：\n- 画出直角三角形\n- 写出推导过程');
assert.equal(rt1.modelAnswer,'第一步：构造正方形\n第二步：面积恒等式 $a^2+b^2=c^2$');
assert.equal(rt1.rubric,'图形正确 40 分\n推导严密 60 分');
assert.equal(rt1.enabled,undefined);

// 12. 听力文本（TTS）：中文键名也认；和音频地址同时出现时保留听力文本，设备端不接受两者并存
const listenItem=normalizeBank([{题目:'听录音，选出正确的答语。',选项:['It is next to the bank.','By bus.'],答案:'A',
 听力文本:'Where is the bookstore?',音频地址:'https://example.com/a.mp3',播放次数:3,必须播放:true}]).items[0];
assert.equal(listenItem.listen,'Where is the bookstore?');
assert.equal(listenItem.audio,undefined);
assert.equal(listenItem.listenTimes,3);
assert.equal(listenItem.listenRequired,true);
// 语速在 0.7–1.5 之外不带走，免得设备端校验整批失败
assert.equal(normalizeBank([{题目:'听音选词',选项:['x','y'],答案:'B',listen:'sheep',listenRate:1.1}]).items[0].listenRate,1.1);
assert.equal(normalizeBank([{题目:'听音选词',选项:['x','y'],答案:'B',listen:'sheep',listenRate:3}]).items[0].listenRate,undefined);

console.log('question-import tests passed');
