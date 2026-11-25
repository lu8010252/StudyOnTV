import {cloneElement,isValidElement,useEffect,useId,useRef} from 'react';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {Field as UiField,FieldLabel} from '@/components/ui/field';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';

const VALID_MEDIA_RE=/^(\/api\/media\/|https?:\/\/|data:image\/)/;

/** 题干 / 答案里的富文本与 $...$ 公式交给共享渲染器处理 */
export function MathText({text,className='',plain=false}:{text:string,className?:string,plain?:boolean}){
 const ref=useRef<HTMLDivElement>(null);
 useEffect(()=>{
  if(!ref.current)return;
  const w=window as any;
  if(plain){
   ref.current.textContent=w.SOTV_PLAIN?w.SOTV_PLAIN(text||''):(text||'');
   return;
  }
  if(w.SOTV_RICH)w.SOTV_RICH(ref.current,text||'');
  else{ref.current.textContent=text||'';w.SOTV_MATH?.(ref.current);}
 },[text,plain]);
 return <div ref={ref} className={className}/>;
}

/** 提取题目全部有效配图（兼容单图 image、多图 images 与设备端 /api/media/ 本地路径） */
export function questionImages(q:any):string[]{
 if(!q)return [];
 const out:string[]=[];
 const seen=new Set<string>();
 const push=(u:any)=>{
  const s=typeof u==='string'?u.trim():'';
  if(s&&VALID_MEDIA_RE.test(s)&&!seen.has(s)){seen.add(s);out.push(s);}
 };
 if(Array.isArray(q.images))q.images.forEach(push);
 push(q.image);
 return out;
}

/** 题目详情、题库列表共用的答案文本（支持纯图片选项显示「选项 X」） */
export function answerOf(q:any){
 if(!q)return '';
 if(q.type==='subjective')return q.modelAnswer||'由家长批改';
 if(q.type==='text')return(q.answers||[]).join(' / ');
 const a=q.type==='boolean'?['正确','错误']:(q.a||[]);
 const fmtOpt=(i:number)=>{
  const text=String(a[i]??'').trim();
  if(text)return text;
  if(Array.isArray(q.optionImages)&&q.optionImages[i])return `选项 ${String.fromCharCode(65+i)}（配图）`;
  return `选项 ${String.fromCharCode(65+i)}`;
 };
 if(q.type==='multiple')return(q.correctIndices||[]).map((i:number)=>fmtOpt(i)).join('、');
 return typeof q.k==='number'&&q.k>=0&&q.k<a.length?fmtOpt(q.k):(a[q.k]||'');
}

export function download(data:any,name:string){
 const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));
 const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();
 setTimeout(()=>URL.revokeObjectURL(url),3000);
}

export const labels:{[key:string]:string}={single:'单选',multiple:'多选',boolean:'判断',text:'填空',subjective:'主观题'};
export const dot:{[key:string]:string}={single:'bg-teal-600 dark:bg-teal-400',multiple:'bg-blue-600 dark:bg-blue-400',boolean:'bg-amber-600 dark:bg-amber-400',text:'bg-violet-600 dark:bg-violet-400',subjective:'bg-rose-600 dark:bg-rose-400'};

/** 新增题目的空表单（编辑时用题目字段覆盖） */
export const blank={id:'',type:'single',q:'',opts:['','','',''],optionImages:['','','',''],correct:[0],accepted:'',difficulty:2,tip:'',modelAnswer:'',rubric:'',subject:'',level:'',bank:'默认题库',image:'',images:[] as string[],audio:'',listen:'',listenTimes:'5',listenRequired:false,listenRate:'',language:'zh-CN'};

export function Stars({n}:{n:number}){
 const level=Math.min(5,Math.max(0,Math.round(Number(n)||0)));
 return <span className="whitespace-nowrap text-xs tracking-wide" title={level+' 星'}><span className="text-amber-600 dark:text-amber-400">{'★'.repeat(level)}</span><span className="text-muted-foreground/40">{'☆'.repeat(5-level)}</span></span>;
}

export function Count({value,suffix,className=''}:{value:number|string,suffix:string,className?:string}){
 return <span className={'inline-flex items-baseline gap-1 text-xs text-muted-foreground '+className}><b className="font-medium tabular-nums">{value}</b>{suffix}</span>;
}

export function FilterSelect({label,value,onChange,options}:{label:string,value:string,onChange:(value:string)=>void,options:[string,string][]}){
 return <Select value={value} onValueChange={onChange}><SelectTrigger size="sm" aria-label={label} className="w-full min-w-0 px-1.5"><SelectValue>{options.find(([optionValue])=>optionValue===value)?.[1]||'请选择'}</SelectValue></SelectTrigger><SelectContent>{options.map(([optionValue,optionLabel])=><SelectItem key={optionValue} value={optionValue}>{optionLabel}</SelectItem>)}</SelectContent></Select>;
}

export function ChoiceSelect({value,onChange,options,label,id}:{value:string,onChange:(value:string)=>void,options:[string,string][],label:string,id?:string}){
 return <Select value={value} onValueChange={onChange}><SelectTrigger id={id} aria-label={label} className="w-full"><SelectValue>{options.find(([optionValue])=>optionValue===value)?.[1]||'请选择'}</SelectValue></SelectTrigger><SelectContent>{options.map(([optionValue,optionLabel])=><SelectItem key={optionValue} value={optionValue}>{optionLabel}</SelectItem>)}</SelectContent></Select>;
}

/** 表单里「标签 + 输入框」：自动给第一个输入控件接上 id / htmlFor */
export function Field({label,children}:{label:string,children:any}){
 const autoId=useId();
 let target:string|undefined;
 const link=(child:any)=>{
  if(isValidElement(child)&&(child.type===Input||child.type===Textarea||child.type===ChoiceSelect)){
   const own=(child.props as any).id as string|undefined;
   const id=own||autoId;
   target=id;
   return own?child:cloneElement(child as any,{id});
  }
  return child;
 };
 const content=Array.isArray(children)?children.map(link):link(children);
 return <UiField><FieldLabel htmlFor={target}>{label}</FieldLabel>{content}</UiField>;
}
