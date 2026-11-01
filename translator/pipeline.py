"""Übersetzungs-Pipeline ohne GUI-Abhängigkeit: Glossar erstellen, Abschnitte übersetzen, Qualität prüfen."""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator

from .chunking import Chunk, split_into_chunks
from .config import Config
from .glossary import GLOSSARY_PATH, load_glossary, merge_glossary, parse_glossary, save_glossary
from .llm_client import LLMClient, LLMError, Completion
from .prompts import GEMMA_STOP, chat_system_prompt, gemma_prompt, glossary_request

log = logging.getLogger(__name__)

MAX_OUTPUT_TOKENS = 4096
MAX_SPLIT_DEPTH = 2
MIN_SPLITTABLE_CHARS = 400
RETRY_TEMPERATURE = 0.3
_TERMINAL_PUNCTUATION = tuple(".!?;:…»«\"”’)。！？」』")

GlossaryReview = Callable[[dict[str, str]], dict[str, str]]


class Cancelled(Exception):
    pass


@dataclass
class ChunkResult:
    index: int
    source: str
    translation: str
    warnings: list[str] = field(default_factory=list)


class TranslationPipeline:
    def __init__(self, client: LLMClient, config: Config, glossary_path: Path | str = GLOSSARY_PATH):
        self.client = client
        self.config = config
        self.glossary_path = glossary_path

    # --- Glossar -----------------------------------------------------------------------------

    def build_glossary(self, chunks: list[Chunk], review: GlossaryReview | None = None) -> dict[str, str]:
        """KI-Vorschlag aus dem ersten Abschnitt + gespeichertes Glossar; `review` darf es korrigieren.
        TranslateGemma hat kein Glossar-Feld im Template, dort bleibt es leer."""
        if self.config.is_translategemma or not chunks:
            return {}
        try:
            reply = self.client.chat(
                self.config.model,
                [{"role": "user", "content": glossary_request(chunks[0].text, self.config.lang_target)}],
                max_tokens=1024,
            )
            proposal = parse_glossary(reply.text)
        except LLMError as exc:
            log.warning("Glossar-Vorschlag fehlgeschlagen: %s", exc)
            proposal = {}
        merged = merge_glossary(load_glossary(self.glossary_path), proposal)
        if review is not None:
            merged = review(merged)
        save_glossary(merged, self.glossary_path)
        return merged

    # --- Übersetzung -------------------------------------------------------------------------

    def translate(self, chunks: list[Chunk], glossary: dict[str, str] | None = None,
                  cancel: threading.Event | None = None) -> Iterator[ChunkResult]:
        for chunk in chunks:
            if cancel is not None and cancel.is_set():
                raise Cancelled()
            yield self.translate_chunk(chunk, glossary or {})

    def translate_chunk(self, chunk: Chunk, glossary: dict[str, str]) -> ChunkResult:
        text, warnings = self._translate_text(chunk.text, glossary, depth=0)
        if warnings:
            log.warning("Abschnitt %d: %s", chunk.index, "; ".join(warnings))
        return ChunkResult(chunk.index, chunk.text, text, warnings)

    def _translate_text(self, text: str, glossary: dict[str, str], depth: int) -> tuple[str, list[str]]:
        completion = self._generate(text, glossary, temperature=0.0)

        # Abgeschnitten: eine Wiederholung mit identischen Parametern lieferte dasselbe Ergebnis,
        # deshalb den Text halbieren und die Hälften einzeln übersetzen.
        if completion.finish_reason == "length" and depth < MAX_SPLIT_DEPTH and len(text) > MIN_SPLITTABLE_CHARS:
            parts = split_into_chunks(text, len(text) // 2 + 100)
            if len(parts) > 1:
                outputs, warnings = [], ["Abschnitt wegen Token-Limit geteilt und neu übersetzt"]
                for part in parts:
                    out, part_warnings = self._translate_text(part.text, glossary, depth + 1)
                    outputs.append(out)
                    warnings.extend(w for w in part_warnings if w not in warnings)
                return "\n\n".join(outputs), warnings

        warnings = self._check(text, completion)
        if warnings and completion.finish_reason != "length":
            retry = self._generate(text, glossary, temperature=RETRY_TEMPERATURE)
            retry_warnings = self._check(text, retry)
            if len(retry_warnings) < len(warnings):
                return retry.text, retry_warnings
        return completion.text, warnings

    def _generate(self, text: str, glossary: dict[str, str], temperature: float) -> Completion:
        cfg = self.config
        if cfg.is_translategemma:
            return self.client.complete(
                cfg.model, gemma_prompt(text, cfg.lang_source, cfg.lang_target),
                max_tokens=MAX_OUTPUT_TOKENS, temperature=temperature, stop=GEMMA_STOP,
            )
        system = chat_system_prompt(cfg.translation_prompt, cfg.lang_source, cfg.lang_target, glossary)
        return self.client.chat(
            cfg.model, [{"role": "system", "content": system}, {"role": "user", "content": text}],
            max_tokens=MAX_OUTPUT_TOKENS, temperature=temperature,
        )

    @staticmethod
    def _check(source: str, completion: Completion) -> list[str]:
        warnings = []
        if completion.finish_reason == "length":
            warnings.append("Ausgabe abgeschnitten (Token-Limit)")
        if not completion.text:
            warnings.append("Leere Antwort")
        elif source.rstrip().endswith(_TERMINAL_PUNCTUATION) and not completion.text.rstrip().endswith(_TERMINAL_PUNCTUATION):
            warnings.append("Übersetzung endet ohne Satzzeichen - evtl. unvollständig")
        return warnings
