package com.example.subtitletranslation.util

import java.util.Locale

object AsrLanguageResolver {

    private val supportedCodes = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo",
        "es", "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu",
        "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt",
        "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te",
        "th", "tl", "tr", "uk", "ur", "vi", "zh"
    )

    private val aliases = mapOf(
        "cn" to "zh",
        "cz" to "cs",
        "eng" to "en",
        "ger" to "de",
        "gr" to "el",
        "jp" to "ja",
        "kr" to "ko",
        "sp" to "es"
    )

    private val namedLanguages = mapOf(
        "arabic" to "ar",
        "chinese" to "zh",
        "czech" to "cs",
        "dutch" to "nl",
        "english" to "en",
        "esperanto" to "eo",
        "french" to "fr",
        "german" to "de",
        "greek" to "el",
        "hindi" to "hi",
        "indonesian" to "id",
        "italian" to "it",
        "japanese" to "ja",
        "korean" to "ko",
        "polish" to "pl",
        "portuguese" to "pt",
        "romanian" to "ro",
        "russian" to "ru",
        "spanish" to "es",
        "swedish" to "sv",
        "thai" to "th",
        "turkish" to "tr",
        "ukrainian" to "uk",
        "urdu" to "ur",
        "vietnamese" to "vi"
    )

    private val ignoredTokens = setOf(
        "android", "big", "graph", "large", "lgraph", "model", "server", "small", "vosk"
    )

    fun resolveTranslateSourceLanguage(modelId: String?): String? {
        if (modelId.isNullOrBlank()) return null
        val tokens = modelId.lowercase(Locale.ROOT)
            .split(Regex("[^a-z]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val candidates = buildList {
            tokenAfter(tokens, "small")?.let(::add)
            tokenAfter(tokens, "model")?.let(::add)
            addAll(tokens)
        }

        for (candidate in candidates) {
            normalizeCandidate(candidate)?.let { return it }
        }
        return null
    }

    private fun tokenAfter(tokens: List<String>, marker: String): String? {
        val index = tokens.indexOf(marker)
        return if (index >= 0 && index + 1 < tokens.size) tokens[index + 1] else null
    }

    private fun normalizeCandidate(raw: String): String? {
        if (raw in ignoredTokens) return null
        val mapped = aliases[raw] ?: namedLanguages[raw] ?: raw
        return mapped.takeIf { it in supportedCodes }
    }
}
