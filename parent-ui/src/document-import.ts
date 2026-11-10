export function parseQuestionText(text:string):any[]{
 const normalized=text.replace(/\r/g,'').replace(/^\uFEFF/,'').trim();
 if(!normalized)throw new Error('文档没有可提取的文字；扫描版 PDF 请先转为文字。');
 const chunks=normalized.split(/\n(?=\s*(?:\d+[.、．)]|【\d+】)\s*)/).filter(Boolean);
 const questions:any[]=[];const failures:number[]=[];
 chunks.forEach((chunk,index)=>{
  const answer=chunk.match(/(?:^|\n)\s*(?:参考答案|正确答案|答案)\s*[:：]\s*([^\n]+)/);
  if(!answer){failures.push(index+1);return;}
  const body=chunk.slice(0,answer.index).replace(/^\s*(?:\d+[.、．)]|【\d+】)\s*/,'').trim();
  const tip=chunk.match(/(?:^|\n)\s*(?:解析|解答)\s*[:：]\s*([\s\S]*)/);
  const pattern=/(?:^|\n|\s{1,})([A-H])[.、．:：)]\s*/g;
  const matches=[...body.matchAll(pattern)];
  const raw=answer[1].trim();
  const q:any={q:body,difficulty:2,tip:tip?.[1].trim()||''};
  if(matches.length>=2){
   q.q=body.slice(0,matches[0].index).trim();
   q.a=matches.map((m,i)=>body.slice((m.index||0)+m[0].length,matches[i+1]?.index??body.length).trim());
   const letters=raw.toUpperCase().replace(/[\s,，、;；]/g,'');
   if(!/^[A-H]+$/.test(letters)){failures.push(index+1);return;}
   const indices=[...new Set(letters.split('').map(letter=>matches.findIndex(m=>m[1]===letter)))];
   if(indices.some(i=>i<0)){failures.push(index+1);return;}
   q.type=indices.length>1?'multiple':'single';
   if(q.type==='multiple')q.correctIndices=indices;else q.k=indices[0];
  }else if(['正确','错误','对','错','√','×'].includes(raw)){
   q.type='boolean';q.a=['正确','错误'];q.k=['正确','对','√'].includes(raw)?0:1;
  }else {q.type='text';q.answers=raw.split(/[|｜]/).map(s=>s.trim()).filter(Boolean);}
  if(!q.q){failures.push(index+1);return;}questions.push(q);
 });
 if(failures.length)throw new Error(`第 ${failures.slice(0,5).join('、')} 段无法识别。每题请用“1. 题干”、A./B. 选项、“答案：A”（多选 AB，填空直接写答案）、“解析：…”的格式。可编辑后重新解析。`);
 if(!questions.length)throw new Error('没有识别到题目，请检查文档格式。');
 return questions;
}

export async function extractDocument(file:File):Promise<string>{
 const extension=file.name.split('.').pop()?.toLowerCase();
 if(extension==='docx'){
  const mammoth=await import('mammoth/mammoth.browser');
  const result=await mammoth.extractRawText({arrayBuffer:await file.arrayBuffer()});
  return result.value;
 }
 if(extension==='pdf'){
  const pdf=await import('pdfjs-dist');
  const worker=await import('pdfjs-dist/build/pdf.worker.min.mjs?url');
  pdf.GlobalWorkerOptions.workerSrc=worker.default;
  const document=await pdf.getDocument({data:new Uint8Array(await file.arrayBuffer()),isEvalSupported:false}).promise;
  try{
   if(document.numPages>150)throw new Error('文档超过 150 页，请拆分导入。');
   const pages:string[]=[];
   for(let page=1;page<=document.numPages;page++){
    const content=await(await document.getPage(page)).getTextContent();
    pages.push(content.items.map((item:any)=>item.str+(item.hasEOL?'\n':' ')).join(''));
   }
   return pages.join('\n');
  }finally{await document.destroy();}
 }
 return (await file.text()).replace(/^\uFEFF/,'');
}
