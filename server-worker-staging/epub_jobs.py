"""Durable EPUB translation jobs with readable partial EPUBs."""
import json, threading, time, uuid, zipfile
from pathlib import Path


class EpubJobs:
    def __init__(self, data, translate):
        self.root = Path(data) / "epub-jobs"; self.root.mkdir(parents=True, exist_ok=True)
        self.translate = translate; self.lock = threading.Lock(); self.jobs = {}
        for path in self.root.glob("*.json"):
            row = json.loads(path.read_text("utf-8"))
            if row.get("status") == "running": row.update(status="queued", message="Restarted; resume this EPUB")
            self.jobs[row["id"]] = row; self._save(row)

    def _save(self, row):
        (self.root / (row["id"] + ".json")).write_text(json.dumps(row, ensure_ascii=False), "utf-8")

    def _txt_to_epub(self, name, data):
        import html, io, re
        text = data.decode("utf-8", "replace").lstrip("\ufeff")
        lines = text.splitlines()
        pattern = re.compile(r'^\s*(Chương|Hồi|Tiết|Chapter|第[0-9一二三四五六七八九十百千万]+[章回节]|第\s*\d+\s*章).{0,50}$', re.IGNORECASE)
        chapters = []
        cur_title = "Mở đầu"
        cur_paras = []
        cur_len = 0
        for line in lines:
            line_str = line.strip()
            if not line_str: continue
            if pattern.match(line_str) or (cur_len >= 3500 and len(cur_paras) >= 5):
                if cur_paras:
                    chapters.append((cur_title, cur_paras))
                    cur_title = line_str if pattern.match(line_str) else f"Phần {len(chapters) + 1}"
                    cur_paras = [] if pattern.match(line_str) else [line_str]
                    cur_len = 0 if pattern.match(line_str) else len(line_str)
                    continue
            cur_paras.append(line_str)
            cur_len += len(line_str)
        if cur_paras: chapters.append((cur_title, cur_paras))
        if not chapters: chapters = [("Chương 1", ["Nội dung trống"])]

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("mimetype", "application/epub+zip")
            z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
            manifest_items, spine_items = [], []
            for idx, (title, paras) in enumerate(chapters, 1):
                cid, href = f"c{idx}", f"c{idx}.xhtml"
                manifest_items.append(f'<item id="{cid}" href="{href}" media-type="application/xhtml+xml"/>')
                spine_items.append(f'<itemref idref="{cid}"/>')
                body_html = f"<h2>{html.escape(title)}</h2>" + "".join(f"<p>{html.escape(p)}</p>" for p in paras)
                xhtml = f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{html.escape(title)}</title></head><body>{body_html}</body></html>'
                z.writestr(f"OEBPS/{href}", xhtml)
            book_title = html.escape(Path(name).stem)
            opf = f'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>{book_title}</dc:title><dc:language>vi</dc:language></metadata><manifest>{"".join(manifest_items)}</manifest><spine>{"".join(spine_items)}</spine></package>'
            z.writestr("OEBPS/content.opf", opf)
        return buf.getvalue()

    def create(self, name, data, model):
        is_txt = name.lower().endswith(".txt")
        if (not name.lower().endswith(".epub") and not is_txt) or len(data) > 80 * 1024 * 1024:
            raise ValueError("File must be EPUB or TXT at most 80 MB")
        if is_txt:
            data = self._txt_to_epub(name, data)
            name = Path(name).stem + ".epub"
        job_id = uuid.uuid4().hex; source = self.root / (job_id + ".source.epub"); source.write_bytes(data)
        with zipfile.ZipFile(source) as book:
            chapters = [x.filename for x in book.infolist() if not x.is_dir() and x.filename.lower().endswith((".xhtml", ".html", ".htm"))]
        if not chapters: source.unlink(); raise ValueError("EPUB/TXT has no chapters")
        row = {"id":job_id,"name":Path(name).name,"status":"queued","message":"Queued","total":len(chapters),"completed":0,"created_at":int(time.time()),"model":model,"output":job_id+".partial.epub"}
        with self.lock: self.jobs[job_id] = row; self._save(row)
        threading.Thread(target=self._run, args=(row, source, chapters), daemon=True).start()
        return dict(row)

    def get(self, job_id):
        with self.lock: return dict(self.jobs[job_id]) if job_id in self.jobs else None

    def list(self):
        with self.lock: return [dict(row) for row in self.jobs.values() if (self.root / row.get("output", "")).is_file()]

    def output(self, job_id):
        row = self.get(job_id)
        path = self.root / row.get("output", "") if row else None
        return path if path and path.is_file() else None

    def _write_partial(self, source, target, translated):
        with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as outgoing:
            for entry in incoming.infolist(): outgoing.writestr(entry, translated.get(entry.filename, incoming.read(entry.filename)))

    def _run(self, row, source, chapters):
        translated = {}; target = self.root / row["output"]
        try:
            row.update(status="running", message="Translating 0/%s chapters" % row["total"]); self._save(row)
            with zipfile.ZipFile(source) as incoming:
                for chapter in chapters:
                    translated[chapter] = self.translate(incoming.read(chapter).decode("utf-8", "replace"), row["model"]).encode("utf-8")
                    row["completed"] += 1; row["message"] = "Translated %s/%s chapters" % (row["completed"], row["total"])
                    self._write_partial(source, target, translated); self._save(row)
            row.update(status="complete", message="Complete", output=row["id"]+".vi.epub"); target.replace(self.root / row["output"]); self._save(row)
        except Exception as error:
            row.update(status="failed", message=str(error)[:280]); self._save(row)
