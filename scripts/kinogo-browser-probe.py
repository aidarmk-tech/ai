import urllib.request,re,json
URLS=["https://zona.movie/templates/zona/js/libs.js","https://kodir2.github.io/actualize.js","https://zona.movie/series/mobland-1478/","https://zona.movie/film/anel-76932/"]
for url in URLS:
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0"}),timeout=30) as r:t=r.read(2500000).decode("utf-8","replace")
  if url.endswith(".js"):
   hits=[t[max(0,m.start()-350):m.start()+1600] for m in re.finditer(r"iframe|tabs-sel2|tabs-box2|player|Player",t)]
   print("SCRIPT",json.dumps({"url":url,"size":len(t),"hits":hits[:35]},ensure_ascii=False),flush=True)
  else:
   print("HTML",json.dumps({"url":url,"data":[l[:3000] for l in t.splitlines() if re.search(r"data-|kinopoisk|tmdb|kp_id|iframe|kodir|player|Player|tab-cont|tabs-b",l)][:100]},ensure_ascii=False),flush=True)
 except Exception as e:print("ERROR",str(e)[:200])
