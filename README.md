# AI-Translator

Offline-Übersetzer für Dokumente (TXT, MD, PDF, DOCX, EPUB) mit einem lokalen Modell über LM Studio
(oder einen anderen OpenAI-kompatiblen Server). Ausgelegt auf `google.translategemma-4b-it`.

## Start

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

In den Einstellungen IP, Port und API-Schlüssel (LM Studio verlangt einen Token: Developer-Einstellungen) eintragen, „Modelle laden“, Modell wählen.
Dann „Datei übersetzen…“. Links steht das Original, rechts die Übersetzung; sie erscheint beim Entstehen (Streaming, in den Einstellungen abschaltbar) und ist
frei bearbeitbar (Strg+Z, Suchen/Ersetzen mit Strg+F). Der Abschnitt, in dem der Cursor steht, ist links gelb hinterlegt;
Warnungen stehen in der Hinweisliste (Doppelklick springt hin). „Ergebnis speichern…“ exportiert den Text aus dem rechten Feld.

**Abschnittsgrenzen**: Abschnitte überlappen nicht. Stattdessen sieht das Modell die letzten Absätze des Vorgängers
(Original + Übersetzung) als Kontext, damit Namen und Ton über die Grenze hinweg gleich bleiben. Es muss nichts zusammengefügt werden.

**Glossar** („Glossar…“): Begriffe mit fester Übersetzung (z. B. Zaubertranknamen) anlegen, als Datei speichern/laden (ein Glossar je
Buch oder Reihe). Wirkt bei allen Modellen; fehlt ein Begriff im Ergebnis, erscheint ein Hinweis.

**Überschriften und Export**: Kapitelüberschriften werden beim Einlesen erkannt (Formatvorlagen, PDF-Schriftgröße, „Kapitel 1“,
„CHAPTER ONE“ …) und beim Export zu echten Überschriften: Word-Formatvorlagen, PDF-Lesezeichen, EPUB-Kapitel mit Inhaltsverzeichnis.
Absätze, *kursiv* und **fett** bleiben erhalten (TXT ohne Auszeichnung, MD mit).

## Aufbau

| Modul | Aufgabe |
|---|---|
| `translator/documents.py` | Dateien lesen (Absatzstruktur, Überschriften, Tabellen, EPUB in Lesereihenfolge) |
| `translator/chunking.py` | Text an Absatz-/Satzgrenzen in Abschnitte teilen, ohne Textverlust; Überschriften bleiben beim Folgeabsatz |
| `translator/pipeline.py` | Glossar, Übersetzung, Qualitätsprüfung (GUI-unabhängig) |
| `translator/llm_client.py` | HTTP-Client; Fehler als Exception, `finish_reason` wird ausgewertet |
| `translator/prompts.py` | TranslateGemma-Prompt (Template nachgebildet) und Prompts für andere Modelle |
| `translator/glossary.py` | Glossar lesen/speichern, passende Begriffe finden, Einhaltung prüfen |
| `translator/exporters.py` | Export nach TXT, MD, DOCX, PDF, EPUB (Überschriften, Absätze, Kapitel) |
| `translator/gui.py` | Tkinter-GUI, Worker-Thread über Queue entkoppelt |
| `translator/dialogs.py` | Glossar-Editor, Suchen/Ersetzen |

Tests: `python -m unittest discover -s tests` (benötigt `PYTHONPATH=.`).

## Roadmap

- Token-Budget statt Zeichenzahl für die Abschnittsgröße.
- Einzelnen Abschnitt neu übersetzen (mit dem Text des Vorgängers aus dem Textfeld als Kontext).
- Alternativ Online-Modelle (OpenAI-kompatibler Endpunkt) wählbar.
