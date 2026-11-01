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
Dann „Datei übersetzen…“. Das Ergebnis erscheint abschnittsweise (mit Original-Anfang/-Ende zur Orientierung)
und kann im Textfeld von Hand korrigiert werden; „Ergebnis speichern…“ schreibt nur die Übersetzungen
(TXT, MD, DOCX, PDF, EPUB).

## Aufbau

| Modul | Aufgabe |
|---|---|
| `translator/documents.py` | Dateien lesen (Absatzstruktur, Überschriften, Tabellen, EPUB in Lesereihenfolge) |
| `translator/chunking.py` | Text an Absatz-/Satzgrenzen in Abschnitte teilen, ohne Textverlust |
| `translator/pipeline.py` | Glossar, Übersetzung, Qualitätsprüfung (GUI-unabhängig) |
| `translator/llm_client.py` | HTTP-Client; Fehler als Exception, `finish_reason` wird ausgewertet |
| `translator/prompts.py` | TranslateGemma-Prompt (Template nachgebildet) und Prompts für andere Modelle |
| `translator/exporters.py` | Abschnittsmarken und Export |
| `translator/gui.py` | Tkinter-GUI, Worker-Thread über Queue entkoppelt |

Tests: `python -m unittest discover -s tests` (benötigt `PYTHONPATH=.`).

## Roadmap

- **Überlappende Übersetzung**: Abschnitte überlappen und werden automatisch zusammengefügt
  (heute noch von Hand – siehe `Chunk` in `chunking.py`).
- Kontext-Strategie für lange Texte (Token-Budget statt Zeichenzahl, Vorgänger-Abschnitt als Kontext).
- Glossar auch für TranslateGemma.
- Alternativ Online-Modelle (OpenAI-kompatibler Endpunkt) wählbar.
