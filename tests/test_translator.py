import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from translator.chunking import boundary_sentences, split_into_chunks
from translator.config import Config
from translator.documents import read_document
from translator.exporters import extract_translations, format_block, save_document
from translator.glossary import merge_glossary, parse_glossary
from translator.languages import code_from_display, language_name
from translator.llm_client import Completion, LLMClient, LLMError
from translator.pipeline import Cancelled, TranslationPipeline
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

    def test_boundary_sentences(self):
        text = "Eins. Zwei! Drei? Vier."
        self.assertEqual(boundary_sentences(text, "first", 2), "Eins. Zwei!")
        self.assertEqual(boundary_sentences(text, "last", 2), "Drei? Vier.")


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


class GlossaryTest(unittest.TestCase):
    def test_parse_keeps_case_and_strips_bullets(self):
        g = parse_glossary("- NASA: NASA\n1. **iPhone**: iPhone\nkein Eintrag\nLeer:\n")
        self.assertEqual(g, {"NASA": "NASA", "iPhone": "iPhone"})

    def test_merge_existing_wins_case_insensitive(self):
        merged = merge_glossary({"Orc": "Ork"}, {"orc": "Orks", "Elf": "Elfe"})
        self.assertEqual(merged, {"Orc": "Ork", "Elf": "Elfe"})


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
        import fitz
        doc = fitz.open()
        doc.new_page().insert_text((72, 700), "Der Satz beginnt hier und")
        doc.new_page().insert_text((72, 72), "endet auf der naechsten Seite.")
        doc.save(self.dir / "p.pdf")
        self.assertEqual(read_document(self.dir / "p.pdf"),
                         "Der Satz beginnt hier und endet auf der naechsten Seite.")


class ExtractTest(unittest.TestCase):
    def test_blocks_roundtrip(self):
        shown = format_block(1, 2, "Eins. Zwei.", "Uno.", ["warn"]) + format_block(2, 2, "Drei.", "Dos.", [])
        self.assertEqual(extract_translations(shown), "Uno.\n\nDos.")
        self.assertEqual(extract_translations("  freier Text "), "freier Text")


class FakeClient:
    def __init__(self, handler):
        self.handler, self.calls = handler, []

    def complete(self, model, prompt, **kw):
        self.calls.append(("complete", prompt, kw))
        return self.handler(prompt, kw)

    def chat(self, model, messages, **kw):
        self.calls.append(("chat", messages, kw))
        return self.handler(messages[-1]["content"], kw)


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
            return Completion("x", "length") if prompt.count("Satz.") > 70 else Completion("Teil.", "stop")
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

    def do_GET(self):
        if self.path == "/api/v1/models":
            self._send(200, {"models": [{"key": "a", "type": "llm"}, {"key": "emb", "type": "embedding"}]})
        else:
            self._send(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
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

    def test_list_models_skips_embeddings(self):
        self.assertEqual(self.client.list_models(), ["a"])


if __name__ == "__main__":
    unittest.main()
