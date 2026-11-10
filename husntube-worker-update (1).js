// Cloudflare Worker (free): sends push notifications (OneSignal) and TURN credentials (Cloudflare) to signed-in HusnTube users.
// Variables to add in Cloudflare: FIREBASE_PROJECT, ONESIGNAL_APP_ID, ONESIGNAL_API_KEY (secret), CF_TURN_KEY_ID, CF_TURN_API_TOKEN (secret)
const CORS={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"Authorization, Content-Type","Access-Control-Allow-Methods":"POST, OPTIONS"};
const J=(o,s=200)=>new Response(JSON.stringify(o),{status:s,headers:{...CORS,"Content-Type":"application/json"}});
const b64=s=>Uint8Array.from(atob(s.replace(/-/g,"+").replace(/_/g,"/")),c=>c.charCodeAt(0));
let JWK=null,JT=0;
async function verify(t,P){const[h,p,s]=t.split(".");const H=JSON.parse(new TextDecoder().decode(b64(h))),D=JSON.parse(new TextDecoder().decode(b64(p)));
if(!JWK||Date.now()-JT>36e5){JWK=(await(await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")).json()).keys;JT=Date.now()}
const k=JWK.find(x=>x.kid===H.kid);if(!k||H.alg!=="RS256")return null;
const key=await crypto.subtle.importKey("jwk",k,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["verify"]);
const ok=await crypto.subtle.verify("RSASSA-PKCS1-v1_5",key,b64(s),new TextEncoder().encode(h+"."+p));
if(!ok||D.aud!==P||D.iss!=="https://securetoken.google.com/"+P||D.exp<Date.now()/1000||!D.sub)return null;
if(D.firebase&&D.firebase.sign_in_provider==="anonymous")return null;return D}
const fsGet=(P,path,tok)=>fetch("https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/"+path,{headers:{Authorization:"Bearer "+tok}}).then(r=>r.ok?r.json():null).catch(()=>null);
const fsGetS=async(P,path,tok,key)=>{const one=(k)=>fetch("https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/"+path+(k?(path.indexOf("?")>=0?"&":"?")+"key="+encodeURIComponent(k):""),{headers:{Authorization:"Bearer "+tok}}).then(async r=>{if(r.ok)return{s:r.status,j:await r.json()};let t="";try{t=(await r.text()).slice(0,200)}catch(e){}return{s:r.status,j:null,t}}).catch(e=>({s:0,j:null,t:String(e&&e.message||e).slice(0,100)}));
let r=await one(null);if(r.s===429){await new Promise(z=>setTimeout(z,700));r=await one(null);if(r.s===429&&key)r=await one(key)}return r};
const te=new TextEncoder();
const b64u=buf=>btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
async function lkToken(env,uid,name,room,canPub){const now=Math.floor(Date.now()/1000);
const head=b64u(te.encode(JSON.stringify({alg:"HS256",typ:"JWT"})));
const body=b64u(te.encode(JSON.stringify({iss:env.LIVEKIT_API_KEY,sub:uid,name,nbf:now-10,exp:now+3600,video:{room,roomJoin:true,canPublish:!!canPub,canSubscribe:true,canPublishData:true}})));
const key=await crypto.subtle.importKey("raw",te.encode(env.LIVEKIT_API_SECRET),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
const sig=b64u(await crypto.subtle.sign("HMAC",key,te.encode(head+"."+body)));return head+"."+body+"."+sig}
// ---- YouTube helpers: Cache API caching + soft daily budget (keeps the free 10,000-unit quota safe) ----
// search.list = 100 units (up to 50 results), videos.list = 1 unit (views/likes/comments/duration for 50 videos).
// Optional variables: YT_DAILY (max search calls/day per datacenter, default 80 => ~8,080 units), YT_VDAILY (max stats calls/day, default 1000).
async function ytCached(key,ttl,fn){const c=caches.default,rq=new Request("https://yt-cache.internal/"+encodeURIComponent(key)),r=await c.match(rq);if(r)return{j:await r.json(),hit:1};const j=await fn();if(j&&!j.err&&!j.error)await c.put(rq,new Response(JSON.stringify(j),{headers:{"Cache-Control":"max-age="+ttl,"Content-Type":"application/json"}}));return{j,hit:0}}
async function ytBudget(max,tag){const c=caches.default,rq=new Request("https://yt-cache.internal/budget/"+tag+"/"+new Date().toISOString().slice(0,10)),r=await c.match(rq);let n=r?(+(await r.text())||0):0;if(n>=max)return false;await c.put(rq,new Response(String(n+1),{headers:{"Cache-Control":"max-age=90000"}}));return true}
const ytDur=s=>{const m=/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(s||"");return m?(+m[1]||0)*3600+(+m[2]||0)*60+(+m[3]||0):0};
async function ytStats(ids,k){if(!ids.length)return{};const r=await fetch("https://www.googleapis.com/youtube/v3/videos?part=statistics,contentDetails&id="+ids.join(",")+"&key="+k,{signal:AbortSignal.timeout(6000)}),j=await r.json(),o={};(j.items||[]).forEach(v=>{const s=v.statistics||{};o[v.id]={v:+s.viewCount||0,l:+s.likeCount||0,c:+s.commentCount||0,d:ytDur(v.contentDetails&&v.contentDetails.duration)}});return o}

// ---- مسارات عامة (بلا حساب): دعم الزوار ونسيان كلمة السر، تكتب في Firestore بمفتاح الخدمة FB_SA ----
let SAT=null;const RLM=new Map();
async function saToken(env,scope){if(SAT&&SAT.s===scope&&SAT.e>Date.now()+60000)return SAT.t;const sa=JSON.parse(env.FB_SA),now=Math.floor(Date.now()/1000),hd=b64u(te.encode(JSON.stringify({alg:"RS256",typ:"JWT"}))),cl=b64u(te.encode(JSON.stringify({iss:sa.client_email,scope,aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3000}))),pem=String(sa.private_key||"").replace(/-----[A-Z ]+-----/g,"").replace(/\s+/g,""),der=Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),key=await crypto.subtle.importKey("pkcs8",der,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]),sig=b64u(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,te.encode(hd+"."+cl))),tr=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"grant_type="+encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")+"&assertion="+hd+"."+cl+"."+sig}),tj=await tr.json();if(!tj.access_token)throw new Error("sa:"+String(tj.error_description||tj.error||"").slice(0,120));SAT={s:scope,t:tj.access_token,e:Date.now()+(tj.expires_in||3000)*1000};return tj.access_token}
function rlim(k,max,win){const n=Date.now(),a=(RLM.get(k)||[]).filter(t=>n-t<win);if(a.length>=max){RLM.set(k,a);return false}a.push(n);RLM.set(k,a);if(RLM.size>3000)RLM.clear();return true}
async function pubRoute(path,b,env,req){const P=env.FIREBASE_PROJECT;if(!env.FB_SA)return J({error:"nosa"},501);const ip=req.headers.get("CF-Connecting-IP")||"x",base="https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/";
const fv=v=>typeof v==="boolean"?{booleanValue:v}:typeof v==="number"?{integerValue:String(Math.floor(v))}:{stringValue:String(v)},fields=o=>{const f={};for(const k in o)f[k]=fv(o[k]);return f};
try{const t=await saToken(env,"https://www.googleapis.com/auth/datastore"),H={Authorization:"Bearer "+t,"Content-Type":"application/json"};
if(path==="/gsup"){const tid=String(b.tid||""),text=String(b.text||"").trim().slice(0,1000),contact=String(b.contact||"").trim().slice(0,120),name=String(b.name||"").trim().slice(0,40);if(!/^g_[a-z0-9]{16,32}$/.test(tid)||!text)return J({error:"bad"},400);if(!rlim(ip+"s",12,600000))return J({error:"rate"},429);
const now=Date.now(),mid="m"+now.toString(36)+Math.random().toString(36).slice(2,6);let r=await fetch(base+"support/"+tid+"/msgs/"+mid,{method:"PATCH",headers:H,body:JSON.stringify({fields:fields({from:"u",text,t:now,guest:true})})});if(!r.ok)return J({error:"fs",status:r.status,msg:(await r.text()).slice(0,120)},502);
const fm={name:name||contact||"زائر",email:contact,last:now,lastText:text.slice(0,200),unread:true,guest:true},q=Object.keys(fm).map(k=>"updateMask.fieldPaths="+k).join("&");r=await fetch(base+"support/"+tid+"?"+q,{method:"PATCH",headers:H,body:JSON.stringify({fields:fields(fm)})});if(!r.ok)return J({error:"fs",status:r.status,msg:(await r.text()).slice(0,120)},502);return J({ok:true,id:mid,t:now})}
if(path==="/gsupget"){const tid=String(b.tid||"");if(!/^g_[a-z0-9]{16,32}$/.test(tid))return J({error:"bad"},400);if(!rlim(ip+"g",200,600000))return J({error:"rate"},429);const r=await fetch(base+"support/"+tid+"/msgs?pageSize=60&orderBy=t",{headers:H});if(!r.ok)return J({msgs:[]});const j=await r.json(),ms=(j.documents||[]).map(d=>{const f=d.fields||{};return{id:d.name.split("/").pop(),from:f.from&&f.from.stringValue,text:f.text&&f.text.stringValue,t:f.t?+(f.t.integerValue||f.t.doubleValue||0):0}}).filter(m=>m.text);return J({msgs:ms})}
if(path==="/forgot"){const em=String(b.email||"").trim().toLowerCase().slice(0,120);if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em))return J({error:"bad"},400);if(!rlim(ip+"f",5,3600000))return J({error:"rate"},429);const id="r"+Date.now().toString(36)+Math.random().toString(36).slice(2,6),r=await fetch(base+"resetReq/"+id,{method:"PATCH",headers:H,body:JSON.stringify({fields:fields({email:em,t:Date.now(),uid:"",guest:true})})});if(!r.ok)return J({error:"fs",status:r.status,msg:(await r.text()).slice(0,120)},502);return J({ok:true})}
return J({error:"bad"},400)}catch(e){return J({error:"err",msg:String((e&&e.message)||e).slice(0,120)},500)}}

// ---- إشعار البريد الإلكتروني (رسالة جديدة / مكالمة فائتة) عبر Resend ----
// متغيرات Cloudflare: RESEND_API_KEY (secret), MAIL_FROM مثل "HusnTube <noreply@yourdomain.com>", APP_URL (اختياري، الافتراضي https://husntubee1.web.app), ANDROID_PKG (اختياري: اسم حزمة تطبيق الأندرويد), TG_TOKEN (توكن بوت تيليجرام), TG_SECRET (كلمة سر للـ webhook), MAIL_ONLY (اختياري: بريد واحد أو عدة بريدات مفصولة بفاصلة فقط يُرسل لها — للتجربة؛ احذفه لتفعيل الجميع)
const MAILC=new Map(),escH=x=>String(x).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function mailNotify(env,P,u,b,to){
if((!env.RESEND_API_KEY&&!env.TG_TOKEN)||!env.FB_SA||!b.mail||!Array.isArray(to))return;const pid=String(b.dm||"");if(!/^[A-Za-z0-9_-]{6,160}$/.test(pid))return;
const kind=b.kind==="call"?"call":"msg",dt=await saToken(env,"https://www.googleapis.com/auth/datastore"),it=await saToken(env,"https://www.googleapis.com/auth/identitytoolkit"),base="https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/",app=String(env.APP_URL||"https://husntubee1.web.app").replace(/\/$/,"");
for(const t of to.slice(0,3)){if(typeof t!=="string"||t===u.sub||!/^[A-Za-z0-9_-]{6,128}$/.test(t))continue;if(pid.indexOf(u.sub)<0||pid.indexOf(t)<0)continue;
const ck=t+"|"+kind,now=Date.now();if(now-(MAILC.get(ck)||0)<(kind==="call"?120000:600000))continue;
const H={Authorization:"Bearer "+dt},pr=await fetch(base+"presence/"+t,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),seen=pr&&pr.fields&&pr.fields.t?+(pr.fields.t.integerValue||0):0;
if(kind==="msg"&&seen&&now-seen<200000)continue;
const ud=await fetch(base+"users/"+t,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),mOff=!!(ud&&ud.fields&&ud.fields.mailOff&&ud.fields.mailOff.booleanValue);
const rn=String(kind==="call"?b.body:b.title||"صديق").slice(0,40),nm=escH(rn),link=app+"/open.html?dm="+encodeURIComponent(pid)+(env.ANDROID_PKG?"&pkg="+encodeURIComponent(env.ANDROID_PKG):""),
head=kind==="call"?"📞 لديك مكالمة فائتة":"💬 لديك رسالة جديدة",sub=kind==="call"?"اتصل بك "+nm+" ولم تكن متصلاً.":"أرسل لك "+nm+" رسالة جديدة.";
MAILC.set(ck,now);if(MAILC.size>3000)MAILC.clear();
let sent=false;
if(env.TG_TOKEN){const td=await fetch(base+"tg/"+t,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),ch=td&&td.fields&&td.fields.chat&&td.fields.chat.stringValue;
if(ch){const r=await fetch("https://api.telegram.org/bot"+env.TG_TOKEN+"/sendMessage",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({chat_id:ch,text:kind==="call"?"📞 لديك مكالمة فائتة من "+rn+" في HusnTube":"💬 رسالة جديدة من "+rn+" في HusnTube",reply_markup:{inline_keyboard:[[{text:"فتح HusnTube",url:link}]]}})}).catch(()=>null);sent=!!(r&&r.ok)}}
if(sent||mOff||!env.RESEND_API_KEY)continue;
const lk=await fetch("https://identitytoolkit.googleapis.com/v1/projects/"+P+"/accounts:lookup",{method:"POST",headers:{Authorization:"Bearer "+it,"Content-Type":"application/json"},body:JSON.stringify({localId:[t]})}).then(r=>r.json()).catch(()=>null),em=lk&&lk.users&&lk.users[0]&&lk.users[0].email;
if(!em||/@phone\.husntube\.app$/.test(em))continue;
if(env.MAIL_ONLY&&!String(env.MAIL_ONLY).toLowerCase().split(",").map(x=>x.trim()).includes(String(em).toLowerCase()))continue;
const html='<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif;background:#150d2b;padding:24px"><div style="max-width:460px;margin:auto;background:#fff;border-radius:18px;padding:26px;text-align:center"><h2 style="margin:0 0 8px;color:#2a1760">'+head+'</h2><p style="color:#444;font-size:15px;line-height:1.8">'+sub+'</p><a href="'+link+'" style="display:inline-block;margin:14px 0;background:linear-gradient(135deg,#7c5cff,#e040fb);color:#fff;text-decoration:none;padding:13px 30px;border-radius:30px;font-weight:bold">فتح HusnTube</a><p style="color:#999;font-size:12px">يمكنك إيقاف هذه الرسائل من ملفي ← ⋮ ← إيقاف إشعارات البريد.</p></div></div>';
await fetch("https://api.resend.com/emails",{method:"POST",headers:{Authorization:"Bearer "+env.RESEND_API_KEY,"Content-Type":"application/json"},body:JSON.stringify({from:env.MAIL_FROM||"HusnTube <onboarding@resend.dev>",to:[em],subject:head.replace(/^\S+\s/,"")+" — HusnTube",html})}).catch(()=>{})}}
async function fcmNotify(env,P,u,b,to){const out=new Set();if(!env.FB_SA||!Array.isArray(to))return out;const pid=String(b.dm||"");if(!/^[A-Za-z0-9_-]{6,160}$/.test(pid))return out;
const k=["ring","cancel","call"].includes(b.kind)?b.kind:"msg",dt=await saToken(env,"https://www.googleapis.com/auth/datastore"),ft=await saToken(env,"https://www.googleapis.com/auth/firebase.messaging"),base="https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/",app=String(env.APP_URL||"https://husntubee1.web.app").replace(/\/$/,"");
for(const t of to.slice(0,3)){if(typeof t!=="string"||t===u.sub||!/^[A-Za-z0-9_-]{6,128}$/.test(t)||pid.indexOf(u.sub)<0||pid.indexOf(t)<0)continue;const H={Authorization:"Bearer "+dt};
const fd=await fetch(base+"fcm/"+t,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),tk=fd&&fd.fields&&fd.fields.tok&&fd.fields.tok.stringValue;if(!tk)continue;
if(k==="msg"){const pr=await fetch(base+"presence/"+t,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),seen=pr&&pr.fields&&pr.fields.t?+(pr.fields.t.integerValue||0):0;if(seen&&Date.now()-seen<60000)continue}
const data={type:k,title:String(b.title||"HusnTube").slice(0,60),body:String(b.body||"").slice(0,140),dm:pid,cid:String(b.cid||"").slice(0,40),url:app+"/?dm="+encodeURIComponent(pid)};
const r=await fetch("https://fcm.googleapis.com/v1/projects/"+P+"/messages:send",{method:"POST",headers:{Authorization:"Bearer "+ft,"Content-Type":"application/json"},body:JSON.stringify({message:{token:tk,data,android:{priority:"HIGH",ttl:k==="ring"?"45s":"3600s"}}})}).catch(()=>null);
if(r&&r.ok){if(k!=="cancel")out.add(t)}else if(r&&r.status===404)await fetch(base+"fcm/"+t,{method:"DELETE",headers:H}).catch(()=>{})}
return out}
async function tgHook(env,req,b){const P=env.FIREBASE_PROJECT;if(!env.TG_TOKEN||!env.FB_SA)return J({ok:true});
if(env.TG_SECRET&&req.headers.get("X-Telegram-Bot-Api-Secret-Token")!==env.TG_SECRET)return J({error:"auth"},401);
const m=b&&b.message,ch=m&&m.chat&&m.chat.id,tx=String((m&&m.text)||"").trim();if(!ch||!tx)return J({ok:true});
const say=t=>fetch("https://api.telegram.org/bot"+env.TG_TOKEN+"/sendMessage",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({chat_id:ch,text:t})}).catch(()=>{});
try{const dt=await saToken(env,"https://www.googleapis.com/auth/datastore"),H={Authorization:"Bearer "+dt,"Content-Type":"application/json"},base="https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/";
const mm=tx.match(/^\/start(?:@\w+)?\s+([A-Za-z0-9_-]{16,64})$/);
if(mm){const c=mm[1],d=await fetch(base+"tglink/"+c,{headers:H}).then(r=>r.ok?r.json():null).catch(()=>null),uid=d&&d.fields&&d.fields.uid&&d.fields.uid.stringValue,t0=d&&d.fields&&d.fields.t?+(d.fields.t.integerValue||0):0;
if(!uid||!t0||Date.now()-t0>1800000){await say("❌ رابط الربط منتهي أو غير صالح. ارجع إلى HusnTube وأعد المحاولة.");return J({ok:true})}
await fetch(base+"tg/"+uid,{method:"PATCH",headers:H,body:JSON.stringify({fields:{chat:{stringValue:String(ch)},t:{integerValue:String(Date.now())}}})});
await fetch(base+"tglink/"+c,{method:"DELETE",headers:H}).catch(()=>{});
await say("✅ تم ربط حسابك في HusnTube بنجاح! ستصلك هنا إشعارات المكالمات الفائتة والرسائل. للإيقاف أرسل /stop");return J({ok:true})}
if(/^\/stop/.test(tx)){const q=await fetch("https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents:runQuery",{method:"POST",headers:H,body:JSON.stringify({structuredQuery:{from:[{collectionId:"tg"}],where:{fieldFilter:{field:{fieldPath:"chat"},op:"EQUAL",value:{stringValue:String(ch)}}},limit:5}})}).then(r=>r.json()).catch(()=>[]);
for(const r of (Array.isArray(q)?q:[])){if(r.document&&r.document.name)await fetch("https://firestore.googleapis.com/v1/"+r.document.name,{method:"DELETE",headers:H}).catch(()=>{})}
await say("🔕 أُوقفت إشعارات تيليجرام.");return J({ok:true})}
await say("مرحباً 👋 لربط حسابك افتح HusnTube ← ملفي ← ⋮ ← ربط إشعارات تيليجرام.")}catch(e){await say("⚠️ تعذر الربط الآن، حاول مرة أخرى بعد قليل.")}
return J({ok:true})}
export default{async fetch(req,env,ctx){
if(req.method==="OPTIONS")return new Response(null,{headers:CORS});
const P=env.FIREBASE_PROJECT,tok=(req.headers.get("Authorization")||"").replace(/^Bearer /,"");
const path=new URL(req.url).pathname;let b={};try{b=await req.json()}catch(e){}
if(path==="/tg")return req.method==="POST"?await tgHook(env,req,b):J({ok:true,token:!!env.TG_TOKEN,secret:!!env.TG_SECRET,sa:!!env.FB_SA});
if(path==="/gsup"||path==="/gsupget"||path==="/forgot")return await pubRoute(path,b,env,req);
const u=await verify(tok,P).catch(()=>null);if(!u)return J({error:"auth"},401);
if(path==="/fcmtest"){if(!env.FB_SA)return J({ok:false,err:"FB_SA غير مضبوط"});try{const dt=await saToken(env,"https://www.googleapis.com/auth/datastore"),ft=await saToken(env,"https://www.googleapis.com/auth/firebase.messaging"),fd=await fetch("https://firestore.googleapis.com/v1/projects/"+P+"/databases/(default)/documents/fcm/"+u.sub,{headers:{Authorization:"Bearer "+dt}}).then(r=>r.ok?r.json():null).catch(()=>null),tk=fd&&fd.fields&&fd.fields.tok&&fd.fields.tok.stringValue;
if(!tk)return J({ok:false,err:"لا يوجد رمز جهاز محفوظ لحسابك (التطبيق لم يسجّل الجهاز)"});
const dl=Math.min(20,Math.max(0,+b.delay||0));if(dl)await new Promise(r=>setTimeout(r,dl*1000));
const r=await fetch("https://fcm.googleapis.com/v1/projects/"+P+"/messages:send",{method:"POST",headers:{Authorization:"Bearer "+ft,"Content-Type":"application/json"},body:JSON.stringify({message:{token:tk,data:{type:b.ring?"ring":"msg",title:"HusnTube",body:b.ring?"اختبار رنين مكالمة":"اختبار الإشعارات ✅",url:"https://husntubee1.web.app/"},android:{priority:"HIGH",ttl:"60s"}}})}),tx=await r.text();return J({ok:r.ok,status:r.status,msg:tx.slice(0,300)})}catch(e){return J({ok:false,err:String((e&&e.message)||e).slice(0,200)})}}
if(path==="/turn"){const r=await fetch("https://rtc.live.cloudflare.com/v1/turn/keys/"+env.CF_TURN_KEY_ID+"/credentials/generate",{method:"POST",headers:{Authorization:"Bearer "+env.CF_TURN_API_TOKEN,"Content-Type":"application/json"},body:JSON.stringify({ttl:86400})});
const d=await r.json();const s=d.iceServers;return J({iceServers:Array.isArray(s)?s:[s]})}
if(path==="/yt"){const q=String(b.q||"").trim().slice(0,120),pt=/^[A-Za-z0-9_-]{1,120}$/.test(String(b.pt||""))?String(b.pt):"",k=env.YT_KEY||(/^AIza[0-9A-Za-z_-]{35}$/.test(String(b.k||""))?String(b.k):"");if(!q)return J({error:"bad"},400);if(!k)return J({err:"nokey"});
const out=await ytCached("s|"+q.toLowerCase()+"|"+pt,21600,async()=>{if(!(await ytBudget(+env.YT_DAILY||80,"s")))return{err:"quota"};
try{const r=await fetch("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&videoSyndicated=true&safeSearch=moderate&relevanceLanguage=ar&maxResults=50&q="+encodeURIComponent(q)+(pt?"&pageToken="+pt:"")+"&key="+k,{signal:AbortSignal.timeout(8000)}),j=await r.json();if(j.error)return j;
const ids=(j.items||[]).map(i=>i.id&&i.id.videoId).filter(Boolean),st=await ytStats(ids,k).catch(()=>({}));j.items=(j.items||[]).map(i=>Object.assign(i,{st:st[i.id&&i.id.videoId]||null}));return j}catch(e){return{error:"yt"}}});
return J(Object.assign({},out.j,{cached:out.hit}))}
if(path==="/ytpl"){const q=String(b.q||"").trim().slice(0,100),k=env.YT_KEY||(/^AIza[0-9A-Za-z_-]{35}$/.test(String(b.k||""))?String(b.k):"");if(!q)return J({error:"bad"},400);if(!k)return J({err:"nokey"});
const out=await ytCached("p|"+q.toLowerCase(),43200,async()=>{if(!(await ytBudget(+env.YT_PDAILY||40,"p")))return{err:"quota"};
try{const nz=t=>String(t||"").toLowerCase().replace(/[\u064B-\u0652\u0640]/g,"").replace(/[أإآٱ]/g,"ا").replace(/ى/g,"ي").replace(/ة/g,"ه").replace(/[^\p{L}\p{N}\s]/gu," "),qt=nz(q).split(/\s+/).filter(x=>x.length>1);
const r=await fetch("https://www.googleapis.com/youtube/v3/search?part=snippet&type=playlist&maxResults=6&relevanceLanguage=ar&q="+encodeURIComponent(q)+"&key="+k,{signal:AbortSignal.timeout(8000)}),j=await r.json();if(j.error)return j;
const cand=(j.items||[]).filter(i=>i.id&&i.id.playlistId&&i.snippet).map(i=>{const t=nz(i.snippet.title+" "+i.snippet.channelTitle);return{i,s:qt.length?qt.filter(x=>t.includes(x)).length/qt.length:0}}).filter(o=>o.s>=0.6).sort((a,c)=>c.s-a.s).slice(0,2);
const pl=await Promise.all(cand.map(async o=>{const id=o.i.id.playlistId,eps=[];let pt="";for(let n=0;n<5;n++){const rr=await fetch("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId="+id+(pt?"&pageToken="+pt:"")+"&key="+k,{signal:AbortSignal.timeout(8000)}),jj=await rr.json();if(jj.error)break;(jj.items||[]).forEach(x=>{const sn=x.snippet||{},v=sn.resourceId&&sn.resourceId.videoId;if(v&&!/^(private|deleted) video$/i.test(sn.title||""))eps.push({v,t:String(sn.title||"").slice(0,90)})});pt=jj.nextPageToken||"";if(!pt)break}
const sn=o.i.snippet,th=sn.thumbnails||{};return{id,t:sn.title,ch:sn.channelTitle,th:(th.medium||th.high||th.default||{}).url||"",eps}}));
return{pl:pl.filter(p=>p.eps.length>0)}}catch(e){return{error:"yt"}}});
return J(Object.assign({},out.j,{cached:out.hit}))}
if(path==="/ytv"){const ids=(Array.isArray(b.ids)?b.ids:[]).map(String).filter(x=>/^[A-Za-z0-9_-]{11}$/.test(x)).slice(0,50),k=env.YT_KEY||(/^AIza[0-9A-Za-z_-]{35}$/.test(String(b.k||""))?String(b.k):"");if(!ids.length)return J({error:"bad"},400);if(!k)return J({err:"nokey"});
const out=await ytCached("v|"+ids.slice().sort().join(","),1800,async()=>{if(!(await ytBudget(+env.YT_VDAILY||1000,"v")))return{err:"quota"};
try{const r=await fetch("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics,contentDetails&id="+ids.join(",")+"&key="+k,{signal:AbortSignal.timeout(7000)}),j=await r.json();if(j.error)return j;const o={};
(j.items||[]).forEach(v=>{const s=v.statistics||{},sn=v.snippet||{};o[v.id]={v:+s.viewCount||0,l:+s.likeCount||0,c:+s.commentCount||0,d:ytDur(v.contentDetails&&v.contentDetails.duration),t:sn.title||"",ch:sn.channelTitle||"",p:String(sn.publishedAt||"").slice(0,4)}});return{items:o}}catch(e){return{error:"yt"}}});
return J(Object.assign({},out.j,{cached:out.hit}))}
if(path==="/tmdb"){const p=String(b.p||"");if(!/^(search\/multi|(movie|tv)\/\d{1,9}\/videos)$/.test(p))return J({error:"bad"},400);const qs=String(b.qs||"").slice(0,300),k=/^[a-f0-9]{32}$/i.test(String(b.k||""))?String(b.k):(env.TMDB_KEY||"15d21e5f88d372e396009ac06600ee74");try{const r=await fetch("https://api.themoviedb.org/3/"+p+"?api_key="+k+(qs?"&"+qs:""),{signal:AbortSignal.timeout(8000)});return J(await r.json())}catch(e){return J({error:"tmdb"},502)}}
if(path==="/push"){const to=b.to==="all"?"all":(Array.isArray(b.to)?b.to.filter(x=>typeof x==="string").slice(0,100):[]);
const rd=await fsGet(P,"roles/"+u.sub,tok),staff=!!(rd&&rd.fields&&rd.fields.role&&["owner","sup"].includes(rd.fields.role.stringValue));
if(to==="all"&&!staff)return J({error:"forbidden"},403);
if(to!=="all"&&!staff){if(!to.length)return J({error:"empty"},400);
if(b.g){const g=await fsGet(P,"groups/"+b.g,tok),mem=g&&g.fields&&g.fields.members&&g.fields.members.arrayValue.values?g.fields.members.arrayValue.values.map(v=>v.stringValue):[];
if(!mem.includes(u.sub)||!to.every(x=>mem.includes(x)))return J({error:"forbidden"},403)}
else{for(const t of to){const f=await fsGet(P,"friends/"+[u.sub,t].sort().join("_"),tok);if(!f||!f.fields||!f.fields.status||f.fields.status.stringValue!=="accepted")return J({error:"forbidden"},403)}}}
const body={app_id:env.ONESIGNAL_APP_ID,headings:{en:String(b.title||"HusnTube").slice(0,60)},contents:{en:String(b.body||"رسالة جديدة").slice(0,140)},url:b.url||undefined,
...(to==="all"?{included_segments:["Total Subscriptions"]}:{include_aliases:{external_id:to},target_channel:"push"})};
let r={ok:true};if(b.kind!=="cancel")r=await fetch("https://api.onesignal.com/notifications?c=push",{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Key "+env.ONESIGNAL_API_KEY},body:JSON.stringify(body)});
if(to!=="all"&&!b.g&&(b.mail||b.kind==="ring"||b.kind==="cancel")){const pm=(async()=>{const dl=await fcmNotify(env,P,u,b,to).catch(()=>new Set());if(b.mail)await mailNotify(env,P,u,b,to.filter(x=>!dl.has(x)))})().catch(()=>{});if(ctx&&ctx.waitUntil)ctx.waitUntil(pm);else await pm}
return J({ok:r.ok},r.ok?200:502)}
if(path==="/setpw"){const RR=await fsGetS(P,"roles/"+u.sub,tok,String(b.fk||"").slice(0,80)),rd0=RR.j,rl0=rd0&&rd0.fields&&rd0.fields.role&&rd0.fields.role.stringValue,envOwn=String(env.OWNER_UIDS||"").split(",").map(x=>x.trim()).filter(Boolean).includes(u.sub);if(!envOwn&&!["owner","sup"].includes(rl0)){if(!RR.j&&RR.s!==404)return J({error:"quota",status:RR.s,msg:RR.t||""},503);return J({error:"forbidden"},403)};
const tu=String(b.uid||""),pw=String(b.pw||"");if(!/^[A-Za-z0-9_-]{6,128}$/.test(tu)||pw.length<6||pw.length>64)return J({error:"bad"},400);if(!env.FB_SA)return J({error:"nosa"},501);
try{const sa=JSON.parse(env.FB_SA),now=Math.floor(Date.now()/1000),hd=b64u(te.encode(JSON.stringify({alg:"RS256",typ:"JWT"}))),cl=b64u(te.encode(JSON.stringify({iss:sa.client_email,scope:"https://www.googleapis.com/auth/identitytoolkit",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3000}))),pem=String(sa.private_key||"").replace(/-----[A-Z ]+-----/g,"").replace(/\s+/g,""),der=Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),key=await crypto.subtle.importKey("pkcs8",der,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]),sig=b64u(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,te.encode(hd+"."+cl))),tr=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"grant_type="+encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")+"&assertion="+hd+"."+cl+"."+sig}),tj=await tr.json();
if(!tj.access_token)return J({error:"sa",msg:String(tj.error_description||tj.error||"").slice(0,160)},502);const r=await fetch("https://identitytoolkit.googleapis.com/v1/projects/"+P+"/accounts:update",{method:"POST",headers:{Authorization:"Bearer "+tj.access_token,"Content-Type":"application/json"},body:JSON.stringify({localId:tu,password:pw})});if(!r.ok){let tx="";try{tx=(await r.text()).slice(0,200)}catch(e2){}return J({error:"upd",msg:tx},502)}let em2="",ver=false;try{const lr=await fetch("https://identitytoolkit.googleapis.com/v1/projects/"+P+"/accounts:lookup",{method:"POST",headers:{Authorization:"Bearer "+tj.access_token,"Content-Type":"application/json"},body:JSON.stringify({localId:[tu]})}),lj=await lr.json(),us=(lj.users||[])[0];em2=(us&&us.email)||"";const FK=String(b.fk||"");if(em2&&FK){const vr=await fetch("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key="+encodeURIComponent(FK),{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:em2,password:pw,returnSecureToken:false})});ver=vr.ok}}catch(e3){}
return J({ok:true,email:em2,verified:ver})}catch(e){return J({error:(e&&e.name==="SyntaxError")?"badjson":"err",msg:String((e&&e.message)||e).slice(0,140)},500)}}
if(path==="/lk"){const room=String(b.room||"").slice(0,80);let canPub=false;
if(!room)return J({error:"room"},400);if(!env.LIVEKIT_API_KEY||!env.LIVEKIT_API_SECRET)return J({error:"config"},500);
const FK=String(b.fk||"").slice(0,80);const R=await fsGetS(P,"rooms/"+room,tok,FK),rm=R.j;if(!rm)return R.s===404?J({error:"closed"},403):J({error:"backend",status:R.s,msg:R.t||""},503);if(!rm.fields||!rm.fields.status||rm.fields.status.stringValue!=="live")return J({error:"closed"},403);
const kk=await fsGet(P,"rooms/"+room+"/kick/"+u.sub,tok);if(kk&&kk.fields)return J({error:"kicked"},403);
const ML=await fsGetS(P,"rooms/"+room+"/mics?pageSize=30",tok,FK);canPub=!!(ML.j&&ML.j.documents&&ML.j.documents.some(d=>d.fields&&d.fields.uid&&d.fields.uid.stringValue===u.sub));
const st=(rm.fields.settings&&rm.fields.settings.mapValue&&rm.fields.settings.mapValue.fields)||{};const isMod=!!(rm.fields.mods&&rm.fields.mods.arrayValue&&(rm.fields.mods.arrayValue.values||[]).some(v=>v.stringValue===u.sub));const isOwner=!!(rm.fields.owner&&rm.fields.owner.stringValue===u.sub);
if(st.allowListen&&st.allowListen.booleanValue===false&&!canPub&&!isOwner&&!isMod)return J({error:"listen-off"},403);
const token=await lkToken(env,u.sub,String(b.name||"عضو").slice(0,40),room,canPub);
return J({token,url:env.LIVEKIT_URL||"wss://husntube-ovmwp2ps.livekit.cloud"})}
if(path==="/resolve"){const u0=String(b.u||"");let x;try{x=new URL(u0)}catch(e){return J({error:"bad"},400)}
const ok=h=>/(^|\.)(tiktok\.com|fb\.watch|fb\.com|facebook\.com)$/.test(h.replace(/^(www|m)\./,""));
if(x.protocol!=="https:"||!ok(x.hostname))return J({error:"host"},400);
const tf=(u,o)=>fetch(u,{...o,signal:AbortSignal.timeout(3500)});
const UA="Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
const out={url:u0};
if(/tiktok\.com/.test(x.hostname)){
try{const o=await(await tf("https://www.tiktok.com/oembed?url="+encodeURIComponent(u0))).json();out.title=o.title||"";out.thumb=o.thumbnail_url||"";const m=/data-video-id="(\d+)"/.exec(o.html||"");if(m)out.url="https://www.tiktok.com/@"+(o.author_unique_id||"user")+"/video/"+m[1]}catch(e){}
if(!/\/video\/\d+/.test(out.url)){let cur=u0;for(let i=0;i<4;i++){try{const r=await tf(cur,{redirect:"manual",headers:{"User-Agent":UA}});const l=r.headers.get("location");if(!l)break;cur=new URL(l,cur).href;if(/\/video\/\d+/.test(cur)){out.url=cur;break}}catch(e){break}}}
}else{
try{const r=await tf(u0,{redirect:"follow",headers:{"User-Agent":"facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)","Accept-Language":"en"}});const t=await r.text();
const mt=p=>{const m=new RegExp('<meta[^>]+property=["\']'+p+'["\'][^>]+content=["\']([^"\']*)["\']','i').exec(t)||new RegExp('<meta[^>]+content=["\']([^"\']*)["\'][^>]+property=["\']'+p+'["\']','i').exec(t);return m?m[1].replace(/&amp;/g,"&").replace(/&quot;/g,'"'):""};
const ou=mt("og:url");if(ou&&ok(new URL(ou).hostname))out.url=ou;else if(r.url&&ok(new URL(r.url).hostname)&&!/\/login/.test(r.url))out.url=r.url;out.title=mt("og:title");out.thumb=mt("og:image")}catch(e){}
}
return J(out)}
return J({error:"not found"},404)}}
