package com.example.subtitletranslation

import com.example.subtitletranslation.util.AsrLanguageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrLanguageResolverTest {

    @Test
    fun resolvesEnglishFromVoskModelId() {
        assertEquals(
            "en",
            AsrLanguageResolver.resolveTranslateSourceLanguage("vosk-model-small-en-us-zamia-0.5")
        )
    }

    @Test
    fun resolvesChineseAliasFromModelId() {
        assertEquals(
            "zh",
            AsrLanguageResolver.resolveTranslateSourceLanguage("vosk-model-cn-0.22")
        )
    }

    @Test
    fun resolvesEnglishFromNamedCustomModel() {
        assertEquals(
            "en",
            AsrLanguageResolver.resolveTranslateSourceLanguage("custom-english-uk-model")
        )
    }

    @Test
    fun returnsNullWhenModelIdHasNoLanguageSignal() {
        assertNull(AsrLanguageResolver.resolveTranslateSourceLanguage("build-2026-release"))
    }
}
