"""Read-only smoke checks for the public metadata used by LampMedia catalogs."""
import concurrent.futures
import json
import re
import urllib.parse
import urllib.request
from html.parser import HTMLParser

class Metadata(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.links = []
        self.meta = {}
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "a" and values.get("href"):
            self.links.append(values["href"])
        if tag == "meta" and values.get("property", "").startswith("og:"):
            self.meta[values["property"]] = values.get("content", "")

def read(url):
    request = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"})
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read(2 * 1024 * 1024 + 1)
        assert len(body) <= 2 * 1024 * 1024, "Metadata response exceeds app limit"
        return body.decode("utf-8", "replace"), response.url

def cards(url, provider):
    html, final = read(url)
    page = Metadata(html)
    pattern = r"^/(?:films|series)/(?:[^/]+/)*[0-9]+-[^/]+\.html$" if provider == "rezka" else r"^/(?:film|serial)/[^/]+$"
    links = []
    for href in page.links:
        link = urllib.parse.urljoin(final, href)
        parsed = urllib.parse.urlparse(link)
        if parsed.hostname == urllib.parse.urlparse(final).hostname and re.match(pattern, parsed.path) and link not in links:
            links.append(link)
    return links, page

def check(provider):
    if provider == "rezka":
        base = "https://rezka.ac"
        first_url, second_url = base + "/films/", base + "/films/page/2/"
    else:
        base = "https://kazplay.kz"
        first_url, second_url = base + "/films?page=1", base + "/films?page=2"
    first, _ = cards(first_url, provider)
    second, _ = cards(second_url, provider)
    assert first and second and set(second) - set(first), provider + ": pagination did not advance"
    html, _ = read(first[0])
    meta = Metadata(html).meta
    assert meta.get("og:title") and meta.get("og:description") and meta.get("og:image"), provider + ": detail metadata missing"
    if provider == "rezka":
        query = urllib.parse.urlencode({"do":"search", "subaction":"search", "story":"Matrix"})
        search, _ = cards(base + "/index.php?" + query, provider)
        assert search, "Rezka: no results for known title"
        search_count = len(search)
        series, _ = cards(base + "/series/", provider)
    else:
        text, _ = read(base + "/api/v2/film/search?" + urllib.parse.urlencode({"query":"Титаник"}))
        data = json.loads(text)
        assert data.get("success") is True and isinstance(data.get("data"), list), "KazPlay: unexpected search response"
        results = data["data"]
        assert any("Титаник" in item.get("rus_title", "") for item in results), "KazPlay: search did not match query"
        assert all(item.get("slug") and item.get("title") for item in results), "KazPlay: search identity missing"
        search_count = len(results)
        series, _ = cards(base + "/serials", provider)
    assert series, provider + ": no series"
    return {"provider": provider, "movies": len(first), "next_page": len(second),
            "new_items": len(set(second)-set(first)), "series": len(series),
            "search_results": search_count, "detail_metadata": True}

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for result in pool.map(check, ("rezka", "kazplay")):
        print("CATALOG_SMOKE " + json.dumps(result, ensure_ascii=False))


class KinoGoCards(HTMLParser):
    def __init__(self,html,base):
        super().__init__(convert_charrefs=True)
        self.base,self.depth,self.active,self.current,self.links=base,0,None,None,[]
        self.feed(html)
    def handle_starttag(self,tag,attrs):
        values=dict(attrs)
        if tag=="div":
            self.depth+=1
            if "movie" in values.get("class","").split() and values.get("id","").isdigit():
                self.active,self.current=self.depth,values["id"]
        if tag=="a" and self.active is not None:
            url=urllib.parse.urljoin(self.base,values.get("href",""))
            parsed=urllib.parse.urlparse(url)
            if parsed.hostname==urllib.parse.urlparse(self.base).hostname and re.match(r"^/"+self.current+r"-[^/]+\.html$",parsed.path) and url not in self.links:
                self.links.append(url)
    def handle_endtag(self,tag):
        if tag=="div":
            if self.active==self.depth:self.active,self.current=None,None
            self.depth-=1

def kinogo_cards(url):
    html,final=read(url)
    return KinoGoCards(html,final).links

base="https://kz.kinogo.moi"
movies=kinogo_cards(base+"/film/")
second=kinogo_cards(base+"/film/page/2/")
series=kinogo_cards(base+"/serial/")
ru_movies=kinogo_cards(base+"/film/russkie-filmy/")
ru_series=kinogo_cards(base+"/serial/russkye_serialy/")
kz=kinogo_cards(base+"/xfsearch/country/"+urllib.parse.quote("казахстан")+"/")
search=kinogo_cards(base+"/index.php?"+urllib.parse.urlencode({"do":"search","subaction":"search","story":"Matrix"}))
assert movies and series and ru_movies and ru_series and kz and search,"KinoGo metadata section/search missing"
assert set(second)-set(movies),"KinoGo pagination did not advance"
detail,_=read(movies[0])
meta=Metadata(detail).meta
assert meta.get("og:description") and meta.get("og:image"),"KinoGo detail metadata missing"
print("CATALOG_SMOKE "+json.dumps({"provider":"kinogo","movies":len(movies),"series":len(series),
    "new_items":len(set(second)-set(movies)),"ru_movies":len(ru_movies),"ru_series":len(ru_series),
    "kazakhstan":len(kz),"search_results":len(search),"details":True}))
try:
    html,final=read("https://lv.hdrezka.inc/")
    links=Metadata(html).links
    verified=any(re.search(r"/(?:films|series)/.*[0-9]+-.*\.html",link) for link in links)
    print("HDREZKA_PROBE "+json.dumps({"verified":verified,"final":final}))
except Exception as error:
    # This source is explicitly labelled a test entry in the app. An upstream
    # outage is reported, never treated as successful catalog verification.
    print("HDREZKA_PROBE "+json.dumps({"verified":False,"error":str(error)}))

# Read the selected site's public player declarations; never download full video.
from html.parser import HTMLParser
class Players(HTMLParser):
    def __init__(self,html):
        super().__init__(convert_charrefs=True);self.frames=[];self.scripts=[];self.current=None;self.feed(html)
    def handle_starttag(self,tag,attrs):
        values=dict(attrs)
        if tag=="iframe":self.frames.append(values)
        if tag=="script":
            self.current={"src":values.get("src",""),"code":""};self.scripts.append(self.current)
    def handle_endtag(self,tag):
        if tag=="script":self.current=None
    def handle_data(self,data):
        if self.current is not None:self.current["code"]+=data

def safe(value):
    return re.sub(r'https?://[^"\'\s<>]+',lambda m:"<URL:"+urllib.parse.urlparse(m[0]).hostname+">",value)[:1800]
def inspect_player(url):
    try:
        html,final=read(url);page=Players(html)
        return {"page":final,"frames":[{k:safe(v) for k,v in f.items() if k in ("id","class","src","data-src")} for f in page.frames],
                "scripts":[{"src":s["src"],"code":safe(s["code"])} for s in page.scripts if any(x in (s["src"]+" "+s["code"]).lower() for x in ("player","iframe","file:","file :","video","playlist"))][:12]}
    except Exception as error:return {"page":url,"error":str(error)}
movie=kinogo_cards("https://kz.kinogo.moi/film/")[:2]
serial=kinogo_cards("https://kz.kinogo.moi/serial/")[:2]
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for result in pool.map(inspect_player,movie+serial):print("PLAYER_INSPECT "+json.dumps(result,ensure_ascii=False))
