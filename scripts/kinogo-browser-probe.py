import re,json,datetime,urllib.request,urllib.parse,concurrent.futures
key=re.search(r'const val API_KEY = "([^"]+)"',open("lampa-player-native/app/src/main/kotlin/com/lampplayer/tv/data/tmdb/TmdbRepository.kt",encoding="utf-8").read()).group(1)
today=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5))).date()
def get(path,params={}):
 q=urllib.parse.urlencode({"api_key":key,"language":"ru-RU",**params})
 with urllib.request.urlopen("https://api.themoviedb.org/3/"+path+"?"+q,timeout=30) as r:return json.load(r)
def inspect(kind,mode):
 field="first_air_date" if kind=="tv" else "primary_release_date"
 p={"sort_by":field+".desc","include_adult":"false",field+".lte":str(today)}
 if mode=="recent_popular":p.update({"sort_by":"popularity.desc",field+".gte":str(today-datetime.timedelta(days=180))})
 data=get("discover/"+kind,p)
 rows=[{"id":x["id"],"title":x.get("title",x.get("name")),"original":x.get("original_title",x.get("original_name")),"lang":x.get("original_language"),"votes":x.get("vote_count"),"date":x.get("release_date",x.get("first_air_date"))} for x in data.get("results",[])]
 print("FEED",json.dumps({"kind":kind,"mode":mode,"pages":data.get("total_pages"),"rows":rows},ensure_ascii=False),flush=True)
 for row in rows[:5]:
  if not re.search("[А-Яа-яЁё]",row["title"] or ""):
   t=get(kind+"/"+str(row["id"])+"/translations")
   ru=[x.get("data") for x in t.get("translations",[]) if x.get("iso_639_1")=="ru"]
   print("TRANSLATION",json.dumps({"kind":kind,"id":row["id"],"title":row["title"],"ru":ru},ensure_ascii=False),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 list(pool.map(lambda x:inspect(*x),[(k,m) for k in ["movie","tv"] for m in ["date","recent_popular"]]))
