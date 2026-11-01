"""Hilfsdialoge der GUI: Glossar-Editor und Suchen/Ersetzen im Übersetzungstext."""
from __future__ import annotations

import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from .config import Config
from .glossary import GLOSSARY_PATH, load_glossary, parse_glossary, save_glossary

_GLOSSARY_TYPES = [("Glossar", "*.txt"), ("Alle Dateien", "*.*")]


class GlossaryDialog:
    """Begriffe anlegen, ändern, löschen; als Datei speichern/laden (z. B. ein Glossar je Buchreihe).
    Die zuletzt gespeicherte Datei gilt für alle weiteren Übersetzungen."""

    def __init__(self, parent: tk.Misc, config: Config):
        self.config = config
        self.path = Path(config.glossary_path or GLOSSARY_PATH)
        win = self.win = tk.Toplevel(parent)
        win.title("Glossar")
        win.geometry("560x520")
        win.columnconfigure(0, weight=1)
        win.rowconfigure(1, weight=1)

        self.path_var = tk.StringVar(value=str(self.path))
        ttk.Label(win, textvariable=self.path_var, foreground="gray").grid(row=0, column=0, sticky="w", padx=10, pady=(8, 0))

        frame = ttk.Frame(win)
        frame.grid(row=1, column=0, sticky="nsew", padx=10, pady=5)
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(0, weight=1)
        self.tree = ttk.Treeview(frame, columns=("term", "translation"), show="headings", selectmode="browse")
        self.tree.heading("term", text="Begriff (Original)")
        self.tree.heading("translation", text="Übersetzung")
        self.tree.column("term", width=240)
        self.tree.column("translation", width=240)
        scroll = ttk.Scrollbar(frame, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.grid(row=0, column=0, sticky="nsew")
        scroll.grid(row=0, column=1, sticky="ns")
        self.tree.bind("<<TreeviewSelect>>", self._select)

        form = ttk.Frame(win)
        form.grid(row=2, column=0, sticky="we", padx=10, pady=5)
        form.columnconfigure((0, 1), weight=1)
        self.term = ttk.Entry(form)
        self.translation = ttk.Entry(form)
        self.term.grid(row=0, column=0, sticky="we", padx=(0, 4))
        self.translation.grid(row=0, column=1, sticky="we", padx=4)
        self.translation.bind("<Return>", lambda _e: self._apply())
        self.term.bind("<Return>", lambda _e: self.translation.focus_set())
        ttk.Button(form, text="Übernehmen", command=self._apply).grid(row=0, column=2, padx=4)
        ttk.Button(form, text="Löschen", command=self._delete).grid(row=0, column=3)

        files = ttk.Frame(win)
        files.grid(row=3, column=0, sticky="we", padx=10, pady=5)
        ttk.Button(files, text="Öffnen…", command=self._open).pack(side=tk.LEFT)
        ttk.Button(files, text="Speichern unter…", command=self._save_as).pack(side=tk.LEFT, padx=4)
        ttk.Button(files, text="Liste einfügen", command=self._paste).pack(side=tk.LEFT)
        ttk.Button(files, text="Abbrechen", command=win.destroy).pack(side=tk.RIGHT)
        ttk.Button(files, text="Speichern", command=self._save).pack(side=tk.RIGHT, padx=4)

        self._fill(load_glossary(self.path))
        win.transient(parent)
        self.term.focus_set()

    def entries(self) -> dict[str, str]:
        return {self.tree.set(i, "term"): self.tree.set(i, "translation") for i in self.tree.get_children()}

    def _fill(self, glossary: dict[str, str]) -> None:
        self.tree.delete(*self.tree.get_children())
        for term in sorted(glossary, key=str.lower):
            self.tree.insert("", tk.END, values=(term, glossary[term]))

    def _select(self, _event=None) -> None:
        selected = self.tree.selection()
        if not selected:
            return
        for entry, column in ((self.term, "term"), (self.translation, "translation")):
            entry.delete(0, tk.END)
            entry.insert(0, self.tree.set(selected[0], column))

    def _apply(self) -> None:
        term, translation = self.term.get().strip(), self.translation.get().strip()
        if not term or not translation:
            return
        merged = {t: tr for t, tr in self.entries().items() if t.lower() != term.lower()}
        merged[term] = translation
        self._fill(merged)
        self.term.delete(0, tk.END)
        self.translation.delete(0, tk.END)
        self.term.focus_set()

    def _delete(self) -> None:
        for item in self.tree.selection():
            self.tree.delete(item)

    def _paste(self) -> None:
        try:
            added = parse_glossary(self.win.clipboard_get())
        except tk.TclError:
            added = {}
        if not added:
            messagebox.showinfo("Liste einfügen", "Die Zwischenablage enthält keine Zeilen 'Begriff: Übersetzung'.",
                                parent=self.win)
            return
        keep = {t: tr for t, tr in self.entries().items() if t.lower() not in {a.lower() for a in added}}
        self._fill({**keep, **added})

    def _open(self) -> None:
        path = filedialog.askopenfilename(parent=self.win, filetypes=_GLOSSARY_TYPES)
        if path:
            self.path = Path(path)
            self.path_var.set(path)
            self._fill(load_glossary(self.path))

    def _save_as(self) -> None:
        path = filedialog.asksaveasfilename(parent=self.win, defaultextension=".txt", filetypes=_GLOSSARY_TYPES)
        if path:
            self.path = Path(path)
            self.path_var.set(path)
            self._save()

    def _save(self) -> None:
        self._apply()  # noch nicht übernommene Eingabe nicht verlieren
        try:
            save_glossary(self.entries(), self.path)
        except OSError as exc:
            messagebox.showerror("Glossar", f"Speichern fehlgeschlagen: {exc}", parent=self.win)
            return
        self.config.glossary_path = "" if self.path == GLOSSARY_PATH else str(self.path)
        self.config.save()
        self.win.destroy()


class FindReplaceDialog:
    """Suchen/Ersetzen in einem Text-Widget; 'Alle ersetzen' ist ein einziger Undo-Schritt."""

    def __init__(self, parent: tk.Misc, text: tk.Text):
        self.text = text
        win = self.win = tk.Toplevel(parent)
        win.title("Suchen und Ersetzen")
        win.transient(parent)
        win.resizable(False, False)
        self.find = ttk.Entry(win, width=32)
        self.replacement = ttk.Entry(win, width=32)
        self.match_case = tk.BooleanVar(value=False)
        self.message = tk.StringVar()
        ttk.Label(win, text="Suchen:").grid(row=0, column=0, sticky="w", padx=10, pady=(10, 4))
        ttk.Label(win, text="Ersetzen durch:").grid(row=1, column=0, sticky="w", padx=10, pady=4)
        self.find.grid(row=0, column=1, padx=10, pady=(10, 4))
        self.replacement.grid(row=1, column=1, padx=10, pady=4)
        ttk.Checkbutton(win, text="Groß-/Kleinschreibung beachten", variable=self.match_case).grid(
            row=2, column=1, sticky="w", padx=10)
        ttk.Label(win, textvariable=self.message, foreground="gray").grid(row=3, column=0, columnspan=2, sticky="w", padx=10)
        buttons = ttk.Frame(win)
        buttons.grid(row=4, column=0, columnspan=2, pady=10)
        ttk.Button(buttons, text="Weitersuchen", command=self.find_next).pack(side=tk.LEFT, padx=4)
        ttk.Button(buttons, text="Ersetzen", command=self.replace).pack(side=tk.LEFT, padx=4)
        ttk.Button(buttons, text="Alle ersetzen", command=self.replace_all).pack(side=tk.LEFT, padx=4)
        self.find.bind("<Return>", lambda _e: self.find_next())
        win.bind("<Escape>", lambda _e: win.destroy())
        if text.tag_ranges(tk.SEL):
            self.find.insert(0, text.get(tk.SEL_FIRST, tk.SEL_LAST).splitlines()[0])
        self.find.focus_set()

    def _search(self, start: str, stop: str) -> str:
        return self.text.search(self.find.get(), start, stopindex=stop, nocase=not self.match_case.get())

    def find_next(self) -> bool:
        pattern = self.find.get()
        if not pattern:
            return False
        start = self.text.index(tk.SEL_LAST) if self.text.tag_ranges(tk.SEL) else self.text.index(tk.INSERT)
        pos = self._search(start, tk.END) or self._search("1.0", tk.END)  # am Ende von vorn weitersuchen
        if not pos:
            self.message.set("Nicht gefunden.")
            return False
        end = f"{pos}+{len(pattern)}c"
        self.text.tag_remove(tk.SEL, "1.0", tk.END)
        self.text.tag_add(tk.SEL, pos, end)
        self.text.mark_set(tk.INSERT, end)
        self.text.see(pos)
        self.message.set("")
        return True

    def replace(self) -> None:
        pattern = self.find.get()
        if self.text.tag_ranges(tk.SEL) and pattern:
            selected = self.text.get(tk.SEL_FIRST, tk.SEL_LAST)
            if selected == pattern or (not self.match_case.get() and selected.lower() == pattern.lower()):
                start = self.text.index(tk.SEL_FIRST)
                self.text.delete(tk.SEL_FIRST, tk.SEL_LAST)
                self.text.insert(start, self.replacement.get())
        self.find_next()

    def replace_all(self) -> None:
        pattern, replacement = self.find.get(), self.replacement.get()
        if not pattern:
            return
        count, pos = 0, "1.0"
        self.text.edit_separator()
        self.text.configure(autoseparators=False)  # sonst wäre jede Ersetzung ein eigener Undo-Schritt
        try:
            while True:
                pos = self._search(pos, tk.END)
                if not pos:
                    break
                self.text.delete(pos, f"{pos}+{len(pattern)}c")
                self.text.insert(pos, replacement)
                pos = f"{pos}+{len(replacement)}c"
                count += 1
        finally:
            self.text.configure(autoseparators=True)
            self.text.edit_separator()
        self.message.set(f"{count} Ersetzung(en).")
