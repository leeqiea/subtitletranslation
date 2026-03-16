package com.example.subtitletranslation.util

import java.util.Locale

/**
 * 根据 Vosk 模型名推断翻译服务的源语言代码。
 * 这里做的是启发式匹配，所以只返回项目已知的常见语言代码。
 */
object AsrLanguageResolver {

    // 仅保留项目当前翻译链路能稳定识别的语种代码。
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

    // 模型名往往不规范，所以同时兼容缩写、别名和完整语言名。
    fun resolveTranslateSourceLanguage(modelId: String?): String? {
        if (modelId.isNullOrBlank()) return null
        val tokens = modelId.lowercase(Locale.ROOT)
            .split(Regex("[^a-z]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        // “small en us” 这类模型名里，marker 后面的 token 通常最接近真实语言。
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

    // 在模型名里查找类似 "model en"、"small ja" 这样的相邻标记。
    private fun tokenAfter(tokens: List<String>, marker: String): String? {
        val index = tokens.indexOf(marker)
        return if (index >= 0 && index + 1 < tokens.size) tokens[index + 1] else null
    }

    // 统一把 token 归一化到最终支持的语言代码集合中。
    private fun normalizeCandidate(raw: String): String? {
        if (raw in ignoredTokens) return null
        val mapped = aliases[raw] ?: namedLanguages[raw] ?: raw
        return mapped.takeIf { it in supportedCodes }
    }
}
