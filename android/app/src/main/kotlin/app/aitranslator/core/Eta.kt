package app.aitranslator.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Restdauer der Übersetzung, hochgerechnet aus dem bisherigen Tempo. Gewichtet wird nach Zeichen statt nach
 * Abschnitten, weil Abschnitte (Kapitelanfänge, Bildseiten) sehr unterschiedlich lang sind.
 * null = noch keine Grundlage (kein Abschnitt fertig).
 */
fun remainingMillis(elapsedMs: Long, doneChars: Long, totalChars: Long): Long? {
    if (doneChars <= 0 || elapsedMs <= 0) return null
    if (totalChars <= doneChars) return 0
    return elapsedMs * (totalChars - doneChars) / doneChars
}

/** '12 Min.', '1 Std. 5 Min.', 'unter 1 Min.' (auf ganze Minuten gerundet). */
fun formatDuration(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return when {
        minutes < 1 -> "unter 1 Min."
        minutes < 60 -> "$minutes Min."
        else -> "${minutes / 60} Std. ${minutes % 60} Min."
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** 'noch ca. 41 Min. (fertig gegen 14:35)' oder null, solange es nichts zu schätzen gibt. */
fun etaText(elapsedMs: Long, doneChars: Long, totalChars: Long, now: LocalTime = LocalTime.now()): String? {
    val remaining = remainingMillis(elapsedMs, doneChars, totalChars) ?: return null
    if (remaining == 0L) return null
    val duration = formatDuration(remaining)
    val prefix = if (duration.startsWith("unter")) "noch" else "noch ca." // nicht 'noch ca. unter 1 Min.'
    return "$prefix $duration (fertig gegen ${now.plusNanos(remaining * 1_000_000).format(CLOCK)})"
}
