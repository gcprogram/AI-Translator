# AI-Translator – Projektkontext für Claude

Offline-Dokumentübersetzer (Tkinter) gegen einen LM-Studio-Server, Modell `google.translategemma-4b-it`
(Kontext ca. 8192 Token). Abgespalten aus einem früheren kombinierten Translator/Chat-Client
(Original zuletzt im Repo `gcprogram/Python`, Ordner `AIChat-Client`, Commit `217b144`).
Der Chat-Teil lebt jetzt in `gcprogram/AI-Chat-MCP`.

## Regeln des Besitzers
- **Kein Name, keine E-Mail im Code, in Commits, READMEs oder Kommentaren.** Git-Identität ist die
  GitHub-noreply-Adresse (global gesetzt) – nicht ändern. Vor jedem Push `git log --format='%an %ae'` prüfen.
- Commits nur auf Wunsch; Commit-Nachrichten englisch, Code-Kommentare/UI deutsch.
- Kommunikation auf Deutsch.

## Entscheidungen
- **Backend bleibt LM Studio über HTTP** (OpenAI-kompatibel). `translator/llm_client.py` ist die Abstraktion;
  eigenständiges Laden des Modells wurde bewusst verworfen (später ggf. `llama-server` als Unterprozess).
- Das TranslateGemma-Template ist in `translator/prompts.py::gemma_prompt()` nachgebildet und läuft über
  `/v1/completions` (Roh-Prompt). Die Jinja-Datei (`jinja_template_aus_LM-Studio.txt`, gitignored) ist nur Referenz.
- LM Studio verlangt einen **API-Token** (Developer-Einstellungen). Eintragen in den Einstellungen
  (`config.json`, gitignored) oder Umgebungsvariable `LM_API_TOKEN` für `scripts/check_server.py`.
  Token niemals ins Repo oder in Chat-Ausgaben schreiben.

## Tests / Start
```bash
python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
set PYTHONPATH=. && python -m unittest discover -s tests      # 26 Tests, brauchen keinen LM-Studio-Server
python main.py
```

## Offene Aufgaben (Reihenfolge)
1. **BOS prüfen**: `python scripts/check_server.py` (LM Studio läuft, Token nötig). Ergibt, ob `gemma_prompt()` ein
   `<bos>` braucht. Ergebnis hier festhalten und Prompt ggf. anpassen. Zugleich erste echte Probeübersetzung beurteilen.
2. **Überlappende Übersetzung** (Kernproblem, bisher manuelles Zusammenfügen): Modelle sind nicht deterministisch
   über Abschnittsgrenzen. Zu entwerfen/entscheiden: (a) Abschnitte überlappen, beide Übersetzungen der
   Überlappung per Satz-Ausrichtung abgleichen und eine wählen; oder (b) Vorgänger-Abschnitt (Original +
   Übersetzung) als Kontext voranstellen und nur die neuen Sätze übernehmen. `Chunk` in `chunking.py` hat dafür
   noch kein Überlappungsfeld (bewusst, YAGNI).
3. Chunk-Größe in **Token** statt Zeichen (Budget: Prompt + Ausgabe ≤ ~8192; `MAX_OUTPUT_TOKENS` = 4096 in `pipeline.py`).
4. Glossar für TranslateGemma (Template hat kein Feld; evtl. Vor-/Nachbearbeitung).
5. Online-Modell wählbar (andere Base-URL + Chat-Endpunkt; TranslateGemma-Prompt nur für lokales Modell).
6. Bekannt: Satzteilung trennt Abkürzungen wie „z. B.“ falsch.

## Aufbau
`translator/`: `documents.py` (lesen) · `chunking.py` · `pipeline.py` (ohne GUI) · `llm_client.py` · `prompts.py` ·
`glossary.py` · `exporters.py` (Abschnittsmarken `--- BEGINN ABSCHNITT n`, Export) · `config.py` · `gui.py`
(Worker-Thread → Queue → GUI). Nicht getestet: echte LM-Studio-Antworten und das Aussehen der GUI im Alltag.
