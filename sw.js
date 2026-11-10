try{importScripts("https://cdn.onesignal.com/sdks/web/v16/OneSignalSDK.sw.js")}catch(e){}
// HusnTube service worker: always fetch the newest app, never serve an old cached version
const HU_V="20261110dm";
self.addEventListener("install",()=>{self.skipWaiting()});
self.addEventListener("activate",e=>{e.waitUntil((async()=>{try{for(const k of await caches.keys())await caches.delete(k)}catch(x){}await self.clients.claim();try{for(const c of await self.clients.matchAll({type:"window"}))c.postMessage({hu:"upd",v:HU_V})}catch(x){}})())});
self.addEventListener("fetch",e=>{const r=e.request;if(r.method!=="GET")return;let u;try{u=new URL(r.url)}catch(x){return}if(u.origin!==location.origin)return;if(r.mode==="navigate"||/\.(html|js)$/.test(u.pathname)||u.pathname==="/"){e.respondWith(fetch(r,{cache:"no-store"}).catch(()=>new Response("offline",{status:503})))}});
