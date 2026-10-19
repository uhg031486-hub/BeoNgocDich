import os, sys, json, time, zipfile, threading, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

DEFAULT_ROUTER_KEY = "sk-03e57ebc0d35b288-sld2yo-498df0af"
DEFAULT_SERVER_KEY = "sk-03e57ebc0d35b288-sld2yo-498df0af"
DEFAULT_ENDPOINT = "https://r4pqkqr.abc-tunnel.us/v1"
DEFAULT_FUNNEL_URL = "https://smart-tts.tail4cee0d.ts.net"
DEFAULT_MODEL = "ag/gemini-3.8-flash"

MODELS = [
    ("ag/gemini-3.8-flash", "Gemini Flash 3.8 (minimal)"),
    ("ag/gemini-3.8-flash-low", "Gemini Flash 3.8 Low"),
    ("ag/gemini-3.8-flash-medium", "Gemini Flash 3.8 Medium"),
    ("cx/gpt-6-luna", "Luna 6 (minimal)"),
    ("kc/inclusionai/ling-3.0-flash-sante:free", "Ling 3.0 Flash Sante (minimal)"),
    ("kc/nvidia/nemotron-3-super-120b-a12b:free", "Nemotron Super 120B (minimal)"),
    ("kc/nvidia/nemotron-3.5-lightning:free", "Lightning (tắt suy luận)"),
    ("ag/claude-sonnet-5-5", "Claude Sonnet 5.5")
]

SYSTEM_PROMPT = """Bạn là dịch giả và biên tập viên tiểu thuyết sang tiếng Việt. 
Đầu vào có thể thuộc bất kỳ ngôn ngữ nào, pha trộn ngôn ngữ, là bản convert, dịch máy hoặc đã dịch hoàn chỉnh.
BẮT BUỘC xử lý mọi đoạn văn không rỗng. Dịch ngoại ngữ sang tiếng Việt; với tiếng Việt, vẫn phải biên tập lại tự nhiên, chuẩn văn phong truyện đọc, thống nhất xưng hô và giữ nguyên cấu trúc/thẻ HTML.
Không bịa, không tóm tắt, không giải thích ngoài lề. Chỉ trả về trực tiếp nội dung đã dịch/biên tập."""

class RouterClient:
    def __init__(self, endpoint, api_key, model):
        self.endpoint = endpoint.rstrip('/')
        self.api_key = api_key
        self.model = model

    def translate_html_or_text(self, text):
        url = f"{self.endpoint}/chat/completions"
        payload = {
            "model": self.model,
            "reasoning_effort": "minimal",
            "temperature": 0.2,
            "max_tokens": 8192,
            "stream": False,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": text}
            ]
        }
        if "lightning" in self.model.lower():
            payload["reasoning"] = {"enabled": False}
        elif self.model.startswith("kc/"):
            payload["reasoning"] = {"effort": "minimal"}

        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url, data=data,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
                "User-Agent": "CaNgayNguNuong-Desktop/1.0"
            }
        )
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    res = json.loads(resp.read().decode("utf-8", "replace"))
                    return res["choices"][0]["message"]["content"]
            except Exception as e:
                if attempt == 2:
                    raise e
                time.sleep(2)
        return text

class TranslationApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Cả Ngày Ngủ Nướng - Dịch Truyện Windows")
        self.root.geometry("780x680")
        self.root.minsize(650, 600)
        self.selected_file = None
        self.is_translating = False
        self._build_ui()

    def _build_ui(self):
        frame_file = ttk.LabelFrame(self.root, text=" 01. CHỌN BẢN THẢO ", padding=10)
        frame_file.pack(fill="x", padx=15, pady=8)
        self.btn_choose = ttk.Button(frame_file, text="Chọn file EPUB hoặc TXT", command=self.choose_file)
        self.btn_choose.pack(side="left", padx=5)
        self.lbl_file = ttk.Label(frame_file, text="Chưa chọn tệp.", foreground="gray")
        self.lbl_file.pack(side="left", padx=10, fill="x", expand=True)

        frame_cfg = ttk.LabelFrame(self.root, text=" 02. CẤU HÌNH DỊCH 9ROUTER ", padding=10)
        frame_cfg.pack(fill="x", padx=15, pady=8)
        ttk.Label(frame_cfg, text="Model dịch:").grid(row=0, column=0, sticky="w", pady=4)
        self.model_var = tk.StringVar(value=DEFAULT_MODEL)
        model_cb = ttk.Combobox(frame_cfg, textvariable=self.model_var, state="readonly", width=42)
        model_cb['values'] = [f"{m[1]} ({m[0]})" for m in MODELS]
        model_cb.current(0)
        model_cb.grid(row=0, column=1, sticky="w", pady=4, padx=5)

        ttk.Label(frame_cfg, text="Số luồng (1-50):").grid(row=1, column=0, sticky="w", pady=4)
        self.threads_var = tk.StringVar(value="10")
        threads_spin = ttk.Spinbox(frame_cfg, from_=1, to=50, textvariable=self.threads_var, width=10)
        threads_spin.grid(row=1, column=1, sticky="w", pady=4, padx=5)

        ttk.Label(frame_cfg, text="9Router Key:").grid(row=2, column=0, sticky="w", pady=4)
        self.key_var = tk.StringVar(value=DEFAULT_ROUTER_KEY)
        self.entry_key = ttk.Entry(frame_cfg, textvariable=self.key_var, show="*", width=45)
        self.entry_key.grid(row=2, column=1, sticky="w", pady=4, padx=5)

        frame_server = ttk.LabelFrame(self.root, text=" 03. ĐỒNG BỘ VPS / FUNNEL ", padding=10)
        frame_server.pack(fill="x", padx=15, pady=8)
        ttk.Label(frame_server, text="Server URL:").grid(row=0, column=0, sticky="w", pady=4)
        self.server_var = tk.StringVar(value=DEFAULT_FUNNEL_URL)
        ttk.Entry(frame_server, textvariable=self.server_var, width=45).grid(row=0, column=1, sticky="w", pady=4, padx=5)

        btn_box = ttk.Frame(frame_server)
        btn_box.grid(row=1, column=0, columnspan=2, pady=6, sticky="w")
        self.btn_upload = ttk.Button(btn_box, text="Đẩy file lên VPS dịch nền", command=self.upload_to_server)
        self.btn_upload.pack(side="left", padx=5)

        frame_run = ttk.Frame(self.root, padding=10)
        frame_run.pack(fill="both", expand=True, padx=15, pady=5)
        self.btn_start = ttk.Button(frame_run, text="BẮT ĐẦU DỊCH TRỰC TIẾP", command=self.start_translation)
        self.btn_start.pack(fill="x", pady=5)

        self.progress = ttk.Progressbar(frame_run, orient="horizontal", mode="determinate")
        self.progress.pack(fill="x", pady=5)
        self.log_text = tk.Text(frame_run, height=12, state="disabled", wrap="word", bg="#f4f4f4")
        self.log_text.pack(fill="both", expand=True, pady=5)

    def log(self, msg):
        self.log_text.config(state="normal")
        self.log_text.insert("end", f"[{time.strftime('%H:%M:%S')}] {msg}\n")
        self.log_text.see("end")
        self.log_text.config(state="disabled")

    def choose_file(self):
        f = filedialog.askopenfilename(filetypes=[("Truyện EPUB/TXT", "*.epub;*.txt"), ("EPUB files", "*.epub"), ("TXT files", "*.txt")])
        if f:
            self.selected_file = f
            self.lbl_file.config(text=os.path.basename(f), foreground="black")
            self.log(f"Đã chọn: {f}")

    def get_selected_model_id(self):
        val = self.model_var.get()
        for mid, label in MODELS:
            if mid in val:
                return mid
        return DEFAULT_MODEL

    def upload_to_server(self):
        if not self.selected_file or not self.selected_file.lower().endswith(".epub"):
            messagebox.showwarning("Lỗi", "Vui lòng chọn 1 file .epub trước.")
            return
        def _worker():
            try:
                self.log("Đang tải EPUB lên VPS...")
                base_url = self.server_var.get().rstrip('/')
                file_name = os.path.basename(self.selected_file)
                url = f"{base_url}/v1/epub/upload?name={urllib.parse.quote(file_name)}"
                with open(self.selected_file, "rb") as f:
                    data = f.read()
                req = urllib.request.Request(
                    url, data=data,
                    headers={
                        "Content-Type": "application/epub+zip",
                        "Authorization": f"Bearer {DEFAULT_SERVER_KEY}",
                        "X-Translation-Model": self.get_selected_model_id()
                    }
                )
                with urllib.request.urlopen(req, timeout=300) as resp:
                    res = json.loads(resp.read().decode("utf-8"))
                    self.log(f"Đã tạo Job dịch trên Server: ID={res.get('id')} - Tổng {res.get('total')} chương.")
                    messagebox.showinfo("Thành công", f"Server đang dịch ngầm. Job ID: {res.get('id')}")
            except Exception as e:
                self.log(f"Lỗi: {e}")
                messagebox.showerror("Lỗi", str(e))
        threading.Thread(target=_worker, daemon=True).start()

    def start_translation(self):
        if self.is_translating or not self.selected_file:
            return
        api_key = self.key_var.get().strip()
        if not api_key:
            messagebox.showwarning("Thiếu Key", "Vui lòng nhập API Key 9Router.")
            return
        try:
            concurrency = int(self.threads_var.get())
            concurrency = max(1, min(50, concurrency))
        except ValueError:
            concurrency = 10
        self.is_translating = True
        self.btn_start.config(state="disabled")
        self.progress["value"] = 0
        client = RouterClient(DEFAULT_ENDPOINT, api_key, self.get_selected_model_id())
        threading.Thread(target=self._run_process, args=(client, concurrency), daemon=True).start()

    def _run_process(self, client, concurrency):
        src = self.selected_file
        self.log(f"Bắt đầu dịch bằng Model: {client.model} | Số luồng: {concurrency}")
        try:
            if src.lower().endswith(".epub"):
                self._translate_epub(src, client, concurrency)
            else:
                self._translate_txt(src, client)
        except Exception as e:
            self.log(f"Lỗi: {e}")
            messagebox.showerror("Lỗi dịch thuật", str(e))
        finally:
            self.is_translating = False
            self.btn_start.config(state="normal")

    def _translate_txt(self, path, client):
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        out_path = path.rsplit(".", 1)[0] + ".vi.txt"
        self.log("Đang dịch TXT...")
        res = client.translate_html_or_text(content)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(res)
        self.progress["value"] = 100
        self.log(f"Xong: {out_path}")
        messagebox.showinfo("Hoàn thành", f"Đã dịch xong TXT:\n{out_path}")

    def _translate_epub(self, path, client, concurrency):
        with zipfile.ZipFile(path, "r") as z:
            chapters = [x for x in z.namelist() if x.lower().endswith((".xhtml", ".html", ".htm"))]
        total = len(chapters)
        if total == 0:
            raise Exception("File EPUB không chứa file XHTML/HTML nào.")
        self.log(f"Tổng số chương: {total}")
        translated_map = {}
        completed = 0
        out_path = path.rsplit(".", 1)[0] + ".vi.epub"

        def _trans_one(ch_name):
            with zipfile.ZipFile(path, "r") as z:
                raw_text = z.read(ch_name).decode("utf-8", "replace")
            res_text = client.translate_html_or_text(raw_text)
            return ch_name, res_text.encode("utf-8")

        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = {executor.submit(_trans_one, ch): ch for ch in chapters}
            for fut in as_completed(futures):
                ch_name, data = fut.result()
                translated_map[ch_name] = data
                completed += 1
                self.progress["value"] = int((completed / total) * 100)
                self.log(f"Đã xong ({completed}/{total}): {ch_name}")

        with zipfile.ZipFile(path, "r") as src_z, zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as dst_z:
            for item in src_z.infolist():
                if item.filename in translated_map:
                    dst_z.writestr(item.filename, translated_map[item.filename])
                else:
                    dst_z.writestr(item.filename, src_z.read(item.filename))
        self.log(f"XONG! Đã lưu tại: {out_path}")
        messagebox.showinfo("Thành công", f"Đã dịch xong EPUB:\n{out_path}")

if __name__ == "__main__":
    root = tk.Tk()
    app = TranslationApp(root)
    root.mainloop()
