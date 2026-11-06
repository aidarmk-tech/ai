"""Inspect public KinoKong video embeds; do not fetch media bodies."""
import asyncio,json,re
from urllib.parse import urlsplit
from playwright.async_api import async_playwright
def safe(url):
 p=urlsplit(url);return p.scheme+"://"+p.netloc+p.path
async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True)
  context=await browser.new_context(viewport={"width":1280,"height":720})
  await context.add_init_script("HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'))};")
  page=await context.new_page()
  media=[]
  async def route(r):
   u=r.request.url
   if re.search(r"\.(?:m3u8|mp4|ts|m4s)(?:[?]|$)",u,re.I):
    if len(media)<15:media.append(safe(u))
    await r.abort()
   else:await r.continue_()
  await context.route("**/*",route)
  page.on("popup",lambda popup:asyncio.create_task(popup.close()))
  url="https://kinokong.es/61709-serial-dok-vse-v-tvoih-rukah-1-4-sezon.html"
  await page.goto(url,wait_until="domcontentloaded",timeout=45000)
  await page.wait_for_timeout(6000)
  print("ROOT",json.dumps(await page.evaluate("""() => ({frames:Array.from(document.querySelectorAll('iframe')).map(e=>({src:e.getAttribute('src'),data:e.getAttribute('data-src'),parents:e.parentElement.outerHTML.slice(0,4000)})), tabs:Array.from(document.querySelectorAll('[class*=tab],[class*=player]')).filter(e=>e.children.length<12).map(e=>e.outerHTML.slice(0,1800)).slice(-35),scripts:Array.from(document.scripts).filter(e=>!e.src&&/iframe|alloha|collaps|hdvb/i.test(e.textContent)).map(e=>e.textContent.slice(0,8000))})"""),ensure_ascii=False))
  for label in ["Collaps","alloha","HDVB"]:
   try:
    candidates=page.get_by_text(label,exact=True)
    if await candidates.count():await candidates.first.click(timeout=3000)
    await page.wait_for_timeout(6000)
    print("TAB",label)
    for f in page.frames:
     if f==page.main_frame:continue
     try:
      data=await f.evaluate("""() => ({url:location.origin+location.pathname,title:document.title,text:document.body.innerText.slice(0,700),player:typeof Playerjs,ids:Array.from(document.querySelectorAll('[id]')).map(e=>e.id).filter(e=>/player|video/i.test(e)).slice(0,15),scripts:Array.from(document.scripts).map(e=>e.src?e.src:e.textContent.slice(0,12000)).slice(-16)})""")
      print("FRAME",json.dumps(data,ensure_ascii=False))
     except Exception as e:print("FRAME_ERROR",str(e)[:160])
   except Exception as e:print("TAB_ERROR",label,str(e)[:160])
  print("MEDIA_PATHS",json.dumps(media))
  await browser.close()
asyncio.run(main())
