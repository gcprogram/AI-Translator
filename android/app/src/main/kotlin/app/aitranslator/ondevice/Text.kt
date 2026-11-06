package app.aitranslator.ondevice

/**
 * Ein Token kann mitten in einem UTF-8-Zeichen enden (Umlaute, CJK, Emoji sind mehrere Bytes und werden oft auf mehrere Token
 * verteilt). [push] liefert nur den Teil, der aus vollständigen Zeichen besteht, und hält den Rest bis zum nächsten Token zurück.
 */
class Utf8Assembler {
    private var buffer = ByteArray(0)

    fun push(bytes: ByteArray): String {
        buffer += bytes
        val complete = completePrefixLength(buffer)
        if (complete == 0) return ""
        val text = String(buffer, 0, complete, Charsets.UTF_8)
        buffer = buffer.copyOfRange(complete, buffer.size)
        return text
    }

    /** Was am Ende noch übrig ist (abgeschnittenes Zeichen), als Ersatzzeichen; leer, wenn alles vollständig war. */
    fun flush(): String {
        if (buffer.isEmpty()) return ""
        val rest = String(buffer, Charsets.UTF_8)
        buffer = ByteArray(0)
        return rest
    }

    private fun completePrefixLength(bytes: ByteArray): Int {
        var i = bytes.size - 1
        var seen = 0
        while (i >= 0 && seen < 4) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) { // Folgebyte: weiter zurück bis zum Startbyte
                i--
                seen++
                continue
            }
            val needed = when {
                b < 0x80 -> 1
                b >= 0xF0 -> 4
                b >= 0xE0 -> 3
                b >= 0xC0 -> 2
                else -> 1
            }
            return if (bytes.size - i >= needed) bytes.size else i
        }
        return bytes.size // nur Folgebytes (kaputte Daten): nicht festhalten
    }
}

/**
 * Erkennt Stop-Wörter im erzeugten Text, auch wenn sie über mehrere Token verteilt ankommen. Ein möglicher Anfang eines
 * Stop-Worts am Ende wird zurückgehalten, damit er nie ausgegeben wird, und bei Entwarnung mit dem nächsten Stück freigegeben.
 */
class StopMatcher(private val stops: List<String>) {
    private var pending = ""
    var stopped = false
        private set

    /** Liefert den Text, der ausgegeben werden darf. Nach einem Treffer ist [stopped] true und nichts mehr kommt nach. */
    fun feed(text: String): String {
        if (stopped) return ""
        pending += text
        val hit = stops.filter { it.isNotEmpty() }.map { pending.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (hit != null) {
            stopped = true
            val out = pending.substring(0, hit)
            pending = ""
            return out
        }
        val keep = longestStopPrefixAtEnd()
        val out = pending.substring(0, pending.length - keep)
        pending = pending.substring(pending.length - keep)
        return out
    }

    /** Am Ende der Erzeugung: zurückgehaltenen Text freigeben (es kam doch kein Stop-Wort mehr). */
    fun flush(): String {
        val out = if (stopped) "" else pending
        pending = ""
        return out
    }

    private fun longestStopPrefixAtEnd(): Int {
        var best = 0
        for (stop in stops) {
            for (length in minOf(stop.length - 1, pending.length) downTo best + 1) {
                if (pending.endsWith(stop.substring(0, length))) {
                    best = length
                    break
                }
            }
        }
        return best
    }
}
