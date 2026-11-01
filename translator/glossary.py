"""Glossar: 'Begriff: Übersetzung'-Zeilen, Groß-/Kleinschreibung bleibt erhalten."""
from __future__ import annotations

import re
from pathlib import Path

from .config import APP_DIR

GLOSSARY_PATH = APP_DIR / "dictionary.txt"

_BULLET = re.compile(r"^\s*(?:[-*•]+|\d+[.)])\s+")


def parse_glossary(text: str) -> dict[str, str]:
    """Liest Zeilen 'Begriff: Übersetzung' - auch KI-Antworten mit Aufzählungszeichen oder **fett**."""
    glossary: dict[str, str] = {}
    for line in text.splitlines():
        line = _BULLET.sub("", line).replace("**", "").replace("`", "")
        if ":" not in line:
            continue
        term, translation = (part.strip() for part in line.split(":", 1))
        if term and translation:
            glossary[term] = translation
    return glossary


def merge_glossary(existing: dict[str, str], proposal: dict[str, str]) -> dict[str, str]:
    """Bestehende Einträge gewinnen; nur wirklich neue Begriffe (case-insensitiv) kommen hinzu."""
    merged = dict(existing)
    known = {term.lower() for term in merged}
    for term, translation in proposal.items():
        if term.lower() not in known:
            merged[term] = translation
            known.add(term.lower())
    return merged


def _word_start(term: str) -> re.Pattern[str]:
    """Wortanfang + Begriff, ohne Wortende: findet auch Beugung und Plural ('Potion' in 'Potions')."""
    return re.compile(rf"(?<!\w){re.escape(term)}", re.IGNORECASE)


def relevant_terms(glossary: dict[str, str], text: str) -> dict[str, str]:
    """Nur die Begriffe, die im Text vorkommen - hält den Prompt klein."""
    return {term: tr for term, tr in glossary.items() if _word_start(term).search(text)}


def missing_terms(terms: dict[str, str], translation: str) -> list[str]:
    """Begriffe, deren vorgegebene Übersetzung im Ergebnis fehlt. Die letzten zwei Buchstaben
    werden ignoriert, damit deutsche Beugung ('Vielsafttranks') keinen Fehlalarm auslöst."""
    return [term for term, tr in terms.items()
            if not _word_start(tr[:-2] if len(tr) >= 6 else tr).search(translation)]


def format_glossary(glossary: dict[str, str]) -> str:
    return "".join(f"{term}: {glossary[term]}\n" for term in sorted(glossary, key=str.lower))


def load_glossary(path: Path | str = GLOSSARY_PATH) -> dict[str, str]:
    path = Path(path)
    if not path.exists():
        return {}
    return parse_glossary(path.read_text(encoding="utf-8"))


def save_glossary(glossary: dict[str, str], path: Path | str = GLOSSARY_PATH) -> None:
    Path(path).write_text(format_glossary(glossary), encoding="utf-8")
