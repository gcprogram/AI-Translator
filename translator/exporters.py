"""Ergebnisanzeige (Abschnittsmarken) und Export in TXT/MD, DOCX, PDF und EPUB."""
from __future__ import annotations

import html
import re
import time
from pathlib import Path

from .chunking import boundary_sentences
from .languages import normalize_code

BLOCK_RE = re.compile(r"--- BEGINN ABSCHNITT \d+\n(.*?)\n--- ENDE ABSCHNITT \d+", re.DOTALL)

_PDF_FONTS = (
    r"C:\Windows\Fonts\arial.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/Library/Fonts/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
)

SAVE_FILETYPES = [("Text", "*.txt"), ("Markdown", "*.md"), ("Word", "*.docx"), ("PDF", "*.pdf"), ("EPUB", "*.epub")]


def format_block(index: int, total: int, source: str, translation: str, warnings: list[str]) -> str:
    """Eine Übersetzung samt Original-Anfang/-Ende (Orientierung für das manuelle Zusammenfügen)."""
    notes = "".join(f"[⚠️ {w}]\n" for w in warnings)
    return (
        f"\n\n--- Original-Anfang:\n{boundary_sentences(source, 'first')}\n"
        f"--- BEGINN ABSCHNITT {index}\n{translation}\n--- ENDE ABSCHNITT {index}\n"
        f"{notes}--- Original-Ende:\n{boundary_sentences(source, 'last')}\n"
    )


def extract_translations(text: str) -> str:
    """Nur die Übersetzungen zwischen den Marken; ohne Marken wird der Text unverändert genommen."""
    matches = BLOCK_RE.findall(text)
    return "\n\n".join(matches) if matches else text.strip()


def save_document(content: str, path: Path | str, language: str = "de") -> None:
    path = Path(path)
    ext = path.suffix.lower()
    if ext == ".docx":
        _save_docx(content, path)
    elif ext == ".pdf":
        _save_pdf(content, path)
    elif ext == ".epub":
        _save_epub(content, path, normalize_code(language))
    else:
        path.write_text(content, encoding="utf-8")


def _save_docx(content: str, path: Path) -> None:
    from docx import Document

    doc = Document()
    for line in content.split("\n"):
        doc.add_paragraph(line.strip())
    doc.save(path)


def _save_pdf(content: str, path: Path) -> None:
    from fpdf import FPDF

    pdf = FPDF()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()
    font = next((f for f in _PDF_FONTS if Path(f).exists()), None)
    if font:  # Unicode-Schrift: Tschechisch, Kyrillisch, Türkisch ... bleiben erhalten
        pdf.add_font("Body", "", font)
        pdf.set_font("Body", size=11)
    else:  # Kernschrift kann nur Latin-1
        pdf.set_font("Helvetica", size=11)
        content = content.encode("latin-1", "replace").decode("latin-1")
    pdf.multi_cell(0, 6, content)
    pdf.output(str(path))


def _save_epub(content: str, path: Path, language: str) -> None:
    from ebooklib import epub

    title = path.stem
    book = epub.EpubBook()
    book.set_identifier(f"translation-{int(time.time())}")
    book.set_title(title)
    book.set_language(language)

    paragraphs = "".join(
        f"<p>{html.escape(p).replace(chr(10), '<br/>')}</p>"
        for p in re.split(r"\n\s*\n", content) if p.strip()
    )
    chapter = epub.EpubHtml(title=title, file_name="chap_1.xhtml", lang=language)
    chapter.content = f"<h1>{html.escape(title)}</h1>{paragraphs}"
    book.add_item(chapter)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.toc = (chapter,)
    book.spine = ["nav", chapter]
    epub.write_epub(str(path), book)
