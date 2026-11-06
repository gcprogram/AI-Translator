"""Bilder des Originals. Im Text steht jedes Bild als eigener Absatz `[[Bild 12]]`. Das Sprachmodell sieht diese Platzhalter
nie: `extract_images` nimmt sie vor dem Übersetzen heraus, `reinsert_images` setzt sie danach an dieselbe Absatzposition
zurück (entspricht core/Images.kt der Android-App)."""
from __future__ import annotations

import hashlib
import re
import struct
from dataclasses import dataclass, field

from .chunking import split_paragraphs

_TOKEN = re.compile(r"\[\[Bild (\d+)\]\]")
_TOKEN_LINE = re.compile(r"^\[\[Bild (\d+)\]\]$")


@dataclass(frozen=True)
class ImageAsset:
    data: bytes
    mime: str


def image_token(image_id: int) -> str:
    return f"[[Bild {image_id}]]"


def image_ids(text: str) -> list[int]:
    """Alle Bild-Nummern im Text, in Reihenfolge (auch mehrfach)."""
    return [int(m.group(1)) for m in _TOKEN.finditer(text)]


def image_id_of_line(line: str) -> int | None:
    """Ist die Zeile genau ein Bild-Platzhalter? Liefert die Nummer."""
    match = _TOKEN_LINE.match(line.strip())
    return int(match.group(1)) if match else None


@dataclass(frozen=True)
class SplitImages:
    """Text ohne Bild-Absätze; `anchors` = (Anzahl Textabsätze davor, Bild-Nummer)."""
    text: str
    anchors: list[tuple[int, int]]
    text_paragraphs: int


def extract_images(text: str) -> SplitImages:
    paragraphs: list[str] = []
    anchors: list[tuple[int, int]] = []
    for paragraph in split_paragraphs(text):
        ids = [image_id_of_line(line) for line in paragraph.splitlines() if line.strip()]
        if ids and all(i is not None for i in ids):
            anchors.extend((len(paragraphs), i) for i in ids)
        else:
            paragraphs.append(paragraph)
    return SplitImages("\n\n".join(paragraphs), anchors, len(paragraphs))


def remove_image_tokens(text: str) -> str:
    return extract_images(text).text


def reinsert_images(translation: str, split: SplitImages) -> str:
    """Hat die Übersetzung gleich viele Absätze wie das Original, stehen die Bilder exakt an denselben Stellen;
    sonst proportional (Absatz 3 von 10 bleibt etwa bei 30 %)."""
    if not split.anchors:
        return translation
    paragraphs = split_paragraphs(translation)
    n = len(paragraphs)

    def position(after: int) -> int:
        if split.text_paragraphs == 0 or n == split.text_paragraphs:
            return min(after, n)
        return min(max(int(after * n / split.text_paragraphs + 0.5), 0), n)

    positions = [(position(after), image_id) for after, image_id in split.anchors]
    result: list[str] = []
    for i in range(n + 1):
        result.extend(image_token(image_id) for pos, image_id in positions if pos == i)
        if i < n:
            result.append(paragraphs[i])
    return "\n\n".join(result)


@dataclass
class ImageCollector:
    """Sammelt die Bilder beim Einlesen. Gleiche Bilder (Zierlinien, Logos) bekommen dieselbe Nummer."""
    images: dict[int, ImageAsset] = field(default_factory=dict)
    _by_hash: dict[str, int] = field(default_factory=dict)

    def add(self, data: bytes, mime: str) -> int:
        digest = hashlib.sha1(data).hexdigest()
        if digest not in self._by_hash:
            image_id = len(self.images) + 1
            self.images[image_id] = ImageAsset(data, mime)
            self._by_hash[digest] = image_id
        return self._by_hash[digest]


_MIME_BY_EXTENSION = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif",
                      "svg": "image/svg+xml", "webp": "image/webp", "bmp": "image/bmp"}
_EXTENSION_BY_MIME = {"image/png": "png", "image/jpeg": "jpg", "image/jpg": "jpg", "image/gif": "gif",
                      "image/svg+xml": "svg", "image/webp": "webp", "image/bmp": "bmp"}


def mime_for_extension(extension: str) -> str:
    return _MIME_BY_EXTENSION.get(extension.lower().lstrip("."), "application/octet-stream")


def extension_for_mime(mime: str) -> str:
    return _EXTENSION_BY_MIME.get(mime.lower(), "bin")


def sniff_mime(data: bytes) -> str | None:
    """Erkennt das Format an den ersten Bytes, falls Endung oder Angabe im Buch fehlt oder falsch ist."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:2] == b"\xff\xd8":
        return "image/jpeg"
    if data[:3] == b"GIF":
        return "image/gif"
    if data[:2] == b"BM":
        return "image/bmp"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if b"<svg" in data[:256]:
        return "image/svg+xml"
    return None


def sniff_size(data: bytes) -> tuple[int, int] | None:
    """Breite und Höhe in Pixeln für PNG, GIF, BMP und JPEG; None, wenn unbekannt."""
    kind = sniff_mime(data)
    size: tuple[int, int] | None = None
    try:
        if kind == "image/png" and len(data) >= 24:
            size = struct.unpack(">II", data[16:24])
        elif kind == "image/gif" and len(data) >= 10:
            size = struct.unpack("<HH", data[6:10])
        elif kind == "image/bmp" and len(data) >= 26:
            width, height = struct.unpack("<ii", data[18:26])
            size = (width, abs(height))
        elif kind == "image/jpeg":
            i = 2
            while i + 9 < len(data):
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker == 0xFF:
                    i += 1
                elif 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                    height, width = struct.unpack(">HH", data[i + 5:i + 9])
                    size = (width, height)
                    break
                elif marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                    i += 2
                else:
                    i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    except struct.error:
        return None
    return size if size and size[0] > 0 and size[1] > 0 else None
