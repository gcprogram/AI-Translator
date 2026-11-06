"""Übersetzungs-Pipeline ohne GUI-Abhängigkeit: Glossar erstellen, Abschnitte übersetzen, Qualität prüfen."""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Callable, Iterator

from .chunking import Chunk, restore_headings, split_into_chunks, split_paragraphs
from .config import Config
from .images import extract_images, reinsert_images, remove_image_tokens
from .glossary import GLOSSARY_PATH, load_glossary, merge_glossary, missing_terms, parse_glossary, \
    relevant_terms, save_glossary
from .llm_client import LLMClient, LLMError, Completion
from .prompts import GEMMA_STOP, Context, chat_messages, chat_system_prompt, gemma_prompt, glossary_request

log = logging.getLogger(__name__)

MAX_OUTPUT_TOKENS = 4096
MAX_SPLIT_DEPTH = 2
MIN_SPLITTABLE_CHARS = 400
RETRY_TEMPERATURE = 0.3
CONTEXT_CHARS = 1200  # Umfang des Vorgängertextes, der als Kontext mitgeschickt wird
# Wartezeiten (Sekunden) vor den Wiederholungen bei vorübergehenden Verbindungsfehlern (WLAN-Aussetzer, Server beschäftigt)
CONNECTION_RETRY_DELAYS = (3.0, 10.0, 30.0)
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


def build_context(source: str, translation: str, budget: int = CONTEXT_CHARS) -> Context | None:
    """Die letzten Absätze von Original und Übersetzung als Paar. Nur wenn beide gleich viele Absätze
    haben, sonst ließe sich nicht sagen, welcher Satz zu welchem gehört - dann lieber kein Kontext."""
    src = split_paragraphs(remove_image_tokens(source))  # Bildplatzhalter gehören nicht in den Kontext
    out = split_paragraphs(remove_image_tokens(translation))
    if not src or len(src) != len(out):
        return None
    taken, size = 0, 0
    for paragraph in reversed(src):
        if taken and size + len(paragraph) > budget:
            break
        taken += 1
        size += len(paragraph)
    return "\n\n".join(src[-taken:]), "\n\n".join(out[-taken:])


def _strip_context_echo(text: str, context_translation: str) -> str:
    """Manche Modelle übersetzen den Kontext erneut mit; dann steht er vorn in der Ausgabe.
    Bleibt nach dem Abschneiden nichts übrig, war es keine Wiederholung und die Ausgabe bleibt."""
    echo = context_translation.strip()
    if echo and text.startswith(echo):
        return text[len(echo):].lstrip() or text
    return text


class TranslationPipeline:
    """`on_retry(versuch, grund)` meldet eine Wiederholung nach einem vorübergehenden Verbindungsfehler. Ein begonnener Stream
    wird dabei verworfen: der Aufrufer sollte den bisher angezeigten Text des Abschnitts zurücksetzen. `sleep` ersetzt das
    Warten (für Tests)."""

    def __init__(self, client: LLMClient, config: Config, glossary_path: Path | str | None = None,
                 on_retry: Callable[[int, str], None] | None = None, sleep: Callable[[float], None] | None = None):
        self.client = client
        self.config = config
        self.glossary_path = Path(glossary_path or config.glossary_path or GLOSSARY_PATH)
        self.on_retry = on_retry
        self._sleep = sleep
        self._cancel: threading.Event | None = None

    # --- Glossar -----------------------------------------------------------------------------

    def build_glossary(self, chunks: list[Chunk], review: GlossaryReview | None = None) -> dict[str, str]:
        """Gespeichertes Glossar; bei Chat-Modellen zusätzlich KI-Vorschlag aus dem ersten Abschnitt,
        den `review` korrigieren darf. TranslateGemma ist kein Chat-Modell und schlägt nichts vor -
        dort pflegt der Benutzer das Glossar selbst."""
        if self.config.is_translategemma:
            return load_glossary(self.glossary_path)
        if not chunks:
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
                  cancel: threading.Event | None = None,
                  on_delta: Callable[[Chunk, str], None] | None = None,
                  initial_context: Context | None = None) -> Iterator[ChunkResult]:
        """Mit on_delta wird gestreamt: jedes neue Textstück des ersten Übersetzungsversuchs geht sofort an
        on_delta(chunk, text). Das endgültige Ergebnis kann davon abweichen (Wiederholung, Teilung,
        Überschriften-Korrektur) und ersetzt den Streamtext. Abbrechen wirkt dann auch mitten im Abschnitt.
        `initial_context` ist der Kontext für den ersten Abschnitt, wenn eine Übersetzung fortgesetzt wird."""
        self._cancel = cancel
        previous: ChunkResult | None = None
        for chunk in chunks:
            if cancel is not None and cancel.is_set():
                raise Cancelled()
            context = None
            if self.config.use_context:
                context = build_context(previous.source, previous.translation) if previous is not None else initial_context
            stream = None
            if on_delta is not None:
                def stream(delta: str, chunk: Chunk = chunk) -> None:
                    if cancel is not None and cancel.is_set():
                        raise Cancelled()
                    on_delta(chunk, delta)
            previous = self.translate_chunk(chunk, glossary or {}, context, stream)
            yield previous

    def translate_chunk(self, chunk: Chunk, glossary: dict[str, str], context: Context | None = None,
                        on_delta: Callable[[str], None] | None = None) -> ChunkResult:
        # Bildplatzhalter sieht das Modell nie: heraus vor dem Übersetzen, danach an dieselbe Stelle zurück
        split = extract_images(chunk.text)
        if not split.text.strip():  # nur Bilder: kein Modellaufruf nötig
            return ChunkResult(chunk.index, chunk.text, reinsert_images("", split))
        text, warnings = self._translate_text(split.text, glossary, context, depth=0, on_delta=on_delta)
        text = reinsert_images(restore_headings(split.text, text), split)
        if warnings:
            log.warning("Abschnitt %d: %s", chunk.index, "; ".join(warnings))
        return ChunkResult(chunk.index, chunk.text, text, warnings)

    def _translate_text(self, text: str, glossary: dict[str, str], context: Context | None,
                        depth: int, on_delta: Callable[[str], None] | None = None) -> tuple[str, list[str]]:
        terms = relevant_terms(glossary, text)
        completion = self._generate(text, terms, context, temperature=0.0, on_delta=on_delta)

        # Abgeschnitten: eine Wiederholung mit identischen Parametern lieferte dasselbe Ergebnis,
        # deshalb den Text halbieren und die Hälften einzeln übersetzen.
        if completion.finish_reason == "length" and depth < MAX_SPLIT_DEPTH and len(text) > MIN_SPLITTABLE_CHARS:
            parts = split_into_chunks(text, len(text) // 2 + 100)
            if len(parts) > 1:
                outputs, warnings = [], ["Abschnitt wegen Token-Limit geteilt und neu übersetzt"]
                part_context = context
                for part in parts:
                    out, part_warnings = self._translate_text(part.text, glossary, part_context, depth + 1)
                    outputs.append(out)
                    part_context = build_context(part.text, out) if self.config.use_context else None
                    warnings.extend(w for w in part_warnings if w not in warnings)
                return "\n\n".join(outputs), warnings

        warnings = self._check(text, completion, terms)
        if warnings and completion.finish_reason != "length":
            retry = self._generate(text, terms, context, temperature=RETRY_TEMPERATURE)
            retry_warnings = self._check(text, retry, terms)
            if len(retry_warnings) < len(warnings):
                return retry.text, retry_warnings
        return completion.text, warnings

    def _generate(self, text: str, terms: dict[str, str], context: Context | None,
                  temperature: float, on_delta: Callable[[str], None] | None = None) -> Completion:
        cfg = self.config
        extra = {"on_delta": on_delta} if on_delta is not None else {}

        def call() -> Completion:
            if cfg.is_translategemma:
                return self.client.complete(
                    cfg.model, gemma_prompt(text, cfg.lang_source, cfg.lang_target, terms, context),
                    max_tokens=MAX_OUTPUT_TOKENS, temperature=temperature, stop=GEMMA_STOP, **extra,
                )
            system = chat_system_prompt(cfg.translation_prompt, cfg.lang_source, cfg.lang_target, terms)
            return self.client.chat(
                cfg.model, chat_messages(system, text, context),
                max_tokens=MAX_OUTPUT_TOKENS, temperature=temperature, **extra,
            )

        completion = self._retrying_on_connection_errors(call)
        if context:
            completion = replace(completion, text=_strip_context_echo(completion.text, context[1]))
        return completion

    def _retrying_on_connection_errors(self, call: Callable[[], Completion]) -> Completion:
        """Wiederholt nur vorübergehende Verbindungsfehler; alles andere (und der letzte Fehlschlag) geht nach oben."""
        attempt = 0
        while True:
            try:
                return call()
            except LLMError as exc:
                if not exc.transient or attempt >= len(CONNECTION_RETRY_DELAYS):
                    raise
                if self.on_retry is not None:
                    self.on_retry(attempt + 1, str(exc))
                self._wait(CONNECTION_RETRY_DELAYS[attempt])
                attempt += 1

    def _wait(self, seconds: float) -> None:
        if self._sleep is not None:
            self._sleep(seconds)
        elif self._cancel is not None:
            if self._cancel.wait(seconds):  # Abbrechen unterbricht das Warten sofort
                raise Cancelled()
        else:
            time.sleep(seconds)

    @staticmethod
    def _check(source: str, completion: Completion, terms: dict[str, str]) -> list[str]:
        warnings = []
        if completion.finish_reason == "length":
            warnings.append("Ausgabe abgeschnitten (Token-Limit)")
        if not completion.text:
            warnings.append("Leere Antwort")
        elif source.rstrip().endswith(_TERMINAL_PUNCTUATION) and not completion.text.rstrip().endswith(_TERMINAL_PUNCTUATION):
            warnings.append("Übersetzung endet ohne Satzzeichen - evtl. unvollständig")
        if completion.text:
            warnings.extend(f"Glossar nicht eingehalten: {term} → {terms[term]}"
                            for term in missing_terms(terms, completion.text))
        return warnings
