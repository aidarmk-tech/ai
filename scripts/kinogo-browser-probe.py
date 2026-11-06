"""Inspect the selected KinoGo card's real embed tree without downloading video or changing region."""
import asyncio,json,re
from urllib.parse import urlparse
from pathlib import Path
from playwright.async_api import async_playwright
def safe(url):
    p=urlparse(url)
    return {"host":p.hostname,"path":p.path if re.match(r'^/embed/(movie|trailer)/\d+/?$',p.path) else "<resource>"}
def shape(value,depth=0):
    if depth>3:return type(value).__name__
    if isinstance(value,dict):return {k:shape(v,depth+1) for k,v in list(value.items())[:20] if not re.search(r'token|cookie|auth|secret',k,re.I)}
    if isinstance(value,list):return {"count":len(value),"first":shape(value[0],depth+1) if value else None}
    return type(value).__name__
async def main():
    async with async_playwright() as p:
        browser=await p.chromium.launch()
        context=await browser.new_context(locale="ru-RU",viewport={"width":1280,"height":720})
        await context.add_init_script("window.__lampMetadataFrame=window!==top;"+Path("lampa-player-native/app/src/main/assets/center/online-resolver.js").read_text())
        page=await context.new_page();events=[];js=[]
        async def route(r):
            if r.request.resource_type=="media" or re.search(r'\.(?:m3u8|mp4|ts|m4s)(?:\?|$)',r.request.url,re.I):await r.abort()
            else:await r.continue_()
        await context.route("**/*",route)
        async def response(r):
            host=urlparse(r.url).hostname or ""
            if host=="kz.kinogo.moi":return
            if r.request.resource_type in ("document","xhr","fetch"):
                try:
                    text=await r.text()
                    data=json.loads(text) if len(text)<1000000 else None
                except:data=None
                events.append({**safe(r.url),"status":r.status,"type":r.request.resource_type,"shape":shape(data) if data else None})
            if r.request.resource_type=="script" and len(js)<25:
                try:
                    text=await r.text()
                    keys=re.findall(r'.{0,100}(?:Playerjs|jwplayer|videojs|playlist|seasons|episodes|postMessage|\\.m3u8).{0,160}',text)[:12]
                    keys=[re.sub(r'https?://[^\s"\'<>]+',"<url>",x) for x in keys]
                    js.append({**safe(r.url),"length":len(text),"keys":keys})
                except:pass
        page.on("response",response)
        await page.goto("https://kz.kinogo.moi/62389-gangsterlend-2-sezon.html",wait_until="domcontentloaded",timeout=40000)
        await page.wait_for_timeout(1000)
        try:await page.get_by_text("Смотреть онлайн",exact=True).first.click(timeout=2000)
        except:pass
        await page.wait_for_timeout(7000)
        try:await page.get_by_text("Плеер 2",exact=True).first.click(timeout=2000)
        except:pass
        await page.wait_for_timeout(7000)
        frames=[]
        for f in page.frames:
            try:
                s=await f.evaluate("window.__lampSnapshot?JSON.parse(window.__lampSnapshot()):{}")
                frames.append({**safe(f.url),"parent":safe(f.parent_frame.url) if f.parent_frame else None,
                    "title":await f.title(),"body":(await f.locator("body").inner_text(timeout=1000))[:180],
                    "iframes":await f.locator("iframe").evaluate_all("(els)=>els.map(e=>({src:e.getAttribute('src'),lazy:e.getAttribute('data-src')}))"),
                    "snapshot":{"files":len(s.get("files",[])),"lists":len(s.get("lists",[])),"ids":s.get("ids"),"player":s.get("playerType"),"shapes":s.get("shapes"),"blocked":s.get("blocked")}})
            except Exception as e:frames.append({**safe(f.url),"error":type(e).__name__})
        for f in frames:
            for e in f.get("iframes",[]):
                e["src"]=safe(e["src"]) if e.get("src") else None;e["lazy"]=safe(e["lazy"]) if e.get("lazy") else None
        root_info=await page.evaluate("""()=>({
            tabs:Array.from(document.querySelectorAll('a,button,li,span,div')).filter(e=>e.childElementCount===0&&/^(Смотреть онлайн|Плеер 2)$/.test(e.textContent.trim())).map(e=>({tag:e.tagName,cls:e.className,id:e.id,parent:e.parentElement.outerHTML.slice(0,2000)})).slice(0,8),
            frames:Array.from(document.querySelectorAll('iframe')).map(e=>({id:e.id,cls:e.className,srcdoc:!!e.srcdoc,parent:e.parentElement.outerHTML.slice(0,1500)})).slice(0,4),
            scripts:Array.from(document.scripts).map(e=>e.src).filter(Boolean),
            inline:Array.from(document.scripts).filter(e=>!e.src&&/iframe|femd|nextembed|walking-as/.test(e.textContent)).map(e=>e.textContent.slice(0,7000))
        })""")
        root_info=json.loads(re.sub(r'https?://[^\\s"\\\'<>]+',"<url>",json.dumps(root_info,ensure_ascii=False)))
        print("ROOT_INFO "+json.dumps(root_info,ensure_ascii=False),flush=True)
        print("KINOGO_INSPECT "+json.dumps({"final":page.url,"frames":frames,"events":events[:30],"scripts":js},ensure_ascii=False),flush=True)
        await context.close();await browser.close()
asyncio.run(main())
