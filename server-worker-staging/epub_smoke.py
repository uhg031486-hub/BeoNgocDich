import io,json,time,zipfile,urllib.request
ROOT=r"C:\\SmartTTS-VPS-20261012-v7139"
key=open(ROOT+r"\\data\\server-key.txt",encoding="utf-8-sig").read().strip()
buf=io.BytesIO()
with zipfile.ZipFile(buf,"w",zipfile.ZIP_DEFLATED) as z:
 z.writestr("mimetype","application/epub+zip")
 z.writestr("META-INF/container.xml",'<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
 z.writestr("OEBPS/content.opf",'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>')
 z.writestr("OEBPS/c1.xhtml",'<html xmlns="http://www.w3.org/1999/xhtml"><body><p>The hunter enters the forest.</p></body></html>')
base="http://127.0.0.1:17861"
def request(path,data=None,headers={}):
 h={"Authorization":"Bearer "+key,**headers};req=urllib.request.Request(base+path,data=data,headers=h,method="POST" if data is not None else "GET")
 with urllib.request.urlopen(req,timeout=250) as x:return x.status,x.read()
status,raw=request("/v1/epub/upload?name=smoke.epub",buf.getvalue(),{"Content-Type":"application/epub+zip","X-Translation-Model":"ag/gemini-3.8-flash"})
row=json.loads(raw);print("UPLOAD",status,row["id"],row["total"])
for _ in range(36):
 time.sleep(5);_,raw=request("/v1/epub/jobs/"+row["id"]);job=json.loads(raw)
 if job["status"] in ("complete","failed"):break
print("STATUS",job["status"],job["completed"],job["total"],job["message"])
status,data=request("/v1/epub/jobs/"+row["id"]+"/download");print("DOWNLOAD",status,len(data),data[:2]==b"PK")
