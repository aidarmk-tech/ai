
import asyncio,json,re,urllib.request
from urllib.parse import urlsplit
from playwright.async_api import async_playwright
URLS=["https://zona.movie/series/mobland-1478/","https://zona.movie/film/anel-76932/"]
def safe(value):
 try:
  p=urlsplit(value);return p.scheme+"://"+p.netloc+p.path
 except:return ""
def read(url):
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0"}),timeout=30) as r:raw=r.read(2500000);final=r.url
  text=raw.decode("utf-8",errors="replace")
  print("HTTP",json.dumps({"url":safe(final),"size":len(raw),"title":re.findall("<title>(.*?)</title>",text,re.S),
    "player_markup":[text[max(0,m.start()-400):m.start()+5500] for m in re.finditer(r'class="tabs-box2',text)][:2],
    "lines":[re.sub(r"https?[^\\s'\"<>]+","<url>",l)[:1600] for l in text.splitlines() if re.search(r"iframe|data-(?:src|player|tab)|player[12]|Плеер [12]",l,re.I)][:40]},ensure_ascii=False),flush=True)
 except Exception as e:print("HTTP_ERROR",safe(url),str(e)[:180],flush=True)
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
  async def snap():
   return await page.evaluate("""() => {
    function safe(v){try{var u=new URL(v,location.href);return u.origin+u.pathname;}catch(e){return String(v).slice(0,100);}}
    function attrs(el){return Object.fromEntries(Array.from(el.attributes).filter(a=>a.name!=='style').map(a=>[a.name,/src|url|frame/.test(a.name)?safe(a.value):a.value.slice(0,300)]));}
    return {url:safe(location.href),title:document.title,
      frames:Array.from(document.querySelectorAll('iframe')).map(e=>({attrs:attrs(e),parent:attrs(e.parentElement),grand:attrs(e.parentElement.parentElement)})),
      tabs:Array.from(document.querySelectorAll('[data-tab],[data-player],[class*=player],[class*=Player],[class*=tab],[class*=Tab]')).filter(e=>e.children.length<6).map(e=>({tag:e.tagName,attrs:attrs(e),text:e.textContent.trim().slice(0,300)})).slice(-45),
      scripts:Array.from(document.scripts).filter(e=>e.src).map(e=>safe(e.src)).slice(-15),
      inline:Array.from(document.scripts).filter(e=>!e.src&&/iframe|player|Player|video/.test(e.textContent)).map(e=>e.textContent.replace(/https?:[^\\s"'<>]+/g,'<url>').slice(0,9000)).slice(-12)};
   }""")
  print("PAGE",json.dumps(await snap(),ensure_ascii=False),flush=True)
  for label in ["🔥 Плеер 2 (4K)","🔥 Плеер 2 (HD)","Плеер 2"]:

   el=page.get_by_text(label,exact=True)
   if await el.count():
    await el.first.click(timeout=4000,force=True);await page.wait_for_timeout(4000)
    print("ALTERNATE",json.dumps(await snap(),ensure_ascii=False),flush=True);break
  for f in page.frames:
   if f==page.main_frame:continue
   try:print("FRAME",json.dumps(await f.evaluate("({url:location.origin+location.pathname,title:document.title,text:document.body?.innerText.slice(0,800),player:typeof Playerjs,venom:typeof VenomPlayer})"),ensure_ascii=False),flush=True)
   except Exception as e:print("FRAME_ERROR",str(e)[:150],flush=True)
 except Exception as e:print("PAGE_ERROR",safe(url),str(e)[:200],flush=True)
 await context.close()
async def main():
 await asyncio.gather(*(asyncio.to_thread(read,u) for u in URLS))
 async with async_playwright() as p:
  browser=await p.chromium.launch();await asyncio.gather(*(inspect(browser,u) for u in URLS));await browser.close()
asyncio.run(main())
