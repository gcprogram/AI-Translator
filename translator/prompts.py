"""Prompt-Aufbau. Der TranslateGemma-Prompt bildet das Chat-Template des Modells nach."""
from __future__ import annotations

from .languages import language_name, normalize_code

GEMMA_STOP = ["<end_of_turn>"]


def gemma_prompt(text: str, source_code: str, target_code: str) -> str:
    """Roh-Prompt für /v1/completions (Template: jinja_template_aus_LM-Studio.txt, Variante 'text').

    Ergänzung gegenüber dem Original-Template: Markdown soll erhalten bleiben.
    """
    src, tgt = language_name(source_code), language_name(target_code)
    src_code, tgt_code = normalize_code(source_code), normalize_code(target_code)
    return (
        f"<start_of_turn>user\n"
        f"You are a professional {src} ({src_code}) to {tgt} ({tgt_code}) translator. "
        f"Your goal is to accurately convey the meaning and nuances of the original {src} text "
        f"while adhering to {tgt} grammar, vocabulary, and cultural sensitivities.\n"
        f"Produce only the {tgt} translation, without any additional explanations or commentary. "
        f"Preserve the Markdown format in the translation. "
        f"Please translate the following Markdown text from {src} into {tgt}:\n\n\n"
        f"{text.strip()}<end_of_turn>\n"
        f"<start_of_turn>model\n"
    )


def chat_system_prompt(template: str, source_code: str, target_code: str, glossary: dict[str, str]) -> str:
    prompt = template.replace("{source}", language_name(source_code)).replace("{target}", language_name(target_code))
    if glossary:
        terms = "\n".join(f"- {term} = {translation}" for term, translation in sorted(glossary.items()))
        prompt += f"\n\nUse this glossary consistently:\n{terms}"
    return prompt


def glossary_request(sample: str, target_code: str) -> str:
    return (
        f"Create a glossary for translation into {language_name(target_code)}. "
        "Extract only special and technical terms, proper names, and fictional terms. "
        "List only single words or connected word groups. "
        "Answer with one entry per line in the format 'Original: Translation'. "
        "If no term is found, answer with an empty message.\n\n"
        f"Text:\n{sample}"
    )
