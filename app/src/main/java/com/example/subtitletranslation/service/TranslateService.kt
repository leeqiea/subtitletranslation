package com.example.subtitletranslation.service

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.atomic.AtomicLong

class TranslateService {

    private val seq = AtomicLong(0)
    private var translator: Translator? = null
    private var translatorKey: String? = null
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    fun translate(
        text: String,
        targetLang: String,
        onResult: (String, String) -> Unit,
        onDetectedSource: ((String?) -> Unit)? = null
    ) {
        val target = normalizeTranslateLang(targetLang)
        if (target == null) {
            onDetectedSource?.invoke(null)
            onResult(text, text)
            return
        }

        val seqId = seq.incrementAndGet()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langTag ->
                if (seq.get() != seqId) return@addOnSuccessListener
                val source = normalizeTranslateLang(langTag)
                onDetectedSource?.invoke(source)
                if (source == null || source == target) {
                    onResult(text, text)
                    return@addOnSuccessListener
                }
                val tr = getTranslator(source, target)
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.translate(text)
                            .addOnSuccessListener { translated ->
                                if (seq.get() != seqId) return@addOnSuccessListener
                                onResult(text, translated)
                            }
                            .addOnFailureListener {
                                onResult(text, text)
                            }
                    }
                    .addOnFailureListener {
                        onResult(text, text)
                    }
            }
            .addOnFailureListener {
                onDetectedSource?.invoke(null)
                onResult(text, text)
            }
    }

    fun cancelPending() {
        seq.incrementAndGet()
    }

    fun close() {
        try {
            translator?.close()
        } catch (_: Exception) {
        }
        translator = null
        translatorKey = null
        try {
            languageIdentifier.close()
        } catch (_: Exception) {
        }
    }

    private fun getTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang->$targetLang"
        if (translator != null && translatorKey == key) return translator!!
        try {
            translator?.close()
        } catch (_: Exception) {
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
        translator = Translation.getClient(options)
        translatorKey = key
        return translator!!
    }

    private fun normalizeTranslateLang(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == "und") return null
        TranslateLanguage.fromLanguageTag(tag)?.let { return it }
        val base = tag.substringBefore('-')
        return TranslateLanguage.fromLanguageTag(base)
    }
}
