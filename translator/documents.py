"""Liest TXT, PDF, DOCX und EPUB und liefert Text mit Markdown-artiger Absatzstruktur."""
from __future__ import annotations

import logging
import re
from pathlib import Path

log = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = (".txt", ".md", ".pdf", ".docx", ".epub")
_TERMINAL_PUNCTUATION = tuple(".!?…:;»«\"”’)。！？」』")


def read_document(path: Path | str) -> str:
    path = Path(path)
    ext = path.suffix.lower()
    readers = {".txt": _read_text, ".md": _read_text, ".pdf": _read_pdf, ".docx": _read_docx, ".epub": _read_epub}
    if ext not in readers:
        raise ValueError(f"Dateiformat {ext or '(ohne Endung)'} wird nicht unterstützt.")
    return readers[ext](path).strip()


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "cp1252"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _join_paragraphs(paragraphs: list[str]) -> str:
    """Absätze verbinden; ein Absatz, der über einen Seitenumbruch läuft, wird wieder zusammengesetzt."""
    result: list[str] = []
    for paragraph in paragraphs:
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if result and not result[-1].endswith(_TERMINAL_PUNCTUATION) and paragraph[0].islower():
            result[-1] += " " + paragraph
        else:
            result.append(paragraph)
    return "\n\n".join(result)


def _read_pdf(path: Path) -> str:
    import fitz  # PyMuPDF

    paragraphs: list[str] = []
    with fitz.open(path) as doc:
        for page in doc:
            for block in page.get_text("blocks", sort=True):
                if block[6] == 0:  # Textblock (kein Bild)
                    paragraphs.append(re.sub(r"\s*\n\s*", " ", block[4]))
    return _join_paragraphs(paragraphs)


def _read_docx(path: Path) -> str:
    from docx import Document
    from docx.table import Table

    blocks: list[str] = []
    for item in Document(path).iter_inner_content():
        if isinstance(item, Table):
            for row in item.rows:
                cells = [cell.text.strip() for cell in row.cells]
                if any(cells):
                    blocks.append(" | ".join(cells))
            continue
        text = item.text.strip()
        if not text:
            continue
        style = item.style.name if item.style is not None else ""
        if style == "Title":
            text = f"# {text}"
        elif style.startswith("Heading"):
            digits = re.search(r"\d+", style)
            text = f"{'#' * min(int(digits.group()) if digits else 1, 6)} {text}"
        blocks.append(text)
    return "\n\n".join(blocks)


def _read_epub(path: Path) -> str:
    import ebooklib
    from bs4 import BeautifulSoup
    from ebooklib import epub

    book = epub.read_epub(str(path))
    items = [book.get_item_with_id(idref) for idref, _ in book.spine]
    items = [i for i in items if i is not None and i.get_type() == ebooklib.ITEM_DOCUMENT]
    if not items:  # kein brauchbarer Spine: alle Dokumente in Manifest-Reihenfolge
        items = list(book.get_items_of_type(ebooklib.ITEM_DOCUMENT))

    blocks: list[str] = []
    for item in items:
        content = item.get_content()
        for parser in ("lxml-xml", "lxml"):
            soup = BeautifulSoup(content, parser)
            found = soup.find_all(["h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "blockquote"])
            if found:
                break
        for tag in found:
            if tag.name == "blockquote" and tag.find("p"):
                continue  # Inhalt wird über die enthaltenen <p> erfasst
            text = re.sub(r"\s+", " ", tag.get_text()).strip()
            if not text:
                continue
            if tag.name.startswith("h"):
                text = f"{'#' * int(tag.name[1])} {text}"
            blocks.append(text)
    if not blocks:
        log.error("EPUB-Extraktion ergab keinen Text: %s", path)
    return "\n\n".join(blocks)
