import asyncio,json,re,urllib.request
from urllib.parse import urlsplit
from playwright.async_api import async_playwright
BASE="https://kinotac.org"
def safe(value):
 try:
  p=urlsplit(value);return p.scheme+"://"+p.netloc+p.path
 except:return ""
def read(url):
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0"}),timeout=30) as r:t=r.read(2500000).decode("utf-8","replace");final=r.url
  snippets=[t[max(0,m.start()-300):m.start()+2200] for m in re.finditer(r"<iframe|class=[\"'][^\"']*(?:player|tabs|video)|new Playerjs|new Player|iframe_url",t,re.I)][:16]
  snippets=[re.sub(r"https?://[^\s'\"<>]+",lambda m:safe(m.group(0)),x) for x in snippets]
  print("HTTP",json.dumps({"url":safe(final),"title":re.findall("<title>(.*?)</title>",t,re.S),"size":len(t),"markup":snippets},ensure_ascii=False),flush=True)
  return t
 except Exception as e:print("HTTP_ERROR",safe(url),str(e)[:180],flush=True);return ""
async def inspect(browser,url):
 context=await browser.new_context(viewport={"width":1280,"height":720});page=await context.new_page()
 await context.add_init_script("HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'))};")
 async def route(r):
  if re.search(r"\.(?:m3u8|mp4|ts|m4s|mpd)(?:[?]|$)",r.request.url,re.I):await r.abort()
  else:await r.continue_()
 await context.route("**/*",route)
 page.on("popup",lambda p:asyncio.create_task(p.close()))
 try:
  await page.goto(url,wait_until="domcontentloaded",timeout=45000);await page.wait_for_timeout(6000)
  v=await page.evaluate("""() => {
    function safe(v){try{var u=new URL(v,location.href);return u.origin+u.pathname;}catch(e){return String(v).slice(0,100);}}
    function attrs(el){return Object.fromEntries(Array.from(el.attributes).filter(a=>a.name!=='style').map(a=>[a.name,/src|url|frame|link/.test(a.name)?safe(a.value):a.value.slice(0,250)]));}
    return {url:safe(location.href),title:document.title,frames:Array.from(document.querySelectorAll('iframe')).map(e=>({attrs:attrs(e),parent:attrs(e.parentElement),grand:attrs(e.parentElement.parentElement)})),
      tabs:Array.from(document.querySelectorAll('[data-tab],[data-player],[class*=player],[class*=tab],[id*=player]')).map(e=>({tag:e.tagName,attrs:attrs(e),text:e.textContent.trim().slice(0,250)})).slice(-25),
      scripts:Array.from(document.scripts).filter(e=>e.src).map(e=>safe(e.src)).slice(-12),
      inline:Array.from(document.scripts).filter(e=>!e.src&&/iframe|player|Player/.test(e.textContent)).map(e=>e.textContent.replace(/https?:[^\s"'<>]+/g,'<url>').slice(0,6500)).slice(-8)};
   }""")
  print("PAGE",json.dumps(v,ensure_ascii=False),flush=True)
  await page.evaluate("// KinoTac website controls: keep SDK players on the site and never select a trailer.\n(function(){\n'use strict';\nif(window!==window.top||window.__lampTacControls)return;\nfunction excluded(e){return !!e.closest('[id^=\"comm\"],#full-comms,.comm-item,[class^=\"comment\"],.advertisement,.ads,.trailer,.trail');}\nfunction group(){return Array.from(document.querySelectorAll('article.full .pmovie__player')).find(function(e){return !excluded(e);})||null;}\nfunction rawPanels(){var g=group();return g?Array.from(g.querySelectorAll('.tabs-block__content')):[];}\nfunction validFrame(e){var value=e.getAttribute('src');if(!value||value==='about:blank')value=e.getAttribute('data-src');if(!value||excluded(e))return false;\n try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&!u.pathname.split('/').some(function(p){return /^(trailer|trailer-cdn)$/i.test(p);});}catch(err){return false;}}\nfunction entries(){return rawPanels().map(function(panel,slot){\n if(excluded(panel)||panel.classList.contains('fplay'))return null;\n var frame=panel.classList.contains('fplayer')&&Array.from(panel.querySelectorAll('iframe')).find(validFrame);\n var sdk=panel.querySelector('video-player#cdnvideohubvideoplayer[data-title-id]');\n return frame||sdk?{panel:panel,slot:slot,frame:frame,sdk:sdk}:null;\n }).filter(Boolean).slice(0,3);}\nvar session=0;\nfunction focus(index,activate){var pass=++session,g=group();if(!g)return false;var attempts=0;\n function locate(){if(pass!==session)return;var entry=entries()[index];\n if(entry){var buttons=Array.from(g.querySelectorAll('.tabs-block__select > button')),button=buttons[entry.slot];\n if(activate&&button&&!button.classList.contains('is-active')&&!button.classList.contains('active'))button.click();\n rawPanels().forEach(function(p){if(p===entry.panel){p.classList.remove('d-none');p.style.display='block';}else{p.classList.add('d-none');p.style.display='none';}});\n buttons.forEach(function(b,i){b.classList.toggle('is-active',i===entry.slot);b.classList.toggle('active',i===entry.slot);});\n var target=entry.frame||entry.sdk;\n if(entry.frame){var src=target.getAttribute('src'),lazy=target.getAttribute('data-src');\n if((!src||src==='about:blank')&&lazy){try{var u=new URL(lazy,location.href);if(/^https?:$/.test(u.protocol))target.src=u.href;}catch(e){}}\n target.loading='eager';}\n if(target){entry.panel.style.width='100%';entry.panel.style.minHeight='240px';target.style.width='100%';target.style.height=Math.max(240,Math.round(innerHeight*.85))+'px';\n target.tabIndex=0;target.scrollIntoView({block:'start'});try{target.focus({preventScroll:true});}catch(e){}return;}\n }\n g.scrollIntoView({block:'start'});if(++attempts<24)setTimeout(locate,250);\n }locate();return true;}\nwindow.__lampTacControls={focus:focus,count:function(){return entries().length;}};\n})();\n")
  for index in [0,2]:
   v=await page.evaluate("(i)=>{window.__lampTacControls.focus(i,true);return {count:window.__lampTacControls.count(),article:!!document.querySelector('article.full'),selected:document.querySelector('.tabs-block__select .active')?.textContent,frames:document.querySelectorAll('article.full .pmovie__player .tabs-block__content.fplayer iframe').length,focused:document.activeElement?.tagName};}",index)
   print("CONTROLS",json.dumps(v,ensure_ascii=False),flush=True)
  for f in page.frames:
   if f==page.main_frame:continue
   try:print("FRAME",json.dumps(await f.evaluate("({url:location.origin+location.pathname,title:document.title,text:document.body?.innerText.slice(0,700),player:typeof Playerjs,venom:typeof VenomPlayer})"),ensure_ascii=False),flush=True)
   except Exception as e:print("FRAME_ERROR",str(e)[:150],flush=True)
 except Exception as e:print("PAGE_ERROR",safe(url),str(e)[:200],flush=True)
 await context.close()
async def main():
 home=await asyncio.to_thread(read,BASE+"/")
 links=re.findall(r'href=["\'](?:https://kinotac.org)?(/(\d+)-[^"\'/]+\.html)',home)
 other=next((BASE+u for u,i in links if i!="20817"),BASE+"/20817-korol-talsy.html")
 urls=[BASE+"/20817-korol-talsy.html",other]
 await asyncio.gather(*(asyncio.to_thread(read,u) for u in urls))
 async with async_playwright() as p:
  browser=await p.chromium.launch();await asyncio.gather(*(inspect(browser,u) for u in urls));await browser.close()
asyncio.run(main())
