"""Private, bounded cache for translated book chapters.

The cache stores only text. Audio/TTS jobs are deliberately not enqueued here.
Files are addressed by a caller-supplied content identity and evicted oldest
first when the configured byte budget is reached.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path


class TranslationStore:
    def __init__(self, root: Path, max_bytes: int = 20 * 1024**3, ttl_days: int = 0):
        self.root = Path(root) / "translations"
        self.root.mkdir(parents=True, exist_ok=True)
        self.max_bytes = int(max_bytes)
        self.ttl_seconds = int(ttl_days * 86400)
        self.lock = threading.RLock()

    def _safe_key(self, key: str) -> str:
        value = str(key or "").strip()
        if not value or len(value) > 512:
            raise ValueError("Translation key is required")
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _paths(self, key: str):
        digest = self._safe_key(key)
        return self.root / (digest + ".json"), self.root / (digest + ".meta")

    def _cleanup(self):
        now = time.time()
        rows = []
        total = 0
        for p in self.root.glob("*.json"):
            try:
                size = p.stat().st_size + (p.with_suffix(".meta").stat().st_size if p.with_suffix(".meta").exists() else 0)
                total += size
                age = now - p.stat().st_mtime
                rows.append((p.stat().st_mtime, p, size))
                if self.ttl_seconds > 0 and age > self.ttl_seconds:
                    p.unlink(missing_ok=True)
                    total -= size
                    p.with_suffix(".meta").unlink(missing_ok=True)
            except OSError:
                continue
        if total <= self.max_bytes:
            return
        for _, p, size in sorted(rows):
            if total <= self.max_bytes:
                break
            try:
                p.unlink(missing_ok=True)
                p.with_suffix(".meta").unlink(missing_ok=True)
                total -= size
            except OSError:
                continue

    @staticmethod
    def _merge_json_text(old_text: object, new_text: object, kind: str) -> str:
        """Merge memory payloads so concurrent chapter requests cannot erase names."""
        try:
            old = json.loads(old_text) if isinstance(old_text, str) and old_text.strip() else {}
        except (ValueError, TypeError):
            old = {}
        try:
            new = json.loads(new_text) if isinstance(new_text, str) and new_text.strip() else {}
        except (ValueError, TypeError):
            new = {}
        if kind == "character-glossary":
            result, seen = [], set()
            for row in list(old.get("entries", [])) + list(new.get("entries", [])):
                if not isinstance(row, dict):
                    continue
                source = str(row.get("source") or "").strip()
                target = str(row.get("target") or "").strip()
                if source and target and source not in seen:
                    seen.add(source); result.append({"source": source, "target": target})
            return json.dumps({"entries": result}, ensure_ascii=False)
        if kind == "relationship-memory":
            result, seen = [], set()
            for row in list(old.get("relations", [])) + list(new.get("relations", [])):
                if not isinstance(row, dict):
                    continue
                marker = json.dumps(row, ensure_ascii=False, sort_keys=True)
                if marker not in seen:
                    seen.add(marker); result.append(row)
            return json.dumps({"relations": result}, ensure_ascii=False)
        return str(new_text or "")

    def put(self, key: str, payload: dict):
        if not isinstance(payload, dict):
            raise ValueError("Translation payload must be an object")
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise ValueError("Translated text is required")
        if len(text.encode("utf-8")) > 4 * 1024**2:
            raise ValueError("One translated chapter is too large")
        data = dict(payload)
        path, meta = self._paths(key)
        tmp = path.with_suffix(".tmp")
        with self.lock:
            if data.get("merge") and data.get("kind") in {"character-glossary", "relationship-memory"} and path.exists():
                try:
                    current = json.loads(path.read_text(encoding="utf-8"))
                    merged = self._merge_json_text(current.get("text"), text, str(data.get("kind")))
                    data["text"] = merged
                    data["translated_text"] = merged
                except (OSError, ValueError, TypeError):
                    pass
            data.pop("merge", None)
            data["updated_at"] = int(time.time())
            tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, path)
            meta.write_text(json.dumps({"key": str(key), "updated_at": data["updated_at"]}, ensure_ascii=False), encoding="utf-8")
            size = path.stat().st_size
            self._cleanup()
        return {"stored": path.exists(), "bytes": size, "max_bytes": self.max_bytes}

    def sync(self, items: list):
        """Acknowledge only persisted records; clients keep unacknowledged jobs."""
        if not isinstance(items, list) or not 1 <= len(items) <= 100:
            raise ValueError("Sync needs 1-100 records")
        prepared = []
        for item in items:
            if not isinstance(item, dict):
                raise ValueError("Each sync record must be an object")
            row = dict(item)
            key = str(row.pop("key", "") or "")
            self._safe_key(key)
            if row.get("kind") not in {"source", "chapter"}:
                raise ValueError("Sync only accepts source/chapter records")
            if not isinstance(row.get("text"), str) or not row["text"].strip():
                raise ValueError("Sync text is required")
            if len(row["text"].encode("utf-8")) > 4 * 1024**2:
                raise ValueError("One sync record is too large")
            if row["kind"] == "source":
                row["translated_text"] = ""
            elif not isinstance(row.get("translated_text"), str) or not row["translated_text"].strip():
                raise ValueError("Translated chapter text is required")
            prepared.append((key, row))
        stored = []
        for key, row in prepared:
            if self.put(key, row).get("stored"):
                stored.append(key)
        return {"stored_keys": stored, "count": len(stored)}

    def get(self, key: str):
        path, _ = self._paths(key)
        with self.lock:
            if not path.exists():
                return None
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                os.utime(path, None)
                return data
            except (OSError, ValueError, TypeError):
                return None

    def status(self):
        with self.lock:
            total = sum(p.stat().st_size for p in self.root.iterdir() if p.suffix in (".json", ".meta") and p.is_file())
            return {"bytes": total, "max_bytes": self.max_bytes, "ttl_days": self.ttl_seconds // 86400}
