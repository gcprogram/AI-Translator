# AI-Translator – Projektkontext für Claude

Offline-Dokumentübersetzer (Tkinter) gegen einen LM-Studio-Server, Modell `google.translategemma-4b-it`
(Kontext ca. 8192 Token). Abgespalten aus einem früheren kombinierten Translator/Chat-Client
(Original zuletzt im Repo `gcprogram/Python`, Ordner `AIChat-Client`, Commit `217b144`).
Der Chat-Teil lebt jetzt in `gcprogram/AI-Chat-MCP`.

## Regeln des Besitzers
- **Kein Name, keine E-Mail im Code, in Commits, READMEs oder Kommentaren.** Git-Identität ist die
  GitHub-noreply-Adresse (global gesetzt) – nicht ändern. Vor jedem Push `git log --format='%an %ae'` prüfen.
- Nach erfolgreichen Tests immer committen und pushen (ohne Nachfrage; vorher Autoren prüfen, s. o.). Commit-Nachrichten englisch, Code-Kommentare/UI deutsch.
- Alle Dateien im Unix-Format (LF), siehe `.gitattributes`; nur `*.bat`, `*.cmd`, `*.ps1` dürfen CRLF haben.
- Kommunikation auf Deutsch.

## Entscheidungen
- **Backend bleibt LM Studio über HTTP** (OpenAI-kompatibel). `translator/llm_client.py` ist die Abstraktion;
  eigenständiges Laden des Modells wurde bewusst verworfen (später ggf. `llama-server` als Unterprozess).
- Das TranslateGemma-Template ist in `translator/prompts.py::gemma_prompt()` nachgebildet und läuft über
  `/v1/completions` (Roh-Prompt). Die Jinja-Datei (`jinja_template_aus_LM-Studio.txt`, gitignored) ist nur Referenz.
- **Abschnittsgrenzen: keine Überlappung, sondern Kontext.** Abschnitte überlappen nicht; stattdessen wird der Vorgänger
  (letzte Absätze, ca. 1200 Zeichen, Original + Übersetzung) als bereits beantworteter Gesprächszug vor die Anfrage gestellt
  (`prompts.gemma_prompt(context=...)`, `pipeline.build_context`). Es gibt nichts zusammenzufügen, daher auch kein Abzählen
  von Sätzen (scheitert, wenn ein Modell einen Satz teilt) und keinen zweiten KI-Durchlauf. Kontext nur bei gleicher
  Absatzzahl in Original und Übersetzung (sonst unsichere Zuordnung). Abschaltbar: Einstellungen → Kontext.
  Abschnitte enden an Absätzen, Kapitel (# / ##) beginnen nach Möglichkeit einen neuen Abschnitt.
- **Streaming**: `LLMClient.complete/chat(on_delta=...)` liest SSE; die Pipeline streamt nur den ersten Versuch eines Abschnitts
  (`translate(on_delta=...)`). Das Endergebnis (Retry, Teilung, `#`-Korrektur, Kontext-Echo entfernt) ersetzt den Streamtext;
  Abbrechen wirkt mitten im Abschnitt, der Rest bleibt stehen und steht in der Hinweisliste. Gegen TranslateGemma in LM Studio geprüft.
  Abschaltbar (Einstellungen → Anzeige).
- **Glossar** (Datei `Begriff: Übersetzung`, Dialog „Glossar…“, `config.glossary_path`) gilt für alle Modelle. Nur Begriffe, die im
  Abschnitt vorkommen, kommen als Satz in den Gemma-Prompt bzw. System-Prompt; fehlt die Übersetzung im Ergebnis, gibt es
  einen Hinweis (und einen Wiederholversuch). TranslateGemma schlägt keine Begriffe vor (kein Chat-Modell), dort pflegt man sie selbst.
- **Überschriften**: `documents.py` macht sie zu Markdown (Formatvorlagen, PDF-Schriftgröße, Muster wie „Kapitel 1“/„CHAPTER ONE“/
  GROSSBUCHSTABEN); `chunking.restore_headings` setzt verlorene `#` nach der Übersetzung wieder ein; `exporters.py` erzeugt daraus
  DOCX-Überschriftenformate, PDF-Lesezeichen und EPUB-Kapitel mit Inhaltsverzeichnis. Die alten Abschnittsmarken im Textfeld gibt es nicht mehr.
- **Android-App** in `android/` (native Kotlin + Compose, Paket `app.aitranslator`): Kern aus Python portiert
  (`core/`: Chunking, Prompts, Glossar, Pipeline als `Flow`, `LmStudioBackend` mit OkHttp/SSE; `io/`: DocumentReader, Exporters,
  PdfExporter, PdfTextExtractor; `data/`: Einstellungen, Glossare, Sitzungssicherung; `ui/`). Handy = Client für LM Studio im WLAN;
  `LlmBackend` ist die Naht für ein späteres On-Device-Modell (noch nicht gebaut). Änderungen an der Kernlogik gehören in **beide**
  Implementierungen (Python und Kotlin), die Tests spiegeln sich 1:1.
  Bilder: Im Text stehen sie als eigener Absatz `[[Bild n]]` (`core/Images.kt`); `Pipeline.translateChunk` nimmt sie vor dem Modellaufruf
  heraus und setzt sie danach an dieselbe Absatzposition zurück (proportional, wenn das Modell die Absatzzahl ändert). Die Dateien liegen
  in `filesDir/images` (`ImageStore`), nicht im Speicher. Gelesen aus EPUB (img/svg-image in Dokumentreihenfolge) und DOCX (Beziehungen),
  geschrieben in DOCX/EPUB/PDF; aus PDF über den PDFBox-Operator Do mit Position (Mindestgröße gegen Zierpunkte); PDF-Lesezeichen entstehen nachträglich mit PDFBox (`PdfBookmarks`). Fortsetzen: `SessionData.pending` hält die noch offenen Abschnitte; `LlmException.transient`
  steuert die automatische Wiederholung (`CONNECTION_RETRY_DELAYS_MS`). Restzeit: `core/Eta.kt`.
  **Modell auf dem Gerät** (`ondevice/`, `app/src/main/cpp`): llama.cpp (fest b12164, `scripts/fetch-llama-cpp.sh` lädt es nach
  `android/third_party`, gitignored) als JNI-Bibliothek. `OnDeviceBackend` implementiert `LlmBackend.complete()` mit dem Roh-Prompt von
  TranslateGemma (`chat()` ist nicht unterstützt). Das Modell wird über einen Dateideskriptor geladen (`llama_model_load_from_file_ptr`
  mit `dup`/`fdopen`): `/proc/self/fd/N` zu öffnen verbietet Android für Dateien aus dem Dateiwähler. Keine Kopie (Handy war zu 95 % voll).
  Kontext 4096 Token (KV-Zwischenspeicher kostet Arbeitsspeicher), 4 Threads, nur CPU, `armv8.2-a+dotprod`. UTF-8-Grenzen und
  Stop-Wörter stehen in Kotlin (`Text.kt`, testbar). `OnDeviceEngine.releaseWhenIdle()` gibt das Modell nach jedem Lauf frei:
  sonst hielt die App ~3 GB und `lmkd` beendete sie im Hintergrund. Gemessen (Galaxy S21): 17 Token/s Prompt, 3,9 Token/s Antwort.
  Gradle baut die native Bibliothek nur, wenn `third_party/llama.cpp` existiert und `-PnoNative` fehlt; `-Pabis=arm64-v8a` baut nur ARM.
  Stolpersteine, die schon aufgetreten sind: (00) llama.cpp ändert die C-API oft (z. B. `use_mmap` wurde zu `load_mode`): beim Wechsel der
  Version `llama_jni.cpp` gegen `include/llama.h` prüfen. (0) Textdateien mit Windows-Zeilenenden (`\r\n`) erzeugten keine Absätze (Absatzerkennung
  verlangt `\n\n`): `readText` normalisiert jetzt, in Kotlin und Python (`_read_text`). (1) Androids Regex (ICU) kennt keine Flags wie `(?U)` und definiert `\s`/`\b` anders als die
  JVM; deshalb `WS = [\s\p{Z}]` und explizite Klassen, nie `(?U)`. (2) Testnamen mit Leerzeichen (Backticks) lassen sich bei minSdk 26
  nicht dexen: camelCase. (3) PDFBox-Android und `PdfDocument` laufen nur auf einem Gerät; die JVM-Tests werden deshalb zusätzlich als
  Gerätetests ausgeführt (`sourceSets.androidTest` teilt `src/test/kotlin`). (4) Sind Handy und Emulator gleichzeitig angeschlossen:
  `ANDROID_SERIAL=emulator-5554` setzen und alle `adb`-Aufrufe mit `-s`, nichts ungefragt auf das echte Gerät installieren.
- LM Studio verlangt einen **API-Token** (Developer-Einstellungen). Eintragen in den Einstellungen
  (`config.json`, gitignored) oder Umgebungsvariable `LM_API_TOKEN` für `scripts/check_server.py`.
  Token niemals ins Repo oder in Chat-Ausgaben schreiben.

## Tests / Start
```bash
python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
set PYTHONPATH=. && python -m unittest discover -s tests      # 56 Tests, brauchen keinen LM-Studio-Server
python main.py
```
Android (`cd android`, JDK 17, `ANDROID_HOME` gesetzt):
```bash
./gradlew testDebugUnitTest            # 133 JVM-Tests
./gradlew connectedDebugAndroidTest    # 146 Tests auf Emulator/Gerät (zuvor ANDROID_SERIAL setzen); AVD z. B. aitranslator_test (API 36)
```

## Offene Aufgaben (Reihenfolge)
1. **BOS prüfen**: `python scripts/check_server.py` (LM Studio läuft, Token nötig). Ergibt, ob `gemma_prompt()` ein
   `<bos>` braucht. Ergebnis hier festhalten und Prompt ggf. anpassen. Zugleich erste echte Probeübersetzung beurteilen.
2. **Kontext-Strategie am echten Modell prüfen** (umgesetzt, aber nur mit Fake-Client getestet): Folgt TranslateGemma dem
   Mehr-Zug-Prompt (Vorgänger als Gesprächszug) und dem Glossar-Satz im Anweisungstext, ohne den Kontext mitzuübersetzen?
   Mit/ohne Kontext (Einstellungen) an einem Kapitelübergang vergleichen; Ergebnis hier festhalten.
3. Chunk-Größe in **Token** statt Zeichen (Budget: Prompt + Ausgabe ≤ ~8192; `MAX_OUTPUT_TOKENS` = 4096 in `pipeline.py`).
4. Online-Modell wählbar (andere Base-URL + Chat-Endpunkt; TranslateGemma-Prompt nur für lokales Modell).
5. Bekannt: Satzteilung trennt Abkürzungen wie „z. B.“ falsch.
6. **Android am echten Setup prüfen**: App auf dem Handy gegen LM Studio im WLAN (IP, Port, Token, Firewall) und mit einem echten
   Buch (PDF/EPUB) ausprobieren; bisher nur Emulator gegen einen Fake-Server und JVM/Gerätetests. Der Benutzer hatte beim ersten
   langen Lauf einen Verbindungsfehler (Wortlaut unbekannt); vermutete Ursache: Handy gesperrt oder WLAN/PC im Ruhezustand.
   Vordergrunddienst (`TranslationService`: Benachrichtigung, Wake- und WLAN-Sperre, Typ dataSync) ist gebaut; das ViewModel gehört der
   Anwendung (`TranslatorApplication` ist `ViewModelStoreOwner`), damit der Lauf das Schließen der Activity übersteht. Auf dem Emulator geprüft
   (Zurück-Taste, Bildschirm aus: Lauf vollständig, Sperren danach frei); **echter Doze-Ruhezustand auf dem Handy noch nicht geprüft**
   (Knopf „Akku-Optimierung ausschalten“ in den Einstellungen als Rückfall). Android 15 begrenzt dataSync-Dienste auf rund 6 Stunden je Tag.
   On-Device-Backend ist gebaut und auf dem Galaxy S21 mit dem echten Modell geprüft (Abschnitt übersetzt, Speicher danach frei).
   Offen: längerer Lauf auf dem Handy (viele Abschnitte, Hitze/Akku). Desktop-Programm hat (noch) keine Bilder, Restzeit-Schätzung,
   Fortsetzen und Verbindungswiederholung.

## Aufbau
`translator/`: `documents.py` (lesen, Überschriften erkennen) · `chunking.py` · `pipeline.py` (ohne GUI) · `llm_client.py` ·
`prompts.py` · `glossary.py` · `exporters.py` (Blöcke → TXT/MD/DOCX/PDF/EPUB) · `config.py` · `gui.py` (Worker-Thread → Queue → GUI;
Original | Übersetzung nebeneinander, Abschnittsmarken `sec<n>` als Tk-Marks) · `dialogs.py` (Glossar-Editor, Suchen/Ersetzen). Nicht getestet: echte LM-Studio-Antworten und das Aussehen der GUI im Alltag.
