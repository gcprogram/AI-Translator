"""Export in TXT, MD, DOCX, PDF und EPUB. Der Text wird einmal in Blöcke (Überschrift/Absatz) zerlegt,
daraus entstehen alle Formate: Überschriften werden echte Überschriften, Absätze bleiben Absätze,
**fett** und *kursiv* bleiben erhalten (TXT: ohne Auszeichnung)."""
from __future__ import annotations

import html
import re
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from .languages import normalize_code

SAVE_FILETYPES = [("Text", "*.txt"), ("Markdown", "*.md"), ("Word", "*.docx"), ("PDF", "*.pdf"), ("EPUB", "*.epub")]

# Schriftfamilien mit Unicode-Abdeckung (Tschechisch, Kyrillisch, Türkisch ...), je Stil eine Datei.
_PDF_FONT_FAMILIES = (
    {"": r"C:\Windows\Fonts\arial.ttf", "B": r"C:\Windows\Fonts\arialbd.ttf",
     "I": r"C:\Windows\Fonts\ariali.ttf", "BI": r"C:\Windows\Fonts\arialbi.ttf"},
    {"": "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "B": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
     "I": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf",
     "BI": "/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf"},
    {"": "/Library/Fonts/Arial.ttf", "B": "/Library/Fonts/Arial Bold.ttf",
     "I": "/Library/Fonts/Arial Italic.ttf", "BI": "/Library/Fonts/Arial Bold Italic.ttf"},
    {"": "/System/Library/Fonts/Supplemental/Arial.ttf", "B": "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
     "I": "/System/Library/Fonts/Supplemental/Arial Italic.ttf",
     "BI": "/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf"},
)
_HEADING_PT = {1: 20, 2: 16, 3: 14}  # PDF-Schriftgrößen, ab Ebene 4: 12
_BODY_PT = 11

_HEADING_LINE = re.compile(r"^(#{1,6})[ \t]+(.*\S)[ \t]*$")
_INLINE = re.compile(r"\*\*(?=\S)(.+?)(?<=\S)\*\*|\*(?=\S)(.+?)(?<=\S)\*")


@dataclass(frozen=True)
class Block:
    kind: str  # "heading" | "paragraph"
    text: str
    level: int = 0  # nur für Überschriften (1-6)


def parse_blocks(content: str) -> list[Block]:
    """Absätze (durch Leerzeilen getrennt) und '#'-Überschriften. Eine Überschrift direkt vor dem
    Fließtext ohne Leerzeile wird abgetrennt; Zeilenumbrüche innerhalb eines Absatzes bleiben erhalten."""
    blocks: list[Block] = []
    for chunk in re.split(r"\n[ \t]*\n", content.replace("\r\n", "\n")):
        lines: list[str] = []
        for line in chunk.split("\n"):
            heading = _HEADING_LINE.match(line.strip())
            if heading:
                if lines:
                    blocks.append(Block("paragraph", "\n".join(lines)))
                    lines = []
                blocks.append(Block("heading", heading.group(2).strip(), len(heading.group(1))))
            elif line.strip():
                lines.append(line.strip())
        if lines:
            blocks.append(Block("paragraph", "\n".join(lines)))
    return blocks


def inline_runs(text: str) -> list[tuple[str, bool, bool]]:
    """Zerlegt **fett**/*kursiv* in (Text, fett, kursiv)-Stücke."""
    runs: list[tuple[str, bool, bool]] = []
    pos = 0
    for match in _INLINE.finditer(text):
        if match.start() > pos:
            runs.append((text[pos:match.start()], False, False))
        bold = match.group(1) is not None
        runs.append((match.group(1) or match.group(2), bold, not bold))
        pos = match.end()
    if pos < len(text):
        runs.append((text[pos:], False, False))
    return runs


def strip_inline(text: str) -> str:
    return "".join(run for run, _, _ in inline_runs(text))


def chapter_level(blocks: list[Block]) -> int:
    """Überschriftenebene, die Kapitel trennt: die höchste Ebene, die mindestens zweimal vorkommt
    (eine einzelne Titelzeile ist kein Kapitel), sonst die höchste vorhandene. 0 = keine Überschriften."""
    levels = Counter(b.level for b in blocks if b.kind == "heading")
    if not levels:
        return 0
    return min((level for level, count in levels.items() if count >= 2), default=min(levels))


def split_chapters(blocks: list[Block], title: str) -> list[tuple[str, list[Block]]]:
    """(Kapiteltitel, Blöcke). Text vor der ersten Kapitelüberschrift bildet ein Kapitel mit dem Dokumenttitel."""
    level = chapter_level(blocks)
    if not level:
        return [(title, blocks)]
    chapters: list[tuple[str, list[Block]]] = []
    for block in blocks:
        if block.kind == "heading" and block.level <= level:
            chapters.append((strip_inline(block.text), []))
        elif not chapters:
            chapters.append((title, []))
        chapters[-1][1].append(block)
    return chapters


def to_markdown(blocks: list[Block]) -> str:
    return "\n\n".join(f"{'#' * b.level} {b.text}" if b.kind == "heading" else b.text for b in blocks) + "\n"


def to_plain_text(blocks: list[Block]) -> str:
    return "\n\n".join(strip_inline(b.text) for b in blocks) + "\n"


def save_document(content: str, path: Path | str, language: str = "de", title: str | None = None) -> None:
    path = Path(path)
    blocks = parse_blocks(content)
    title = title or path.stem
    ext = path.suffix.lower()
    if ext == ".docx":
        _save_docx(blocks, path, title)
    elif ext == ".pdf":
        _save_pdf(blocks, path, title)
    elif ext == ".epub":
        _save_epub(blocks, path, title, normalize_code(language))
    elif ext == ".md":
        path.write_text(to_markdown(blocks), encoding="utf-8")
    else:
        path.write_text(to_plain_text(blocks), encoding="utf-8")


def _save_docx(blocks: list[Block], path: Path, title: str) -> None:
    from docx import Document

    doc = Document()
    doc.core_properties.title = title
    level = chapter_level(blocks)
    for i, block in enumerate(blocks):
        if block.kind == "heading":
            heading = doc.add_heading(strip_inline(block.text), min(block.level, 9))
            if i and level and block.level <= level:
                heading.paragraph_format.page_break_before = True  # Kapitel beginnen auf neuer Seite
            continue
        paragraph = doc.add_paragraph()
        for text, bold, italic in inline_runs(block.text):
            for n, line in enumerate(text.split("\n")):
                run = paragraph.add_run()
                if n:
                    run.add_break()
                run.add_text(line)
                run.bold, run.italic = bold or None, italic or None
    doc.save(path)


def _pdf_font_files() -> dict[str, str]:
    for family in _PDF_FONT_FAMILIES:
        if Path(family[""]).exists():
            return {style: file for style, file in family.items() if Path(file).exists()}
    return {}


def _save_pdf(blocks: list[Block], path: Path, title: str) -> None:
    from fpdf import FPDF

    pdf = FPDF()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.set_title(title)
    pdf.add_page()
    fonts = _pdf_font_files()
    for style, file in fonts.items():
        pdf.add_font("Body", style, file)
    family = "Body" if fonts else "Helvetica"  # Kernschrift kann nur Latin-1

    def text_of(value: str) -> str:
        return value if fonts else value.encode("latin-1", "replace").decode("latin-1")

    def font(bold: bool, italic: bool, size: int) -> None:
        style = ("B" if bold else "") + ("I" if italic else "")
        pdf.set_font(family, style if not fonts or style in fonts else "", size)

    level = chapter_level(blocks)
    outline_depth = 0
    for i, block in enumerate(blocks):
        if block.kind == "heading":
            size = _HEADING_PT.get(block.level, 12)
            if i and level and block.level <= level:
                pdf.add_page()  # Kapitel beginnen auf neuer Seite
            elif pdf.will_page_break(size + 30):
                pdf.add_page()  # Überschrift nicht allein am Seitenende
            outline_depth = min(block.level - 1, outline_depth + 1)
            pdf.start_section(text_of(strip_inline(block.text)), outline_depth)
            font(True, False, size)
            pdf.multi_cell(0, size * 0.55, text_of(strip_inline(block.text)), new_x="LMARGIN", new_y="NEXT")
            pdf.ln(4)
            continue
        for text, bold, italic in inline_runs(block.text):
            font(bold, italic, _BODY_PT)
            pdf.write(6, text_of(text))
        pdf.ln(10)
    pdf.output(str(path))


def _html_inline(text: str) -> str:
    parts = []
    for run, bold, italic in inline_runs(text):
        escaped = html.escape(run).replace("\n", "<br/>")
        parts.append(f"<strong>{escaped}</strong>" if bold else f"<em>{escaped}</em>" if italic else escaped)
    return "".join(parts)


def _html_block(block: Block) -> str:
    if block.kind == "heading":
        return f"<h{block.level}>{html.escape(strip_inline(block.text))}</h{block.level}>"
    return f"<p>{_html_inline(block.text)}</p>"


def _save_epub(blocks: list[Block], path: Path, title: str, language: str) -> None:
    from ebooklib import epub

    book = epub.EpubBook()
    book.set_identifier(f"translation-{int(time.time())}")
    book.set_title(title)
    book.set_language(language)

    chapters = []
    for n, (chapter_title, chapter_blocks) in enumerate(split_chapters(blocks, title), start=1):
        body = "".join(_html_block(b) for b in chapter_blocks)
        if not any(b.kind == "heading" for b in chapter_blocks):  # Dokument ohne Überschriften
            body = f"<h1>{html.escape(chapter_title)}</h1>{body}"
        chapter = epub.EpubHtml(title=chapter_title, file_name=f"chap_{n}.xhtml", lang=language)
        chapter.content = body or "<p></p>"
        book.add_item(chapter)
        chapters.append(chapter)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.toc = tuple(chapters)
    book.spine = ["nav", *chapters]
    epub.write_epub(str(path), book)
