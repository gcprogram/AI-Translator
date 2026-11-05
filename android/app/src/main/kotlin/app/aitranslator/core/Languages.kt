package app.aitranslator.core

/** Sprachcodes und -namen. TranslateGemma erwartet ISO-Codes mit englischem Sprachnamen. */
object Languages {
    val NAMES: Map<String, String> = linkedMapOf(
        "af" to "Afrikaans", "ar" to "Arabic", "az" to "Azerbaijani", "be" to "Belarusian",
        "bg" to "Bulgarian", "bn" to "Bengali", "ca" to "Catalan", "cs" to "Czech",
        "cy" to "Welsh", "da" to "Danish", "de" to "German", "el" to "Greek",
        "en" to "English", "es" to "Spanish", "et" to "Estonian", "fa" to "Persian",
        "fi" to "Finnish", "fr" to "French", "ga" to "Irish", "gl" to "Galician",
        "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
        "hu" to "Hungarian", "hy" to "Armenian", "id" to "Indonesian", "is" to "Icelandic",
        "it" to "Italian", "ja" to "Japanese", "ka" to "Georgian", "kk" to "Kazakh",
        "km" to "Khmer", "kn" to "Kannada", "ko" to "Korean", "lt" to "Lithuanian",
        "lv" to "Latvian", "mk" to "Macedonian", "ml" to "Malayalam", "mn" to "Mongolian",
        "mr" to "Marathi", "ms" to "Malay", "mt" to "Maltese", "my" to "Burmese",
        "nl" to "Dutch", "no" to "Norwegian", "pa" to "Punjabi", "pl" to "Polish",
        "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak",
        "sl" to "Slovenian", "sq" to "Albanian", "sr" to "Serbian", "sv" to "Swedish",
        "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai",
        "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek",
        "vi" to "Vietnamese", "zh" to "Chinese",
    )

    /** 'de-DE' / 'de_DE' / 'DE' -> 'de'. Unbekannte Codes bleiben (klein geschrieben) erhalten. */
    fun normalizeCode(code: String): String =
        code.trim().replace("_", "-").split("-")[0].lowercase()

    /** Englischer Sprachname; bei unbekanntem Code der Code selbst (nie ein Platzhalter-Text). */
    fun name(code: String): String = NAMES[normalizeCode(code)] ?: code

    fun displayName(code: String): String {
        val normalized = normalizeCode(code)
        return "${name(normalized)} ($normalized)"
    }

    /** 'German (de)' -> 'de'. Akzeptiert auch einen nackten Code. */
    fun codeFromDisplay(text: String): String {
        if ("(" in text && text.trimEnd().endsWith(")")) {
            return normalizeCode(text.substring(text.lastIndexOf('(') + 1, text.lastIndexOf(')')))
        }
        return normalizeCode(text)
    }

    val displayList: List<String> = NAMES.keys.map(::displayName).sorted()
}
