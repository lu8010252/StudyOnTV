import {cloneElement,isValidElement,useEffect,useId,useRef} from 'react';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {Field as UiField,FieldLabel} from '@/components/ui/field';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';

/** 题干 / 答案里的 $...$ 公式交给学习页同一套 KaTeX 渲染 */
export function MathText({text,className=''}:{text:string,className?:string}){
 const ref=useRef<HTMLDivElement>(null);
 useEffect(()=>{if(!ref.current)return;ref.current.textContent=text||'';(window as any).SOTV_MATH?.(ref.current);},[text]);
 return <div ref={ref} className={className}/>;
}

/** 题目详情、题库列表共用的答案文本 */
export function answerOf(q:any){
 if(q.type==='subjective')return q.modelAnswer||'由家长批改';
 if(q.type==='text')return(q.answers||[]).join(' / ');
 const a=q.type==='boolean'?['正确','错误']:(q.a||[]);
 return q.type==='multiple'?(q.correctIndices||[]).map((i:number)=>a[i]).join('、'):a[q.k]||'';
}

export function download(data:any,name:string){
 const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));
 const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();
 setTimeout(()=>URL.revokeObjectURL(url),3000);
}

export const labels:{[key:string]:string}={single:'单选',multiple:'多选',boolean:'判断',text:'填空',subjective:'主观题'};
export const dot:{[key:string]:string}={single:'bg-teal-600 dark:bg-teal-400',multiple:'bg-blue-600 dark:bg-blue-400',boolean:'bg-amber-600 dark:bg-amber-400',text:'bg-violet-600 dark:bg-violet-400',subjective:'bg-rose-600 dark:bg-rose-400'};

/** 新增题目的空表单（编辑时用题目字段覆盖） */
export const blank={id:'',type:'single',q:'',opts:['','','',''],correct:[0],accepted:'',difficulty:2,tip:'',modelAnswer:'',rubric:'',subject:'',level:'',bank:'默认题库',image:'',audio:'',language:'zh-CN'};

export function Stars({n}:{n:number}){
 return <span className="whitespace-nowrap text-xs tracking-wide" title={n+' 星'}><span className="text-amber-600 dark:text-amber-400">{'★'.repeat(n)}</span><span className="text-muted-foreground/40">{'☆'.repeat(5-n)}</span></span>;
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
