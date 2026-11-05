"""Inspect public catalog HTML without fetching or logging playback data."""
import concurrent.futures, json, re, urllib.request, urllib.parse
from html.parser import HTMLParser

class Node:
    def __init__(self, tag="", attrs=(), parent=None):
        self.tag, self.attrs, self.parent, self.children, self.text = tag, dict(attrs), parent, [], ""
    def dump(self, depth=0):
        allowed = {"class","id","href","src","data-src","data-id","action","method","name","type","placeholder","property","content","itemprop","alt","data-original","data-lazy-src","style","data-background"}
        return {"tag": self.tag, "attrs": {k:v for k,v in self.attrs.items() if k in allowed},
                "text": self.text.strip()[:250], "children": [c.dump(depth+1) for c in self.children[:12] if c.tag not in ("script","style","iframe","svg")] if depth<5 else []}

class Page(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.root=Node("root"); self.node=self.root; self.nodes=[]
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        node=Node(tag,attrs,self.node);self.node.children.append(node);self.nodes.append(node)
        if tag not in ("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"):
            self.node=node
    def handle_endtag(self, tag):
        node=self.node
        while node.parent:
            if node.tag==tag:
                self.node=node.parent; return
            node=node.parent
    def handle_data(self, data):
        self.node.text += data

def fetch(url):
    request=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return Page(response.read(2*1024*1024).decode("utf-8","replace")), response.url

def probe(url):
    try:
        page, final=fetch(url)
        anchors=[n for n in page.nodes if n.tag=="a" and re.search(r"/(?:films|series)/.*[0-9].*\.html|/(?:film|serial)/[^/?]+",n.attrs.get("href",""))]
        cards=[]; links=[]
        for a in anchors:
            link=urllib.parse.urljoin(final,a.attrs["href"])
            if link in links: continue
            links.append(link)
            if len(cards)<2:
                parent=a if "/film/" in link or "/serial/" in link else a.parent
                if parent is a.parent and parent and parent.parent: parent=parent.parent
                cards.append(parent.dump() if parent else a.dump())
        result={"url":url,"final":final,"count":len(set(links)),
                "forms":[n.dump() for n in page.nodes if n.tag=="form"][:2],
                "headings":[n.dump() for n in page.nodes if n.tag=="h1"][:2],
                "images":[n.attrs for n in page.nodes if n.tag=="img"][:2],
                "cards":cards,
                "pagination":[n.dump() for n in page.nodes if n.tag=="a" and ("page=" in n.attrs.get("href","") or "/page/" in n.attrs.get("href",""))][:4]}
        if links:
            detail, target=fetch(links[0])
            result["detail"]={"url":target,"headings":[n.dump() for n in detail.nodes if n.tag in ("h1","h2")][:3],
                "metadata":[n.dump() for n in detail.nodes if n.tag=="meta" and n.attrs.get("property","").startswith("og:")],
                "info":[n.dump() for n in detail.nodes if any(key in n.attrs.get("class","") for key in ("b-post__info","b-post__description","original-title","film-info","film-description"))][:2]}
        if url=="https://kazplay.kz/films":
            scripts=[n.attrs.get("src") for n in page.nodes if n.tag=="script" and n.attrs.get("src","").startswith("/_next/")]
            snippets=[]
            for source in scripts[:18]:
                try:
                    request=urllib.request.Request(urllib.parse.urljoin(final,source),headers={"User-Agent":"Mozilla/5.0"})
                    with urllib.request.urlopen(request,timeout=8) as response:
                        code=response.read(1800000).decode("utf-8","replace")
                    for match in re.finditer(r"/api/search|/search\?|/search/|router\.push|/api/films",code):
                        snippets.append(code[max(0,match.start()-100):match.end()+350])
                except Exception: pass
            result["search_code"]=snippets[:10]
        return json.dumps(result,ensure_ascii=False)
    except Exception as error:
        return json.dumps({"url":url,"error":str(error)},ensure_ascii=False)

urls=["https://rezka.ac/films/","https://kazplay.kz/films","https://kazplay.kz/serials",
      "https://rezka.ac/index.php?do=search&subaction=search&story=Matrix",
      "https://rezka.ac/search/?do=search&subaction=search&q=Matrix",
      "https://kazplay.kz/search?q=Titanic","https://kazplay.kz/search?query=Titanic"]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    for result in pool.map(probe,urls): print("CATALOG_PROBE "+result)

try:
    request=urllib.request.Request("https://kazplay.kz/api/v2/film/search?query="+urllib.parse.quote("Титаник"),headers={"User-Agent":"Mozilla/5.0"})
    with urllib.request.urlopen(request,timeout=15) as response: data=json.load(response)
    items=data.get("data",[]) if isinstance(data,dict) else data
    print("SEARCH_API "+json.dumps({"keys":list(data) if isinstance(data,dict) else [],"items":[{k:v for k,v in x.items() if k not in ("video","url","sources","stream","playlist")} for x in items[:2]]},ensure_ascii=False))
except Exception as error:
    print("SEARCH_API "+str(error))
