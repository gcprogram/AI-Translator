"""Prüft den LM-Studio-Server: Modellliste, BOS-Verhalten von /v1/completions, Probeübersetzung.

Token: Umgebungsvariable LM_API_TOKEN oder `api_key` in config.json.
Aufruf (im Projektordner): python scripts/check_server.py [modell-id]
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from translator.config import Config  # noqa: E402
from translator.llm_client import LLMClient, LLMError  # noqa: E402
from translator.prompts import GEMMA_STOP, gemma_prompt  # noqa: E402


def prompt_tokens(client: LLMClient, model: str, prompt: str) -> int:
    return client.complete(model, prompt, max_tokens=1).prompt_tokens


def main() -> int:
    cfg = Config.load()
    client = LLMClient(cfg.base_url, os.environ.get("LM_API_TOKEN") or cfg.api_key)
    try:
        models = client.list_models()
    except LLMError as exc:
        print(f"Server nicht nutzbar: {exc}")
        return 1
    print("Modelle:", ", ".join(models))
    model = sys.argv[1] if len(sys.argv) > 1 else next((m for m in models if "translategemma" in m.lower()), cfg.model)
    print("Verwende:", model)

    # BOS-Test: "Hello" ist ein Token. 1 => kein automatisches BOS, 2 => BOS wird ergänzt.
    # "<bos>Hello": +1 gegenüber "Hello" => <bos> wird als Spezialtoken erkannt (sonst deutlich mehr).
    n1, n2 = prompt_tokens(client, model, "Hello"), prompt_tokens(client, model, "<bos>Hello")
    print(f"prompt_tokens: 'Hello'={n1}  '<bos>Hello'={n2}")
    if n1 == 2 and n2 == 3:
        print("-> BOS wird automatisch ergänzt, '<bos>' als Text würde doppelt zählen: Prompt ohne <bos> lassen (aktueller Stand).")
    elif n1 == 1 and n2 == 2:
        print("-> KEIN automatisches BOS: gemma_prompt() muss '<bos>' voranstellen.")
    else:
        print("-> Uneindeutig (Tokenisierung/Usage prüfen), Rohwerte oben an den Entwickler-Chat melden.")

    sample = "The quick brown fox jumps over the lazy dog. It was a sunny day."
    result = client.complete(model, gemma_prompt(sample, "en", "de"), max_tokens=200, stop=GEMMA_STOP)
    print(f"\nProbeübersetzung (finish_reason={result.finish_reason}, prompt_tokens={result.prompt_tokens}):")
    print(result.text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
