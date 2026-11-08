# Inspect the uploaded unit artifact after the build completes.
import io,json,os,time,urllib.request,urllib.error,zipfile,re,html
TOKEN=os.environ["GITHUB_TOKEN"]
BASE="https://api.github.com/repos/aidarmk-tech/ai"
RUN=42449978379
def api(path):
    req=urllib.request.Request(BASE+path,headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","User-Agent":"LampMedia-check"})
    with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
artifact=None
for _ in range(20):
    artifact=next((a for a in api(f"/actions/runs/{RUN}/artifacts")["artifacts"] if a["name"]=="LampMedia-ARM"),None)
    if artifact:break
    time.sleep(10)
if not artifact:
    print("No unit-test artifact yet");raise SystemExit(0)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None
req=urllib.request.Request(BASE+f'/actions/artifacts/{artifact["id"]}/zip',headers={"Authorization":"Bearer "+TOKEN,"User-Agent":"LampMedia-check"})
try:
    response=urllib.request.build_opener(NoRedirect()).open(req,timeout=30)
except urllib.error.HTTPError as e:
    if e.code not in (301,302,303,307,308):raise
    response=urllib.request.urlopen(e.headers["Location"],timeout=60)
with response:blob=response.read()
with zipfile.ZipFile(io.BytesIO(blob)) as z:
    for name in z.namelist():
        if name.endswith("com.lampplayer.tv.center.MediaCenterUiTest.html"):
            content=z.read(name).decode()
            errors=re.findall(r"<pre>(.*?)</pre>",content,re.S)
            for error in errors[:6]:print("FAILURE_DETAIL",html.unescape(re.sub("<[^>]+>","",error))[:6500])
        if name.endswith(("media-center-active-library-filters.png","media-center-poster-actions.png","media-center-category-home.png")):
            import base64
            png=z.read(name)
            if len(png)<400000:print("RENDER "+json.dumps({"name":name,"png":base64.b64encode(png).decode()}))
    print("Inspection complete")
