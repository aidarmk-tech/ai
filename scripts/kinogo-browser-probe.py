"""Inspect public player setup and actual request shape; never download movie bytes."""
import asyncio,json,re
from urllib.parse import urlparse
from playwright.async_api import async_playwright
def clean(text):
    text=re.sub(r'https?://[^\s"\'<>\\)]+',lambda m:urlparse(m[0]).scheme+'://'+str(urlparse(m[0]).hostname)+'/…',text)
    return text[:18000]
async def check(browser,url):
    context=await browser.new_context(locale="ru-RU")
    page=await context.new_page()
    events=[];scripts=[]
    async def route(r):
        req=r.request
        if req.resource_type=="media" or re.search(r'\.(?:m3u8|ts|mp4|m4s)(?:\?|$)',req.url,re.I):
            events.append({"media_host":urlparse(req.url).hostname,"suffix":urlparse(req.url).path.rsplit("/",1)[-1][-70:],"header_names":list(req.headers),"referer":req.headers.get("referer"),"cookie_present":bool(req.headers.get("cookie"))})
            await r.abort()
        else:await r.continue_()
    await context.route("**/*",route)
    async def response(r):
        if r.request.resource_type=="script" and urlparse(r.url).hostname=="kinovod.pro":
            try:
                data=await r.text()
                hits=[]
                for match in list(re.finditer(r'm3u8|\.mp4/|master-|new Playerjs|\.api\(.file|video_host',data))[:18]:
                    hits.append(clean(data[max(0,match.start()-220):match.end()+300]))
                scripts.append({"path":urlparse(r.url).path,"len":len(data),"hits":hits})
            except:pass
    page.on("response",response)
    try:
        await page.goto(url,wait_until="domcontentloaded",timeout=40000)
        await page.wait_for_timeout(8000)
        inline=await page.locator("script:not([src])").all_text_contents()
        result={"source":url,"title":await page.title(),"final":page.url,
            "notice":(await page.locator("body").inner_text())[-1600:],
            "inline":[clean(x) for x in inline if re.search(r'Playerjs|videoplayer|player-file|\.m3u8',x)],
            "scripts":scripts,"events":events[:12]}
        print("INSPECT "+json.dumps(result,ensure_ascii=False),flush=True)
    except Exception as e:print("INSPECT "+str(e),flush=True)
    finally:await context.close()
async def main():
    async with async_playwright() as p:
        browser=await p.chromium.launch()
        try:
            await check(browser,"https://kinovod.pro/film/385358-film")
            await check(browser,"https://kinovod.pro/serial/240279-korol-talsy")
        finally:await browser.close()
asyncio.run(main())
