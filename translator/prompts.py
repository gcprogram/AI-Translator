"""Prompt-Aufbau. Der TranslateGemma-Prompt bildet das Chat-Template des Modells nach."""
from __future__ import annotations

from .languages import language_name, normalize_code

GEMMA_STOP = ["<end_of_turn>"]

# (Original, Übersetzung) des Textes direkt vor dem aktuellen Abschnitt
Context = tuple[str, str]


def _glossary_lines(glossary: dict[str, str]) -> str:
    return "; ".join(f"{term} = {translation}" for term, translation in sorted(glossary.items()))


def _gemma_user_turn(text: str, source_code: str, target_code: str, glossary: dict[str, str]) -> str:
    src, tgt = language_name(source_code), language_name(target_code)
    src_code, tgt_code = normalize_code(source_code), normalize_code(target_code)
    terms = f"Always translate these terms exactly as given: {_glossary_lines(glossary)}. " if glossary else ""
    return (
        f"<start_of_turn>user\n"
        f"You are a professional {src} ({src_code}) to {tgt} ({tgt_code}) translator. "
        f"Your goal is to accurately convey the meaning and nuances of the original {src} text "
        f"while adhering to {tgt} grammar, vocabulary, and cultural sensitivities.\n"
        f"Produce only the {tgt} translation, without any additional explanations or commentary. "
        f"Preserve the Markdown format in the translation. {terms}"
        f"Please translate the following Markdown text from {src} into {tgt}:\n\n\n"
        f"{text.strip()}<end_of_turn>\n"
    )


def gemma_prompt(text: str, source_code: str, target_code: str,
                 glossary: dict[str, str] | None = None, context: Context | None = None) -> str:
    """Roh-Prompt für /v1/completions (Template: jinja_template_aus_LM-Studio.txt, Variante 'text').

    Ergänzungen gegenüber dem Original-Template: Markdown soll erhalten bleiben; passende Glossarbegriffe
    stehen als Satz im Anweisungstext; der Vorgängerabschnitt steht als bereits beantworteter Gesprächszug
    davor, damit Namen, Anrede und Ton über die Abschnittsgrenze hinweg gleich bleiben.
    """
    glossary = glossary or {}
    history = ""
    if context:
        history = (_gemma_user_turn(context[0], source_code, target_code, glossary)
                   + f"<start_of_turn>model\n{context[1].strip()}<end_of_turn>\n")
    return history + _gemma_user_turn(text, source_code, target_code, glossary) + "<start_of_turn>model\n"


def chat_system_prompt(template: str, source_code: str, target_code: str, glossary: dict[str, str]) -> str:
    prompt = template.replace("{source}", language_name(source_code)).replace("{target}", language_name(target_code))
    if glossary:
        terms = "\n".join(f"- {term} = {translation}" for term, translation in sorted(glossary.items()))
        prompt += f"\n\nUse this glossary consistently:\n{terms}"
    return prompt


def chat_messages(system: str, text: str, context: Context | None = None) -> list[dict[str, str]]:
    messages = [{"role": "system", "content": system}]
    if context:
        messages += [{"role": "user", "content": context[0]}, {"role": "assistant", "content": context[1]}]
    return messages + [{"role": "user", "content": text}]


def glossary_request(sample: str, target_code: str) -> str:
    return (
        f"Create a glossary for translation into {language_name(target_code)}. "
        "Extract only special and technical terms, proper names, and fictional terms. "
        "List only single words or connected word groups. "
        "Answer with one entry per line in the format 'Original: Translation'. "
        "If no term is found, answer with an empty message.\n\n"
        f"Text:\n{sample}"
    )
