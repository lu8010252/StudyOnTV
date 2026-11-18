import {useEffect,useRef,useState} from 'react';
import {Download,Share,X} from 'lucide-react';
import {Button} from '@/components/ui/button';
import {Alert,AlertDescription} from '@/components/ui/alert';
import {Card,CardContent,CardFooter} from '@/components/ui/card';

interface InstallPromptEvent extends Event {
  prompt:()=>Promise<void>;
  userChoice:Promise<{outcome:'accepted'|'dismissed'}>;
}

const DISMISS_KEY='sotv-install-prompt-dismissed';
const DISMISS_DAYS=7;

function recentlyDismissed(){
  try{
    const at=Number(localStorage.getItem(DISMISS_KEY)||0);
    return Date.now()-at<DISMISS_DAYS*24*60*60*1000;
  }catch{return false;}
}

function isStandalone(){
  return window.matchMedia('(display-mode: standalone)').matches||(navigator as Navigator&{standalone?:boolean}).standalone===true;
}

function isIos(){
  return /iphone|ipad|ipod/i.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
}

export default function InstallPrompt({enabled}:{enabled:boolean}){
  const[deferredPrompt,setDeferredPrompt]=useState<InstallPromptEvent|null>(null);
  const[visible,setVisible]=useState(false);
  const[showGuide,setShowGuide]=useState(false);
  const enabledRef=useRef(enabled);

  useEffect(()=>{enabledRef.current=enabled;},[enabled]);

  useEffect(()=>{
    const onBeforeInstall=(e:Event)=>{
      e.preventDefault();
      setDeferredPrompt(e as InstallPromptEvent);
      if(enabledRef.current&&!isStandalone()&&!recentlyDismissed())setVisible(true);
    };
    const onInstalled=()=>{
      setVisible(false);
      setDeferredPrompt(null);
    };
    window.addEventListener('beforeinstallprompt',onBeforeInstall);
    window.addEventListener('appinstalled',onInstalled);
    return()=>{
      window.removeEventListener('beforeinstallprompt',onBeforeInstall);
      window.removeEventListener('appinstalled',onInstalled);
    };
  },[]);

  useEffect(()=>{
    if(!enabled){
      setVisible(false);
      return;
    }
    if(isStandalone()||recentlyDismissed())return;
    // Only prompt when beforeinstallprompt is actually available or on iOS Add-to-Home-Screen
    // (avoids showing a non-working install button on plain LAN HTTP).
    if(!deferredPrompt&&!isIos())return;
    const timer=window.setTimeout(()=>setVisible(true),2200);
    return()=>window.clearTimeout(timer);
  },[enabled,deferredPrompt]);

  function dismiss(){
    try{localStorage.setItem(DISMISS_KEY,String(Date.now()));}catch{}
    setVisible(false);
  }

  async function install(){
    if(!deferredPrompt){
      setShowGuide(true);
      return;
    }
    await deferredPrompt.prompt();
    const choice=await deferredPrompt.userChoice;
    if(choice.outcome==='accepted')setVisible(false);
    setDeferredPrompt(null);
  }

  const ios=isIos();
  if(!enabled||!visible||(!deferredPrompt&&!ios))return null;

  return <aside className="fixed inset-x-3 bottom-[calc(5.5rem+env(safe-area-inset-bottom))] z-[60] md:inset-x-auto md:bottom-5 md:right-5 md:w-[390px]" role="dialog" aria-label="安装家长管理">
    <Card className="relative gap-0 overflow-hidden border-border/70 bg-card/95 py-0 shadow-xl backdrop-blur">
      <Button type="button" variant="ghost" size="icon" className="absolute end-2 top-2 z-10 size-8" aria-label="暂不安装" onClick={dismiss}><X/></Button>
      <CardContent className="grid grid-cols-[44px_minmax(0,1fr)] gap-3 px-4 py-4 pe-11">
        <img className="size-11 rounded-xl shadow-sm" src="./icons/icon-192.png" alt=""/>
        <div>
          <strong className="text-sm font-semibold">添加到主屏幕</strong>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">像普通 App 一样快速打开，不必每次输入网址。</p>
        </div>
        {showGuide&&<Alert className="col-span-full bg-muted/45 px-3 py-2">
          <Share/>
          <AlertDescription className="text-xs">Safari 浏览器：点击底部“分享”，选择“添加到主屏幕”。</AlertDescription>
        </Alert>}
      </CardContent>
      <CardFooter className="justify-end gap-2 border-t px-4 py-3">
        {deferredPrompt?(
          <Button size="sm" onClick={install}><Download/>立即安装</Button>
        ):!showGuide?(
          <Button size="sm" variant="outline" onClick={()=>setShowGuide(true)}><Share/>查看添加方法</Button>
        ):(
          <Button size="sm" variant="ghost" onClick={dismiss}>知道了</Button>
        )}
      </CardFooter>
    </Card>
  </aside>;
}
