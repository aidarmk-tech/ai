"""Observe KinoGo's normal browser embed initialization; do not download video."""
import asyncio, json, re
from urllib.parse import urlparse
from playwright.async_api import async_playwright

def label(url):
    parsed=urlparse(url)
    return {"host":parsed.hostname,"path":parsed.path[:100] if "/embed/" in parsed.path else "<page>"}

def relevant(url):
    host=urlparse(url).hostname or ""
    return host in ("kz.kinogo.moi","api.nextembed.ws") or host.endswith(".stravers.live")

async def check(browser,url):
    context=await browser.new_context(locale="ru-RU",viewport={"width":1280,"height":720})
    page=await context.new_page()
    events=[]
    async def route(request_route):
        request=request_route.request
        if request.resource_type=="media" or re.search(r"\.(ts|m4s|mp4)(?:\?|$)",request.url,re.I):
            await request_route.abort()
        else:
            await request_route.continue_()
    await context.route("**/*",route)
    def response(response):
        if relevant(response.url) and response.request.resource_type=="document":
            events.append({**label(response.url),"status":response.status})
        if ".m3u8" in response.url.lower():
            events.append({"manifest_host":urlparse(response.url).hostname,"status":response.status})
    page.on("response",response)
    context.on("page",lambda popup:asyncio.create_task(popup.close()) if popup!=page else None)
    try:
        await page.goto(url,wait_until="domcontentloaded",timeout=40000)
        await page.wait_for_timeout(2000)
        try:
            await page.get_by_text("Смотреть онлайн",exact=True).first.click(timeout=4000)
        except Exception: pass
        try:
            await page.locator("iframe[data-src]").first.scroll_into_view_if_needed(timeout=4000)
        except Exception: pass
        await page.wait_for_timeout(6000)
        attrs=await page.locator("iframe").evaluate_all("(els)=>els.map(e=>({src:e.src,data:e.getAttribute('data-src')}))")
        frames=[]
        for frame in page.frames:
            if frame==page.main_frame or not relevant(frame.url): continue
            try:
                body=(await frame.locator("body").inner_text(timeout=1500)).strip()
                frames.append({**label(frame.url),"text":body[:200]})
            except Exception:frames.append(label(frame.url))
        result={"source":url,"events":events[:16],"frames":frames,
                "iframe_hosts":[{"active":label(a["src"]) if a.get("src") else None,
                    "declared":label(a["data"] if a.get("data","").startswith("http") else "https:"+a["data"]) if a.get("data") else None} for a in attrs]}
        print("BROWSER_PROBE "+json.dumps(result,ensure_ascii=False),flush=True)
    except Exception as error:
        print("BROWSER_PROBE "+json.dumps({"source":url,"error":str(error),"events":events[:16]},ensure_ascii=False),flush=True)
    finally:await context.close()

async def main():
    async with async_playwright() as p:
        browser=await p.chromium.launch()
        try:
            for url in ("https://kz.kinogo.moi/62752-sintetika.html","https://kz.kinogo.moi/62687-podnogotnaya-2-sezon.html"):
                await check(browser,url)
        finally:await browser.close()
asyncio.run(main())
