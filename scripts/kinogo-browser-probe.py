import os,urllib.request,urllib.error,io,zipfile,re,html
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
req=urllib.request.Request("https://api.github.com/repos/aidarmk-tech/ai/actions/artifacts/13581947188/zip",headers={"Authorization":"Bearer "+os.environ["GITHUB_TOKEN"],"Accept":"application/vnd.github+json"})
try:
 response=urllib.request.build_opener(NoRedirect()).open(req,timeout=30);data=response.read()
except urllib.error.HTTPError as e:
 if e.code not in (301,302,303,307,308):raise
 with urllib.request.urlopen(e.headers["Location"],timeout=60) as response:data=response.read()
with zipfile.ZipFile(io.BytesIO(data)) as z:
 for name in z.namelist():
  if name.endswith("com.lampplayer.tv.center.MediaCenterUiTest.html"):
   text=z.read(name).decode("utf-8","replace")
   for block in re.findall(r"<pre>(.*?)</pre>",text,re.S):print("FAILURE",html.unescape(re.sub("<[^>]+>","",block))[:4000])
