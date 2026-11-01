import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from translator.chunking import restore_headings, split_into_chunks
from translator.config import Config
from translator.documents import mark_headings, read_document
from translator.exporters import chapter_level, parse_blocks, save_document
from translator.glossary import merge_glossary, missing_terms, parse_glossary, relevant_terms
from translator.languages import code_from_display, language_name
from translator.llm_client import Completion, LLMClient, LLMError
from translator.pipeline import Cancelled, TranslationPipeline, build_context
from translator.prompts import gemma_prompt


def words(text):
    return text.split()


class ChunkingTest(unittest.TestCase):
    def test_no_text_lost_and_size_respected(self):
        text = "\n\n".join(f"Satz eins von Absatz {i}. Satz zwei! Satz drei? " * 8 for i in range(30))
        chunks = split_into_chunks(text, 700)
        self.assertGreater(len(chunks), 3)
        self.assertEqual(words(" ".join(c.text for c in chunks)), words(text))
        self.assertTrue(all(len(c.text) <= 700 for c in chunks))
        self.assertEqual([c.index for c in chunks], list(range(1, len(chunks) + 1)))

    def test_overlong_paragraph_is_split_at_sentences_not_truncated(self):
        text = " ".join(f"Das ist Satz Nummer {i}." for i in range(200))
        chunks = split_into_chunks(text, 500)
        self.assertEqual(words(" ".join(c.text for c in chunks)), words(text))
        self.assertTrue(all(c.text.endswith(".") for c in chunks))

    def test_single_overlong_sentence_is_hard_split(self):
        text = "wort " * 500
        chunks = split_into_chunks(text, 300)
        self.assertEqual(words(" ".join(c.text for c in chunks)), words(text))
        self.assertTrue(all(len(c.text) <= 300 for c in chunks))

    def test_cjk_without_spaces(self):
        text = "这是一个句子。" * 300
        chunks = split_into_chunks(text, 200)
        self.assertEqual("".join(c.text for c in chunks), text)

    def test_small_limit_and_empty(self):
        self.assertEqual(split_into_chunks("", 100), [])
        self.assertEqual(len(split_into_chunks("Hallo Welt. Noch ein Satz.", 50)), 1)
        text = "Eins. Zwei. Drei. Vier. Fünf."
        self.assertEqual(words(" ".join(c.text for c in split_into_chunks(text, 8))), words(text))

    def test_heading_stays_with_following_paragraph(self):
        body = "Ein Absatz mit Text. " * 5  # ~105 Zeichen
        text = f"{body}\n\n## Kapitel\n\n{body}"
        for limit in (120, 130, 220):
            chunks = split_into_chunks(text, limit)
            self.assertEqual(words(" ".join(c.text for c in chunks)), words(text))
            self.assertFalse(any(c.text.endswith("Kapitel") for c in chunks), limit)

    def test_chapter_starts_new_chunk_once_chunk_is_filled(self):
        body = "Satz eins. Satz zwei. Satz drei.\n\n" * 10
        text = f"# Eins\n\n{body}# Zwei\n\n{body}"
        chunks = split_into_chunks(text, 1000)
        self.assertEqual(len(chunks), 2)
        self.assertTrue(chunks[1].text.startswith("# Zwei"))

    def test_restore_headings(self):
        source = "# Titel\n\nText.\n\n## Unter\n\nMehr."
        self.assertEqual(restore_headings(source, "Titel\n\nText.\n\nUnter\n\nMehr."),
                         "# Titel\n\nText.\n\n## Unter\n\nMehr.")
        broken = "Titel Text.\n\nMehr."  # Absatzzahl passt nicht: unverändert lassen
        self.assertEqual(restore_headings(source, broken), broken)


class ConfigAndLanguageTest(unittest.TestCase):
    def test_corrupt_config_gives_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text("{kaputt", encoding="utf-8")
            self.assertEqual(Config.load(path).port, "1234")

    def test_legacy_language_codes_are_normalized(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(json.dumps({"lang_target": "de-DE", "max_chunk_chars": "x", "foo": 1}), encoding="utf-8")
            cfg = Config.load(path)
            self.assertEqual(cfg.lang_target, "de")
            self.assertEqual(cfg.max_chunk_chars, 3500)
            cfg.save(path)
            self.assertEqual(Config.load(path), cfg)

    def test_language_helpers(self):
        self.assertEqual(language_name("de-DE"), "German")
        self.assertEqual(language_name("xx"), "xx")  # kein Platzhalter-Text
        self.assertEqual(code_from_display("German (de)"), "de")

    def test_gemma_prompt_uses_real_language_names(self):
        prompt = gemma_prompt("Hello.", "en", "de-DE")
        self.assertIn("English (en) to German (de) translator", prompt)
        self.assertTrue(prompt.endswith("<start_of_turn>model\n"))
        self.assertNotIn("exactly as given", prompt)

    def test_gemma_prompt_with_glossary_and_context(self):
        prompt = gemma_prompt("Next.", "en", "de", {"Polyjuice Potion": "Vielsafttrank"}, ("Prev.", "Vorher."))
        self.assertIn("Polyjuice Potion = Vielsafttrank", prompt)
        # Vorgänger als bereits beantworteter Zug, danach die eigentliche Anfrage
        self.assertEqual(prompt.count("<start_of_turn>user"), 2)
        self.assertLess(prompt.index("Prev."), prompt.index("<start_of_turn>model\nVorher.<end_of_turn>"))
        self.assertLess(prompt.index("Vorher."), prompt.index("Next."))
        self.assertTrue(prompt.endswith("Next.<end_of_turn>\n<start_of_turn>model\n"))


class GlossaryTest(unittest.TestCase):
    def test_parse_keeps_case_and_strips_bullets(self):
        g = parse_glossary("- NASA: NASA\n1. **iPhone**: iPhone\nkein Eintrag\nLeer:\n")
        self.assertEqual(g, {"NASA": "NASA", "iPhone": "iPhone"})

    def test_merge_existing_wins_case_insensitive(self):
        merged = merge_glossary({"Orc": "Ork"}, {"orc": "Orks", "Elf": "Elfe"})
        self.assertEqual(merged, {"Orc": "Ork", "Elf": "Elfe"})

    def test_relevant_terms_matches_plural_and_case_but_not_inside_words(self):
        glossary = {"Potion": "Trank", "Orc": "Ork", "Elf": "Elfe"}
        self.assertEqual(relevant_terms(glossary, "Two potions and an ORC; the shelf."), {"Potion": "Trank", "Orc": "Ork"})

    def test_missing_terms_tolerates_german_inflection(self):
        terms = {"Polyjuice Potion": "Vielsafttrank", "Orc": "Ork"}
        self.assertEqual(missing_terms(terms, "Er trank den Rest des Vielsafttranks. Ork."), [])
        self.assertEqual(missing_terms(terms, "Er trank den Vielfruchttrank. Ork."), ["Polyjuice Potion"])


class DocumentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_txt_encodings(self):
        (self.dir / "a.txt").write_bytes("Grüße aus Köln".encode("cp1252"))
        (self.dir / "b.txt").write_bytes("﻿Grüße".encode("utf-8"))
        self.assertEqual(read_document(self.dir / "a.txt"), "Grüße aus Köln")
        self.assertEqual(read_document(self.dir / "b.txt"), "Grüße")

    def test_unsupported(self):
        with self.assertRaises(ValueError):
            read_document(self.dir / "x.xyz")

    def test_docx_headings_and_tables(self):
        from docx import Document
        doc = Document()
        doc.add_heading("Titel", level=2)
        doc.add_paragraph("Ein Absatz.")
        table = doc.add_table(rows=1, cols=2)
        table.rows[0].cells[0].text, table.rows[0].cells[1].text = "A", "B"
        doc.save(self.dir / "t.docx")
        self.assertEqual(read_document(self.dir / "t.docx"), "## Titel\n\nEin Absatz.\n\nA | B")

    def test_exports_roundtrip(self):
        content = "Erster Absatz mit Čeština & <Tags>.\n\nZweiter Absatz."
        for ext in (".docx", ".epub", ".pdf", ".txt"):
            path = self.dir / f"out{ext}"
            save_document(content, path, "de")
            self.assertTrue(path.stat().st_size > 0, ext)
        self.assertIn("Zweiter Absatz.", read_document(self.dir / "out.docx"))
        epub_text = read_document(self.dir / "out.epub")
        self.assertIn("Čeština & <Tags>.", epub_text)
        self.assertIn("Zweiter Absatz.", epub_text)
        self.assertIn("Zweiter Absatz.", read_document(self.dir / "out.pdf"))

    def test_pdf_page_break_is_joined(self):
        import pymupdf
        doc = pymupdf.open()
        doc.new_page().insert_text((72, 700), "Der Satz beginnt hier und")
        doc.new_page().insert_text((72, 72), "endet auf der naechsten Seite.")
        doc.save(self.dir / "p.pdf")
        self.assertEqual(read_document(self.dir / "p.pdf"),
                         "Der Satz beginnt hier und endet auf der naechsten Seite.")


class HeadingDetectionTest(unittest.TestCase):
    def test_chapter_words_numbers_and_subtitle(self):
        text = "Prolog\n\nEs war einmal.\n\nKapitel 1\n\nDer Anfang\n\nText hier.\n\n2\n\nNoch ein Absatz."
        self.assertEqual(mark_headings(text),
                         "# Prolog\n\nEs war einmal.\n\n# Kapitel 1 – Der Anfang\n\nText hier.\n\n# 2\n\nNoch ein Absatz.")

    def test_parts_make_chapters_second_level(self):
        marked = mark_headings("Teil Eins\n\nKapitel 1\n\nText.\n\nKapitel 2\n\nText.")
        self.assertEqual(marked, "# Teil Eins\n\n## Kapitel 1\n\nText.\n\n## Kapitel 2\n\nText.")

    def test_existing_markdown_is_left_alone(self):
        text = "# Titel\n\nKapitel 1\n\nText."
        self.assertEqual(mark_headings(text), text)

    def test_running_text_is_not_a_heading(self):
        text = "Er sagte nichts.\n\nSie ging nach Hause und schlief ein, weil es spät war und niemand mehr da war"
        self.assertEqual(mark_headings(text), text)

    def test_pdf_font_sizes_and_page_numbers(self):
        import pymupdf
        with tempfile.TemporaryDirectory() as tmp:
            doc = pymupdf.open()
            page = doc.new_page()
            page.insert_text((72, 100), "Das Grosse Buch", fontsize=24)
            page.insert_text((72, 150), "Ein normaler Absatz im Fliesstext des Buches.", fontsize=11)
            page.insert_text((72, 180), "Abschnitt Zwei", fontsize=16)
            page.insert_text((72, 210), "Noch mehr Fliesstext hier drin.", fontsize=11)
            page.insert_text((300, 820), "7", fontsize=11)
            doc.save(Path(tmp) / "h.pdf")
            self.assertEqual(
                read_document(Path(tmp) / "h.pdf"),
                "# Das Grosse Buch\n\nEin normaler Absatz im Fliesstext des Buches.\n\n"
                "## Abschnitt Zwei\n\nNoch mehr Fliesstext hier drin.")


class ExportTest(unittest.TestCase):
    CONTENT = ("# Das Buch\n\nVorwort mit *Kursiv*.\n\n# Kapitel 1\n\nErster **fetter** Absatz.\nZweite Zeile.\n\n"
               "## Unterkapitel\n\nText.\n\n# Kapitel 2\n\nSchluss & <Ende>.")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_parse_blocks_splits_glued_heading_and_keeps_line_breaks(self):
        blocks = parse_blocks("# Titel\nText A\nText B\n\n\n\nEnde")
        self.assertEqual([(b.kind, b.level, b.text) for b in blocks],
                         [("heading", 1, "Titel"), ("paragraph", 0, "Text A\nText B"), ("paragraph", 0, "Ende")])

    def test_chapter_level_ignores_single_title(self):
        self.assertEqual(chapter_level(parse_blocks(self.CONTENT)), 1)
        self.assertEqual(chapter_level(parse_blocks("# Titel\n\n## A\n\nx\n\n## B\n\ny")), 2)
        self.assertEqual(chapter_level(parse_blocks("nur Text")), 0)

    def test_txt_has_no_markup_and_md_keeps_it(self):
        save_document(self.CONTENT, self.dir / "o.txt")
        save_document(self.CONTENT, self.dir / "o.md")
        txt = (self.dir / "o.txt").read_text(encoding="utf-8")
        self.assertNotIn("#", txt)
        self.assertNotIn("*", txt)
        self.assertIn("Erster fetter Absatz.\nZweite Zeile.", txt)
        self.assertIn("## Unterkapitel", (self.dir / "o.md").read_text(encoding="utf-8"))

    def test_docx_uses_heading_styles_and_runs(self):
        from docx import Document
        save_document(self.CONTENT, self.dir / "o.docx")
        paragraphs = Document(self.dir / "o.docx").paragraphs
        styles = [(p.style.name, p.text) for p in paragraphs if p.style.name.startswith("Heading")]
        self.assertEqual(styles, [("Heading 1", "Das Buch"), ("Heading 1", "Kapitel 1"),
                                  ("Heading 2", "Unterkapitel"), ("Heading 1", "Kapitel 2")])
        body = next(p for p in paragraphs if p.text.startswith("Erster"))
        self.assertEqual([(r.text, r.bold) for r in body.runs if r.text.strip() and r.bold], [("fetter", True)])
        self.assertTrue(paragraphs[[p.text for p in paragraphs].index("Kapitel 1")].paragraph_format.page_break_before)

    def test_epub_has_one_chapter_per_heading(self):
        from ebooklib import epub
        save_document(self.CONTENT, self.dir / "o.epub", "de")
        book = epub.read_epub(str(self.dir / "o.epub"))
        self.assertEqual([t.title for t in book.toc], ["Das Buch", "Kapitel 1", "Kapitel 2"])
        text = read_document(self.dir / "o.epub")
        self.assertIn("Schluss & <Ende>.", text)
        self.assertIn("## Unterkapitel", text)

    def test_pdf_has_bookmarks_for_headings(self):
        import pymupdf
        save_document(self.CONTENT, self.dir / "o.pdf")
        with pymupdf.open(self.dir / "o.pdf") as doc:
            self.assertEqual([entry[1] for entry in doc.get_toc()], ["Das Buch", "Kapitel 1", "Unterkapitel", "Kapitel 2"])
            self.assertGreaterEqual(doc.page_count, 3)  # Kapitel beginnen auf neuer Seite
        text = read_document(self.dir / "o.pdf")
        self.assertIn("Schluss & <Ende>.", text)
        self.assertTrue(text.startswith("# Das Buch"))


class FakeClient:
    def __init__(self, handler):
        self.handler, self.calls = handler, []

    def _stream_to(self, completion, kw):
        if kw.get("on_delta"):
            for word in completion.text.split(" "):
                kw["on_delta"](word + " ")
        return completion

    def complete(self, model, prompt, **kw):
        self.calls.append(("complete", prompt, kw))
        return self._stream_to(self.handler(prompt, kw), kw)

    def chat(self, model, messages, **kw):
        self.calls.append(("chat", messages, kw))
        return self._stream_to(self.handler(messages[-1]["content"], kw), kw)


def gemma_config():
    return Config(model="google.translategemma-4b-it", lang_source="en", lang_target="de").sanitize()


class PipelineTest(unittest.TestCase):
    def test_gemma_translates_every_chunk(self):
        client = FakeClient(lambda p, kw: Completion("Übersetzt.", "stop"))
        pipe = TranslationPipeline(client, gemma_config())
        chunks = split_into_chunks("Eins. " * 400, 500)
        results = list(pipe.translate(chunks))
        self.assertEqual(len(results), len(chunks))
        self.assertTrue(all(r.warnings == [] for r in results))
        self.assertTrue(all(c[0] == "complete" and c[2]["stop"] == ["<end_of_turn>"] for c in client.calls))

    def test_truncated_output_is_split_and_retranslated(self):
        def handler(prompt, kw):
            request = prompt.split("<start_of_turn>user")[-1]  # ohne mitgesendeten Kontext
            return Completion("x", "length") if request.count("Satz.") > 70 else Completion("Teil.", "stop")
        pipe = TranslationPipeline(FakeClient(handler), gemma_config())
        chunk = split_into_chunks("Satz. " * 100, 5000)[0]
        result = pipe.translate_chunk(chunk, {})
        self.assertIn("Abschnitt wegen Token-Limit geteilt und neu übersetzt", result.warnings)
        self.assertIn("Teil.", result.translation)
        self.assertNotIn("x", result.translation)

    def test_suspicious_output_retried_with_temperature_and_better_one_wins(self):
        def handler(prompt, kw):
            return Completion("Abgehackt", "stop") if kw["temperature"] == 0.0 else Completion("Vollständig.", "stop")
        pipe = TranslationPipeline(FakeClient(handler), gemma_config())
        result = pipe.translate_chunk(split_into_chunks("Ein ganzer Satz.", 100)[0], {})
        self.assertEqual((result.translation, result.warnings), ("Vollständig.", []))

    def test_build_context_takes_last_aligned_paragraphs_within_budget(self):
        source, translation = "A1.\n\nA2.\n\nA3.", "B1.\n\nB2.\n\nB3."
        self.assertEqual(build_context(source, translation, budget=7), ("A2.\n\nA3.", "B2.\n\nB3."))
        self.assertEqual(build_context(source, translation, budget=1), ("A3.", "B3."))  # mindestens ein Absatz
        self.assertIsNone(build_context(source, "B1.\n\nB2."))  # Absatzzahl passt nicht: keine falsche Zuordnung

    def test_previous_chunk_is_sent_as_context_and_echo_is_removed(self):
        def handler(prompt, kw):
            if "Zweiter Satz" in prompt.split("<start_of_turn>model")[-2]:  # aktuelle Anfrage = Chunk 2
                return Completion("Eins übersetzt.\n\nZwei übersetzt.", "stop")  # Kontext mitübersetzt
            return Completion("Eins übersetzt.", "stop")
        client = FakeClient(handler)
        pipe = TranslationPipeline(client, gemma_config())
        chunks = split_into_chunks("Erster Satz.\n\nZweiter Satz.", 15)
        results = list(pipe.translate(chunks))
        self.assertEqual([r.translation for r in results], ["Eins übersetzt.", "Zwei übersetzt."])
        second_prompt = client.calls[1][1]
        self.assertIn("Erster Satz.<end_of_turn>\n<start_of_turn>model\nEins übersetzt.<end_of_turn>", second_prompt)

    def test_context_can_be_switched_off(self):
        client = FakeClient(lambda p, kw: Completion("X.", "stop"))
        cfg = gemma_config()
        cfg.use_context = False
        list(TranslationPipeline(client, cfg).translate(split_into_chunks("Erster Satz.\n\nZweiter Satz.", 15)))
        self.assertTrue(all(call[1].count("<start_of_turn>user") == 1 for call in client.calls))

    def test_glossary_reaches_gemma_prompt_and_missing_term_is_reported(self):
        client = FakeClient(lambda p, kw: Completion("Er trank den Trank.", "stop"))
        pipe = TranslationPipeline(client, gemma_config())
        chunk = split_into_chunks("He drank the Polyjuice Potion.", 100)[0]
        result = pipe.translate_chunk(chunk, {"Polyjuice Potion": "Vielsafttrank", "Orc": "Ork"})
        self.assertIn("Polyjuice Potion = Vielsafttrank", client.calls[0][1])
        self.assertNotIn("Orc", client.calls[0][1])  # nur Begriffe, die im Abschnitt vorkommen
        self.assertEqual(result.warnings, ["Glossar nicht eingehalten: Polyjuice Potion → Vielsafttrank"])
        self.assertEqual(len(client.calls), 2)  # Wiederholung mit Temperatur wurde versucht

    def test_translation_keeps_heading_markers(self):
        client = FakeClient(lambda p, kw: Completion("Der Anfang\n\nEs war einmal.", "stop"))
        pipe = TranslationPipeline(client, gemma_config())
        result = pipe.translate_chunk(split_into_chunks("# The Start\n\nOnce upon a time.", 100)[0], {})
        self.assertEqual(result.translation, "# Der Anfang\n\nEs war einmal.")

    def test_gemma_uses_saved_glossary_without_asking_the_model(self):
        client = FakeClient(lambda p, kw: Completion("", "stop"))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "g.txt"
            path.write_text("Orc: Ork\n", encoding="utf-8")
            glossary = TranslationPipeline(client, gemma_config(), path).build_glossary(
                split_into_chunks("Text.", 100), review=lambda g: self.fail("kein Dialog für TranslateGemma"))
        self.assertEqual(glossary, {"Orc": "Ork"})
        self.assertEqual(client.calls, [])

    def test_streaming_passes_deltas_only_for_first_attempt(self):
        def handler(prompt, kw):  # erster Versuch unvollständig, Wiederholung gut
            return Completion("Abgehackt", "stop") if kw["temperature"] == 0.0 else Completion("Vollständig.", "stop")
        client = FakeClient(handler)
        seen = []
        pipe = TranslationPipeline(client, gemma_config())
        chunk = split_into_chunks("Ein ganzer Satz.", 100)[0]
        result = list(pipe.translate([chunk], on_delta=lambda c, d: seen.append((c.index, d))))[0]
        self.assertEqual(seen, [(1, "Abgehackt ")])  # Retry wird nicht gestreamt
        self.assertEqual(result.translation, "Vollständig.")  # Endergebnis ersetzt den Streamtext
        self.assertIn("on_delta", client.calls[0][2])
        self.assertNotIn("on_delta", client.calls[1][2])

    def test_without_on_delta_client_is_not_asked_to_stream(self):
        client = FakeClient(lambda p, kw: Completion("X.", "stop"))
        list(TranslationPipeline(client, gemma_config()).translate(split_into_chunks("Satz.", 100)))
        self.assertNotIn("on_delta", client.calls[0][2])

    def test_cancel_works_in_the_middle_of_a_chunk(self):
        cancel = threading.Event()
        client = FakeClient(lambda p, kw: Completion("eins zwei drei vier.", "stop"))
        pipe = TranslationPipeline(client, gemma_config())
        seen = []

        def on_delta(chunk, delta):
            seen.append(delta)
            if len(seen) == 2:
                cancel.set()
        with self.assertRaises(Cancelled):
            list(pipe.translate(split_into_chunks("Satz.", 100), cancel=cancel, on_delta=on_delta))
        self.assertEqual(seen, ["eins ", "zwei "])  # nach dem Abbruch kommt nichts mehr

    def test_cancel(self):
        pipe = TranslationPipeline(FakeClient(lambda p, kw: Completion("A.", "stop")), gemma_config())
        cancel = threading.Event()
        gen = pipe.translate(split_into_chunks("Satz. " * 300, 300), cancel=cancel)
        next(gen)
        cancel.set()
        with self.assertRaises(Cancelled):
            next(gen)

    def test_chat_model_uses_system_prompt_with_glossary_and_ignores_tuple_bug(self):
        replies = iter([Completion("- Orc: Ork", "stop")] + [Completion("Fertig.", "stop")] * 5)
        client = FakeClient(lambda p, kw: next(replies))
        cfg = Config(model="qwen", lang_source="en", lang_target="de").sanitize()
        with tempfile.TemporaryDirectory() as tmp:
            pipe = TranslationPipeline(client, cfg, glossary_path=Path(tmp) / "g.txt")
            chunks = split_into_chunks("The Orc came. " * 3, 100)
            glossary = pipe.build_glossary(chunks, review=lambda g: {**g, "Elf": "Elfe"})
            self.assertEqual(glossary, {"Orc": "Ork", "Elf": "Elfe"})
            self.assertNotIn("('", client.calls[0][1][0]["content"])  # keine Tupel-Repr im Prompt
            self.assertIn("German", client.calls[0][1][0]["content"])  # Zielsprache nicht hartkodiert
            list(pipe.translate(chunks, glossary))
            system = client.calls[1][1][0]["content"]
            self.assertIn("Orc = Ork", system)
            self.assertIn("from English to German", system)
            self.assertEqual((Path(tmp) / "g.txt").read_text(encoding="utf-8"), "Elf: Elfe\nOrc: Ork\n")


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, status, body, raw=False):
        data = body.encode() if raw else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _stream(self, body):
        chat = self.path.endswith("chat/completions")
        pieces = ["Hal", "lo ä", "— Welt"]
        make = (lambda t: {"choices": [{"delta": {"content": t}, "finish_reason": None}]}) if chat else             (lambda t: {"choices": [{"text": t, "finish_reason": None}]})
        events = [make(t) for t in pieces]
        if body["model"] != "cut":  # "cut": Stream endet ohne finish_reason
            events.append({"choices": [{"delta": {}, "text": "", "finish_reason": "stop"}]})
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")  # ohne charset: requests würde Latin-1 annehmen
        self.end_headers()
        for event in events:
            self.wfile.write(f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")

    def do_GET(self):
        if self.path == "/api/v1/models":
            self._send(200, {"models": [{"key": "a", "type": "llm"}, {"key": "emb", "type": "embedding"}]})
        else:
            self._send(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if body.get("stream") and body["model"] != "bad":
            return self._stream(body)
        if body["model"] == "bad":
            self._send(500, "<html>kaputt</html>", raw=True)
        elif body["model"] == "garbage":
            self._send(200, {"nichts": 1})
        else:
            self._send(200, {"choices": [{"text": " Hallo ", "finish_reason": "length"}],
                             "usage": {"prompt_tokens": 3, "completion_tokens": 4}})


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), _Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.client = LLMClient(f"http://127.0.0.1:{cls.server.server_port}")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_complete_ok(self):
        c = self.client.complete("m", "p", max_tokens=5)
        self.assertEqual((c.text, c.finish_reason, c.completion_tokens), ("Hallo", "length", 4))

    def test_errors_raise_instead_of_returning_text(self):
        with self.assertRaises(LLMError):
            self.client.complete("bad", "p", max_tokens=5)
        with self.assertRaises(LLMError):
            self.client.complete("garbage", "p", max_tokens=5)
        with self.assertRaises(LLMError):
            LLMClient("http://127.0.0.1:1").list_models()

    def test_complete_streams_deltas_and_returns_full_text(self):
        deltas = []
        c = self.client.complete("m", "p", max_tokens=5, on_delta=deltas.append)
        self.assertEqual(deltas, ["Hal", "lo ä", "— Welt"])
        self.assertEqual((c.text, c.finish_reason), ("Hallo ä— Welt", "stop"))

    def test_chat_streams_delta_content(self):
        deltas = []
        c = self.client.chat("m", [{"role": "user", "content": "x"}], max_tokens=5, on_delta=deltas.append)
        self.assertEqual("".join(deltas), "Hallo ä— Welt")
        self.assertEqual(c.finish_reason, "stop")

    def test_stream_without_finish_reason_counts_as_truncated(self):
        self.assertEqual(self.client.complete("cut", "p", max_tokens=5, on_delta=lambda d: None).finish_reason, "length")

    def test_exception_in_callback_stops_the_stream(self):
        class Stop(Exception):
            pass

        def boom(delta):
            raise Stop()
        with self.assertRaises(Stop):
            self.client.complete("m", "p", max_tokens=5, on_delta=boom)

    def test_stream_errors_raise(self):
        with self.assertRaises(LLMError):
            self.client.complete("bad", "p", max_tokens=5, on_delta=lambda d: None)

    def test_list_models_skips_embeddings(self):
        self.assertEqual(self.client.list_models(), ["a"])


if __name__ == "__main__":
    unittest.main()
