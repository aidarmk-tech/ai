
import asyncio,json,re,urllib.request
from urllib.parse import urlsplit
from html.parser import HTMLParser
from playwright.async_api import async_playwright
COMMON="(function(){\n'use strict';\nif((window!==window.top&&!window.__lampMetadataFrame)||window.__lampOnline)return;\nHTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'));};\nvar state=window.__lampOnline={files:[],lists:[],playerFiles:[],playerLists:[],seen:{},errors:[],cap:480,ids:[],instances:[],xhr:[],shapes:[],jsonXhr:0};\nwindow.__lampBlockModern=function(){state.modernBlocked=true;};\nwindow.__lampRecordModern=function(rows,bound){\n var list=rows.map(function(row){return {title:row.voice||\"Основная озвучка\",folder:[{id:row.id||\"\",title:row.title||\"\",season:row.season||0,episode:row.episode||0,file:row.file}]};});\n remember(list,\"list\",!!bound);\n};\nvar originalParse=JSON.parse,originalStringify=JSON.stringify;\nfunction remember(value,kind,bound){\n try{\n  if(value==null)return;\n  var data=originalStringify(value);if(!data||data.length>900000)return;\n  var key=(bound?'player:':'')+kind+data;\n  if(state.seen[key])return;\n  state.seen[key]=1;\n  var target=bound?(kind==='file'?state.playerFiles:state.playerLists):(kind==='file'?state.files:state.lists);\n  if(target.length<16)target.push(value);\n }catch(e){}\n}\nfunction playlist(value,bound,voice,depth){\n if(window.__lampObserveModern)window.__lampObserveModern(value,!!bound);\n depth=depth||0;if(depth>5)return;\n if(typeof value==='string'&&value.length<900000&&/^\\s*[\\[{]/.test(value)){\n  try{playlist(originalParse(value),bound,voice,depth+1);}catch(e){}\n  return;\n }\n if(value&&typeof value==='object'&&!Array.isArray(value)){\n  if(state.shapes.length<8){var shape=Object.keys(value).slice(0,16).sort().join(',');if(state.shapes.indexOf(shape)<0)state.shapes.push(shape);}\n  if(value.file||value.folder)remember(voice?[{title:voice,folder:[value]}]:[value],'list',bound);\n  ['data','result','playlist'].forEach(function(key){if(value[key]!=null)playlist(value[key],bound,voice,depth+1);});\n  return;\n }\n if(!Array.isArray(value)||!value.length||value.length>1000)return;\n if(value.some(function(v){return v&&typeof v==='object'&&(v.file||v.folder)}))remember(voice?[{title:voice,folder:value}]:value,'list',bound);\n}\nfunction playerFile(value,voice){\n if(value==null||value==='')return;\n remember(voice&&!Array.isArray(value)?{title:voice,file:value}:value,'file',true);\n playlist(value,true,voice);\n if(typeof value==='string'&&value.length<900000&&/^\\s*\\[/.test(value)){try{playlist(originalParse(value),true,voice);}catch(e){}}\n}\nfunction activeVoice(){\n try{var label=document.querySelector('.player-main-block .translations li.active span[data-name]');return label?label.getAttribute('data-name')||'':'';}catch(e){return '';}\n}\nJSON.parse=function(){var result=originalParse.apply(JSON,arguments);playlist(result);return result;};\nvar open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;\nXMLHttpRequest.prototype.open=function(method,url){this.__lampUrl=String(url);return open.apply(this,arguments);};\nXMLHttpRequest.prototype.send=function(){\n this.addEventListener('load',function(){\n  try{\n   if(this.responseType==='json'){\n    state.jsonXhr++;\n    if(state.xhr.length<12)state.xhr.push([this.status,-1]);\n    playlist(this.response);\n    if(window.__lampObserveModern)window.__lampObserveModern(this.response,false,this.__lampUrl);\n   }else if(!this.responseType||this.responseType==='text'){\n    var text=this.responseText;\n    if(state.xhr.length<12)state.xhr.push([this.status,text?text.length:0]);\n    if(text&&text.length<900000){var value=originalParse(text);playlist(value);if(window.__lampObserveModern)window.__lampObserveModern(value,false,this.__lampUrl);}\n   }\n  }catch(e){}\n });\n return send.apply(this,arguments);\n};\nif(window.fetch){var fetch=window.fetch;window.fetch=function(){return fetch.apply(this,arguments).then(function(response){try{response.clone().text().then(function(text){if(text.length<900000){try{var value=originalParse(text);playlist(value);if(window.__lampObserveModern)window.__lampObserveModern(value,false,response.url);}catch(e){}}}).catch(function(){});}catch(e){}return response;});};}\nfunction wrapPlayer(){\n var original=window.Playerjs;\n if(typeof original==='function'&&!original.__lampWrapped){\n  var wrapped=function(options){\n   var id=typeof options==='string'?options:options&&options.id;\n   if(state.ids.length<12&&id)state.ids.push(String(id));\n   var main=typeof id==='string'&&(/^(?:video)?player$/i.test(id)||(window.__lampMetadataFrame&&/^player(?:js|[12])?$/i.test(id))),voice=main?activeVoice():'';\n   if(main&&options&&typeof options==='object')playerFile(options.file,voice);\n   var instance=Reflect.construct(original,Array.prototype.slice.call(arguments));\n   if(main&&instance&&typeof instance.api==='function'){\n    if(state.instances.length<4)state.instances.push({player:instance,voice:voice});\n    var api=instance.api;\n    try{instance.api=function(command,value){\n     if(arguments.length>1&&/^(file|playlist|preload|play)$/.test(command)){\n      playerFile(value,voice);\n     }\n     return api.apply(instance,arguments);\n    };}catch(e){}\n   }\n   return instance;\n  };\n  wrapped.prototype=original.prototype;Object.setPrototypeOf(wrapped,original);\n  wrapped.__lampWrapped=true;window.Playerjs=wrapped;\n }\n state.instances.forEach(function(entry){try{playlist(entry.player.api('playlist_folders'),true,entry.voice);}catch(e){}});\n if(window.user_data){state.cap=(user_data.is_user_pro_plus||user_data.is_user_loyal)?2160:(user_data.is_user_pro?720:480);}\n}\n// Catch assignment before the page immediately constructs its first player.\ntry {\n var playerValue=window.Playerjs;\n Object.defineProperty(window,'Playerjs',{configurable:true,enumerable:true,\n  get:function(){return playerValue;},\n  set:function(value){playerValue=value;if(typeof value==='function'&&!value.__lampWrapped)wrapPlayer();}\n });\n if(playerValue)wrapPlayer();\n}catch(e){}\nwindow.__lampInstallPlayer=wrapPlayer;\nsetInterval(wrapPlayer,50);\nfunction providerMessage(){\n if(!document.body)return '';\n // Providers can replace the whole player container with an availability notice.\n // Read visible source text while excluding comments and form/template content.\n var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node,parts=[],count=0;\n while((node=walker.nextNode())&&count++<6000){\n  var parent=node.parentElement;\n  if(!parent||parent.closest('script,style,noscript,template,textarea,input,[id^=\"comment\"],[class*=\"comment\"]'))continue;\n  if(!parent.getClientRects().length||getComputedStyle(parent).visibility==='hidden')continue;\n  parts.push(node.nodeValue);\n }\n return parts.join(' ');\n}\nwindow.__lampSnapshot=function(){\n wrapPlayer();\n var text=providerMessage();\n var blocked=/(недоступ(?:но|ен)[\\s\\S]{0,90}(?:стране|регион[ае])|заблокировано[\\s\\S]{0,90}правообладателя|нет видео файла)/i.exec(text);\n // Once a player is observed, unrelated page JSON cannot stand in for its playlist.\n var bound=state.ids.length||state.playerFiles.length||state.playerLists.length;\n var result={files:state.modernBlocked?[]:bound?state.playerFiles:state.files,lists:state.modernBlocked?[]:bound?state.playerLists:state.lists,cap:state.cap,blocked:state.modernBlocked?\"Видео заблокировано источником\":blocked?blocked[0]:null,playerType:typeof window.Playerjs,ids:state.ids,xhr:state.xhr,shapes:state.shapes,jsonXhr:state.jsonXhr,modern:window.__lampModernStats?window.__lampModernStats():null};\n var json=originalStringify(result);return json.length<1000000?json:'{}';\n};\n})();\n"
def safe(u):
 try:
  p=urlsplit(u);return p.scheme+"://"+p.netloc+p.path
 except:return "<value>"
class Tabs(HTMLParser):
 def __init__(self):super().__init__();self.urls=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);v=a.get("data-iframe","")
  if tag=="li" and v.startswith("http") and "trail" not in a.get("class","") and "trailer" not in v and v not in self.urls:self.urls.append(v)
def source():
 with urllib.request.urlopen(urllib.request.Request("https://kinokong.es/index.php?newsid=105623",headers={"User-Agent":"Mozilla/5.0"}),timeout=30) as r:
  raw=r.read(2500000);url=r.url
 text=raw.decode("windows-1251",errors="replace");parser=Tabs();parser.feed(text)
 print("TITLE",json.dumps({"url":safe(url),"title":re.findall(r"<title>(.*?)</title>",text),"players":[safe(u) for u in parser.urls]},ensure_ascii=False),flush=True)
 return url,parser.urls
async def main():
 url,embeds=await asyncio.to_thread(source)
 async with async_playwright() as p:
  browser=await p.chromium.launch();context=await browser.new_context(viewport={"width":1280,"height":720})
  await context.add_init_script("window.__lampMetadataFrame=window!==window.top;"+COMMON)
  media=[]
  async def route(r):
   if re.search(r"\.(?:m3u8|mp4|ts|m4s|mpd)(?:[?]|$)",r.request.url,re.I):media.append(safe(r.request.url));await r.abort()
   else:await r.continue_()
  await context.route("**/*",route)
  page=await context.new_page();page.on("popup",lambda w:asyncio.create_task(w.close()))
  await page.goto(url,wait_until="domcontentloaded",timeout=45000)
  for index,embed in enumerate(embeds[:2]):
   await page.evaluate("u=>{document.body.innerHTML='<iframe style=\"width:100%;height:650px\"></iframe>';document.querySelector('iframe').src=u;}",embed)
   await page.wait_for_timeout(12000)
   print("PLAYER",index,flush=True)
   for frame in page.frames:
    if frame==page.main_frame:continue
    try:
     data=await frame.evaluate("""() => {
      function clean(v,d){if(d>4)return typeof v==='object'?(Array.isArray(v)?'array:'+v.length:Object.keys(v||{}).slice(0,20)):typeof v;
       if(typeof v==='string'){if(/^https?:/.test(v)){try{let u=new URL(v);return u.origin+u.pathname;}catch(e){}}
        if(v.startsWith('#'))return 'encoded:'+v.length;return v.length>800?'text:'+v.length:v;}
       if(Array.isArray(v))return v.slice(0,4).map(x=>clean(x,d+1));
       if(v&&typeof v==='object'){if(v.nodeType)return 'DOM:'+v.nodeName;let o={};Object.keys(v).slice(0,35).forEach(k=>{if(/token|cookie|key|header/i.test(k)){o[k]='<redacted>';return;}try{o[k]=clean(v[k],d+1);}catch(e){o[k]='error';}});return o;}return typeof v==='function'?'function':v;}
      function inspect(p){if(!p)return null;let o={type:typeof p,keys:Object.keys(p).slice(0,25),api:typeof p.api};if(typeof p.api==='function'){
       for(let k of ['id','file','playlist','playlist_folders','playlist_id']){try{o[k]=clean(p.api(k),0);}catch(e){o[k]='error';}}}return o;}
      let globals={};
      for(let k of ['player','playerConfigs','pljssglobal','Playerjs','videoPlayer','playerjs']){try{globals[k]=clean(eval('typeof '+k+'===\"undefined\"?null:'+k),0);}catch(e){globals[k]='error';}}
      return {url:location.origin+location.pathname,title:document.title,text:document.body.innerText.slice(0,1400),globals:globals,
       named:typeof player==='undefined'?null:inspect(player),registered:typeof pljssglobal==='undefined'?null:Array.from(pljssglobal).slice(0,5).map(inspect),
       ids:Array.from(document.querySelectorAll('[id]')).map(e=>e.id).filter(x=>/player|video/i.test(x)).slice(0,20),
       scripts:Array.from(document.scripts).map(e=>e.src?new URL(e.src).origin+new URL(e.src).pathname:e.textContent.replace(/https?:[^\\s"'<>]+/g,'<url>').slice(0,15000)).slice(-15),
       snapshot:JSON.parse(window.__lampSnapshot())};
     }""")
     print("FRAME",json.dumps(data,ensure_ascii=False),flush=True)
    except Exception as e:print("FRAME_ERROR",str(e)[:300],flush=True)
  print("MEDIA_PATHS",json.dumps(media[:8]),flush=True);await browser.close()
asyncio.run(main())
