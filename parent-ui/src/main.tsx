import {Component,useEffect,useRef,useState,lazy,Suspense,type ErrorInfo,type ReactNode} from 'react';
import {root} from './react-root';
import {QueryClient,QueryClientProvider,useQuery} from '@tanstack/react-query';
import {AlertCircle,LoaderCircle,BookOpen,BookX,ClipboardCheck,SlidersHorizontal,ChartColumn,Leaf,LogOut,ArrowRight,ArrowLeftRight,LockKeyhole,Monitor,MonitorSmartphone,Settings,Sun,Moon,Laptop,ChevronsUpDown,Tv,Palette,KeyRound,CloudDownload,RadioTower,FileText,Bot,Eye,EyeOff,House,ShieldCheck,Users,Search,Volume2,X,RefreshCw} from 'lucide-react';
import {Toaster,toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription} from '@/components/ui/dialog';
import {Card,CardHeader,CardTitle,CardContent,CardFooter} from '@/components/ui/card';
import {Checkbox} from '@/components/ui/checkbox';
import {Field,FieldContent,FieldError,FieldGroup,FieldLabel} from '@/components/ui/field';
import {Badge} from '@/components/ui/badge';
import {Alert,AlertDescription} from '@/components/ui/alert';
import {Separator} from '@/components/ui/separator';
import {InputGroup,InputGroupAddon,InputGroupButton,InputGroupInput} from '@/components/ui/input-group';
import {Tabs,TabsContent,TabsList,TabsTrigger} from '@/components/ui/tabs';
import {Sidebar,SidebarContent,SidebarFooter,SidebarGroup,SidebarGroupLabel,SidebarHeader,SidebarInset,SidebarMenu,SidebarMenuBadge,SidebarMenuButton,SidebarMenuItem,SidebarProvider,SidebarRail,SidebarTrigger,useSidebar} from '@/components/ui/sidebar';
import {DropdownMenu,DropdownMenuContent,DropdownMenuItem,DropdownMenuLabel,DropdownMenuSeparator,DropdownMenuTrigger,DropdownMenuRadioGroup,DropdownMenuRadioItem} from '@/components/ui/dropdown-menu';
import {api,getHost,setHost,setToken,enablePreview,SESSION_STORAGE_KEY} from './api';
import RemotePage from './RemotePage';
import DevicePanel from './DevicePanel';
import SpeechSettings from './SpeechSettings';
import KeepAlivePanel from './KeepAlivePanel';
import LogsPage from './LogsPage';
import AiSettings from './AiSettings';
import ChildrenPanel from './ChildrenPanel';
import FeedbackPanel from './FeedbackPanel';
import InstallPrompt from './InstallPrompt';
import {useIsMobile} from './hooks/use-mobile';
const RulesPage=lazy(()=>import('./RulesPage'));
const QuestionBank=lazy(()=>import('./QuestionBank'));
const WrongBookPage=lazy(()=>import('./WrongBookPage'));
const GradingPage=lazy(()=>import('./GradingPage'));
const RecordsPage=lazy(()=>import('./RecordsPage'));
const DevicesPage=lazy(()=>import('./DevicesPage'));
import './styles.css';

const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:3000,refetchOnWindowFocus:false},mutations:{retry:false}}});

class ErrorBoundary extends Component<{resetKey?:string;children:ReactNode},{error:Error|null}>{
 state:{error:Error|null}={error:null};
 static getDerivedStateFromError(error:Error){return {error};}
 componentDidUpdate(prevProps:{resetKey?:string}){
  if(this.state.error&&prevProps.resetKey!==this.props.resetKey){
   this.setState({error:null});
  }
 }
 componentDidCatch(error:Error,info:ErrorInfo){
  console.error('[ErrorBoundary]',error,info);
 }
 render(){
  if(!this.state.error)return this.props.children;
  return <Card className="my-8 border-destructive/30 shadow-xs">
   <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
    <AlertCircle className="size-8 text-destructive"/>
    <div className="space-y-1">
     <p className="text-sm font-semibold">页面组件加载失败</p>
     <p className="max-w-md text-xs text-muted-foreground">可能是设备刚升级或局域网连接短暂波动。请点击重试或刷新页面加载最新资源。</p>
    </div>
    <div className="flex flex-wrap items-center justify-center gap-2 pt-1">
     <Button size="sm" variant="outline" onClick={()=>this.setState({error:null})}><RefreshCw/>重试加载</Button>
     <Button size="sm" onClick={()=>location.reload()}>刷新整页</Button>
    </div>
   </CardContent>
  </Card>;
 }
}

type PageId='remote'|'rules'|'bank'|'wrong'|'grading'|'records'|'devices'|'settings';
const pages:{id:PageId,title:string,short:string,icon:any,description:string}[]=[
 {id:'remote',title:'首页',short:'首页',icon:House,description:'状态、学习模式与今日概览'},
 {id:'grading',title:'作答批改',short:'批改',icon:ClipboardCheck,description:'处理待批作业'},
 {id:'bank',title:'题库',short:'题库',icon:BookOpen,description:'题目、分类与导入导出'},
 {id:'wrong',title:'错题库',short:'错题',icon:BookX,description:'错题沉淀、筛选与一键加练'},
 {id:'rules',title:'学习计划',short:'计划',icon:SlidersHorizontal,description:'背诵模式、范围、时段与奖励'},
 {id:'records',title:'学情与记录',short:'学情',icon:ChartColumn,description:'薄弱项诊断、记忆曲线与使用时长'},
 {id:'devices',title:'多设备',short:'设备',icon:MonitorSmartphone,description:'局域网多设备发现、一键切换与批量同步'},
 {id:'settings',title:'设置',short:'设置',icon:Settings,description:'设备、备份、外观、更新与安全'}];
type SectionId='device'|'children'|'keepalive'|'display'|'update'|'security'|'integration'|'logs'|'ai'|'speech';
const sections:{id:SectionId,title:string,icon:any}[]=[{id:'device',title:'设备',icon:Tv},{id:'children',title:'孩子',icon:Users},{id:'speech',title:'朗读',icon:Volume2},{id:'keepalive',title:'后台保活',icon:ShieldCheck},{id:'display',title:'界面与反馈',icon:Palette},{id:'update',title:'版本更新',icon:CloudDownload},{id:'security',title:'管理密码',icon:KeyRound},{id:'integration',title:'家庭自动化',icon:RadioTower},{id:'logs',title:'诊断日志',icon:FileText},{id:'ai',title:'模型 API（高级）',icon:Bot}];
// 全局面板搜索：一个输入框查设置分区、其它页面和常用功能，选中后直接跳过去
type SearchEntry={id:string,page:PageId,section?:SectionId,where:string,title:string,keywords:string,anchor?:string};
const searchEntries:SearchEntry[]=[
 // 设置各分区
 {id:'set-device',page:'settings',section:'device',where:'设置 · 设备',title:'设备名称与连接',keywords:'设备 名称 重命名 连接 ip 端口 信息'},
 {id:'set-backup',page:'settings',section:'device',where:'设置 · 设备',title:'数据备份与换机迁移',keywords:'备份 恢复 迁移 换机 导出 还原 json'},
 {id:'set-children',page:'settings',section:'children',where:'设置 · 孩子',title:'孩子档案',keywords:'孩子 档案 姓名 多孩子 切换 新建'},
 {id:'set-speech',page:'settings',section:'speech',where:'设置 · 朗读',title:'朗读设置',keywords:'朗读 语音 发音 声音 题目 选项 答案 音色 语速 tts'},
 {id:'set-speech-auto',page:'settings',section:'speech',where:'设置 · 朗读',title:'自动朗读题目',keywords:'朗读 自动 开关 题目',anchor:'setting-auto-read'},
 {id:'set-speech-scope',page:'settings',section:'speech',where:'设置 · 朗读',title:'朗读内容（题目 / 选项 / 答案）',keywords:'朗读 内容 范围 选项 答案 题目 读什么',anchor:'setting-read-scope'},
 {id:'set-speech-language',page:'settings',section:'speech',where:'设置 · 朗读',title:'朗读语言',keywords:'朗读 语言 粤语 普通话 英语 系统语言'},
 {id:'set-speech-mode',page:'settings',section:'speech',where:'设置 · 朗读',title:'朗读通路与语音识别',keywords:'朗读 通路 引擎 音色 deepseek azure 阿里云 语音识别 转写 密钥'},
 {id:'set-keepalive',page:'settings',section:'keepalive',where:'设置 · 后台保活',title:'开机自启与后台保活',keywords:'开机 自启 后台 保活 电池 优化 权限 悬浮窗 无障碍 小米 tcl 华为'},
 {id:'set-display',page:'settings',section:'display',where:'设置 · 界面与反馈',title:'界面、学习页字号与反馈',keywords:'界面 主题 深色 字号 字体 反馈 学习页 显示'},
 {id:'set-update',page:'settings',section:'update',where:'设置 · 版本更新',title:'检查更新',keywords:'更新 升级 版本 检查 下载 apk 蒲公英'},
 {id:'set-security',page:'settings',section:'security',where:'设置 · 管理密码',title:'管理密码',keywords:'密码 管理 口令 修改 安全 解锁'},
 {id:'set-integration',page:'settings',section:'integration',where:'设置 · 家庭自动化',title:'家庭自动化',keywords:'家庭 自动化 集成 令牌 token 接口 局域网'},
 {id:'set-logs',page:'settings',section:'logs',where:'设置 · 诊断日志',title:'诊断日志',keywords:'日志 诊断 排错 错误 记录 反馈'},
 {id:'set-ai',page:'settings',section:'ai',where:'设置 · 模型 API',title:'模型 API（AI 批改与出题）',keywords:'ai 模型 api 密钥 key deepseek 批改 出题 视觉 手写'},
 // 其它页面
 {id:'home',page:'remote',where:'首页 · 设备管理',title:'今日概览与学习模式切换',keywords:'首页 概览 纯粹学习 提分 免锁屏 剩余时间 使用状态 今天 控制'},
 {id:'home-unlock',page:'remote',where:'首页 · 设备控制',title:'临时解锁 / 暂停使用 / 关闭管控',keywords:'解锁 临时 加时 暂停 关闭管控 恢复 锁屏 调整剩余时间'},
 {id:'home-history',page:'remote',where:'首页 · 学习记录',title:'近期学习记录',keywords:'学习记录 使用时间 每天 记录 近期'},
 {id:'rules',page:'rules',where:'学习计划',title:'学习计划（先背再测、间隔重复、时段）',keywords:'计划 规则 学习 任务 先背再测 闪卡'},
 {id:'rules-task',page:'rules',where:'学习计划 · 任务',title:'每组题量、先背再测、答题奖励',keywords:'题量 每题 奖励 分钟 通过 一组 正确率 组数 先背再测'},
 {id:'rules-time',page:'rules',where:'学习计划 · 时段',title:'可用时段、网课时段、休息',keywords:'时段 时间 网课 休息 睡眠 晚上 可用 周一 周二 节假日'},
 {id:'rules-scope',page:'rules',where:'学习计划 · 范围',title:'年级、科目与题库范围',keywords:'年级 科目 范围 题库 难度 出题 顺序'},
 {id:'rules-more',page:'rules',where:'学习计划 · 记忆与加练',title:'艾宾浩斯间隔重复、只背错题、加练',keywords:'加练 错题 复习 记忆曲线 anki 只背错题 密码 解锁'},
 {id:'rules-guard-settings',page:'rules',where:'学习计划 · 高级',title:'拦截系统设置与应用管理（防卸载加固）',keywords:'防卸载 卸载 系统设置 应用管理 手机管家 拦截 密码'},
 {id:'bank',page:'bank',where:'题库',title:'题目列表与筛选',keywords:'题库 题目 搜索 筛选 暂停 难度 科目'},
 {id:'bank-import',page:'bank',where:'题库 · 导入导出',title:'导入 / 导出题目',keywords:'导入 json 文档 导出 备份 添加题目'},
 {id:'bank-ai',page:'bank',where:'题库 · AI 出题',title:'AI 错题举一反三与模板出题',keywords:'ai 出题 生成 变式 错题 举一反三 在线题库 市场 共享题库'},
 {id:'bank-auto',page:'bank',where:'题库 · 自动补题',title:'远程题库与自动补题',keywords:'远程 题库 同步 自动补题 来源'},
 {id:'wrong',page:'wrong',where:'错题库',title:'错题库与加练池管理',keywords:'错题 错题本 错题库 加练 加练池 清空 已掌握 复习'},
 {id:'grading',page:'grading',where:'作答批改',title:'待批改与 AI 批改',keywords:'批改 作业 待批 评分 ai 主观题 手写'},
 {id:'records',page:'records',where:'学情与记录',title:'学情诊断、顽固错题与记忆成熟度',keywords:'学情 记录 成长 统计 进度 正确率 时长 趋势 薄弱项 错题榜'},
 {id:'devices',page:'devices',where:'多设备',title:'局域网多设备发现与一键切换',keywords:'多设备 局域网 发现 扫描 切换 电视 平板 收藏 添加 ip'},
 {id:'devices-sync',page:'devices',where:'多设备 · 批量同步',title:'一键批量同步学习计划与题库',keywords:'同步 批量 多设备 题库 计划 朗读 ai 合并 覆盖'},
];
const searchHits=(query:string,localOnly=false)=>{
 const q=query.trim().toLowerCase();
 if(!q)return [];
 const words=q.split(/\s+/).filter(Boolean);
 return searchEntries
  .filter(entry=>!localOnly||entry.section!=='update')
  .map(entry=>{
   const text=(entry.title+' '+entry.keywords+' '+entry.where).toLowerCase();
   return {entry,score:words.every(w=>text.includes(w))?words.filter(w=>entry.title.toLowerCase().includes(w)).length:0,ok:text.includes(q)||words.every(w=>text.includes(w))};
  })
  .filter(hit=>hit.ok)
  .sort((a,b)=>b.score-a.score)
  .slice(0,8)
  .map(hit=>hit.entry);
};

/* ---------- 记住登录：保存在本机浏览器，30 天有效；断开连接后失效 ---------- */
function saveSession(host:string,token:string){try{localStorage.setItem(SESSION_STORAGE_KEY,JSON.stringify({host,token}));}catch{}}
function forgetSession(){try{localStorage.removeItem(SESSION_STORAGE_KEY);}catch{}}
function restoreSession():boolean{
 try{
  const v=JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY)||'null');
  if(!v?.token||!v?.host)return false;
  // 面板由被控设备直接提供时（非本地 Vite 5173 调试端，且原本也是连同源或未显式指定远程 HTTPS），以当前地址为准
  const isDevServer=location.port==='5173';
  const isServedByDevice=!isDevServer&&location.protocol.startsWith('http')&&!!location.host&&!/^https:\/\//i.test(v.host)&&location.pathname.startsWith('/admin');
  setHost(isServedByDevice?location.host:v.host);
  setToken(v.token);
  return true;
 }catch{return false;}
}

/* ---------- 主题：浅色 / 深色 / 跟随系统（参考 shadcn-admin ThemeProvider） ---------- */
type Theme='light'|'dark'|'system';
function readTheme():Theme{try{const v=localStorage.getItem('sotv-panel-theme');return v==='light'||v==='dark'?v:'system';}catch{return 'system';}}
function useTheme(){const[theme,set]=useState<Theme>(readTheme);useEffect(()=>{const media=window.matchMedia('(prefers-color-scheme: dark)');const apply=()=>{const dark=theme==='dark'||(theme==='system'&&media.matches);document.documentElement.classList.toggle('dark',dark);document.querySelector('meta[name=theme-color]')?.setAttribute('content',dark?'#15181a':'#f5f6f5');};apply();media.addEventListener('change',apply);return()=>media.removeEventListener('change',apply);},[theme]);return{theme,setTheme:(t:Theme)=>{try{localStorage.setItem('sotv-panel-theme',t);}catch{}set(t);}};}
function ThemeSwitch({theme,setTheme}:{theme:Theme,setTheme:(t:Theme)=>void}){return <DropdownMenu modal={false}><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="切换浅色或深色"><Sun className="size-[18px] scale-100 rotate-0 transition-all dark:scale-0 dark:-rotate-90"/><Moon className="absolute size-[18px] scale-0 rotate-90 transition-all dark:scale-100 dark:rotate-0"/></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuRadioGroup value={theme} onValueChange={v=>setTheme(v as Theme)}><DropdownMenuRadioItem value="light"><Sun/>浅色</DropdownMenuRadioItem><DropdownMenuRadioItem value="dark"><Moon/>深色</DropdownMenuRadioItem><DropdownMenuRadioItem value="system"><Laptop/>跟随系统</DropdownMenuRadioItem></DropdownMenuRadioGroup></DropdownMenuContent></DropdownMenu>}

/* ---------- 路由：#/rules、#/settings/update …（手机返回键可用） ---------- */
function parseHash():{page:PageId,section:SectionId}{const [p,s]=location.hash.replace(/^#\/?/,'').split('/');return{page:(pages.some(x=>x.id===p)?p:'remote') as PageId,section:(sections.some(x=>x.id===s)?s:'device') as SectionId};}
function useRoute(){const[route,set]=useState(parseHash);useEffect(()=>{const on=()=>{set(parseHash());window.scrollTo({top:0});};window.addEventListener('hashchange',on);return()=>window.removeEventListener('hashchange',on);},[]);return{...route,go:(page:PageId,section?:SectionId)=>{location.hash='/'+page+(section?'/'+section:'');}};}

function App(){const[session,setSession]=useState(()=>restoreSession()?1:0),[next,setNext]=useState(2);const theme=useTheme();function logout(remote=true){if(remote)api('/api/logout','POST',{}).catch(()=>{});setToken('');forgetSession();setSession(0);client.clear();}function switchHost(targetHost:string,name?:string){try{setHost(targetHost);setToken('');forgetSession();client.clear();setSession(0);toast.message(`已切换到 ${name||targetHost}，请输入管理密码登录`);}catch(e:any){toast.error(e?.message||'切换设备失败');}}useEffect(()=>{const handler=()=>{logout(false);toast.error('登录已失效，请重新验证管理密码');};window.addEventListener('session-expired',handler);return()=>window.removeEventListener('session-expired',handler);},[]);return <><Toaster richColors position="top-center" theme={theme.theme}/><InstallPrompt enabled={session!==0}/>{session?<Dashboard sessionId={session} logout={logout} onSwitchHost={switchHost} theme={theme}/>:<Login onLogin={()=>{setSession(next);setNext(n=>n+1);}} theme={theme}/>}</>}

function Login({onLogin,theme}:{onLogin:()=>void,theme:ReturnType<typeof useTheme>}){
 const[host,updateHost]=useState(getHost()),[secret,setSecret]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const[visible,setVisible]=useState(false),[accepted,setAccepted]=useState(true),[crashReports,setCrashReports]=useState(false),[crashTouched,setCrashTouched]=useState(false),[docType,setDocType]=useState<'terms'|'privacy'|null>(null);
 async function submit(e:any){e.preventDefault();if(busy)return;setError('');if(!accepted)return setError('请先同意使用说明和隐私条款');if(!secret)return setError('请输入管理密码');if(secret.length<6)return setError('管理密码至少 6 位');setBusy(true);try{setHost(host);const result=await api('/api/auth','POST',{passphrase:secret,remember:true});setToken(result.token);saveSession(getHost(),result.token);if(crashTouched&&!result?.localOnly){const deviceInfo=await api('/api/device').catch(()=>null);if(!deviceInfo?.localOnly){await api('/api/device/crash','PUT',{consent:crashReports}).catch(()=>{});}}setSecret('');onLogin();}catch(e:any){setError(e.message==='Failed to fetch'?'无法连接设备，请检查设备地址和 WiFi':e.message);}finally{setBusy(false);}}
 return <main className="relative min-h-svh overflow-hidden bg-background">
  <div aria-hidden className="pointer-events-none absolute inset-0 bg-[linear-gradient(to_right,color-mix(in_oklab,var(--border)_45%,transparent)_1px,transparent_1px),linear-gradient(to_bottom,color-mix(in_oklab,var(--border)_45%,transparent)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:linear-gradient(to_bottom,black,transparent_78%)]"/>
  <header className="relative z-10 mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 md:px-6"><div className="flex items-center gap-2 text-sm font-semibold"><span className="brand-icon size-8"><Leaf className="size-4"/></span><span>题了个题</span></div><ThemeSwitch {...theme}/></header>
  <div className="relative z-10 mx-auto grid min-h-[calc(100svh-4rem)] w-full max-w-6xl items-center gap-8 px-4 pb-12 lg:grid-cols-[minmax(0,1fr)_400px] lg:gap-12 lg:px-6">
   <section className="hidden max-w-xl lg:block">
    <Badge variant="outline" className="bg-background px-3 py-1 text-xs font-normal text-muted-foreground">家长控制台</Badge>
    <h1 className="mt-4 text-3xl font-semibold leading-[1.2] tracking-[-0.02em]">科学背题清错题，<span className="block text-muted-foreground">屏幕时间也能管。</span></h1>
    <div className="mt-6 grid max-w-md grid-cols-3 gap-2 text-xs text-muted-foreground">
     {['先背再测 · 闪卡','错题举一反三','可选屏幕管控'].map(item=><div key={item} className="rounded-lg border border-border bg-card px-3 py-3">{item}</div>)}
    </div>
   </section>
   <Card className="w-full border-border bg-card shadow-sm" aria-labelledby="login-title">
    <CardHeader className="gap-1.5 px-5 pb-4 pt-5">
     <CardTitle id="login-title" className="text-2xl tracking-tight">登录家长管理</CardTitle>
     <p className="text-xs leading-5 text-muted-foreground">在电视或手机端打开「题了个题 → 家长设置」查看设备地址与管理密码</p>
    </CardHeader>
    <CardContent className="px-5 pb-5">
     <form onSubmit={submit} className="flex flex-col gap-4">
      <FieldGroup className="gap-4">
       <Field>
        <FieldLabel htmlFor="device-host">设备地址</FieldLabel>
        <InputGroup>
         <InputGroupAddon><Monitor/></InputGroupAddon>
         <InputGroupInput id="device-host" autoComplete="off" autoCapitalize="none" spellCheck={false} inputMode="url" required value={host} onChange={e=>updateHost(e.target.value)} placeholder="192.168.1.20:8099"/>
        </InputGroup>
       </Field>
       <Field>
        <FieldLabel htmlFor="management-secret">管理密码</FieldLabel>
        <InputGroup>
         <InputGroupAddon><LockKeyhole/></InputGroupAddon>
         <InputGroupInput id="management-secret" type={visible?'text':'password'} autoComplete="current-password" required value={secret} onChange={e=>setSecret(e.target.value)} placeholder="输入管理密码"/>
         <InputGroupAddon align="inline-end"><InputGroupButton size="icon-sm" onClick={()=>setVisible(v=>!v)} aria-label={visible?'隐藏密码':'显示密码'}>{visible?<EyeOff/>:<Eye/>}</InputGroupButton></InputGroupAddon>
        </InputGroup>
       </Field>
       <FieldGroup className="gap-3 pt-1">
        <Field orientation="horizontal" className="items-start gap-3">
         <Checkbox id="login-accepted" className="mt-0.5" checked={accepted} onCheckedChange={value=>setAccepted(value===true)}/>
         <FieldContent>
          <div className="flex flex-wrap items-center gap-x-1 gap-y-0.5 text-sm"><FieldLabel htmlFor="login-accepted" className="font-normal">我已阅读并同意</FieldLabel><Button type="button" variant="link" className="h-auto p-0 text-sm" onClick={()=>setDocType('terms')}>《使用说明》</Button><span className="text-muted-foreground">和</span><Button type="button" variant="link" className="h-auto p-0 text-sm" onClick={()=>setDocType('privacy')}>《隐私说明》</Button></div>
         </FieldContent>
        </Field>
        <Field orientation="horizontal" className="items-start gap-3">
         <Checkbox id="crash-reports" className="mt-0.5" checked={crashReports} onCheckedChange={value=>{setCrashReports(value===true);setCrashTouched(true);}}/>
         <FieldContent><FieldLabel htmlFor="crash-reports" className="font-normal text-muted-foreground">参与软件体验提升计划</FieldLabel></FieldContent>
        </Field>
       </FieldGroup>
      </FieldGroup>
      <FieldError>{error}</FieldError>
      <Button className="w-full" type="submit" disabled={busy||!accepted}>{busy?'登录中…':'登录'}<ArrowRight className="size-4"/></Button>
     </form>
    </CardContent>
    {import.meta.env.DEV&&<CardFooter className="border-t px-5 py-3"><Button type="button" variant="ghost" className="w-full text-xs text-muted-foreground" onClick={()=>{enablePreview();client.clear();onLogin();}}>开发预览</Button></CardFooter>}
   </Card>
  </div>
  <Dialog open={docType!==null} onOpenChange={open=>{if(!open)setDocType(null);}}>
   <DialogContent className="max-w-lg">
    <DialogHeader>
     <DialogTitle>{docType==='terms'?'使用说明':'隐私说明'}</DialogTitle>
     <DialogDescription asChild>
      <div className="space-y-2.5 pt-2 text-xs leading-6 text-muted-foreground">
       {docType==='terms'?(
        <>
         <p>1. <strong>开源本地工具</strong>：「题了个题（StudyOnTV）」是运行在家庭设备上的本地助学与可选屏幕时间管理工具，家长请合理设置学习任务与休息时长。</p>
         <p>2. <strong>适用模式</strong>：支持「纯粹学习提分模式」（无需锁屏权限）与「先学再看管控模式」。开启设备管控前，请确保您对所管理设备拥有合法监护或管理权限。</p>
         <p>3. <strong>密码与备份</strong>：管理密码保存在被控设备本地，建议定期在「设置 → 设备」导出整机备份以便换机恢复。</p>
        </>
       ):(
        <>
         <p>1. <strong>数据留存本地</strong>：孩子档案、题库配图、答题记录、手写作答与时间规则均保存在您的电视/平板本地 SQLite 数据库中，仅通过家庭局域网供家长面板访问，无中心化账号服务器。</p>
         <p>2. <strong>第三方服务按需调用</strong>：仅当家长主动配置并启用 AI 出题/批改模型或云端语音朗读时，相关题目或作答内容才会直接发送至您所选的服务商接口；API Key 仅保存在您的设备本地。</p>
         <p>3. <strong>软件体验提升计划</strong>：勾选「参与软件体验提升计划」后，应用在发生异常时仅发送不含个人信息的脱敏故障诊断与设备型号版本，帮助优化设备兼容性与使用体验；您可随时在「设置 → 设备」中关闭。</p>
        </>
       )}
      </div>
     </DialogDescription>
    </DialogHeader>
   </DialogContent>
  </Dialog>
 </main>;
}

function AppSidebar({page,go,pending,device,logout}:{page:PageId,go:(p:PageId)=>void,pending:number,device:any,logout:()=>void}){
 const groups:[string,PageId[]][]=[['学习',['remote','rules','bank','wrong','grading','records']],['系统',['devices','settings']]];
 const {isMobile,setOpenMobile}=useSidebar();
 const navigate=(p:PageId)=>{go(p);if(isMobile)setOpenMobile(false);};
 return <Sidebar collapsible="icon" variant="sidebar">
  <SidebarHeader><SidebarMenu><SidebarMenuItem><SidebarMenuButton size="lg" onClick={()=>navigate('remote')} tooltip="题了个题"><span className="brand-icon size-8"><Leaf className="size-4"/></span><div className="grid flex-1 text-start text-sm leading-tight"><span className="truncate font-semibold">题了个题</span><span className="truncate text-xs text-muted-foreground">家长管理</span></div></SidebarMenuButton></SidebarMenuItem><SidebarMenuItem><SidebarMenuButton size="sm" isActive={page==='devices'} onClick={()=>navigate('devices')} tooltip={`切换设备（当前：${device?.name||'被控设备'}）`} className="mt-1 text-xs text-muted-foreground"><ArrowLeftRight className="size-3.5 shrink-0"/><span className="truncate">{device?.name||'当前设备'}</span><span className="ms-auto shrink-0 text-[11px] opacity-75">切换</span></SidebarMenuButton></SidebarMenuItem></SidebarMenu></SidebarHeader>
  <SidebarContent>{groups.map(([label,ids])=><SidebarGroup key={label}><SidebarGroupLabel>{label}</SidebarGroupLabel><SidebarMenu>{ids.map(id=>{const p=pages.find(x=>x.id===id)!;return <SidebarMenuItem key={id}><SidebarMenuButton isActive={page===id} tooltip={p.title} onClick={()=>navigate(id)}><p.icon/><span>{p.title}</span></SidebarMenuButton>{id==='grading'&&pending>0&&<SidebarMenuBadge>{pending}</SidebarMenuBadge>}</SidebarMenuItem>;})}</SidebarMenu></SidebarGroup>)}</SidebarContent>
  <SidebarFooter><SidebarMenu><SidebarMenuItem><DeviceMenu device={device} logout={logout} side="right"><SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent"><span className="flex size-8 items-center justify-center text-muted-foreground"><Tv className="size-[18px]"/></span><div className="grid flex-1 text-start text-sm leading-tight"><span className="truncate font-semibold">{device?.name||'被控设备'}</span><span className="truncate text-xs text-muted-foreground"><span className="me-1.5 inline-block size-1.5 rounded-full bg-emerald-500"/>{getHost()}</span></div><ChevronsUpDown className="ms-auto size-4"/></SidebarMenuButton></DeviceMenu></SidebarMenuItem></SidebarMenu></SidebarFooter>
  <SidebarRail/>
 </Sidebar>;}

function DeviceMenu({device,logout,children,side='bottom'}:{device:any,logout:()=>void,children:ReactNode,side?:'right'|'bottom'}){return <DropdownMenu modal={false}><DropdownMenuTrigger asChild>{children}</DropdownMenuTrigger><DropdownMenuContent className="min-w-56 rounded-lg" side={side} align="end" sideOffset={4}><DropdownMenuLabel className="font-normal"><div className="grid text-sm leading-tight"><span className="font-semibold">{device?.name||'被控设备'}</span><span className="text-xs text-muted-foreground">{device?`${device.flavor==='tv'?'电视版':'手机 / 平板版'} v${device.version}`:getHost()}</span></div></DropdownMenuLabel><DropdownMenuSeparator/><DropdownMenuItem onClick={()=>{location.hash='/devices';}}><MonitorSmartphone/>多设备管理与同步</DropdownMenuItem><DropdownMenuItem onClick={()=>{location.hash='/settings/device';}}><Settings/>设备设置</DropdownMenuItem><DropdownMenuItem onClick={logout} variant="destructive"><LogOut/>断开并重新连接</DropdownMenuItem></DropdownMenuContent></DropdownMenu>;}

function Header({title,children}:{title:string,children:ReactNode}){
 const isMobile=useIsMobile();
 return <header className="app-header sticky top-0 z-30 h-10"><div className="app-header-inner relative flex h-full items-center gap-2 px-3 md:px-5">{!isMobile&&<><SidebarTrigger variant="ghost"/><Separator orientation="vertical" className="h-5"/></>}<span className="md:hidden flex"><span className="brand-icon size-7"><Leaf className="size-4"/></span></span><h2 className="text-sm font-semibold truncate">{title}</h2><div className="ms-auto flex items-center gap-1">{children}</div></div></header>;}

function BottomNav({page,go,pending}:{page:PageId,go:(p:PageId)=>void,pending:number}){return <nav className="bottom-nav" aria-label="主导航">{pages.map(p=><Button key={p.id} type="button" variant="ghost" aria-current={page===p.id?'page':undefined} onClick={()=>go(p.id)}><p.icon/><span>{p.short}</span>{p.id==='grading'&&pending>0&&<Badge className="badge" variant="destructive">{pending>99?'99+':pending}</Badge>}</Button>)}</nav>;}

function SettingsPage({section,go,sessionId,state,logout,localOnly}:{section:SectionId,go:(p:PageId,s:SectionId)=>void,sessionId:number,state:any,logout:()=>void,localOnly:boolean}){
 const visibleSections=localOnly?sections.filter(s=>s.id!=='update'):sections;
 const activeSection:SectionId=localOnly&&section==='update'?'device':section;
 return <Tabs value={activeSection} orientation="vertical" onValueChange={value=>go('settings',value as SectionId)} className="gap-4 lg:grid lg:grid-cols-[14rem_minmax(0,1fr)] lg:items-start lg:gap-6">
  <TabsList aria-label="设置分区" className="sticky top-10 z-20 -mx-4 flex w-[calc(100%+2rem)] flex-nowrap justify-start gap-1 overflow-x-auto bg-background/95 px-3 py-2 backdrop-blur lg:static lg:mx-0 lg:block lg:w-full lg:space-y-1 lg:overflow-visible lg:bg-transparent lg:px-0 lg:py-0 lg:backdrop-blur-none">
   {visibleSections.map(s=><TabsTrigger key={s.id} value={s.id} className="shrink-0 whitespace-nowrap justify-start gap-2 px-3 max-[520px]:px-2.5 max-[520px]:text-xs lg:flex lg:w-full lg:justify-start"><s.icon/>{s.title}</TabsTrigger>)}
  </TabsList>
  <TabsContent value={activeSection} className="m-0 w-full min-w-0">{activeSection==='children'?<ChildrenPanel sessionId={sessionId}/>:activeSection==='speech'?<SpeechSettings sessionId={sessionId}/>:activeSection==='keepalive'?<KeepAlivePanel sessionId={sessionId}/>:activeSection==='display'?<FeedbackPanel state={state} sessionId={sessionId}/>:activeSection==='logs'?<LogsPage sessionId={sessionId}/>:activeSection==='ai'?<AiSettings sessionId={sessionId}/>:<DevicePanel sessionId={sessionId} only={activeSection} onPasswordChanged={logout} onOpenKeepAlive={()=>go('settings','keepalive')}/>}</TabsContent>
 </Tabs>;}

/** 面板全局搜索：设置分区、其它页面和常用功能都在里面，选中后跳过去并按需滚动到具体设置项 */
function GlobalSearch({go,localOnly}:{go:(p:PageId,s?:SectionId)=>void,localOnly:boolean}){
 const[open,setOpen]=useState(false),[query,setQuery]=useState('');
 const hits=searchHits(query,localOnly);
 const jump=(entry:SearchEntry)=>{
  setOpen(false);setQuery('');
  go(entry.page,entry.section);
  if(entry.anchor)window.setTimeout(()=>document.getElementById(entry.anchor!)?.scrollIntoView({block:'center'}),150);
 };
 return <>
  <Button variant="ghost" size="icon" aria-label="搜索设置" onClick={()=>setOpen(true)}><Search className="size-[18px]"/></Button>
  <Dialog open={open} onOpenChange={setOpen}>
   <DialogContent className="top-20 max-h-[70vh] translate-y-0 gap-3 sm:max-w-lg">
    <DialogHeader><DialogTitle>搜索</DialogTitle><DialogDescription>设置、页面和常用功能都能搜，输入关键词直接跳过去。</DialogDescription></DialogHeader>
    <InputGroup className="h-9">
     <InputGroupAddon><Search/></InputGroupAddon>
     <InputGroupInput aria-label="搜索设置与页面" placeholder="例如 先背再测、备份、朗读、导入题目" value={query} autoFocus onChange={e=>setQuery(e.target.value)}
      onKeyDown={e=>{if(e.key==='Enter'&&hits[0]){e.preventDefault();jump(hits[0]);}else if(e.key==='Escape')setOpen(false);}}/>
     {query&&<InputGroupAddon align="inline-end"><InputGroupButton size="icon-xs" aria-label="清空搜索" onClick={()=>setQuery('')}><X/></InputGroupButton></InputGroupAddon>}
    </InputGroup>
    <div role="listbox" aria-label="搜索结果" className="max-h-72 overflow-y-auto rounded-md border">
     {hits.length?hits.map(entry=><button key={entry.id} type="button" role="option" aria-selected={false} className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm hover:bg-accent" onClick={()=>jump(entry)}>
      <span className="min-w-0 truncate">{entry.title}</span><span className="ms-auto shrink-0 text-xs text-muted-foreground">{entry.where}</span>
     </button>):<p className="px-3 py-6 text-center text-xs text-muted-foreground">{query.trim()?'没有匹配的设置，换个说法试试。':'输入关键词开始搜索'}</p>}
    </div>
   </DialogContent>
  </Dialog>
 </>;}

function Dashboard({sessionId,logout,onSwitchHost,theme}:{sessionId:number,logout:()=>void,onSwitchHost:(host:string,name?:string)=>void,theme:ReturnType<typeof useTheme>}){
 const isMobile=useIsMobile();
 const{page,section,go}=useRoute();
 const state=useQuery({queryKey:['state',sessionId],queryFn:()=>api('/api/state'),refetchInterval:5000});
 const device=useQuery({queryKey:['device',sessionId],queryFn:()=>api('/api/device')});
 const reviews=useQuery({queryKey:['reviews',sessionId],queryFn:()=>api('/api/reviews'),refetchInterval:15000});
 const pending=reviews.data?.items?.length||0;
 const connected=!state.error;
 const localOnly=!!device.data?.localOnly;
 const updateChecked=useRef(false);
 // 打开家长后台时自动检查一次版本；纯本地版跳过，有新版本才提示
 useEffect(()=>{
  if(!device.data||device.data.localOnly||updateChecked.current)return;
  updateChecked.current=true;
  let stop=false;let tries=0;
  api('/api/update/check','POST',{}).catch(()=>{});
  const timer=setInterval(async()=>{
   tries++;
   try{
    const u=await api('/api/update');
    if(stop)return;
    if(!u.checking){
     clearInterval(timer);
     if(u.hasUpdate&&u.latest){
      toast.message(`发现新版本 v${u.latest}，请升级`,{action:{label:'去更新',onClick:()=>go('settings','update')}});
     }
    }
   }catch{}
   if(tries>=20)clearInterval(timer);
  },2500);
  return()=>{stop=true;clearInterval(timer);};
 },[device.data?.localOnly,!!device.data]);
 const current=pages.find(p=>p.id===page)!;
 return <SidebarProvider>
  {!isMobile&&<AppSidebar page={page} go={go} pending={pending} device={device.data} logout={logout}/>}
  <SidebarInset className="min-w-0">
   <Header title={page==='remote'?(device.data?.name||'我的设备'):current.title}>
    <Badge
     variant="secondary"
     className={
      connected
       ? 'me-1 hidden sm:flex bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300'
       : 'me-1 hidden sm:flex bg-amber-50 text-amber-700 dark:bg-amber-400/10 dark:text-amber-300'
     }
    >
     <span className={`me-1.5 size-1.5 rounded-full ${connected?'bg-emerald-500':'bg-amber-500'}`}/>
     {connected?'已连接':'连接中断'}
    </Badge>
    <GlobalSearch go={go} localOnly={localOnly}/><ThemeSwitch {...theme}/><div className="md:hidden"><DeviceMenu device={device.data} logout={logout}><Button variant="ghost" size="icon" aria-label="设备与连接"><Tv/></Button></DeviceMenu></div>
   </Header>
   <main className="page-content px-4 pt-4 md:px-6 md:pt-5 w-full max-w-6xl mx-auto">
    <div className={page==='remote'?'hidden':'mb-4 flex items-end justify-between gap-4 border-b pb-4'}><h1 className="text-2xl font-semibold tracking-tight">{current.title}</h1><p className="hidden shrink-0 text-xs text-muted-foreground sm:block">{current.description}</p></div>
    {state.error&&<Alert variant="destructive" className="mb-4"><AlertCircle/><AlertDescription>暂未能刷新数据，请确认设备仍在同一局域网。</AlertDescription></Alert>}
    <ErrorBoundary resetKey={`${page}-${section}`}>
     {page==='remote'&&<RemotePage state={state.data} sessionId={sessionId}/>}
     <Suspense fallback={<div className="flex items-center justify-center gap-2 py-16 text-sm text-muted-foreground"><LoaderCircle className="size-4 animate-spin"/>正在加载…</div>}>
      {page==='rules'&&<RulesPage state={state.data} sessionId={sessionId}/>}
      {page==='bank'&&<QuestionBank sessionId={sessionId}/>}
      {page==='wrong'&&<WrongBookPage sessionId={sessionId} state={state.data}/>}
      {page==='grading'&&<GradingPage sessionId={sessionId}/>}
      {page==='records'&&<RecordsPage sessionId={sessionId}/>}
      {page==='devices'&&<DevicesPage sessionId={sessionId} device={device.data} questionCount={state.data?.questions} onSwitchHost={onSwitchHost}/>}
     </Suspense>
     {page==='settings'&&<SettingsPage section={section} go={go} sessionId={sessionId} state={state.data} logout={logout} localOnly={localOnly}/>}
    </ErrorBoundary>
   </main>
  </SidebarInset>
  <BottomNav page={page} go={p=>go(p)} pending={pending}/>
 </SidebarProvider>;}

root.render(<QueryClientProvider client={client}><App/></QueryClientProvider>);

if(import.meta.env.PROD&&'serviceWorker'in navigator){
 window.addEventListener('load',()=>{navigator.serviceWorker.register('./sw.js').catch(()=>{});});
}
