package com.example.subtitletranslation.service

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.atomic.AtomicLong

/**
 * 负责把识别出的文本翻译成目标语言。
 * 这里额外处理了语言识别、翻译模型懒加载，以及“只保留最新一次请求结果”这三个问题。
 */
class TranslateService {

    // 每发起一次翻译就递增，用来丢弃已经过时的异步回调。
    private val seq = AtomicLong(0)
    // 当前缓存的翻译器实例，避免相同语种对反复创建对象。
    private var translator: Translator? = null
    private var translatorKey: String? = null
    // 记录哪个语种对的模型已经确认可用，避免每次都重复触发下载检查。
    private var readyTranslatorKey: String? = null
    // 没有传入源语言时，先让 ML Kit 猜测文本语言。
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    // 优先使用外部提示的源语言；没有提示时再回退到自动识别。
    fun translate(
        text: String,
        targetLang: String,
        sourceLangHint: String? = null,
        onResult: (String, String) -> Unit,
        onDetectedSource: ((String?) -> Unit)? = null
    ) {
        val target = normalizeTranslateLang(targetLang)
        // 目标语言本身无效时，直接原样返回，避免进入无意义的异步流程。
        if (target == null) {
            onDetectedSource?.invoke(null)
            onResult(text, text)
            return
        }

        val seqId = seq.incrementAndGet()
        val hintedSource = normalizeTranslateLang(sourceLangHint)
        if (hintedSource != null) {
            onDetectedSource?.invoke(hintedSource)
            // 源语言和目标语言相同，不需要翻译。
            if (hintedSource == target) {
                onResult(text, text)
                return
            }
            translateWithResolvedSource(text, hintedSource, target, seqId, onResult)
            return
        }

        // 没有模型语言提示时，退回到文本语言识别。
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langTag ->
                if (seq.get() != seqId) return@addOnSuccessListener
                val source = normalizeTranslateLang(langTag)
                onDetectedSource?.invoke(source)
                if (source == null || source == target) {
                    onResult(text, text)
                    return@addOnSuccessListener
                }
                translateWithResolvedSource(text, source, target, seqId, onResult)
            }
            .addOnFailureListener {
                onDetectedSource?.invoke(null)
                onResult(text, text)
            }
    }

    // 通过增加序列号，让所有旧请求的回调自动失效。
    fun cancelPending() {
        seq.incrementAndGet()
    }

    // Service 销毁时释放翻译器和语言识别器。
    fun close() {
        try {
            translator?.close()
        } catch (_: Exception) {
        }
        translator = null
        translatorKey = null
        readyTranslatorKey = null
        try {
            languageIdentifier.close()
        } catch (_: Exception) {
        }
    }

    // 同一个源/目标语种对复用翻译器；语种变化时再重建。
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
        readyTranslatorKey = null
        return translator!!
    }

    // 真正翻译前先确保离线翻译模型已经可用。
    private fun translateWithResolvedSource(
        text: String,
        sourceLang: String,
        targetLang: String,
        seqId: Long,
        onResult: (String, String) -> Unit
    ) {
        val tr = getTranslator(sourceLang, targetLang)
        val key = "$sourceLang->$targetLang"
        if (readyTranslatorKey == key) {
            translateText(tr, text, seqId, onResult)
            return
        }
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (seq.get() != seqId) return@addOnSuccessListener
                readyTranslatorKey = key
                translateText(tr, text, seqId, onResult)
            }
            .addOnFailureListener {
                // 模型下载失败时保持原文，避免 UI 卡在“等待翻译”状态。
                if (seq.get() != seqId) return@addOnFailureListener
                onResult(text, text)
            }
    }

    // 最后一层调用，只有当前请求仍然是最新请求时才回填结果。
    private fun translateText(
        translator: Translator,
        text: String,
        seqId: Long,
        onResult: (String, String) -> Unit
    ) {
        translator.translate(text)
            .addOnSuccessListener { translated ->
                if (seq.get() != seqId) return@addOnSuccessListener
                onResult(text, translated)
            }
            .addOnFailureListener {
                if (seq.get() != seqId) return@addOnFailureListener
                onResult(text, text)
            }
    }

    // 统一把诸如 zh-CN、en-US 这样的标签规整成 ML Kit 能识别的语言代码。
    private fun normalizeTranslateLang(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == "und") return null
        TranslateLanguage.fromLanguageTag(tag)?.let { return it }
        val base = tag.substringBefore('-')
        return TranslateLanguage.fromLanguageTag(base)
    }
}
