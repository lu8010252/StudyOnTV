/* Browser practice only: never cache parent APIs, credentials or the controlled-device pages. */
const CACHE='studyontv-practice-v1';
const FILES=['./practice.html','./assets/css/practice.css','./assets/js/practice.js'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(FILES)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('studyontv-practice-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(event.request.method!=='GET'||url.origin!==self.location.origin)return;if(!FILES.some(file=>new URL(file,self.location.href).pathname===url.pathname)&&url.pathname!=='/practice')return;event.respondWith(fetch(event.request).then(response=>{if(response.ok){const copy=response.clone();caches.open(CACHE).then(cache=>cache.put(event.request,copy));}return response;}).catch(()=>caches.match(url.pathname==='/practice'?'./practice.html':event.request)));});
