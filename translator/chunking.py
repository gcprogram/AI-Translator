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


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str


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
    if max_chars < 1:
        raise ValueError("max_chars muss positiv sein")
    chunks: list[str] = []
    current = ""
    for unit in _units(text, max_chars):
        if current and len(current) + len(unit) > max_chars:
            chunks.append(current)
            current = ""
        current += unit
    chunks.append(current)
    return [Chunk(i, c.strip()) for i, c in enumerate((c for c in chunks if c.strip()), start=1)]


def split_sentences(text: str) -> list[str]:
    return [s.strip() for s in _pieces(text.strip(), _SENTENCE_END) if s.strip()]


def boundary_sentences(text: str, mode: str = "last", count: int = 2) -> str:
    """Die ersten bzw. letzten `count` Sätze - als Orientierung beim manuellen Zusammenfügen."""
    sentences = split_sentences(text)
    selected = sentences[:count] if mode == "first" else sentences[-count:]
    return " ".join(selected)
