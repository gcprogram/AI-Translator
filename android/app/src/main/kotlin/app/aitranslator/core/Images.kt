package app.aitranslator.core

import java.security.MessageDigest

/**
 * Bilder des Originals. Im Text steht jedes Bild als eigener Absatz `[[Bild 12]]`. Das Sprachmodell sieht diese
 * Platzhalter nie: [extractImages] nimmt sie vor dem Übersetzen heraus, [reinsertImages] setzt sie danach an
 * dieselbe Absatzposition zurück. So kann das Modell sie weder verändern noch verlieren.
 */
class ImageAsset(val bytes: ByteArray, val mime: String)

fun imageToken(id: Int): String = "[[Bild $id]]"

private val TOKEN = Regex("""\[\[Bild (\d+)\]\]""")
private val TOKEN_LINE = Regex("""^\[\[Bild (\d+)\]\]$""")

/** Alle Bild-Nummern im Text, in Reihenfolge (auch mehrfach). */
fun imageIds(text: String): List<Int> = TOKEN.findAll(text).map { it.groupValues[1].toInt() }.toList()

/** Ist die Zeile genau ein Bild-Platzhalter? Liefert die Nummer. */
fun imageIdOfLine(line: String): Int? = TOKEN_LINE.matchEntire(line.trim())?.groupValues?.get(1)?.toInt()

/** Ein Bild steht nach [afterParagraphs] Textabsätzen (0 = vor dem ersten). */
data class ImageAnchor(val afterParagraphs: Int, val id: Int)

/** Text ohne Bild-Absätze, dazu wo die Bilder standen und wie viele Textabsätze es waren. */
data class SplitImages(val text: String, val anchors: List<ImageAnchor>, val textParagraphs: Int)

fun extractImages(text: String): SplitImages {
    val paragraphs = mutableListOf<String>()
    val anchors = mutableListOf<ImageAnchor>()
    for (paragraph in splitParagraphs(text)) {
        val ids = paragraph.lines().filter { it.isNotBlank() }.map { imageIdOfLine(it) }
        if (ids.isNotEmpty() && ids.all { it != null }) {
            ids.forEach { anchors += ImageAnchor(paragraphs.size, it!!) }
        } else {
            paragraphs += paragraph
        }
    }
    return SplitImages(paragraphs.joinToString("\n\n"), anchors, paragraphs.size)
}

fun removeImageTokens(text: String): String = extractImages(text).text

/**
 * Setzt die Bilder zurück in die Übersetzung. Hat sie gleich viele Absätze wie das Original, stehen die Bilder
 * exakt an denselben Stellen; sonst proportional (Absatz 3 von 10 bleibt etwa bei 30 %).
 */
fun reinsertImages(translation: String, split: SplitImages): String {
    if (split.anchors.isEmpty()) return translation
    val paragraphs = splitParagraphs(translation)
    val n = paragraphs.size
    val result = mutableListOf<String>()
    fun position(anchor: ImageAnchor): Int =
        if (split.textParagraphs == 0 || n == split.textParagraphs) minOf(anchor.afterParagraphs, n)
        else Math.round(anchor.afterParagraphs.toDouble() * n / split.textParagraphs).toInt().coerceIn(0, n)
    val positions = split.anchors.map { it to position(it) }
    for (i in 0..n) {
        positions.filter { it.second == i }.forEach { result += imageToken(it.first.id) }
        if (i < n) result += paragraphs[i]
    }
    return result.joinToString("\n\n")
}

/** Sammelt die Bilder beim Einlesen. Gleiche Bilder (Zierlinien, Logos) bekommen dieselbe Nummer. */
class ImageCollector {
    val images = linkedMapOf<Int, ImageAsset>()
    private val byHash = mutableMapOf<String, Int>()

    fun add(bytes: ByteArray, mime: String): Int {
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        return byHash.getOrPut(hash) {
            val id = images.size + 1
            images[id] = ImageAsset(bytes, mime)
            id
        }
    }
}

private val MIME_BY_EXTENSION = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "svg" to "image/svg+xml", "webp" to "image/webp", "bmp" to "image/bmp",
)

fun mimeForExtension(extension: String): String = MIME_BY_EXTENSION[extension.lowercase()] ?: "application/octet-stream"

fun extensionForMime(mime: String): String = when (mime.lowercase()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    else -> "bin"
}

/** Erkennt das Format an den ersten Bytes, falls Dateiendung oder Angabe im Buch fehlt oder falsch ist. */
fun sniffMime(bytes: ByteArray): String? = when {
    bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
    bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "image/gif"
    bytes.size > 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
    bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    bytes.size > 5 && String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1).contains("<svg") -> "image/svg+xml"
    else -> null
}

/** Breite und Höhe in Pixeln für PNG, GIF, BMP und JPEG (ohne Android-Klassen, damit es auf der JVM testbar ist). */
fun sniffImageSize(bytes: ByteArray): Pair<Int, Int>? {
    fun be16(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
    fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
    fun le16(i: Int) = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
    return when (sniffMime(bytes)) {
        "image/png" -> if (bytes.size >= 24) be32(16) to be32(20) else null
        "image/gif" -> if (bytes.size >= 10) le16(6) to le16(8) else null
        "image/bmp" -> if (bytes.size >= 26) le32(18) to kotlin.math.abs(le32(22)) else null
        "image/jpeg" -> {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val marker = bytes[i + 1].toInt() and 0xFF
                when {
                    marker == 0xFF -> i++ // Füllbyte
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> return be16(i + 7) to be16(i + 5)
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2
                    else -> i += 2 + be16(i + 2)
                }
            }
            null
        }
        else -> null
    }.takeIf { it == null || (it.first > 0 && it.second > 0) }
}
