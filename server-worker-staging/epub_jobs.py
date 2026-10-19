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

    def create(self, name, data, model):
        if not name.lower().endswith(".epub") or len(data) > 80 * 1024 * 1024: raise ValueError("EPUB must be at most 80 MB")
        job_id = uuid.uuid4().hex; source = self.root / (job_id + ".source.epub"); source.write_bytes(data)
        with zipfile.ZipFile(source) as book:
            chapters = [x.filename for x in book.infolist() if not x.is_dir() and x.filename.lower().endswith((".xhtml", ".html", ".htm"))]
        if not chapters: source.unlink(); raise ValueError("EPUB has no XHTML chapters")
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
