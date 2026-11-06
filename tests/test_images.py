"""Bilder, Restzeit und Verbindungswiederholung (spiegeln die Tests der Android-App)."""
import io
import tempfile
import threading
import unittest
from datetime import datetime
from pathlib import Path

from translator.chunking import split_into_chunks
from translator.config import Config
from translator.documents import read_document
from translator.eta import eta_text, format_duration, remaining_seconds
from translator.exporters import parse_blocks, save_document
from translator.images import (ImageAsset, ImageCollector, extract_images, image_id_of_line, image_ids, reinsert_images,
                               remove_image_tokens, sniff_mime, sniff_size)
from translator.llm_client import Completion, LLMError
from translator.pipeline import CONNECTION_RETRY_DELAYS, Cancelled, TranslationPipeline, build_context


def png(width=120, height=80, color="red") -> bytes:
    from PIL import Image
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buffer, "PNG")
    return buffer.getvalue()


def gemma_config():
    return Config(model="google.translategemma-4b-it", lang_source="en", lang_target="de").sanitize()


class Scripted:
    """Fake-Client: jede Antwort aus einer Liste; Exceptions werden geworfen."""

    def __init__(self, *replies):
        self.replies, self.prompts = list(replies), []

    def complete(self, model, prompt, **kw):
        self.prompts.append(prompt)
        reply = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        if isinstance(reply, Exception):
            raise reply
        return reply


class ImageTokenTest(unittest.TestCase):
    def test_extract_and_reinsert_at_same_paragraph_positions(self):
        text = "Eins.\n\n[[Bild 1]]\n\nZwei.\n\nDrei.\n\n[[Bild 2]]"
        split = extract_images(text)
        self.assertEqual(split.text, "Eins.\n\nZwei.\n\nDrei.")
        self.assertEqual(reinsert_images("A.\n\nB.\n\nC.", split), "A.\n\n[[Bild 1]]\n\nB.\n\nC.\n\n[[Bild 2]]")

    def test_proportional_when_paragraph_count_changes(self):
        split = extract_images("1.\n\n2.\n\n[[Bild 7]]\n\n3.\n\n4.")
        out = reinsert_images("a\n\nb", split)  # 4 -> 2 Absätze: Bild bei 50 %
        self.assertEqual(out, "a\n\n[[Bild 7]]\n\nb")

    def test_only_images_and_helpers(self):
        self.assertEqual(extract_images("[[Bild 1]]\n\n[[Bild 2]]").text, "")
        self.assertEqual(reinsert_images("", extract_images("[[Bild 1]]\n\n[[Bild 2]]")), "[[Bild 1]]\n\n[[Bild 2]]")
        self.assertEqual(image_ids("x [[Bild 3]] y [[Bild 3]] [[Bild 10]]"), [3, 3, 10])
        self.assertEqual(image_id_of_line(" [[Bild 4]] "), 4)
        self.assertIsNone(image_id_of_line("Text [[Bild 4]]"))
        self.assertEqual(remove_image_tokens("A.\n\n[[Bild 1]]\n\nB."), "A.\n\nB.")

    def test_collector_deduplicates_identical_images(self):
        collector = ImageCollector()
        a, b, again = collector.add(png(color="red"), "image/png"), collector.add(png(color="blue"), "image/png"), \
            collector.add(png(color="red"), "image/png")
        self.assertEqual((a, b, again), (1, 2, 1))
        self.assertEqual(len(collector.images), 2)

    def test_sniffing(self):
        data = png(33, 21)
        self.assertEqual(sniff_mime(data), "image/png")
        self.assertEqual(sniff_size(data), (33, 21))
        self.assertEqual(sniff_mime(b"<svg/>"), "image/svg+xml")
        self.assertIsNone(sniff_mime(b"irgendwas"))
        self.assertIsNone(sniff_size(b"<svg/>"))


class PipelineImagesTest(unittest.TestCase):
    def test_model_never_sees_placeholders_and_images_return_in_place(self):
        client = Scripted(Completion("Übersetzt A.\n\nÜbersetzt B.", "stop"))
        chunk = split_into_chunks("Text A.\n\n[[Bild 1]]\n\nText B.", 200)[0]
        result = TranslationPipeline(client, gemma_config()).translate_chunk(chunk, {})
        self.assertNotIn("Bild", client.prompts[0])
        self.assertEqual(result.translation, "Übersetzt A.\n\n[[Bild 1]]\n\nÜbersetzt B.")

    def test_chunk_with_only_images_makes_no_model_call(self):
        client = Scripted(Completion("X", "stop"))
        chunk = split_into_chunks("[[Bild 1]]\n\n[[Bild 2]]", 200)[0]
        result = TranslationPipeline(client, gemma_config()).translate_chunk(chunk, {})
        self.assertEqual((client.prompts, result.translation), ([], "[[Bild 1]]\n\n[[Bild 2]]"))

    def test_context_ignores_placeholders(self):
        self.assertEqual(build_context("A.\n\n[[Bild 1]]\n\nB.", "X.\n\nY."), ("A.\n\nB.", "X.\n\nY."))


class RetryTest(unittest.TestCase):
    def test_transient_errors_are_retried_and_reported(self):
        client = Scripted(LLMError("weg", transient=True), LLMError("weg", transient=True), Completion("Fertig.", "stop"))
        waits, retries = [], []
        pipe = TranslationPipeline(client, gemma_config(), on_retry=lambda n, why: retries.append((n, why)),
                                   sleep=waits.append)
        result = pipe.translate_chunk(split_into_chunks("Satz.", 100)[0], {})
        self.assertEqual(result.translation, "Fertig.")
        self.assertEqual(retries, [(1, "weg"), (2, "weg")])
        self.assertEqual(waits, list(CONNECTION_RETRY_DELAYS[:2]))

    def test_permanent_errors_are_not_retried(self):
        client = Scripted(LLMError("Nicht autorisiert"))
        pipe = TranslationPipeline(client, gemma_config(), sleep=lambda s: self.fail("kein Warten"))
        with self.assertRaises(LLMError):
            pipe.translate_chunk(split_into_chunks("Satz.", 100)[0], {})
        self.assertEqual(len(client.prompts), 1)

    def test_gives_up_after_the_last_delay(self):
        client = Scripted(LLMError("weg", transient=True))
        pipe = TranslationPipeline(client, gemma_config(), sleep=lambda s: None)
        with self.assertRaises(LLMError):
            pipe.translate_chunk(split_into_chunks("Satz.", 100)[0], {})
        self.assertEqual(len(client.prompts), len(CONNECTION_RETRY_DELAYS) + 1)

    def test_cancel_interrupts_the_wait(self):
        cancel = threading.Event()
        cancel.set()
        pipe = TranslationPipeline(Scripted(LLMError("weg", transient=True)), gemma_config())
        with self.assertRaises(Cancelled):
            list(pipe.translate(split_into_chunks("Satz.", 100), cancel=cancel))

    def test_resume_with_initial_context(self):
        client = Scripted(Completion("Zwei.", "stop"))
        pipe = TranslationPipeline(client, gemma_config())
        chunk = split_into_chunks("Second sentence.", 100)[0]
        list(pipe.translate([chunk], initial_context=("First sentence.", "Erster Satz.")))
        self.assertIn("First sentence.<end_of_turn>\n<start_of_turn>model\nErster Satz.<end_of_turn>", client.prompts[0])


class EtaTest(unittest.TestCase):
    def test_remaining_and_format(self):
        self.assertIsNone(remaining_seconds(60, 0, 100))
        self.assertEqual(remaining_seconds(60, 25, 100), 180)
        self.assertEqual(remaining_seconds(60, 100, 100), 0)
        self.assertEqual(format_duration(10), "unter 1 Min.")
        self.assertEqual(format_duration(41 * 60), "41 Min.")
        self.assertEqual(format_duration(65 * 60), "1 Std. 5 Min.")

    def test_text_names_the_finish_time(self):
        now = datetime(2026, 1, 1, 14, 0)
        self.assertEqual(eta_text(600, 100, 500, now), "noch ca. 40 Min. (fertig gegen 14:40)")
        self.assertEqual(eta_text(10, 99, 100, now), "noch unter 1 Min. (fertig gegen 14:00)")
        self.assertIsNone(eta_text(10, 0, 100, now))
        self.assertIsNone(eta_text(10, 100, 100, now))


class ImageDocumentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_docx_image_is_read_in_place(self):
        from docx import Document
        (self.dir / "a.png").write_bytes(png())
        doc = Document()
        doc.add_paragraph("Vor dem Bild.")
        doc.add_picture(str(self.dir / "a.png"))
        doc.add_paragraph("Nach dem Bild.")
        doc.save(self.dir / "t.docx")
        collector = ImageCollector()
        self.assertEqual(read_document(self.dir / "t.docx", collector), "Vor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.")
        self.assertEqual(collector.images[1].mime, "image/png")
        self.assertNotIn("[[Bild", read_document(self.dir / "t.docx"))  # ohne Collector: keine Platzhalter

    def test_epub_image_is_read_in_place(self):
        from ebooklib import epub
        book = epub.EpubBook()
        book.set_identifier("x"), book.set_title("T"), book.set_language("de")
        chapter = epub.EpubHtml(title="K", file_name="text/k.xhtml", lang="de")
        chapter.content = '<h1>Kapitel</h1><p>Davor.</p><p><img src="../images/a.png"/></p><p>Danach.</p>'
        book.add_item(chapter)
        book.add_item(epub.EpubItem(uid="img", file_name="images/a.png", media_type="image/png", content=png()))
        book.toc, book.spine = [chapter], [chapter]
        book.add_item(epub.EpubNcx()), book.add_item(epub.EpubNav())
        epub.write_epub(str(self.dir / "b.epub"), book)
        collector = ImageCollector()
        text = read_document(self.dir / "b.epub", collector)
        self.assertEqual(text, "# Kapitel\n\nDavor.\n\n[[Bild 1]]\n\nDanach.")
        self.assertEqual(len(collector.images), 1)

    def test_pdf_image_is_read_in_place_and_tiny_ones_are_ignored(self):
        import pymupdf
        doc = pymupdf.open()
        page = doc.new_page()
        page.insert_text((72, 100), "Oben steht Text im Fliesstext.", fontsize=11)
        page.insert_image(pymupdf.Rect(72, 200, 272, 300), stream=png())
        page.insert_image(pymupdf.Rect(72, 330, 80, 338), stream=png(4, 4, "blue"))  # Zierpunkt
        page.insert_text((72, 500), "Unten steht weiterer Text.", fontsize=11)
        doc.save(self.dir / "p.pdf")
        collector = ImageCollector()
        text = read_document(self.dir / "p.pdf", collector)
        self.assertEqual(text, "Oben steht Text im Fliesstext.\n\n[[Bild 1]]\n\nUnten steht weiterer Text.")
        self.assertEqual(len(collector.images), 1)


class ImageExportTest(unittest.TestCase):
    CONTENT = "# Buch\n\nVor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.\n\n[[Bild 1]]\n\n[[Bild 9]]"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.lookup = {1: ImageAsset(png(200, 100), "image/png")}.get  # Bild 9 fehlt: wird übersprungen

    def tearDown(self):
        self.tmp.cleanup()

    def test_parse_blocks_knows_images(self):
        kinds = [(b.kind, b.image_id) for b in parse_blocks(self.CONTENT)]
        self.assertEqual(kinds, [("heading", 0), ("paragraph", 0), ("image", 1), ("paragraph", 0), ("image", 1), ("image", 9)])

    def test_txt_and_md_leave_images_out(self):
        for ext in (".txt", ".md"):
            save_document(self.CONTENT, self.dir / f"o{ext}", images=self.lookup)
            text = (self.dir / f"o{ext}").read_text(encoding="utf-8")
            self.assertNotIn("[[Bild", text)
            self.assertIn("Nach dem Bild.", text)

    def test_docx_has_pictures_at_their_place(self):
        from docx import Document
        save_document(self.CONTENT, self.dir / "o.docx", images=self.lookup)
        doc = Document(self.dir / "o.docx")
        self.assertEqual(len(doc.inline_shapes), 2)
        self.assertLessEqual(doc.inline_shapes[0].width, 6 * 914400)
        with_picture = [i for i, p in enumerate(doc.paragraphs) if p._p.xpath(".//*[local-name()='blip']")]
        texts = [p.text for p in doc.paragraphs]
        self.assertLess(texts.index("Vor dem Bild."), with_picture[0])
        self.assertGreater(texts.index("Nach dem Bild."), with_picture[0])
        self.assertNotIn("[[Bild", "".join(texts))

    def test_docx_roundtrip_reads_the_image_back(self):
        save_document(self.CONTENT, self.dir / "o.docx", images=self.lookup)
        collector = ImageCollector()
        text = read_document(self.dir / "o.docx", collector)
        self.assertIn("Vor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.\n\n[[Bild 1]]", text)  # gleiches Bild, gleiche Nummer
        self.assertEqual(len(collector.images), 1)

    def test_epub_stores_each_image_once(self):
        from ebooklib import epub
        save_document(self.CONTENT, self.dir / "o.epub", "de", images=self.lookup)
        book = epub.read_epub(str(self.dir / "o.epub"))
        images = [i for i in book.get_items() if (i.media_type or "").startswith("image/")]
        self.assertEqual([i.get_name() for i in images], ["images/img_1.png"])
        text = read_document(self.dir / "o.epub", ImageCollector())
        self.assertIn("Vor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.\n\n[[Bild 1]]", text)

    def test_pdf_draws_images(self):
        import pymupdf
        save_document(self.CONTENT, self.dir / "o.pdf", images=self.lookup)
        with pymupdf.open(self.dir / "o.pdf") as doc:
            self.assertGreaterEqual(sum(len(page.get_images()) for page in doc), 1)
        text = read_document(self.dir / "o.pdf", ImageCollector())
        self.assertIn("Vor dem Bild.", text)
        self.assertIn("[[Bild 1]]", text)

    def test_unusable_image_data_does_not_break_the_export(self):
        broken = {1: ImageAsset(b"<svg xmlns='http://www.w3.org/2000/svg'/>", "image/svg+xml")}.get
        for ext in (".docx", ".pdf", ".epub"):
            save_document(self.CONTENT, self.dir / f"b{ext}", images=broken)
            self.assertGreater((self.dir / f"b{ext}").stat().st_size, 0)


if __name__ == "__main__":
    unittest.main()
