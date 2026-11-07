
import asyncio,json,re,urllib.request
from urllib.parse import urlsplit
from playwright.async_api import async_playwright
def safe(u):
 try:
  p=urlsplit(u);return p.scheme+"://"+p.netloc+p.path
 except:return ""
def fetch():
 req=urllib.request.Request("https://kinokong.es/index.php?newsid=82174",headers={"User-Agent":"Mozilla/5.0"})
 with urllib.request.urlopen(req,timeout=30) as r:
  raw=r.read(2000000);url=r.url
 text=raw.decode("windows-1251",errors="replace")
 canonical=re.search(r'<link[^>]+rel=["\']canonical["\'][^>]+href=["\']([^"\']+)',text,re.I)
 print("HTTP",json.dumps({"url":safe(url),"canonical":canonical.group(1) if canonical else None,"size":len(raw),"title":re.findall(r"<title>(.*?)</title>",text,re.S),"tabs":len(re.findall("newTabs",text))},ensure_ascii=False),flush=True)
 return canonical.group(1) if canonical else url
async def main():
 url=await asyncio.to_thread(fetch)
 async with async_playwright() as p:
  browser=await p.chromium.launch()
  for width,agent in [(1280,None),(480,"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"),(960,"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Safari/537.36")]:
   context=await browser.new_context(viewport={"width":width,"height":720},**({"user_agent":agent} if agent else {}))
   page=await context.new_page();errors=[]
   page.on("pageerror",lambda e:errors.append(str(e)[:160]))
   async def route(r):
    if re.search(r"\.(?:m3u8|mp4|ts|m4s)(?:[?]|$)",r.request.url,re.I):await r.abort()
    else:await r.continue_()
   await context.route("**/*",route)
   await context.add_init_script("HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'))};")
   try:
    await page.goto(url,wait_until="domcontentloaded",timeout=45000)
    await page.wait_for_timeout(8000)
    data=await page.evaluate("""() => {
     const safe=v=>{try{var u=new URL(v,location.href);return u.origin+u.pathname;}catch(e){return String(v).slice(0,100);}};
     const attrs=e=>Object.fromEntries(Array.from(e.attributes).filter(a=>!/style/.test(a.name)).map(a=>[a.name,/src|iframe|url|video/.test(a.name)?safe(a.value):a.value.slice(0,120)]));
     return {url:safe(location.href),title:document.title,h1:document.querySelector('h1')?.textContent,ids:Array.from(document.querySelectorAll('[id]')).map(e=>e.id).filter(x=>/full_|player|video|preroll/i.test(x)),
      tabs:Array.from(document.querySelectorAll('.newTabs,[data-iframe],[class*=tabs],[class*=Tabs]')).map(e=>({tag:e.tagName,attrs:attrs(e),text:e.textContent.trim().slice(0,300)})).slice(0,30),
      frames:Array.from(document.querySelectorAll('iframe')).map(e=>({attrs:attrs(e),parent:attrs(e.parentElement),grand:attrs(e.parentElement.parentElement)})),
      scripts:Array.from(document.scripts).filter(e=>e.src&&/player|video|embed|tab/i.test(e.src)).map(e=>safe(e.src)),
      inline:Array.from(document.scripts).filter(e=>!e.src&&/data-iframe|UpPlay|newTabs|preroll/.test(e.textContent)).map(e=>e.textContent.replace(/https?:[^\s"'<>]+/g,'<url>').slice(0,6500)),
      meta:document.querySelector('meta[name=viewport]')?.content,text:document.body.innerText.slice(-600)};
    }""")
    print("PAGE",width,json.dumps(data,ensure_ascii=False),flush=True);print("ERRORS",json.dumps(errors[:10]),flush=True)
   except Exception as e:print("PAGE_ERROR",width,str(e)[:200],flush=True)
   await context.close()
  await browser.close()
asyncio.run(main())
