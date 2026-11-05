"""Liest TXT, PDF, DOCX und EPUB und liefert Text mit Markdown-artiger Absatzstruktur."""
from __future__ import annotations

import logging
import re
from collections import Counter
from pathlib import Path

from .chunking import heading_level

log = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = (".txt", ".md", ".pdf", ".docx", ".epub")
_TERMINAL_PUNCTUATION = tuple(".!?…:;»«\"”’)。！？」』")

# Überschriften ohne Auszeichnung (reiner Text, PDF, DOCX ohne Formatvorlagen)
_PART_WORDS = r"part|book|teil|buch|partie|livre|parte|libro"
_CHAPTER_WORDS = (r"chapter|kapitel|chapitre|cap[ií]tulo|capitolo|prologue?|prolog|epilogue?|epilog|"
                  r"pr[oó]logo|ep[ií]logo|prelude|interlude|appendix|anhang|preface|vorwort|nachwort|"
                  r"foreword|afterword|introduction|einleitung|conclusion|schlusswort|contents|inhalt")
_PART_RE = re.compile(rf"^(?:{_PART_WORDS})\b.{{0,60}}$", re.IGNORECASE)
_CHAPTER_RE = re.compile(rf"^(?:{_CHAPTER_WORDS})\b.{{0,60}}$", re.IGNORECASE)
_NUMBER_RE = re.compile(r"^(?:\d{1,3}|[IVXLC]{1,7})[.)]?$")
_MAX_HEADING_CHARS = 80
_MAX_SUBTITLE_CHARS = 60
_PAGE_MARGIN = 0.08  # oberer/unterer Seitenstreifen (Anteil der Höhe), in dem Seitenzahlen stehen


def read_document(path: Path | str) -> str:
    path = Path(path)
    ext = path.suffix.lower()
    readers = {".txt": _read_text, ".md": _read_text, ".pdf": _read_pdf, ".docx": _read_docx, ".epub": _read_epub}
    if ext not in readers:
        raise ValueError(f"Dateiformat {ext or '(ohne Endung)'} wird nicht unterstützt.")
    return mark_headings(readers[ext](path).strip())


def _normalize_newlines(text: str) -> str:
    """Windows-Zeilenenden (\\r\\n) und alte Mac-Enden (\\r) zu \\n: Absätze erkennt der Rest nur an Leerzeilen (\\n\\n)."""
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "cp1252"):
        try:
            return _normalize_newlines(raw.decode(encoding))
        except UnicodeDecodeError:
            continue
    return _normalize_newlines(raw.decode("utf-8", errors="replace"))


def _join_paragraphs(paragraphs: list[str]) -> str:
    """Absätze verbinden; ein Absatz, der über einen Seitenumbruch läuft, wird wieder zusammengesetzt."""
    result: list[str] = []
    for paragraph in paragraphs:
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if (result and not heading_level(result[-1]) and not result[-1].endswith(_TERMINAL_PUNCTUATION)
                and paragraph[0].islower()):
            result[-1] += " " + paragraph
        else:
            result.append(paragraph)
    return "\n\n".join(result)


def _read_pdf(path: Path) -> str:
    import pymupdf

    blocks: list[tuple[str, float, bool]] = []  # (Text, mittlere Schriftgröße, liegt am Seitenrand)
    with pymupdf.open(path) as doc:
        for page in doc:
            margin = page.rect.height * _PAGE_MARGIN
            for block in page.get_text("dict", sort=True)["blocks"]:
                if block.get("type") != 0:  # Bild
                    continue
                lines = [[sp for sp in line["spans"] if sp["text"].strip()] for line in block["lines"]]
                lines = [line for line in lines if line]
                if not lines:
                    continue
                text = " ".join(" ".join(sp["text"].strip() for sp in line) for line in lines)
                spans = [sp for line in lines for sp in line]
                chars = sum(len(sp["text"]) for sp in spans)
                at_edge = block["bbox"][3] < margin or block["bbox"][1] > page.rect.height - margin
                blocks.append((text, sum(sp["size"] * len(sp["text"]) for sp in spans) / chars, at_edge))
    return _join_paragraphs(_mark_pdf_headings(blocks))


def _looks_like_heading(text: str) -> bool:
    return len(text) <= _MAX_HEADING_CHARS and "\n" not in text and not text.endswith(_TERMINAL_PUNCTUATION)


def _mark_pdf_headings(blocks: list[tuple[str, float, bool]]) -> list[str]:
    """Kurze Blöcke mit deutlich größerer Schrift als der Fließtext werden Überschriften;
    die größte Schrift ist Ebene 1 (höchstens bis Ebene 3). Seitenzahlen am Rand fallen weg."""
    if not blocks:
        return []
    chars_by_size: Counter[int] = Counter()
    for text, size, _ in blocks:
        chars_by_size[round(size)] += len(text)
    body_size = chars_by_size.most_common(1)[0][0]
    blocks = [b for b in blocks if not (b[2] and b[0].strip("-–— ").isdigit() and round(b[1]) <= body_size)]
    heading_sizes = sorted({round(size) for text, size, _ in blocks
                            if round(size) >= body_size * 1.15 and _looks_like_heading(text)}, reverse=True)
    paragraphs = []
    for text, size, _ in blocks:
        if round(size) in heading_sizes and _looks_like_heading(text):
            text = f"{'#' * min(heading_sizes.index(round(size)) + 1, 3)} {text}"
        paragraphs.append(text)
    return paragraphs


def mark_headings(text: str) -> str:
    """Erkennt Kapitelüberschriften im Text ('Kapitel 3', 'CHAPTER ONE', '12', 'DER ANFANG') und macht sie
    zu Markdown ('# Kapitel 3'). Enthält der Text schon Überschriften (Formatvorlagen, Schriftgröße, .md),
    bleibt er unverändert."""
    paragraphs = text.split("\n\n")
    if any(heading_level(p) for p in paragraphs):
        return text
    kinds: dict[int, str] = {}
    for i, paragraph in enumerate(p.strip() for p in paragraphs):
        if not _looks_like_heading(paragraph):
            continue
        if _PART_RE.match(paragraph):
            kinds[i] = "part"
        elif _CHAPTER_RE.match(paragraph) or _NUMBER_RE.match(paragraph):
            kinds[i] = "chapter"
        elif paragraph.isupper() and len(paragraph.split()) >= 2 and sum(c.isalpha() for c in paragraph) >= 3:
            kinds[i] = "chapter"
    if not kinds:
        return text
    chapter_level = 2 if "part" in kinds.values() else 1
    out: list[str] = []
    merged = -1
    for i, paragraph in enumerate(paragraphs):
        if i == merged:
            continue
        if i in kinds:
            title = paragraph.strip()
            following = paragraphs[i + 1].strip() if i + 1 < len(paragraphs) else ""
            if (i + 1) not in kinds and _is_subtitle(following):
                title, merged = f"{title} – {following}", i + 1  # 'Kapitel 1' + 'Der Anfang' = eine Überschrift
            paragraph = f"{'#' * (1 if kinds[i] == 'part' else chapter_level)} {title}"
        out.append(paragraph)
    return "\n\n".join(out)


def _is_subtitle(text: str) -> bool:
    return (0 < len(text) <= _MAX_SUBTITLE_CHARS and "\n" not in text and text[0].isupper()
            and len(text.split()) <= 8 and not text.endswith(_TERMINAL_PUNCTUATION))


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
