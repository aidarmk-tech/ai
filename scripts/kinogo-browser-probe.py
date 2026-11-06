"""Inspect selected public title metadata; no player or media requests."""
import urllib.request,re,json,concurrent.futures
from html.parser import HTMLParser
class Inspector(HTMLParser):
    def __init__(self):
        super().__init__();self.stack=[];self.meta=[];self.images=[];self.paragraphs=[];self.json=[];self.active_json=None;self.facts=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="meta":self.meta.append(a)
        if tag=="img":self.images.append({"attrs":a,"parents":self.stack[-3:]})
        if tag=="script" and a.get("type")=="application/ld+json":self.active_json=""
        if tag not in ("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"):self.stack.append({"tag":tag,"id":a.get("id"),"class":a.get("class"),"itemprop":a.get("itemprop")})
    def handle_endtag(self,tag):
        if tag=="script" and self.active_json is not None:
            try:self.json.append(json.loads(self.active_json))
            except:pass
            self.active_json=None
        for i in range(len(self.stack)-1,-1,-1):
            if self.stack[i]["tag"]==tag:self.stack=self.stack[:i];break
    def handle_data(self,text):
        if self.active_json is not None:self.active_json+=text;return
        text=text.strip()
        if re.match(r"^(?:Год(?: выпуска| выхода)?\s*:?|2020|2022)$",text):self.facts.append({"text":text,"parents":self.stack[-4:]})
        if len(text)>90 and not any(n["tag"] in ("script","style") for n in self.stack):
            self.paragraphs.append({"chars":len(text),"parents":self.stack[-4:]})
def scrub(value):
    if isinstance(value,dict):return {k:len(v) if k=="description" and isinstance(v,str) else scrub(v) for k,v in value.items() if k not in ("aggregateRating","review")}
    if isinstance(value,list):return [scrub(v) for v in value[:12]]
    return value
def inspect(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"}),timeout=25) as response:
            raw=response.read(2097153);final=response.url
        charset=re.search(br'charset=["\']?([a-zA-Z0-9_-]+)',raw[:5000],re.I)
        text=raw.decode(charset.group(1).decode() if charset else "utf-8",errors="replace")
        parser=Inspector();parser.feed(text)
        print("TITLE_META "+json.dumps({"url":final,"meta":[m for m in parser.meta if m.get("property","").startswith("og:") or m.get("itemprop") in ("datePublished","dateCreated","name")],
            "facts":parser.facts,"json":scrub(parser.json),"paragraphs":parser.paragraphs[:15],"images":[{"attrs":{k:("<embedded>" if v.startswith("data:") else v) for k,v in i["attrs"].items()},"parents":i["parents"]} for i in parser.images if any("full" in str(p) or "images" in str(p) for p in i["parents"])][:14]},ensure_ascii=False),flush=True)
    except Exception as e:print("TITLE_META "+json.dumps({"url":url,"error":str(e)},ensure_ascii=False),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(inspect,["https://kinotac.org/20817-korol-talsy.html","https://zona.movie/series/mobland-1478/","https://kinokong.es/61709-serial-dok-vse-v-tvoih-rukah-1-4-sezon.html"]))
