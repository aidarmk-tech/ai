"""Read public catalog HTML only; inspect navigation and card structure without loading players."""
import asyncio,json,re
from urllib.parse import urlparse
from playwright.async_api import async_playwright
async def inspect(context,url):
    try:
        response=await context.request.get(url,timeout=30000,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"})
        html=await response.text()
        page=await context.new_page()
        await page.route("**/*",lambda r:r.abort())
        await page.set_content(html,wait_until="domcontentloaded")
        info=await page.evaluate("""()=>({
            title:document.title,
            links:Array.from(document.querySelectorAll('a[href]')).filter(a=>a.querySelector('img')||/film|serial|kino|movie|search|page/.test(a.getAttribute('href'))).slice(0,65).map(a=>({href:a.getAttribute('href'),text:a.textContent.trim().slice(0,80),cls:a.className,img:a.querySelector('img')?.outerHTML})),
            samples:Array.from(document.querySelectorAll('a')).filter(a=>a.querySelector('img')).slice(0,5).map(a=>(a.closest('article,.short,.item,.movie,.th-item,.poster')||a.parentElement.parentElement).outerHTML.slice(0,5000)),
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
            ["https://kinotac.org/serialy/","https://kinokong.es/serialo/"]]:
            await asyncio.gather(*(inspect(context,url) for url in urls))
        await context.close();await browser.close()
asyncio.run(main())
