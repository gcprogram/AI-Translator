"""Restzeit der Übersetzung, hochgerechnet aus dem bisherigen Tempo (entspricht core/Eta.kt der Android-App).
Gewichtet wird nach Zeichen statt nach Abschnitten, weil Abschnitte sehr unterschiedlich lang sind."""
from __future__ import annotations

from datetime import datetime, timedelta


def remaining_seconds(elapsed: float, done_chars: int, total_chars: int) -> float | None:
    """None = noch keine Grundlage (kein Abschnitt fertig)."""
    if done_chars <= 0 or elapsed <= 0:
        return None
    if total_chars <= done_chars:
        return 0.0
    return elapsed * (total_chars - done_chars) / done_chars


def format_duration(seconds: float) -> str:
    """'12 Min.', '1 Std. 5 Min.', 'unter 1 Min.' (auf ganze Minuten gerundet)."""
    minutes = int((seconds * 1000 + 30_000) // 60_000)
    if minutes < 1:
        return "unter 1 Min."
    if minutes < 60:
        return f"{minutes} Min."
    return f"{minutes // 60} Std. {minutes % 60} Min."


def eta_text(elapsed: float, done_chars: int, total_chars: int, now: datetime | None = None) -> str | None:
    """'noch ca. 41 Min. (fertig gegen 14:35)' oder None, solange es nichts zu schätzen gibt."""
    remaining = remaining_seconds(elapsed, done_chars, total_chars)
    if remaining is None or remaining == 0:
        return None
    duration = format_duration(remaining)
    prefix = "noch" if duration.startswith("unter") else "noch ca."
    finish = (now or datetime.now()) + timedelta(seconds=remaining)
    return f"{prefix} {duration} (fertig gegen {finish:%H:%M})"
