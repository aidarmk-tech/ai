import os,urllib.request,urllib.error,io,zipfile,base64,json
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
req=urllib.request.Request("https://api.github.com/repos/aidarmk-tech/ai/actions/artifacts/13575534104/zip",headers={"Authorization":"Bearer "+os.environ["GITHUB_TOKEN"],"Accept":"application/vnd.github+json"})
try:
 response=urllib.request.build_opener(NoRedirect()).open(req,timeout=30);data=response.read()
except urllib.error.HTTPError as e:
 if e.code not in (301,302,303,307,308):raise
 with urllib.request.urlopen(e.headers["Location"],timeout=60) as response:data=response.read()
with zipfile.ZipFile(io.BytesIO(data)) as z:
 for wanted in ["media-center-episode-grid.png","media-center-focused-title.png","media-center-episodes.png"]:
  name=next((p for p in z.namelist() if p.endswith("/"+wanted) or p==wanted),None)
  if name:
   content=z.read(name)
   if len(content)<400000:print("RENDER",json.dumps({"name":wanted,"png":base64.b64encode(content).decode()}),flush=True)
  else:print("MISSING",wanted,flush=True)
