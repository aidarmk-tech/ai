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

# Inspect the user-supplied domains using public metadata only.
class Element:
    def __init__(self, tag="", attrs=(), parent=None):
        self.tag,self.attrs,self.parent,self.children,self.text=tag,dict(attrs),parent,[],""
    def dump(self,depth=0):
        allowed={"class","id","href","src","data-src","data-original","data-id","name","type","action","method","placeholder","itemprop","style"}
        return {"tag":self.tag,"attrs":{k:v for k,v in self.attrs.items() if k in allowed},
                "text":self.text.strip()[:180],"children":[c.dump(depth+1) for c in self.children[:10] if c.tag not in ("script","style","iframe","svg")] if depth<5 else []}
class Inspect(HTMLParser):
    def __init__(self,html):
        super().__init__(convert_charrefs=True);self.root=Element("root");self.node=self.root;self.nodes=[];self.feed(html)
    def handle_starttag(self,tag,attrs):
        node=Element(tag,attrs,self.node);self.node.children.append(node);self.nodes.append(node)
        if tag not in ("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"):self.node=node
    def handle_endtag(self,tag):
        node=self.node
        while node.parent:
            if node.tag==tag:self.node=node.parent;return
            node=node.parent
    def handle_data(self,data):self.node.text+=data

def inspect_domain(url):
    try:
        html,final=read(url);page=Inspect(html)
        pattern=r"^/(?:films|series)/(?:[^/]+/)*[0-9]+-[^/]+\.html$|^/[0-9]+-[^/]+\.html$"
        links=[];samples=[]
        for node in page.nodes:
            if node.tag!="a":continue
            target=urllib.parse.urljoin(final,node.attrs.get("href",""))
            parsed=urllib.parse.urlparse(target)
            if parsed.hostname!=urllib.parse.urlparse(final).hostname or not re.match(pattern,parsed.path) or target in links:continue
            links.append(target)
            if len(samples)<2:
                parent=node.parent
                if parent and parent.parent:parent=parent.parent
                if "kinogo" in final and parent and parent.parent:parent=parent.parent
                samples.append(parent.dump() if parent else node.dump())
        forms=[]
        for form in (n for n in page.nodes if n.tag=="form"):
            inputs=[]
            def walk(n):
                if n.tag=="input" and n.attrs.get("name") in ("story","do","subaction","search_start","q","query"):
                    inputs.append({k:v for k,v in n.attrs.items() if k in ("name","value","type")})
                for child in n.children:walk(child)
            walk(form)
            if inputs:forms.append({"attrs":form.attrs,"inputs":inputs})
        result={"url":url,"final":final,"cards":len(links),"samples":samples,"search_forms":forms,
                "pages":[n.dump() for n in page.nodes if n.tag=="a" and "/page/2" in n.attrs.get("href","")][:2]}
        if links:
            detail_html,detail_url=read(links[0]);detail=Inspect(detail_html)
            result["detail"]={"url":detail_url,"meta":{k:v for k,v in Metadata(detail_html).meta.items() if k in ("og:title","og:image","og:description")},
                "description":[n.dump() for n in detail.nodes if n.attrs.get("itemprop")=="description" or any(x in n.attrs.get("class","") for x in ("full-text","fulltext","fullstory","b-post__description_text"))][:2]}
        return result
    except Exception as error:return {"url":url,"error":str(error)}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for result in pool.map(inspect_domain,("https://lv.hdrezka.inc/","https://kz.kinogo.moi/film/","https://kz.kinogo.moi/serial/","https://kz.kinogo.moi/index.php?do=search&subaction=search&story=Matrix")):
        print("CATALOG_INSPECT "+json.dumps(result,ensure_ascii=False))
