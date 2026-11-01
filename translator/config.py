"""Konfiguration. Defekte oder unvollständige config.json führt nie zum Absturz, sondern zu Defaults."""
from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path

from .languages import normalize_code

log = logging.getLogger(__name__)

APP_DIR = Path(__file__).resolve().parent.parent
CONFIG_PATH = APP_DIR / "config.json"

# {source}/{target} werden beim Senden ersetzt. Wird nur für Nicht-TranslateGemma-Modelle benutzt.
DEFAULT_PROMPT = (
    "Translate the following text from {source} to {target}. "
    "Output only the translation, without comments. Preserve Markdown formatting."
)

MIN_CHUNK_CHARS = 500
MAX_CHUNK_CHARS = 20000


@dataclass
class Config:
    ip: str = "127.0.0.1"
    port: str = "1234"
    api_key: str = ""
    model: str = ""
    models: list[str] = field(default_factory=list)
    lang_source: str = "en"
    lang_target: str = "de"
    translation_prompt: str = DEFAULT_PROMPT
    max_chunk_chars: int = 3500
    use_context: bool = True  # Vorgängerabschnitt (Original + Übersetzung) als Kontext mitsenden
    glossary_path: str = ""  # leer = dictionary.txt im Programmordner

    @property
    def base_url(self) -> str:
        return f"http://{self.ip}:{self.port}"

    @property
    def is_translategemma(self) -> bool:
        return "translategemma" in self.model.lower()

    def sanitize(self) -> "Config":
        self.ip = str(self.ip).strip() or "127.0.0.1"
        self.port = str(self.port).strip() or "1234"
        self.lang_source = normalize_code(str(self.lang_source)) or "en"
        self.lang_target = normalize_code(str(self.lang_target)) or "de"
        try:
            chars = int(self.max_chunk_chars)
        except (TypeError, ValueError):
            chars = 3500
        self.max_chunk_chars = max(MIN_CHUNK_CHARS, min(MAX_CHUNK_CHARS, chars))
        self.use_context = bool(self.use_context)
        self.glossary_path = str(self.glossary_path or "").strip()
        if not isinstance(self.models, list):
            self.models = []
        if not str(self.translation_prompt).strip():
            self.translation_prompt = DEFAULT_PROMPT
        return self

    @classmethod
    def load(cls, path: Path | str = CONFIG_PATH) -> "Config":
        path = Path(path)
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise ValueError("Wurzelelement ist kein Objekt")
        except (OSError, ValueError) as exc:
            log.error("Konfiguration %s nicht lesbar (%s) - verwende Defaults.", path, exc)
            return cls()
        known = {f.name for f in fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known}).sanitize()

    def save(self, path: Path | str = CONFIG_PATH) -> None:
        self.sanitize()
        Path(path).write_text(json.dumps(asdict(self), indent=4, ensure_ascii=False), encoding="utf-8")
