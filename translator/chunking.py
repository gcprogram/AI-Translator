"""Zerlegt Text in Abschnitte, ohne Sätze zu zerreißen und ohne Text zu verlieren."""
from __future__ import annotations

import re
from dataclasses import dataclass

# Satzende inkl. schließender Anführungszeichen. Westliche Zeichen verlangen folgenden Leerraum,
# CJK-Satzzeichen nicht (dort steht kein Leerzeichen dahinter).
_SENTENCE_END = re.compile(
    r"""(?:[.!?…]["'»”’)\]]*\s+|[。！？]["」』”’)\]]*\s*)"""
)
_PARAGRAPH = re.compile(r".*?(?:\n[ \t]*\n\s*|\Z)", re.DOTALL)
_HEADING = re.compile(r"^(#{1,6})[ \t]+\S")

# Ein Kapitel beginnt einen neuen Abschnitt, sobald der aktuelle mindestens so viel vom Limit füllt.
_CHAPTER_BREAK_FILL = 4


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str


def heading_level(text: str) -> int:
    """1-6 für Markdown-Überschriften ('## Titel'), sonst 0."""
    match = _HEADING.match(text.lstrip())
    return len(match.group(1)) if match else 0


def split_paragraphs(text: str) -> list[str]:
    return [p.strip() for p in re.split(r"\n[ \t]*\n", text) if p.strip()]


def restore_headings(source: str, translation: str) -> str:
    """Kleine Modelle verlieren gern das '#' vor Überschriften. Stimmt die Absatzzahl überein,
    wird das Präfix aus dem Original wieder eingesetzt; sonst bleibt die Übersetzung unverändert."""
    src, out = split_paragraphs(source), split_paragraphs(translation)
    if len(src) != len(out):
        return translation
    changed = False
    for i, (s, t) in enumerate(zip(src, out)):
        level = heading_level(s)
        if level and not heading_level(t):
            out[i] = f"{'#' * level} {t.lstrip('#').strip()}"
            changed = True
    return "\n\n".join(out) if changed else translation


def _pieces(text: str, pattern: re.Pattern[str]) -> list[str]:
    """Zerlegt text nach pattern; das Trennstück bleibt am vorherigen Teil (nichts geht verloren)."""
    pieces, pos = [], 0
    for match in pattern.finditer(text):
        if match.end() == pos:
            continue
        pieces.append(text[pos:match.end()])
        pos = match.end()
    if pos < len(text):
        pieces.append(text[pos:])
    return [p for p in pieces if p]


def _hard_split(text: str, limit: int) -> list[str]:
    parts = []
    while len(text) > limit:
        cut = text.rfind(" ", 0, limit)
        if cut < limit // 2:  # kein brauchbares Leerzeichen (z. B. CJK)
            cut = limit
        parts.append(text[:cut])
        text = text[cut:]
    parts.append(text)
    return parts


def _units(text: str, limit: int) -> list[str]:
    """Absätze; überlange Absätze werden an Satzenden, überlange Sätze hart geteilt."""
    units: list[str] = []
    for paragraph in _pieces(text, _PARAGRAPH):
        if len(paragraph) <= limit:
            units.append(paragraph)
            continue
        for sentence in _pieces(paragraph, _SENTENCE_END):
            units.extend([sentence] if len(sentence) <= limit else _hard_split(sentence, limit))
    return units


def split_into_chunks(text: str, max_chars: int) -> list[Chunk]:
    """Abschnitte an Absatzgrenzen. Eine Überschrift bleibt bei ihrem Folgeabsatz, und ein Kapitel
    (Überschrift Ebene 1-2) beginnt nach Möglichkeit einen neuen Abschnitt."""
    if max_chars < 1:
        raise ValueError("max_chars muss positiv sein")
    chunks: list[list[str]] = []
    current: list[str] = []
    size = 0
    for unit in _units(text, max_chars):
        starts_chapter = 0 < heading_level(unit) <= 2 and size >= max_chars // _CHAPTER_BREAK_FILL
        if current and (size + len(unit) > max_chars or starts_chapter):
            carry: list[str] = []
            while len(current) > 1 and heading_level(current[-1]):  # keine Überschrift am Abschnittsende
                carry.insert(0, current.pop())
            chunks.append(current)
            current, size = carry, sum(len(u) for u in carry)
        current.append(unit)
        size += len(unit)
    chunks.append(current)
    texts = ("".join(units).strip() for units in chunks)
    return [Chunk(i, t) for i, t in enumerate((t for t in texts if t), start=1)]
