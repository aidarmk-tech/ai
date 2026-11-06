"""Read the repository's own Android HTML reports; print failure stacks only."""
import os,urllib.request,urllib.error,zipfile,io,re,html
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return None
request=urllib.request.Request("https://api.github.com/repos/aidarmk-tech/ai/actions/artifacts/13527554049/zip",
    headers={"Authorization":"Bearer "+os.environ["GITHUB_TOKEN"],"Accept":"application/vnd.github+json"})
try:
    data=urllib.request.build_opener(NoRedirect).open(request).read()
except urllib.error.HTTPError as error:
    if error.code!=302:raise
    data=urllib.request.urlopen(error.headers["Location"],timeout=30).read()
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    for name in archive.namelist():
        if name.endswith(".html") and "CenterOnlineInstrumentedTest" in name:
            source=archive.read(name).decode("utf-8")
            for block in re.findall(r"<pre[^>]*>([\s\S]*?)</pre>",source):
                text=html.unescape(re.sub(r"<[^>]+>","",block))
                if "AssertionError" in text or "Exception" in text:print("FAILURE_DETAIL "+text[:10000],flush=True)
