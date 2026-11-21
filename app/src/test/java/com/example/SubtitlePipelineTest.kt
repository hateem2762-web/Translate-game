package com.example

import android.graphics.Bitmap
import com.example.data.repository.TranslationCacheStore
import com.example.engine.OcrBlock
import com.example.engine.OcrEngine
import com.example.engine.OcrResult
import com.example.engine.SubtitlePipeline
import com.example.engine.TranslationProvider
import com.example.model.DisplayMode
import com.example.model.RoiRect
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitlePipelineTest {

    private class FakeOcrEngine : OcrEngine {
        var lastSourceLangCode: String? = null
        override suspend fun recognize(bitmap: Bitmap, sourceLangCode: String): OcrResult {
            lastSourceLangCode = sourceLangCode
            return OcrResult("", emptyList())
        }
        override fun close() = Unit
    }

    private class FakeProvider(
        override val mode: TranslationEngineMode,
        private val prefix: String
    ) : TranslationProvider {
        var translateCalls = 0
        var batchCalls = 0
        override suspend fun isReady(config: TranslationConfig): Boolean = true
        override suspend fun translate(text: String, config: TranslationConfig): String {
            translateCalls++
            return "$prefix:$text"
        }

        override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
            batchCalls++
            return texts.map { "$prefix:$it" }
        }
        override fun close() = Unit
    }

    private class InMemoryCacheStore : TranslationCacheStore {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(
            engineMode: TranslationEngineMode,
            sourceLang: String,
            targetLang: String,
            normalizedSource: String
        ): String? = map["${engineMode.name}:$sourceLang->$targetLang::${normalizedSource.lowercase()}"]

        override suspend fun put(
            engineMode: TranslationEngineMode,
            sourceLang: String,
            targetLang: String,
            normalizedSource: String,
            translated: String
        ) {
            map["${engineMode.name}:$sourceLang->$targetLang::${normalizedSource.lowercase()}"] = translated
        }

        override suspend fun clear() {
            map.clear()
        }
    }

    private fun simpleResult(text: String): OcrResult {
        val blocks = if (text.isBlank()) {
            emptyList()
        } else {
            listOf(OcrBlock(text = text, boundingBox = RoiRect(10, 10, 200, 40)))
        }
        return OcrResult(fullText = text, blocks = blocks)
    }

    @Test
    fun `typewriter text waits for debounce before translating once`() = runTest {
        val offline = FakeProvider(TranslationEngineMode.OFFLINE_LOCAL, "TR")
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "AI")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offline,
                TranslationEngineMode.SMART_AI to ai
            ),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(debounceMs = 450L)

        pipeline.processRecognizedResult(simpleResult("Hello"), null, config)
        runCurrent()
        assertTrue(pipeline.output.value.isStabilizing)
        assertEquals(0, offline.translateCalls)

        advanceTimeBy(250L)
        pipeline.processRecognizedResult(simpleResult("Hello hero!"), null, config)
        runCurrent()
        assertEquals(0, offline.translateCalls)

        advanceTimeBy(250L)
        pipeline.processRecognizedResult(simpleResult("Hello   hero!"), null, config)
        runCurrent()
        assertEquals(0, offline.translateCalls)

        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, offline.translateCalls)
        assertEquals(0, ai.translateCalls)
        assertEquals("TR:Hello hero!", pipeline.output.value.translatedText)
        assertFalse(pipeline.output.value.isStabilizing)
    }

    @Test
    fun `self OCR guard ignores rendered overlay text and prevents infinite loop`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "Karanlık Orman")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 300L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(simpleResult("Dark Forest"), null, config)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(1, ai.translateCalls)
        assertEquals("Karanlık Orman:Dark Forest", pipeline.output.value.translatedText)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)

        // Next OCR accidentally reads our rendered Turkish overlay text -> must be discarded!
        pipeline.processRecognizedResult(simpleResult("Karanlık Orman:Dark Forest"), null, config)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, ai.translateCalls)
        assertEquals(1L, pipeline.output.value.framesSkipped)
    }

    @Test
    fun `engine switch uses distinct cache and smart AI caches repeated lines`() = runTest {
        val offline = FakeProvider(TranslationEngineMode.OFFLINE_LOCAL, "LOC")
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "AI")
        val cache = InMemoryCacheStore()
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offline,
                TranslationEngineMode.SMART_AI to ai
            ),
            cacheStore = cache
        )

        val aiConfig = TranslationConfig(
            debounceMs = 200L,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(simpleResult("Press Start"), null, aiConfig)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.translateCalls)
        assertEquals("AI:Press Start", pipeline.output.value.translatedText)

        // Blank out for 2 frames then reappear -> AI cache hit, zero extra API calls!
        pipeline.processRecognizedResult(simpleResult(""), null, aiConfig)
        pipeline.processRecognizedResult(simpleResult(""), null, aiConfig)
        pipeline.processRecognizedResult(simpleResult("Press Start"), null, aiConfig)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.translateCalls)
        assertTrue(pipeline.output.value.fromCache)
    }

    @Test
    fun `in place mode batches multi-block OCR into single AI call and caches per block`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "TR")
        val cache = InMemoryCacheStore()
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = cache
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        val multiBlockResult = OcrResult(
            fullText = "Open the gate\nFind the key",
            blocks = listOf(
                OcrBlock("Open the gate", RoiRect(10, 10, 200, 30)),
                OcrBlock("Find the key", RoiRect(10, 50, 200, 30))
            )
        )

        pipeline.processRecognizedResult(multiBlockResult, null, config)
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(0, ai.translateCalls)
        assertEquals(1, ai.batchCalls)
        assertEquals(2, pipeline.output.value.inPlaceBlocks.size)
        assertEquals("TR:Open the gate\nTR:Find the key", pipeline.output.value.translatedText)

        // Re-appearing same multi-block dialogue hits cache with zero API calls
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        pipeline.processRecognizedResult(multiBlockResult, null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.batchCalls)
        assertTrue(pipeline.output.value.fromCache)
    }

    @Test
    fun `chinese normalization joins CJK characters without spaces and passes sourceLang`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "TR")
        val cache = InMemoryCacheStore()
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = cache
        )
        val zhConfig = TranslationConfig(
            sourceLangCode = TranslateLanguage.CHINESE,
            debounceMs = 200L,
            engineMode = TranslationEngineMode.SMART_AI
        )

        val joined = com.example.engine.MlKitOcrEngine.joinChineseBlocks(
            listOf("古老的封印", "正在碎裂！")
        )
        assertEquals("古老的封印正在碎裂！", joined)

        pipeline.processRecognizedResult(
            simpleResult("古老的 封印\n正在 碎裂！"),
            null,
            zhConfig
        )
        advanceTimeBy(200L)
        runCurrent()
        assertEquals("TR:古老的封印正在碎裂！", pipeline.output.value.translatedText)
    }
}
