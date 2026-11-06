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

## Android-App

Im Ordner `android/` liegt eine native Android-App (Kotlin, Jetpack Compose) mit denselben Funktionen: Abschnitte mit Kontext,
Glossar (mehrere benannte Glossare), Streaming, Überschriften-Erkennung, Bearbeiten, Suchen/Ersetzen und Export nach
TXT, MD, DOCX, PDF und EPUB. Die Kernlogik ist aus dem Python-Programm nach Kotlin portiert (`android/app/src/main/kotlin/app/aitranslator/core`).

Es gibt zwei Wege zu übersetzen (Einstellungen → „Wo soll übersetzt werden?“):

- **LM Studio im Netzwerk**: schnell, braucht den PC.
- **Modell auf dem Gerät**: offline, TranslateGemma läuft mit llama.cpp direkt auf dem Handy. Die `.gguf`-Datei (z. B. 
  `google.translategemma-4b-it.Q4_K_S.gguf`, 2,4 GB) wird im Dateiwähler gewählt und **nicht kopiert**, sondern direkt gelesen. Gemessen auf
  einem Galaxy S21: Prompt 17 Token/s, Antwort 3,9 Token/s, also grob 5 Minuten je 3500-Zeichen-Abschnitt (ein Buch mit 50 Abschnitten
  braucht Stunden; die Restzeit-Schätzung zeigt es). Das Modell braucht rund 3 GB Arbeitsspeicher und wird nach jedem Lauf wieder freigegeben.
  Andere Apps vorher zu schließen hilft, sonst beendet Android die App bei Speichermangel (dann „Fortsetzen“).
  Gebaut wird llama.cpp nur, wenn der Quellordner da ist: einmal `sh scripts/fetch-llama-cpp.sh` im Ordner `android/` ausführen
  (lädt die feste Version b12164 nach `android/third_party`, nicht im Repo). Ohne den Ordner, oder mit `-PnoNative`, baut die App ohne dieses Backend.

Beim Netzwerk-Backend ist das Handy der **Client** für LM Studio auf dem PC: in LM Studio „Im lokalen Netzwerk bereitstellen“ aktivieren (und ggf. die
Firewall für den Port freigeben), in der App die IP des PCs, Port und API-Schlüssel eintragen, „Modelle laden“. Im Emulator ist der
PC unter `10.0.2.2` erreichbar. `LlmBackend` ist die Schnittstelle, hinter der beide Backends stehen.

Für lange Bücher auf dem Handy:

- **Bilder** aus EPUB und DOCX werden übernommen und stehen in der Übersetzung an derselben Absatzstelle (Platzhalter `[[Bild n]]`,
  das Modell sieht sie nie; im Export als echte Bilder in DOCX, PDF und EPUB, TXT/MD lassen sie weg). Aus PDF werden noch keine Bilder übernommen.
- **Restzeit-Schätzung** neben „Abschnitt x/y“, aus dem bisherigen Tempo hochgerechnet (nach Zeichen gewichtet).
- **Verbindungsstörungen**: kurze Aussetzer werden automatisch wiederholt (3 s, 10 s, 30 s). Bricht es trotzdem ab, zeigt ein Kasten
  den Fehler im Wortlaut, und „Fortsetzen“ macht beim ersten offenen Abschnitt weiter, auch nach einem Neustart der App.
- **Vordergrunddienst**: Während einer Übersetzung läuft ein Dienst mit Benachrichtigung („AI-Translator“, Fortschritt) sowie CPU- und
  WLAN-Sperre. Die Übersetzung läuft damit bei gesperrtem Bildschirm, nach dem Wechsel in eine andere App und auch dann weiter, wenn die
  Oberfläche geschlossen wird. Beim ersten Start fragt Android nach der Erlaubnis für Benachrichtigungen (ohne sie läuft es trotzdem).
  Schränkt ein Handy Apps im Ruhezustand dennoch ein, hilft in den Einstellungen „Akku-Optimierung ausschalten“. Solange die App vorn ist,
  bleibt der Bildschirm an. Bricht die Verbindung trotzdem ab, hilft „Fortsetzen“.

Unterschiede zum Desktop-Programm: Die Übersetzung steht in Abschnittskarten (Original einblendbar, „Neu“ übersetzt einen Abschnitt
neu) statt in zwei Textfeldern; die Sitzung wird automatisch gesichert und nach einem Neustart wiederhergestellt; das PDF hat keine
Lesezeichen (Androids `PdfDocument` bietet das nicht).

```bash
cd android
./gradlew assembleDebug            # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug             # auf ein angeschlossenes Gerät oder einen Emulator installieren
./gradlew testDebugUnitTest        # 130 Tests auf der JVM, ohne Gerät
./gradlew connectedDebugAndroidTest   # dieselben Tests + 11 Gerätetests auf Emulator/Gerät
```

Voraussetzungen: JDK 17 und Android SDK (`ANDROID_HOME`). Sind mehrere Geräte angeschlossen, vorher `ANDROID_SERIAL` setzen, sonst
installiert Gradle auf alle.

## Roadmap

- Token-Budget statt Zeichenzahl für die Abschnittsgröße.
- Android: PDF-Lesezeichen und Bilder aus PDF; Desktop-Programm auf den Stand der App bringen (Bilder, Restzeit, Fortsetzen, Wiederholung).
- Alternativ Online-Modelle (OpenAI-kompatibler Endpunkt) wählbar.
