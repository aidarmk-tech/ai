"""Observe KinoGo's normal browser embed initialization; do not download video."""
import asyncio, json, re, urllib.request, urllib.error
from pathlib import Path
from urllib.parse import urlparse, urljoin
from playwright.async_api import async_playwright

def label(url):
    parsed=urlparse(url)
    return {"host":parsed.hostname,"path":parsed.path[:100] if "/embed/" in parsed.path else "<page>"}

def relevant(url):
    host=urlparse(url).hostname or ""
    return host in ("kz.kinogo.moi","api.nextembed.ws","kinovod.pro") or host.startswith("walking-as.")


def probe_media(target,headers):
    result={"host":urlparse(target).hostname}
    def get(url):
        request=urllib.request.Request(url,headers=headers)
        with urllib.request.urlopen(request,timeout=15) as response:
            data=response.read(4096)
            return response.status,response.headers.get("Content-Type",""),data,response.url
    try:
        try:
            status,mime,data,final=get(target)
        except urllib.error.HTTPError as error:
            result["original_status"]=error.code
            parsed=urlparse(target)
            if error.code!=404 or not re.search(r"\.mp4/[^/]+\.m3u8$",parsed.path,re.I):raise
            alternate=parsed._replace(path=parsed.path.rsplit("/",1)[0]).geturl()
            status,mime,data,final=get(alternate)
            result["direct_mp4_fallback"]=True
        result.update({"status":status,"mime":mime,"manifest":data.lstrip().startswith(b"#EXTM3U"),"mp4":data[4:8]==b"ftyp"})
        if data.lstrip().startswith(b"#EXTM3U"):
            for _ in range(2):
                lines=data.decode("utf-8","replace").splitlines()
                uri=next((x.strip() for x in lines if x.strip() and not x.startswith("#")),None)
                if not uri:break
                status,mime,data,final=get(urljoin(final,uri))
                if not data.lstrip().startswith(b"#EXTM3U"):
                    result.update({"segment_status":status,"segment_bytes":len(data)});break
    except Exception as error:
        result.update({"error":str(error)})
    return result

async def check(browser,url):
    context=await browser.new_context(locale="ru-RU",viewport={"width":1280,"height":720})
    await context.add_init_script(script=Path("lampa-player-native/app/src/main/assets/center/online-resolver.js").read_text().replace("if(window!==window.top||window.__lampOnline)return;","if(window.__lampOnline)return;"))
    page=await context.new_page()
    events=[]
    media_requests=[]
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
            media_requests.append((response.url,response.request.headers))
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
        try:
            await page.get_by_text("Плеер 2",exact=True).first.click(timeout=4000)
            await page.wait_for_timeout(6000)
        except Exception: pass
        snapshot=await page.evaluate("window.__lampSnapshot ? JSON.parse(window.__lampSnapshot()) : {}")
        attrs=await page.locator("iframe").evaluate_all("(els)=>els.map(e=>({src:e.src,data:e.getAttribute('data-src')}))")
        frames=[]
        for frame in page.frames:
            if frame==page.main_frame or not relevant(frame.url): continue
            try:
                body=(await frame.locator("body").inner_text(timeout=1500)).strip()
                state=await frame.evaluate("window.__lampSnapshot ? JSON.parse(window.__lampSnapshot()) : {}")
                frames.append({**label(frame.url),"text":body[:200],"snapshot":{"files":len(state.get("files",[])),"lists":len(state.get("lists",[])),"ids":state.get("ids"),"blocked":state.get("blocked")}})
            except Exception:frames.append(label(frame.url))
        media=[]
        def walk(value):
            if isinstance(value,dict):
                if isinstance(value.get("file"),str):media.append(value["file"])
                for child in value.values():
                    if isinstance(child,(dict,list)):walk(child)
            elif isinstance(value,list):
                for child in value:walk(child)
            elif isinstance(value,str) and re.search(r"https?://",value):media.append(value)
        walk(snapshot.get("lists",[]));walk(snapshot.get("files",[]))
        checks=[]
        if not snapshot.get("blocked"):
            for value in media[:1]:
                matches=re.findall(r"(?:\[([0-9]{3,4})p?\])?(https?://[^,\s\[\]]+)",value)
                for quality,target in matches[:2]:
                    cookies=await context.cookies([target])
                    headers={"User-Agent":await page.evaluate("navigator.userAgent"),"Referer":url,"Origin":url.split("/")[0]+"//"+urlparse(url).netloc,
                        "Cookie":"; ".join(c["name"]+"="+c["value"] for c in cookies),"Range":"bytes=0-4095"}
                    checks.append(await asyncio.to_thread(probe_media,target,headers))
        if not snapshot.get("blocked") and not checks and media_requests:
            target,headers=media_requests[0]
            headers=dict(headers);headers["Range"]="bytes=0-4095"
            cookies=await context.cookies([target]);headers["Cookie"]="; ".join(c["name"]+"="+c["value"] for c in cookies)
            checks.append(await asyncio.to_thread(probe_media,target,headers))
        result={"source":url,"snapshot":{"files":len(snapshot.get("files",[])),"lists":len(snapshot.get("lists",[])),
                "ids":snapshot.get("ids"),"blocked":snapshot.get("blocked"),"media_records":len(media)},"checks":checks,"events":events[:16],"frames":frames,
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
            for url in ("https://kz.kinogo.moi/6873-gangsterlend-2025.html","https://kinovod.pro/serial/240279-korol-talsy"):
                await check(browser,url)
        finally:await browser.close()
asyncio.run(main())
