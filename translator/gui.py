"""Tkinter-Oberfläche. Worker-Threads sprechen mit der GUI ausschließlich über eine Queue."""
from __future__ import annotations

import dataclasses
import logging
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

from .chunking import split_into_chunks
from .config import Config
from .documents import SUPPORTED_EXTENSIONS, read_document
from .exporters import SAVE_FILETYPES, extract_translations, format_block, save_document
from .glossary import format_glossary, parse_glossary
from .languages import DISPLAY_LIST, code_from_display, display_name
from .llm_client import LLMClient
from .pipeline import Cancelled, ChunkResult, TranslationPipeline

log = logging.getLogger(__name__)
POLL_MS = 100


class TranslatorApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.config = Config.load()
        self.events: queue.Queue = queue.Queue()
        self.cancel = threading.Event()
        self.worker: threading.Thread | None = None
        self.settings_win: tk.Toplevel | None = None
        self.model_combo: ttk.Combobox | None = None

        root.title("AI-Translator")
        root.geometry("900x650")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)
        self._build_toolbar()
        self.output = scrolledtext.ScrolledText(root, wrap=tk.WORD)
        self.output.grid(row=1, column=0, sticky="nsew", padx=10)
        self._build_statusbar()
        root.after(POLL_MS, self._poll)

    # --- Aufbau ------------------------------------------------------------------------------

    def _build_toolbar(self) -> None:
        bar = ttk.Frame(self.root)
        bar.grid(row=0, column=0, sticky="we", padx=10, pady=8)
        self.open_btn = ttk.Button(bar, text="Datei übersetzen…", command=self.open_file)
        self.open_btn.pack(side=tk.LEFT)
        self.cancel_btn = ttk.Button(bar, text="Abbrechen", command=self.cancel.set, state=tk.DISABLED)
        self.cancel_btn.pack(side=tk.LEFT, padx=4)

        self.source_combo = self._lang_combo(bar, self.config.lang_source)
        ttk.Label(bar, text="→").pack(side=tk.LEFT)
        self.target_combo = self._lang_combo(bar, self.config.lang_target)

        ttk.Button(bar, text="Einstellungen", command=self.open_settings).pack(side=tk.RIGHT)
        ttk.Button(bar, text="Ergebnis speichern…", command=self.save_result).pack(side=tk.RIGHT, padx=4)

    def _lang_combo(self, parent, code: str) -> ttk.Combobox:
        combo = ttk.Combobox(parent, values=DISPLAY_LIST, state="readonly", width=18)
        combo.set(display_name(code))
        combo.pack(side=tk.LEFT, padx=(10, 4))
        combo.bind("<<ComboboxSelected>>", lambda _e: self._languages_changed())
        return combo

    def _build_statusbar(self) -> None:
        frame = ttk.Frame(self.root, relief=tk.SUNKEN)
        frame.grid(row=2, column=0, sticky="we", padx=10, pady=8)
        frame.columnconfigure(0, weight=1)
        self.status = tk.StringVar(value="Bereit")
        ttk.Label(frame, textvariable=self.status).grid(row=0, column=0, sticky="w", padx=5)
        self.progress = ttk.Progressbar(frame, length=180, mode="determinate")
        self.progress.grid(row=0, column=1, padx=5, pady=2)

    # --- Aktionen ----------------------------------------------------------------------------

    def _languages_changed(self) -> None:
        self.config.lang_source = code_from_display(self.source_combo.get())
        self.config.lang_target = code_from_display(self.target_combo.get())
        self.config.save()

    def open_file(self) -> None:
        patterns = " ".join(f"*{ext}" for ext in SUPPORTED_EXTENSIONS)
        path = filedialog.askopenfilename(filetypes=[("Dokumente", patterns)])
        if not path:
            return
        if not self.config.model:
            messagebox.showwarning("Kein Modell", "Bitte zuerst in den Einstellungen ein Modell wählen.")
            return
        self.cancel.clear()
        self.output.delete("1.0", tk.END)
        self.progress.configure(value=0)
        self._set_running(True)
        config = dataclasses.replace(self.config)  # Schnappschuss für den Worker
        self.worker = threading.Thread(target=self._run_pipeline, args=(path, config), daemon=True)
        self.worker.start()

    def _set_running(self, running: bool) -> None:
        self.open_btn.configure(state=tk.DISABLED if running else tk.NORMAL)
        self.cancel_btn.configure(state=tk.NORMAL if running else tk.DISABLED)

    def save_result(self) -> None:
        content = extract_translations(self.output.get("1.0", tk.END))
        if not content:
            messagebox.showwarning("Nichts zu speichern", "Es gibt noch keine Übersetzung.")
            return
        path = filedialog.asksaveasfilename(defaultextension=".txt", filetypes=SAVE_FILETYPES)
        if not path:
            return
        try:
            save_document(content, path, self.config.lang_target)
        except Exception as exc:
            log.exception("Speichern fehlgeschlagen")
            messagebox.showerror("Fehler", f"Speichern fehlgeschlagen: {exc}")
        else:
            messagebox.showinfo("Gespeichert", path)

    # --- Worker-Thread -----------------------------------------------------------------------

    def _emit(self, kind: str, *payload) -> None:
        self.events.put((kind, *payload))

    def _run_pipeline(self, path: str, config: Config) -> None:
        try:
            self._emit("status", "Extrahiere Text…")
            text = read_document(path)
            if not text:
                raise ValueError("Die Datei konnte nicht gelesen werden oder ist leer.")
            chunks = split_into_chunks(text, config.max_chunk_chars)
            pipeline = TranslationPipeline(LLMClient(config.base_url, config.api_key), config)

            glossary: dict[str, str] = {}
            if not config.is_translategemma:
                self._emit("status", "KI analysiert Begriffe…")
                glossary = pipeline.build_glossary(chunks, review=self._review_glossary)

            for result in pipeline.translate(chunks, glossary, self.cancel):
                self._emit("result", result, len(chunks))
            self._emit("done", "Übersetzung abgeschlossen.")
        except Cancelled:
            self._emit("done", "Abgebrochen.")
        except Exception as exc:
            log.exception("Pipeline-Fehler")
            self._emit("error", str(exc))

    def _review_glossary(self, glossary: dict[str, str]) -> dict[str, str]:
        """Blockiert den Worker, bis der Benutzer das Glossar im Dialog bestätigt hat."""
        reply: queue.Queue = queue.Queue()
        self._emit("glossary", glossary, reply)
        return reply.get()

    # --- GUI-Thread: Events abarbeiten -------------------------------------------------------

    def _poll(self) -> None:
        try:
            while True:
                kind, *payload = self.events.get_nowait()
                getattr(self, f"_on_{kind}")(*payload)
        except queue.Empty:
            pass
        self.root.after(POLL_MS, self._poll)

    def _on_status(self, text: str) -> None:
        self.status.set(text)

    def _on_result(self, result: ChunkResult, total: int) -> None:
        self.output.insert(tk.END, format_block(result.index, total, result.source, result.translation, result.warnings))
        self.output.see(tk.END)
        self.progress.configure(value=result.index * 100 / total)
        self.status.set(f"Abschnitt {result.index}/{total} übersetzt")

    def _on_done(self, text: str) -> None:
        self.status.set(text)
        self._set_running(False)

    def _on_error(self, text: str) -> None:
        self.status.set("Fehler")
        self._set_running(False)
        messagebox.showerror("Fehler", text)

    def _on_glossary(self, glossary: dict[str, str], reply: queue.Queue) -> None:
        win = tk.Toplevel(self.root)
        win.title("Glossar prüfen und korrigieren")
        win.geometry("420x520")
        ttk.Label(win, text="Format pro Zeile: Begriff: Übersetzung").pack(pady=5)
        editor = tk.Text(win, wrap=tk.WORD)
        editor.insert("1.0", format_glossary(glossary))
        editor.pack(expand=True, fill="both", padx=10, pady=5)

        def confirm() -> None:
            reply.put(parse_glossary(editor.get("1.0", tk.END)))
            win.destroy()

        ttk.Button(win, text="Bestätigen und Übersetzung starten", command=confirm).pack(pady=10)
        win.protocol("WM_DELETE_WINDOW", confirm)
        win.grab_set()

    def _on_models(self, models: list[str] | None, error: str) -> None:
        if models is None:
            self.status.set(error)
            return
        self.config.models = models
        self.status.set(f"{len(models)} Modelle geladen")
        if self.model_combo is not None and self.model_combo.winfo_exists():
            self.model_combo.configure(values=models)
            if models and not self.model_combo.get():
                self.model_combo.set(models[0])

    # --- Einstellungen -----------------------------------------------------------------------

    def open_settings(self) -> None:
        if self.settings_win is not None and self.settings_win.winfo_exists():
            self.settings_win.lift()
            return
        win = self.settings_win = tk.Toplevel(self.root)
        win.title("Einstellungen")
        win.columnconfigure(1, weight=1)
        cfg = self.config

        def row(label: str, r: int, widget: tk.Widget) -> None:
            ttk.Label(win, text=label).grid(row=r, column=0, sticky="nw", padx=10, pady=5)
            widget.grid(row=r, column=1, sticky="we", padx=10, pady=5)

        ip = ttk.Entry(win); ip.insert(0, cfg.ip)
        port = ttk.Entry(win); port.insert(0, cfg.port)
        key = ttk.Entry(win, show="*"); key.insert(0, cfg.api_key)
        model = self.model_combo = ttk.Combobox(win, values=cfg.models)
        model.set(cfg.model)
        chunk = ttk.Entry(win); chunk.insert(0, str(cfg.max_chunk_chars))
        prompt = tk.Text(win, height=5, width=48, wrap=tk.WORD); prompt.insert("1.0", cfg.translation_prompt)
        row("IP-Adresse:", 0, ip)
        row("Port:", 1, port)
        row("API-Schlüssel:", 2, key)
        row("Modell:", 3, model)
        row("Teilstücklänge (Zeichen):", 4, chunk)
        row("Prompt (nicht TranslateGemma):", 5, prompt)

        def load_models() -> None:
            client = LLMClient(f"http://{ip.get().strip()}:{port.get().strip()}", key.get())

            def work() -> None:
                try:
                    self._emit("models", client.list_models(), "")
                except Exception as exc:
                    self._emit("models", None, f"Modelle laden fehlgeschlagen: {exc}")

            self.status.set("Lade Modellliste…")
            threading.Thread(target=work, daemon=True).start()

        def save() -> None:
            try:
                chars = int(chunk.get())
            except ValueError:
                messagebox.showerror("Ungültiger Wert", "Teilstücklänge muss eine ganze Zahl sein.", parent=win)
                return
            cfg.ip, cfg.port, cfg.api_key = ip.get(), port.get(), key.get()
            cfg.model, cfg.max_chunk_chars = model.get().strip(), chars
            cfg.translation_prompt = prompt.get("1.0", tk.END).strip()
            cfg.save()
            win.destroy()

        buttons = ttk.Frame(win)
        buttons.grid(row=6, column=0, columnspan=2, pady=15)
        ttk.Button(buttons, text="Modelle laden", command=load_models).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Speichern", command=save).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Schließen", command=win.destroy).pack(side=tk.LEFT, padx=5)


def run() -> None:
    root = tk.Tk()
    TranslatorApp(root)
    root.mainloop()
