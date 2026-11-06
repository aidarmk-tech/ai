"""Read public catalog HTML only; inspect navigation and card structure without loading players."""
import asyncio,json,re
from urllib.parse import urlparse
from playwright.async_api import async_playwright
async def inspect(context,url):
    try:
        response=await context.request.get(url,timeout=30000,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"})
        raw=await response.body()
        charset=re.search(br'charset=["\\\']?([a-zA-Z0-9_-]+)',raw[:5000],re.I)
        encoding=charset.group(1).decode("ascii") if charset else "utf-8"
        html=raw.decode(encoding,errors="replace")
        page=await context.new_page()
        await page.route("**/*",lambda r:r.abort())
        await page.set_content(html,wait_until="domcontentloaded")
        info=await page.evaluate("""()=>({
            title:document.title,
            links:Array.from(document.querySelectorAll('a[href]')).filter(a=>a.querySelector('img')||/film|serial|kino|movie|search|page/.test(a.getAttribute('href'))).slice(0,65).map(a=>({href:a.getAttribute('href'),text:a.textContent.trim().slice(0,80),cls:a.className,img:a.querySelector('img')?.outerHTML})),
            samples:Array.from(document.querySelectorAll('a.keep-item_title,a.th-in,a')).filter(a=>a.matches('.keep-item_title,.th-in')||a.querySelector('img')).filter(a=>/\\d/.test(a.getAttribute('href')||'')).slice(0,4).map(a=>(a.closest('article,.short,.item,.movie,.th-item,.poster,.keep-item')||a.parentElement.parentElement).outerHTML.slice(0,6000)),
            navigation:Array.from(document.querySelectorAll('a[href]')).filter(a=>/^(?:https?:\\/\\/[^/]+)?\\/(?:film|kino|serial|series|filmy|serialy|serialo|xfsearch|page)[^?#]*\\/$/.test(a.getAttribute('href'))).map(a=>({href:a.getAttribute('href'),text:a.textContent.trim().slice(0,40)})).slice(-50),
            forms:Array.from(document.forms).map(f=>({action:f.getAttribute('action'),method:f.method,fields:Array.from(f.elements).map(e=>({name:e.name,type:e.type,value:e.type==='hidden'?e.value:''}))})),
            json:Array.from(document.querySelectorAll('script[type="application/ld+json"],script#__NEXT_DATA__')).map(e=>e.textContent.slice(0,600))
        })""")
        await page.close()
        print("CATALOG_HTML "+json.dumps({"source":url,"status":response.status,"final":response.url,"bytes":len(html),**info},ensure_ascii=False),flush=True)
    except Exception as error:print("CATALOG_HTML "+json.dumps({"source":url,"error":str(error)},ensure_ascii=False),flush=True)
async def main():
    async with async_playwright() as p:
        browser=await p.chromium.launch()
        context=await browser.new_context(java_script_enabled=False)
        for urls in [
            ["https://kinotac.org/","https://zona.movie/","https://kinokong.es/"],
            ["https://kinotac.org/serialy/","https://kinokong.es/serialo/","https://zona.movie/series/"],
            ["https://kinokong.es/index.php?do=search&subaction=search&story=%D0%9C%D0%B0%D1%82%D1%80%D0%B8%D1%86%D0%B0","https://kinokong.es/index.php?do=search&subaction=search&story=%CC%E0%F2%F0%E8%F6%E0"]]:
            await asyncio.gather(*(inspect(context,url) for url in urls))
        await context.close();await browser.close()
asyncio.run(main())
