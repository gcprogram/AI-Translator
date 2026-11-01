"""Sprachcodes und -namen. TranslateGemma erwartet ISO-Codes mit englischem Sprachnamen."""
from __future__ import annotations

LANGUAGES: dict[str, str] = {
    "af": "Afrikaans", "ar": "Arabic", "az": "Azerbaijani", "be": "Belarusian",
    "bg": "Bulgarian", "bn": "Bengali", "ca": "Catalan", "cs": "Czech",
    "cy": "Welsh", "da": "Danish", "de": "German", "el": "Greek",
    "en": "English", "es": "Spanish", "et": "Estonian", "fa": "Persian",
    "fi": "Finnish", "fr": "French", "ga": "Irish", "gl": "Galician",
    "gu": "Gujarati", "he": "Hebrew", "hi": "Hindi", "hr": "Croatian",
    "hu": "Hungarian", "hy": "Armenian", "id": "Indonesian", "is": "Icelandic",
    "it": "Italian", "ja": "Japanese", "ka": "Georgian", "kk": "Kazakh",
    "km": "Khmer", "kn": "Kannada", "ko": "Korean", "lt": "Lithuanian",
    "lv": "Latvian", "mk": "Macedonian", "ml": "Malayalam", "mn": "Mongolian",
    "mr": "Marathi", "ms": "Malay", "mt": "Maltese", "my": "Burmese",
    "nl": "Dutch", "no": "Norwegian", "pa": "Punjabi", "pl": "Polish",
    "pt": "Portuguese", "ro": "Romanian", "ru": "Russian", "sk": "Slovak",
    "sl": "Slovenian", "sq": "Albanian", "sr": "Serbian", "sv": "Swedish",
    "sw": "Swahili", "ta": "Tamil", "te": "Telugu", "th": "Thai",
    "tr": "Turkish", "uk": "Ukrainian", "ur": "Urdu", "uz": "Uzbek",
    "vi": "Vietnamese", "zh": "Chinese",
}


def normalize_code(code: str) -> str:
    """'de-DE' / 'de_DE' / 'DE' -> 'de'. Unbekannte Codes bleiben (klein geschrieben) erhalten."""
    return code.strip().replace("_", "-").split("-")[0].lower()


def language_name(code: str) -> str:
    """Englischer Sprachname; bei unbekanntem Code der Code selbst (nie ein Platzhalter-Text)."""
    return LANGUAGES.get(normalize_code(code), code)


def display_name(code: str) -> str:
    code = normalize_code(code)
    return f"{language_name(code)} ({code})"


def code_from_display(text: str) -> str:
    """'German (de)' -> 'de'. Akzeptiert auch einen nackten Code."""
    if "(" in text and text.rstrip().endswith(")"):
        return normalize_code(text[text.rfind("(") + 1:text.rfind(")")])
    return normalize_code(text)


DISPLAY_LIST: list[str] = sorted(display_name(c) for c in LANGUAGES)
