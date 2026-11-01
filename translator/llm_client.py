"""HTTP-Client für LM Studio bzw. jeden OpenAI-kompatiblen Server. Fehler werden als LLMError geworfen,
nie als Text zurückgegeben (sonst landet eine Fehlermeldung als 'Übersetzung' im Dokument)."""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Callable

import requests


class LLMError(Exception):
    pass


@dataclass(frozen=True)
class Completion:
    text: str
    finish_reason: str  # "stop" = fertig, "length" = Token-Limit erreicht (Ausgabe abgeschnitten)
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LLMClient:
    def __init__(self, base_url: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._session = requests.Session()

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _request(self, method: str, path: str, timeout: float, **kwargs) -> dict:
        url = f"{self.base_url}{path}"
        try:
            response = self._session.request(method, url, headers=self._headers(), timeout=timeout, **kwargs)
        except requests.Timeout as exc:
            raise LLMError(f"Zeitüberschreitung bei {url}") from exc
        except requests.RequestException as exc:
            raise LLMError(f"Server nicht erreichbar ({url}): {exc}") from exc
        if response.status_code == 401:
            raise LLMError("Nicht autorisiert - API-Schlüssel prüfen.")
        if response.status_code != 200:
            raise LLMError(f"Serverfehler {response.status_code}: {response.text[:300]}")
        try:
            return response.json()
        except ValueError as exc:
            raise LLMError(f"Antwort ist kein gültiges JSON: {response.text[:200]}") from exc

    def list_models(self) -> list[str]:
        """Bevorzugt LM Studios native API (/api/v1/models, ohne Embedding-Modelle), sonst /v1/models."""
        try:
            data = self._request("GET", "/api/v1/models", timeout=10)
            models = [m["key"] for m in data.get("models", []) if "key" in m and m.get("type") != "embedding"]
            if models:
                return models
        except LLMError:
            pass
        data = self._request("GET", "/v1/models", timeout=10)
        return [m["id"] for m in data.get("data", []) if "id" in m]

    def _completion(self, path: str, payload: dict, timeout: float, extract) -> Completion:
        data = self._request("POST", path, timeout=timeout, json=payload)
        try:
            choice = data["choices"][0]
            usage = data.get("usage") or {}
            return Completion(
                text=extract(choice).strip(),
                finish_reason=choice.get("finish_reason") or "stop",
                prompt_tokens=usage.get("prompt_tokens", 0),
                completion_tokens=usage.get("completion_tokens", 0),
            )
        except (KeyError, IndexError, TypeError) as exc:
            raise LLMError(f"Unerwartetes Antwortformat: {str(data)[:300]}") from exc

    def _stream(self, path: str, payload: dict, timeout: float, extract_delta,
                on_delta: Callable[[str], None]) -> Completion:
        """Server-Sent Events lesen und jedes Textstück an on_delta geben. Wirft on_delta (z. B. beim
        Abbrechen), wird die Verbindung geschlossen und die Ausnahme weitergereicht."""
        url = f"{self.base_url}{path}"
        try:
            response = self._session.post(url, headers=self._headers(), json=payload, timeout=timeout, stream=True)
        except requests.Timeout as exc:
            raise LLMError(f"Zeitüberschreitung bei {url}") from exc
        except requests.RequestException as exc:
            raise LLMError(f"Server nicht erreichbar ({url}): {exc}") from exc
        with response:
            if response.status_code == 401:
                raise LLMError("Nicht autorisiert - API-Schlüssel prüfen.")
            if response.status_code != 200:
                raise LLMError(f"Serverfehler {response.status_code}: {response.text[:300]}")
            parts: list[str] = []
            finish_reason = ""
            try:
                for raw in response.iter_lines():  # Bytes: requests würde text/event-stream als Latin-1 lesen
                    line = raw.decode("utf-8", errors="replace").strip()
                    if not line.startswith("data:"):
                        continue
                    body = line[5:].strip()
                    if body == "[DONE]":
                        break
                    data = json.loads(body)
                    if "error" in data:
                        raise LLMError(f"Serverfehler im Stream: {str(data['error'])[:300]}")
                    choice = data["choices"][0]
                    delta = extract_delta(choice) or ""
                    if delta:
                        parts.append(delta)
                        on_delta(delta)
                    finish_reason = choice.get("finish_reason") or finish_reason
            except requests.RequestException as exc:
                raise LLMError(f"Verbindung während der Übersetzung abgebrochen: {exc}") from exc
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                raise LLMError(f"Unerwartetes Stream-Format: {exc}") from exc
        if not finish_reason:  # Stream ohne Abschluss: lieber als abgeschnitten behandeln als als fertig
            finish_reason = "length"
        return Completion(text="".join(parts).strip(), finish_reason=finish_reason)

    def complete(self, model: str, prompt: str, *, max_tokens: int, temperature: float = 0.0,
                 stop: list[str] | None = None, timeout: float = 300,
                 on_delta: Callable[[str], None] | None = None) -> Completion:
        """Mit on_delta wird gestreamt: jedes neue Textstück geht sofort an on_delta; das Ergebnis ist trotzdem
        das vollständige Completion."""
        payload = {"model": model, "prompt": prompt, "max_tokens": max_tokens,
                   "temperature": temperature, "stream": on_delta is not None}
        if stop:
            payload["stop"] = stop
        if on_delta is not None:
            return self._stream("/v1/completions", payload, timeout, lambda c: c.get("text"), on_delta)
        return self._completion("/v1/completions", payload, timeout, lambda c: c["text"])

    def chat(self, model: str, messages: list[dict], *, max_tokens: int, temperature: float = 0.0,
             timeout: float = 300, on_delta: Callable[[str], None] | None = None) -> Completion:
        payload = {"model": model, "messages": messages, "max_tokens": max_tokens,
                   "temperature": temperature, "stream": on_delta is not None}
        if on_delta is not None:
            return self._stream("/v1/chat/completions", payload, timeout,
                                lambda c: (c.get("delta") or {}).get("content"), on_delta)
        return self._completion("/v1/chat/completions", payload, timeout, lambda c: c["message"]["content"])
