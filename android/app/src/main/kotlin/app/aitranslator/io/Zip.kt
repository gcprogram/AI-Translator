package app.aitranslator.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024

/** Alle Einträge einer ZIP-Datei (DOCX, EPUB) im Speicher; ein einzelner Eintrag ist auf 200 MB begrenzt. */
fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                total += n
                require(total <= MAX_ENTRY_BYTES) { "Eintrag ${entry.name} ist zu groß" }
                out.write(buffer, 0, n)
            }
            entries[entry.name] = out.toByteArray()
        }
    }
    return entries
}
