/** 题库页的筛选条件：集中在一处，分页请求由它生成，不再对全量题目在浏览器里过滤 */
export type Filter = {
 search:string;
 bank:string;
 type:string;
 star:string;
 subject:string;
 level:string;
 wrong:string;
 sort:string;
 showAll:boolean;
};

export const PAGE_SIZE=50;

export const defaultFilter:Filter={search:'',bank:'all',type:'all',star:'all',subject:'all',level:'all',wrong:'all',sort:'default',showAll:false};

export type FilterAction={type:'set';key:keyof Filter;value:string|boolean}|{type:'reset'};

export function filterReducer(state:Filter,action:FilterAction):Filter{
 if(action.type==='reset')return {...defaultFilter};
 return {...state,[action.key]:action.value};
}

/** 拼 /api/questions 的查询串：筛选、搜索、排序、分页全部交给设备端 SQL */
export function buildQuery(filter:Filter,page:number,search:string){
 const params=new URLSearchParams({
  limit:String(PAGE_SIZE),
  offset:String(page*PAGE_SIZE),
  enabledOnly:filter.showAll?'false':'true',
  sort:filter.sort==='default'?'newest':filter.sort,
 });
 const keyword=search.trim();
 if(keyword)params.set('search',keyword);
 if(filter.bank!=='all')params.set('bank',filter.bank);
 if(filter.type!=='all')params.set('type',filter.type);
 if(filter.star!=='all')params.set('difficulty',filter.star);
 if(filter.subject!=='all')params.set('subject',filter.subject);
 if(filter.level!=='all')params.set('level',filter.level);
 if(filter.wrong!=='all')params.set('wrong','1');
 return params.toString();
}

export function activeFilterCount(filter:Filter){
 return [filter.search,filter.bank,filter.type,filter.star,filter.subject,filter.level,filter.wrong]
  .filter(value=>value!==''&&value!=='all').length+(filter.showAll?1:0)+(filter.sort!=='default'?1:0);
}

export function extraFilterCount(filter:Filter){
 return [filter.subject,filter.level,filter.wrong].filter(value=>value!=='all').length+(filter.sort!=='default'?1:0);
}

export function hasFilters(filter:Filter){
 return !!filter.search||filter.bank!=='all'||filter.type!=='all'||filter.star!=='all'||filter.subject!=='all'||filter.level!=='all'||filter.wrong!=='all'||filter.showAll||filter.sort!=='default';
}
