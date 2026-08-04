# -*- coding: utf-8 -*-
"""
PDF → Excel Converter — Desktop Application (Tkinter)
======================================================
A complete, professional GUI that:

  • Converts ANY number of PDF files in one batch
  • Converts up to 1000 (or more) pages per file
  • Extracts the merchant / agent name from each PDF and uses it
    as the Excel file title (auto, or manual override)
  • Skips pages you don't want — by page number ("1,3,5-9")
    OR by page title / header keyword
  • Writes every PDF to its own separate .xlsx file
  • Arabic RTL support, progress bar, live log

Runs as-is on Windows / Linux / macOS (Python 3.8+).
Packaged as a single self-contained .exe via PyInstaller (see build_exe.bat).
"""

import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

# Allow running from a bundled PyInstaller one-file app
if getattr(sys, "frozen", False):
    _BASE = sys._MEIPASS
    sys.path.insert(0, _BASE)
else:
    _BASE = os.path.dirname(os.path.abspath(__file__))

from core import (
    convert_pdf,
    parse_skip_keywords,
    parse_skip_numbers,
    sanitize_filename,
)
from extractor import extract_merchant_name

APP_TITLE = "محوّل PDF إلى Excel — PDF Converter"
APP_VERSION = "1.1.0"

DEFAULT_MAX_PAGES = 1000

OUTPUT_FOLDER_NAME = "PDF CONVERTER"


def default_output_dir():
    """The 'PDF CONVERTER' folder on the local disk (Documents, else home)."""
    home = os.path.expanduser("~")
    for base in (os.path.join(home, "Documents"), home):
        try:
            folder = os.path.join(base, OUTPUT_FOLDER_NAME)
            os.makedirs(folder, exist_ok=True)
            return folder
        except Exception:
            continue
    return os.path.join(home, OUTPUT_FOLDER_NAME)


class PdfConverterApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("880x640")
        self.root.minsize(760, 560)

        # center on screen
        self.root.update_idletasks()
        w, h = 880, 640
        x = max((self.root.winfo_screenwidth() - w) // 2, 0)
        y = max((self.root.winfo_screenheight() - h) // 2 - 30, 0)
        self.root.geometry(f"{w}x{h}+{x}+{y}")

        self.pdf_files: list = []
        self.log_queue: queue.Queue = queue.Queue()
        self.busy = False

        self._build_ui()
        self._poll_log()

        try:  # icon (bundled or dev)
            icon = tk.PhotoImage(file=os.path.join(_BASE, "assets", "icon.png"))
            self.root.iconphoto(True, icon)
        except Exception:
            pass

    # ------------------------------------------------------------------ UI
    def _build_ui(self):
        pad = {"padx": 8, "pady": 4}

        # ---- Header -----------------------------------------------------
        header = tk.Frame(self.root, bg="#1F4E79")
        header.pack(fill="x")
        tk.Label(
            header, text=APP_TITLE,
            bg="#1F4E79", fg="white", font=("Segoe UI", 15, "bold"),
        ).pack(anchor="w", padx=12, pady=8)
        tk.Label(
            header, text="تحويل أي عدد من ملفات PDF إلى ملفات Excel منفصلة — حتى 1000 صفحة للملف",
            bg="#1F4E79", fg="#D9E2F3", font=("Segoe UI", 9),
        ).pack(anchor="w", padx=12, pady=(0, 8))

        main = tk.Frame(self.root)
        main.pack(fill="both", expand=True, **pad)

        # ---- Left: file list --------------------------------------------
        left = tk.LabelFrame(main, text="  ملفات PDF  ", font=("Segoe UI", 10, "bold"))
        left.pack(side="left", fill="both", expand=True, padx=(8, 4), pady=4)

        btns = tk.Frame(left)
        btns.pack(fill="x", padx=6, pady=4)
        tk.Button(btns, text="➕ إضافة ملفات", command=self.add_files).pack(side="left")
        tk.Button(btns, text="📂 إضافة مجلد", command=self.add_folder).pack(side="left", padx=6)
        tk.Button(btns, text="🗑 إزالة المحدد", command=self.remove_selected).pack(side="left")
        tk.Button(btns, text="✖ مسح الكل", command=self.clear_all).pack(side="left", padx=6)

        self.file_list = tk.Listbox(
            left, selectmode="extended", font=("Segoe UI", 9), activestyle="dotbox"
        )
        self.file_list.pack(fill="both", expand=True, padx=6, pady=4)
        self.file_count = tk.Label(left, text="0 ملف", fg="#555")
        self.file_count.pack(anchor="w", padx=6, pady=(0, 4))

        # ---- Right: options ---------------------------------------------
        right = tk.Frame(main)
        right.pack(side="left", fill="y", padx=(4, 8), pady=4)

        opt = tk.LabelFrame(right, text="  خيارات التحويل  ", font=("Segoe UI", 10, "bold"))
        opt.pack(fill="x")

        # output folder
        frow = tk.Frame(opt)
        frow.pack(fill="x", padx=6, pady=4)
        tk.Label(frow, text=f"مجلد الإخراج (يُنشأ تلقائياً باسم «{OUTPUT_FOLDER_NAME}»):").pack(anchor="w")
        self.out_dir_var = tk.StringVar(value=default_output_dir())
        tk.Entry(frow, textvariable=self.out_dir_var).pack(fill="x", pady=2)
        tk.Button(frow, text="اختيار مجلد…", command=self.choose_out_dir).pack(anchor="w")

        # max pages
        prow = tk.Frame(opt)
        prow.pack(fill="x", padx=6, pady=4)
        tk.Label(prow, text="الحد الأقصى للصفحات في الملف الواحد:").pack(anchor="w")
        self.max_pages_var = tk.IntVar(value=DEFAULT_MAX_PAGES)
        tk.Spinbox(
            prow, from_=1, to=100000, textvariable=self.max_pages_var, width=10,
        ).pack(anchor="w", pady=2)

        # merchant name
        self.auto_name_var = tk.BooleanVar(value=True)
        tk.Checkbutton(
            opt, text="استخراج اسم التاجر/الوكيل تلقائياً وجعله عنوان ملف الـ Excel",
            variable=self.auto_name_var,
        ).pack(anchor="w", padx=6, pady=(4, 0))
        nrow = tk.Frame(opt)
        nrow.pack(fill="x", padx=6, pady=4)
        tk.Label(nrow, text="اسم يدوي (اختياري — يلغي الاستخراج التلقائي):").pack(anchor="w")
        self.manual_name_var = tk.StringVar()
        tk.Entry(nrow, textvariable=self.manual_name_var).pack(fill="x", pady=2)

        # skip pages
        srow = tk.Frame(opt)
        srow.pack(fill="x", padx=6, pady=4)
        tk.Label(srow, text="استثناء صفحات برقمها (مثال: 1,3,5-9):").pack(anchor="w")
        self.skip_nums_var = tk.StringVar()
        tk.Entry(srow, textvariable=self.skip_nums_var).pack(fill="x", pady=2)
        tk.Label(srow, text="استثناء صفحات بعنوانها/كلماتها (مفصولة بفواصل):").pack(anchor="w", pady=(4, 0))
        self.skip_kw_var = tk.StringVar()
        tk.Entry(srow, textvariable=self.skip_kw_var).pack(fill="x", pady=2)

        # ---- Log ----------------------------------------------------------
        logf = tk.LabelFrame(self.root, text="  سجل التحويل  ", font=("Segoe UI", 10, "bold"))
        logf.pack(fill="both", expand=True, padx=8, pady=(0, 4))
        self.log_text = tk.Text(logf, height=7, font=("Consolas", 9), state="disabled", wrap="word")
        self.log_text.pack(fill="both", expand=True, padx=4, pady=4)
        sb = ttk.Scrollbar(self.log_text, command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")

        # ---- Progress + actions ------------------------------------------
        bottom = tk.Frame(self.root)
        bottom.pack(fill="x", padx=8, pady=(0, 8))
        self.progress = ttk.Progressbar(bottom, mode="determinate")
        self.progress.pack(fill="x", side="left", expand=True, padx=(0, 8))
        self.status_var = tk.StringVar(value="جاهز")
        tk.Label(bottom, textvariable=self.status_var, width=22, anchor="e").pack(side="left")
        self.convert_btn = tk.Button(
            bottom, text="▶ بدء التحويل", command=self.start_conversion,
            bg="#2E7D32", fg="white", font=("Segoe UI", 11, "bold"), padx=14,
        )
        self.convert_btn.pack(side="left", padx=4)
        self.open_btn = tk.Button(bottom, text="📁 فتح مجلد الإخراج", command=self.open_output)
        self.open_btn.pack(side="left", padx=4)

    # -------------------------------------------------------------- actions
    def add_files(self):
        paths = filedialog.askopenfilenames(
            title="اختر ملفات PDF",
            filetypes=[("PDF files", "*.pdf"), ("All files", "*.*")],
        )
        for p in paths:
            if p not in self.pdf_files:
                self.pdf_files.append(p)
        self._refresh_list()

    def add_folder(self):
        folder = filedialog.askdirectory(title="اختر مجلداً يحتوي ملفات PDF")
        if not folder:
            return
        found = 0
        for name in sorted(os.listdir(folder)):
            if name.lower().endswith(".pdf"):
                full = os.path.join(folder, name)
                if full not in self.pdf_files:
                    self.pdf_files.append(full)
                    found += 1
        if found == 0:
            messagebox.showinfo("مجلد", "لم يتم العثور على ملفات PDF في هذا المجلد")
        self._refresh_list()

    def remove_selected(self):
        sel = list(self.file_list.curselection())
        for i in reversed(sel):
            self.pdf_files.pop(i)
        self._refresh_list()

    def clear_all(self):
        self.pdf_files.clear()
        self._refresh_list()

    def _refresh_list(self):
        self.file_list.delete(0, "end")
        for p in self.pdf_files:
            self.file_list.insert("end", os.path.basename(p))
        self.file_count.config(text=f"{len(self.pdf_files)} ملف")

    def choose_out_dir(self):
        d = filedialog.askdirectory(title="اختر مجلد الإخراج")
        if d:
            self.out_dir_var.set(d)

    def open_output(self):
        d = self.out_dir_var.get()
        os.makedirs(d, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(d)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            os.system(f'open "{d}"')
        else:
            os.system(f'xdg-open "{d}" &')

    # ------------------------------------------------------------- logging
    def _log(self, msg: str):
        self.log_queue.put(msg)

    def _poll_log(self):
        try:
            while True:
                msg = self.log_queue.get_nowait()
                self.log_text.configure(state="normal")
                self.log_text.insert("end", msg + "\n")
                self.log_text.see("end")
                self.log_text.configure(state="disabled")
        except queue.Empty:
            pass
        self.root.after(120, self._poll_log)

    def _set_busy(self, busy: bool):
        self.busy = busy
        self.convert_btn.config(state="disabled" if busy else "normal",
                                text="⏳ جارٍ التحويل…" if busy else "▶ بدء التحويل")
        self.progress.config(value=0 if not busy else self.progress["value"])

    # ------------------------------------------------------------ conversion
    def start_conversion(self):
        if self.busy:
            return
        if not self.pdf_files:
            messagebox.showwarning("تنبيه", "أضف ملفات PDF أولاً")
            return
        out_dir = self.out_dir_var.get().strip() or default_output_dir()
        # Make sure the "PDF CONVERTER" folder exists on the local disk
        try:
            os.makedirs(out_dir, exist_ok=True)
        except OSError as e:
            messagebox.showerror("خطأ", f"تعذر إنشاء مجلد الإخراج:\n{e}")
            return
        try:
            max_pages = int(self.max_pages_var.get())
        except (tk.TclError, ValueError):
            max_pages = DEFAULT_MAX_PAGES
        skip_nums = parse_skip_numbers(self.skip_nums_var.get())
        skip_kws = parse_skip_keywords(self.skip_kw_var.get())
        manual = self.manual_name_var.get().strip()
        auto = self.auto_name_var.get()

        self._set_busy(True)
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.configure(state="disabled")
        self._log(f"📁 مجلد الإخراج: {out_dir}")
        self._log(f"بدء التحويل: {len(self.pdf_files)} ملف")
        self._log(f"الحد الأقصى للصفحات: {max_pages} | استثناء أرقام: {skip_nums or '—'} | "
                  f"استثناء كلمات: {skip_kws or '—'}")

        worker = threading.Thread(
            target=self._worker,
            args=(list(self.pdf_files), out_dir, max_pages, skip_nums, skip_kws, manual, auto),
            daemon=True,
        )
        worker.start()

    def _worker(self, files, out_dir, max_pages, skip_nums, skip_kws, manual, auto):
        os.makedirs(out_dir, exist_ok=True)
        total = len(files)
        for idx, pdf in enumerate(files, start=1):
            try:
                self.log_queue.put(f"\n[{idx}/{total}] 📄 {os.path.basename(pdf)}")

                merchant = None
                if manual:
                    merchant = manual
                elif auto:
                    merchant = extract_merchant_name(pdf)

                fname = None
                if merchant:
                    fname = sanitize_filename(merchant)
                    self.log_queue.put(f"   ✔ التاجر/الوكيل المستخرج: {merchant}")

                out, res = convert_pdf(
                    pdf,
                    out_dir,
                    output_filename=fname,
                    merchant=merchant,
                    max_pages=max_pages,
                    skip_numbers=skip_nums,
                    skip_keywords=skip_kws,
                )
                self.log_queue.put(
                    f"   ✔ تم الحفظ: {os.path.basename(out)} "
                    f"({res.pages_converted} صفحة محولة، {res.pages_skipped} مستثناة)"
                )
            except Exception as e:
                self.log_queue.put(f"   ✖ خطأ: {e}")
            finally:
                self.log_queue.put(("PROGRESS", idx, total))

        self.log_queue.put(("DONE", total))

    # ---------------------------------------------------------------- hooks
    def _on_progress(self, idx, total):
        self.progress.config(value=idx * 100 / total)
        self.status_var.set(f"{idx} / {total}")

    def _on_done(self, total):
        self.progress.config(value=100)
        self.status_var.set("اكتمل التحويل ✔")
        self._set_busy(False)
        self._log(f"\n✔ اكتمل تحويل {total} ملف بنجاح!")


def main():
    # handle worker->UI events via the queue poller
    _orig_poll = PdfConverterApp._poll_log

    def _poll_with_events(self):
        try:
            while True:
                item = self.log_queue.get_nowait()
                if isinstance(item, tuple) and item[0] == "PROGRESS":
                    self._on_progress(item[1], item[2])
                    continue
                if isinstance(item, tuple) and item[0] == "DONE":
                    self._on_done(item[1])
                    continue
                self.log_text.configure(state="normal")
                self.log_text.insert("end", item + "\n")
                self.log_text.see("end")
                self.log_text.configure(state="disabled")
        except queue.Empty:
            pass
        self.root.after(120, lambda: _poll_with_events(self))

    PdfConverterApp._poll_log = _poll_with_events

    root = tk.Tk()
    app = PdfConverterApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
