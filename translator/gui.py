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
from .dialogs import FindReplaceDialog, GlossaryDialog
from .exporters import SAVE_FILETYPES, save_document
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

        self.sections: list[int] = []  # Abschnittsnummern in Reihenfolge; Marke 'sec<n>' in beiden Textfeldern
        self.warning_sections: list[int] = []  # Abschnitt je Zeile der Hinweisliste
        self.live: int | None = None  # Abschnitt, der gerade gestreamt wird

        root.title("AI-Translator")
        root.geometry("1100x700")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(1, weight=1)
        self._build_toolbar()
        self._build_panes()
        self._build_warnings()
        self._build_statusbar()
        root.bind("<Control-f>", lambda _e: self.open_find())
        root.bind("<Control-h>", lambda _e: self.open_find())
        root.protocol("WM_DELETE_WINDOW", self.close)
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
        ttk.Button(bar, text="Glossar…", command=self.open_glossary).pack(side=tk.RIGHT, padx=4)
        ttk.Button(bar, text="Suchen/Ersetzen…", command=self.open_find).pack(side=tk.RIGHT)
        ttk.Button(bar, text="Ergebnis speichern…", command=self.save_result).pack(side=tk.RIGHT, padx=4)

    def _lang_combo(self, parent, code: str) -> ttk.Combobox:
        combo = ttk.Combobox(parent, values=DISPLAY_LIST, state="readonly", width=18)
        combo.set(display_name(code))
        combo.pack(side=tk.LEFT, padx=(10, 4))
        combo.bind("<<ComboboxSelected>>", lambda _e: self._languages_changed())
        return combo

    def _build_panes(self) -> None:
        panes = ttk.PanedWindow(self.root, orient=tk.HORIZONTAL)
        panes.grid(row=1, column=0, sticky="nsew", padx=10)
        self.source_view = self._text_pane(panes, "Original", editable=False)
        self.output = self._text_pane(panes, "Übersetzung (frei bearbeitbar)", editable=True)
        self.source_view.tag_configure("current", background="#fff2a8", foreground="black")
        for event in ("<ButtonRelease-1>", "<KeyRelease>"):
            self.output.bind(event, lambda _e: self._highlight_source(self._section_at(self.output)))

    @staticmethod
    def _text_pane(panes: ttk.PanedWindow, title: str, editable: bool) -> tk.Text:
        frame = ttk.Frame(panes)
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(1, weight=1)
        ttk.Label(frame, text=title).grid(row=0, column=0, sticky="w")
        text = scrolledtext.ScrolledText(frame, wrap=tk.WORD, undo=editable, inactiveselectbackground="#b4d5fe")
        text.grid(row=1, column=0, sticky="nsew")
        if not editable:
            text.configure(state=tk.DISABLED)
        panes.add(frame, weight=1)
        return text

    def _build_warnings(self) -> None:
        frame = ttk.Frame(self.root)
        frame.grid(row=2, column=0, sticky="we", padx=10, pady=(6, 0))
        frame.columnconfigure(0, weight=1)
        ttk.Label(frame, text="Hinweise (Doppelklick springt zum Abschnitt)").grid(row=0, column=0, sticky="w")
        self.warnings = tk.Listbox(frame, height=3, activestyle="none")
        self.warnings.grid(row=1, column=0, sticky="we")
        self.warnings.bind("<Double-Button-1>", lambda _e: self._jump_to_warning())

    def _build_statusbar(self) -> None:
        frame = ttk.Frame(self.root, relief=tk.SUNKEN)
        frame.grid(row=3, column=0, sticky="we", padx=10, pady=8)
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
        if self._has_unsaved() and not messagebox.askyesno(
                "Ungespeichert", "Die aktuelle Übersetzung ist nicht gespeichert und wird ersetzt. Fortfahren?"):
            return
        self.cancel.clear()
        self._clear_results()
        self.progress.configure(value=0)
        self._set_running(True)
        config = dataclasses.replace(self.config)  # Schnappschuss für den Worker
        self.worker = threading.Thread(target=self._run_pipeline, args=(path, config), daemon=True)
        self.worker.start()

    def _set_running(self, running: bool) -> None:
        self.open_btn.configure(state=tk.DISABLED if running else tk.NORMAL)
        self.cancel_btn.configure(state=tk.NORMAL if running else tk.DISABLED)

    def _has_unsaved(self) -> bool:
        return bool(self.output.get("1.0", "end-1c").strip()) and bool(self.output.edit_modified())

    def close(self) -> None:
        if self._has_unsaved() and not messagebox.askyesno(
                "Ungespeichert", "Die Übersetzung ist nicht gespeichert. Programm trotzdem beenden?"):
            return
        self.cancel.set()
        self.root.destroy()

    def open_glossary(self) -> None:
        GlossaryDialog(self.root, self.config)

    def open_find(self) -> None:
        FindReplaceDialog(self.root, self.output)

    def save_result(self) -> None:
        content = self.output.get("1.0", "end-1c").strip()
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
            self.output.edit_modified(False)
            messagebox.showinfo("Gespeichert", path)

    # --- Abschnitte in den Textfeldern -------------------------------------------------------

    def _clear_results(self) -> None:
        for n in self.sections:
            for widget in (self.source_view, self.output):
                widget.mark_unset(f"sec{n}")
        self.sections.clear()
        self.live = None
        self.warning_sections.clear()
        self.warnings.delete(0, tk.END)
        self.source_view.configure(state=tk.NORMAL)
        self.source_view.delete("1.0", tk.END)
        self.source_view.configure(state=tk.DISABLED)
        self.output.delete("1.0", tk.END)
        self.output.edit_reset()
        self.output.edit_modified(False)

    def _add_section(self, widget: tk.Text, index: int, text: str) -> None:
        widget.configure(state=tk.NORMAL)
        if widget.get("1.0", "end-1c"):
            widget.insert(tk.END, "\n\n")
        widget.mark_set(f"sec{index}", "end-1c")
        widget.mark_gravity(f"sec{index}", tk.LEFT)  # Text am Abschnittsanfang bleibt im Abschnitt
        widget.insert(tk.END, text)
        if widget is self.source_view:
            widget.configure(state=tk.DISABLED)

    def _section_at(self, widget: tk.Text, index: str = tk.INSERT) -> int | None:
        found = None
        for n in self.sections:
            if not widget.compare(f"sec{n}", "<=", index):
                break
            found = n
        return found

    def _highlight_source(self, section: int | None) -> None:
        self.source_view.tag_remove("current", "1.0", tk.END)
        if section is None:
            return
        position = self.sections.index(section)
        end = f"sec{self.sections[position + 1]}" if position + 1 < len(self.sections) else tk.END
        self.source_view.tag_add("current", f"sec{section}", end)
        self.source_view.see(f"sec{section}")

    def _jump_to_warning(self) -> None:
        selected = self.warnings.curselection()
        if not selected:
            return
        section = self.warning_sections[selected[0]]
        self.output.mark_set(tk.INSERT, f"sec{section}")
        self.output.see(f"sec{section}")
        self.output.focus_set()
        self._highlight_source(section)

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

            if not config.is_translategemma:
                self._emit("status", "KI analysiert Begriffe…")
            glossary = pipeline.build_glossary(chunks, review=None if config.is_translategemma else self._review_glossary)
            self._emit("status", f"Glossar: {len(glossary)} Begriffe")

            on_delta = None
            if config.stream:
                def on_delta(chunk, delta):
                    self._emit("delta", chunk.index, chunk.text, delta, len(chunks))
            for result in pipeline.translate(chunks, glossary, self.cancel, on_delta):
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

    def _on_delta(self, index: int, source: str, delta: str, total: int) -> None:
        """Neues Textstück der laufenden Übersetzung ans Ende hängen (Abschnitt wird beim ersten Stück angelegt)."""
        if self.live != index:
            self.live = index
            self.sections.append(index)
            self._add_section(self.source_view, index, source)
            self._add_section(self.output, index, "")
            self._highlight_source(index)
            self.status.set(f"Abschnitt {index}/{total} wird übersetzt…")
        follow = self.output.yview()[1] >= 0.999  # nur mitscrollen, wenn der Benutzer am Ende steht
        self.output.insert(tk.END, delta)
        if follow:
            self.output.see(tk.END)

    def _close_live(self, reason: str) -> None:
        """Ein unterbrochener Abschnitt bleibt stehen, wird aber in der Hinweisliste markiert."""
        if self.live is not None:
            self.warnings.insert(tk.END, f"Abschnitt {self.live}: {reason}")
            self.warning_sections.append(self.live)
            self.live = None

    def _on_result(self, result: ChunkResult, total: int) -> None:
        if self.live == result.index:  # Streamtext durch das endgültige Ergebnis ersetzen
            start = f"sec{result.index}"
            if self.output.get(start, "end-1c") != result.translation:
                self.output.delete(start, "end-1c")
                self.output.insert(start, result.translation)
            self.live = None
        else:
            self.sections.append(result.index)
            self._add_section(self.source_view, result.index, result.source)
            self._add_section(self.output, result.index, result.translation)
        for warning in result.warnings:
            self.warnings.insert(tk.END, f"Abschnitt {result.index}: {warning}")
            self.warning_sections.append(result.index)
        self.output.see(tk.END)
        self.progress.configure(value=result.index * 100 / total)
        self.status.set(f"Abschnitt {result.index}/{total} übersetzt")

    def _on_done(self, text: str) -> None:
        self._close_live("unvollständig (abgebrochen)")
        self.status.set(text)
        self._set_running(False)
        self.output.edit_reset()  # Strg+Z soll Handarbeit zurücknehmen, nicht ganze Abschnitte

    def _on_error(self, text: str) -> None:
        self._close_live("unvollständig (Fehler)")
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
        stream = tk.BooleanVar(value=cfg.stream)
        stream_box = ttk.Checkbutton(win, text="Übersetzung beim Entstehen anzeigen", variable=stream)
        use_context = tk.BooleanVar(value=cfg.use_context)
        context = ttk.Checkbutton(win, text="Vorgängerabschnitt als Kontext mitsenden", variable=use_context)
        prompt = tk.Text(win, height=5, width=48, wrap=tk.WORD); prompt.insert("1.0", cfg.translation_prompt)
        row("IP-Adresse:", 0, ip)
        row("Port:", 1, port)
        row("API-Schlüssel:", 2, key)
        row("Modell:", 3, model)
        row("Teilstücklänge (Zeichen):", 4, chunk)
        row("Kontext:", 5, context)
        row("Anzeige:", 6, stream_box)
        row("Prompt (nicht TranslateGemma):", 7, prompt)

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
            cfg.stream, cfg.use_context = stream.get(), use_context.get()
            cfg.translation_prompt = prompt.get("1.0", tk.END).strip()
            cfg.save()
            win.destroy()

        buttons = ttk.Frame(win)
        buttons.grid(row=8, column=0, columnspan=2, pady=15)
        ttk.Button(buttons, text="Modelle laden", command=load_models).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Speichern", command=save).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Schließen", command=win.destroy).pack(side=tk.LEFT, padx=5)


def run() -> None:
    root = tk.Tk()
    TranslatorApp(root)
    root.mainloop()
