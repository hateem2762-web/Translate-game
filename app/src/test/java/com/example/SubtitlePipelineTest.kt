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
                OcrBlock("Find the key", RoiRect(10, 100, 200, 30))
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
        repeat(4) { pipeline.processRecognizedResult(simpleResult(""), null, config) }
        pipeline.processRecognizedResult(multiBlockResult, null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.batchCalls)
        assertTrue(pipeline.output.value.fromCache)
    }

    @Test
    fun `in place mode merges stacked multi-line dialogue into one coherent paragraph block`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "TR")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        val stackedDialogue = OcrResult(
            fullText = "Open the gate\nbefore it closes",
            blocks = listOf(
                OcrBlock("Open the gate", RoiRect(10, 10, 220, 30)),
                OcrBlock("before it closes", RoiRect(10, 48, 220, 30))
            )
        )

        pipeline.processRecognizedResult(stackedDialogue, null, config)
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(1, ai.translateCalls)
        assertEquals(0, ai.batchCalls)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertEquals("TR:Open the gate before it closes", pipeline.output.value.translatedText)
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

    @Test
    fun `bounding box stabilizer locks jitter within deadband and smooths genuine drift`() {
        val stabilizer = com.example.engine.BoundingBoxStabilizer(
            originDeadbandPx = 14,
            sizeDeadbandPx = 18,
            emaAlpha = 0.25f
        )

        val initial = listOf(OcrBlock("Defeat the shadow", RoiRect(100, 400, 320, 44)))
        val firstOut = stabilizer.stabilize(initial, isChinese = false)
        assertEquals(RoiRect(100, 400, 320, 44), firstOut.first().boundingBox)

        // Sub-deadband jitter (5px shift) -> exact same coordinates returned
        val jittered = listOf(OcrBlock("Defeat the shadow", RoiRect(105, 404, 326, 48)))
        val secondOut = stabilizer.stabilize(jittered, isChinese = false)
        assertEquals(RoiRect(100, 400, 320, 44), secondOut.first().boundingBox)

        // New text with moderate drift (40px) -> smoothly interpolated instead of full jump
        val drifted = listOf(OcrBlock("A completely different dialogue", RoiRect(140, 440, 360, 50)))
        val thirdOut = stabilizer.stabilize(drifted, isChinese = false)
        assertTrue(thirdOut.first().boundingBox.x in 105..120)
        assertTrue(thirdOut.first().boundingBox.y in 405..420)
    }

    @Test
    fun `minor single-character OCR flicker does not re-trigger translation`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "TR")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(simpleResult("Defeat the ancient dragon now!"), null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.translateCalls)

        // Single punctuation/character OCR noise flicker -> must stay locked, zero extra API calls
        pipeline.processRecognizedResult(simpleResult("Defeat the ancient dragon now."), null, config)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(1, ai.translateCalls)
        assertEquals("TR:Defeat the ancient dragon now!", pipeline.output.value.translatedText)
    }

    @Test
    fun `in place blur keeps subtitle visible during 2 transient blank frames and clears after 3rd blank`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "TR")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(simpleResult("Hold the gate!"), null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)

        // 1st and 2nd transient blank frames must NOT clear the active In-Place Blur overlay
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertTrue(pipeline.isAwaitingBlankClear())

        pipeline.processRecognizedResult(simpleResult(""), null, config)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertTrue(pipeline.isAwaitingBlankClear())

        // 3rd consecutive blank frame clears cleanly when dialogue actually ends
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        assertEquals(0, pipeline.output.value.inPlaceBlocks.size)
        assertFalse(pipeline.isAwaitingBlankClear())
    }

    @Test
    fun `service state holder keeps 30 chronological entries and deduplicates consecutive repeats`() {
        com.example.service.ServiceStateHolder.clearHistory()

        com.example.service.ServiceStateHolder.pushCompletedEntry(
            com.example.model.SubtitleEntry("Line 1", "Satır 1", false, 20L)
        )
        // Consecutive identical text (different spacing/case) must be ignored
        com.example.service.ServiceStateHolder.pushCompletedEntry(
            com.example.model.SubtitleEntry("  line   1 ", "Satır 1", true, 5L)
        )
        assertEquals(1, com.example.service.ServiceStateHolder.telemetry.value.recentEntries.size)

        for (i in 2..35) {
            com.example.service.ServiceStateHolder.pushCompletedEntry(
                com.example.model.SubtitleEntry("Line $i", "Satır $i", false, 10L)
            )
        }
        val entries = com.example.service.ServiceStateHolder.telemetry.value.recentEntries
        assertEquals(30, entries.size)
        assertEquals("Line 6", entries.first().sourceText)
        assertEquals("Line 35", entries.last().sourceText)

        com.example.service.ServiceStateHolder.clearHistory()
        assertTrue(com.example.service.ServiceStateHolder.telemetry.value.recentEntries.isEmpty())
    }

    @Test
    fun `in place layout solver eliminates overlap across 3 tightly stacked lines`() {
        val gap = 6f
        val rects = listOf(
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 20f, top = 20f, width = 300f, height = 56f),
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 24f, top = 50f, width = 300f, height = 56f),
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 18f, top = 82f, width = 300f, height = 56f)
        )

        val fits = com.example.overlay.InPlaceLayoutSolver.resolveVerticalCollisions(
            rects = rects,
            minGapPx = gap,
            minTop = 4f,
            maxBottom = 260f
        )

        assertTrue(fits)
        assertTrue(rects[0].bottom + gap <= rects[1].top + 0.01f)
        assertTrue(rects[1].bottom + gap <= rects[2].top + 0.01f)
        assertTrue(rects.minOf { it.top } >= 4f)
        assertTrue(rects.maxOf { it.bottom } <= 260f + 0.5f)
    }

    @Test
    fun `in place layout solver pulls stack upward when 3 lines hit bottom edge`() {
        val gap = 4f
        val rects = listOf(
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 16f, top = 120f, width = 280f, height = 44f),
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 16f, top = 152f, width = 280f, height = 44f),
            com.example.overlay.InPlaceLayoutSolver.MutableRect(left = 16f, top = 152f, width = 280f, height = 44f)
        )

        val fits = com.example.overlay.InPlaceLayoutSolver.resolveVerticalCollisions(
            rects = rects,
            minGapPx = gap,
            minTop = 4f,
            maxBottom = 200f
        )

        assertTrue(fits)
        assertTrue(rects[0].bottom + gap <= rects[1].top + 0.01f)
        assertTrue(rects[1].bottom + gap <= rects[2].top + 0.01f)
        assertTrue(rects[2].bottom <= 200f + 0.5f)
    }

    @Test
    fun `engine modes include offline hybrid smart AI and free web translation providers`() {
        val modes = TranslationEngineMode.entries
        assertEquals(6, modes.size)
        assertTrue(modes.contains(TranslationEngineMode.OFFLINE_LOCAL))
        assertTrue(modes.contains(TranslationEngineMode.HYBRID))
        assertTrue(modes.contains(TranslationEngineMode.SMART_AI))
        assertTrue(modes.contains(TranslationEngineMode.GOOGLE_FREE))
        assertTrue(modes.contains(TranslationEngineMode.BING_FREE))
        assertTrue(modes.contains(TranslationEngineMode.BAIDU_FREE))
    }

    @Test
    fun `fuzzy cache serves 92pct similar dialogue after blank frames without extra AI quota call`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "AI")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val aiConfig = TranslationConfig(
            debounceMs = 200L,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(
            simpleResult("The ancient dragon awakens in the dark cavern"),
            null,
            aiConfig
        )
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.translateCalls)

        // Blank out dialogue so lastTranslatedSource resets
        pipeline.processRecognizedResult(simpleResult(""), null, aiConfig)
        pipeline.processRecognizedResult(simpleResult(""), null, aiConfig)

        // Re-appear with minor 1-char OCR misread ("cavern" -> "cavern." / "caveru") -> hits 92%+ fuzzy cache!
        pipeline.processRecognizedResult(
            simpleResult("The ancient dragon awakens in the dark caveru"),
            null,
            aiConfig
        )
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(1, ai.translateCalls)
        assertTrue(pipeline.output.value.fromCache)
        assertEquals("AI:The ancient dragon awakens in the dark cavern", pipeline.output.value.translatedText)
    }

    @Test
    fun `minor OCR flicker during debounce does not cancel or delay pending AI translation`() = runTest {
        val ai = FakeProvider(TranslationEngineMode.SMART_AI, "AI")
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val aiConfig = TranslationConfig(
            debounceMs = 300L,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(
            simpleResult("Open the sacred temple gate!"),
            null,
            aiConfig
        )
        advanceTimeBy(150L)

        // Punctuation/1-char flicker halfway through debounce must NOT reset the 300ms timer
        pipeline.processRecognizedResult(
            simpleResult("Open the sacred temple gate."),
            null,
            aiConfig
        )
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, ai.translateCalls)
        assertEquals("AI:Open the sacred temple gate!", pipeline.output.value.translatedText)
    }

    @Test
    fun `ocr preprocessor merges broken paragraph lines and strips stray UI symbols`() {
        val cleaned = com.example.engine.OcrTextPreprocessor.clean(
            "| Find the an-\ncient key\nbefore   darkness falls ■ ~ !",
            isChinese = false
        )
        assertEquals("Find the ancient key before darkness falls!", cleaned)

        val cleanedZh = com.example.engine.OcrTextPreprocessor.clean(
            "古老的 封印\n正在 碎裂 | ！",
            isChinese = true
        )
        assertEquals("古老的封印正在碎裂！", cleanedZh)
    }

    @Test
    fun `hybrid mode emits local draft immediately then refines with AI and updates history in-place`() = runTest {
        val offline = FakeProvider(TranslationEngineMode.OFFLINE_LOCAL, "LOC")
        val delayedAi = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.SMART_AI
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                kotlinx.coroutines.delay(400L)
                return "AI:$text"
            }
            override fun close() = Unit
        }
        val cache = InMemoryCacheStore()
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offline,
                TranslationEngineMode.SMART_AI to delayedAi
            ),
            cacheStore = cache
        )
        val hybridConfig = TranslationConfig(
            debounceMs = 200L,
            engineMode = TranslationEngineMode.HYBRID
        )

        com.example.service.ServiceStateHolder.clearHistory()

        pipeline.processRecognizedResult(simpleResult("Open the gate"), null, hybridConfig)
        advanceTimeBy(200L)
        runCurrent()

        // Step 1: Local preview appeared immediately while AI is still running
        assertEquals(1, offline.translateCalls)
        assertEquals("LOC:Open the gate", pipeline.output.value.translatedText)
        assertTrue(pipeline.output.value.isAiRefining)
        pipeline.output.value.lastCompletedEntry?.let {
            com.example.service.ServiceStateHolder.pushCompletedEntry(it)
        }
        assertEquals(1, com.example.service.ServiceStateHolder.telemetry.value.recentEntries.size)

        // Step 2: AI refinement finishes -> smoothly replaces text and in-place updates history
        advanceTimeBy(450L)
        runCurrent()
        assertEquals(1, delayedAi.calls)
        assertEquals("AI:Open the gate", pipeline.output.value.translatedText)
        assertFalse(pipeline.output.value.isAiRefining)
        pipeline.output.value.lastCompletedEntry?.let {
            com.example.service.ServiceStateHolder.pushCompletedEntry(it)
        }
        val history = com.example.service.ServiceStateHolder.telemetry.value.recentEntries
        assertEquals(1, history.size)
        assertEquals("AI:Open the gate", history.first().translatedText)

        // Step 3: Re-appearing later -> served from AI cache in 0ms without calling offline or AI again
        repeat(3) { pipeline.processRecognizedResult(simpleResult(""), null, hybridConfig) }
        pipeline.processRecognizedResult(simpleResult("Open the gate"), null, hybridConfig)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, offline.translateCalls)
        assertEquals(1, delayedAi.calls)
        assertTrue(pipeline.output.value.fromCache)
        assertEquals("AI:Open the gate", pipeline.output.value.translatedText)

        com.example.service.ServiceStateHolder.clearHistory()
    }

    @Test
    fun `smart AI provider resolves Groq Cerebras OpenRouter endpoints and builds stateless prompt`() {
        val groqBaseConfig = TranslationConfig(
            aiPreset = com.example.model.AiProviderPreset.GROQ,
            aiEndpointUrl = "https://api.groq.com/openai/v1"
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            com.example.engine.SmartAiTranslationProvider.resolveChatEndpoint(groqBaseConfig)
        )

        val cerebrasFullConfig = TranslationConfig(
            aiPreset = com.example.model.AiProviderPreset.CEREBRAS,
            aiEndpointUrl = "https://api.cerebras.ai/v1/chat/completions"
        )
        assertEquals(
            "https://api.cerebras.ai/v1/chat/completions",
            com.example.engine.SmartAiTranslationProvider.resolveChatEndpoint(cerebrasFullConfig)
        )

        assertEquals(120, com.example.engine.SmartAiTranslationProvider.MAX_OUTPUT_TOKENS)
        assertEquals(
            "Sen bir oyun altyazı çevirmenisin. Sadece verilen metni doğal ve akıcı şekilde Türkçe diline çevir, başka hiçbir şey yazma.",
            com.example.engine.SmartAiTranslationProvider.buildSystemPrompt("Türkçe", isBatch = false)
        )
    }

    @Test
    fun `per-provider API key isolation keeps Groq Cerebras Gemini and Custom keys separate`() {
        val config = TranslationConfig(
            aiPreset = com.example.model.AiProviderPreset.GROQ,
            aiApiKey = "gsk_groq_secret",
            aiApiKeys = mapOf(
                com.example.model.AiProviderPreset.GROQ to "gsk_groq_secret",
                com.example.model.AiProviderPreset.CEREBRAS to "csk-cerebras-secret",
                com.example.model.AiProviderPreset.GEMINI to "AIzaGeminiSecret",
                com.example.model.AiProviderPreset.OPENAI_COMPATIBLE to "sk-custom-secret"
            )
        )

        assertEquals("gsk_groq_secret", config.apiKeyFor(com.example.model.AiProviderPreset.GROQ))
        assertEquals("csk-cerebras-secret", config.apiKeyFor(com.example.model.AiProviderPreset.CEREBRAS))
        assertEquals("AIzaGeminiSecret", config.apiKeyFor(com.example.model.AiProviderPreset.GEMINI))
        assertEquals("sk-custom-secret", config.apiKeyFor(com.example.model.AiProviderPreset.OPENAI_COMPATIBLE))
        assertEquals("", config.apiKeyFor(com.example.model.AiProviderPreset.OPENROUTER))
    }

    @Test
    fun `deprecated model IDs sanitize to current defaults and legacy Gemini presets migrate`() {
        val groq = com.example.model.AiProviderPreset.GROQ
        val cerebras = com.example.model.AiProviderPreset.CEREBRAS
        val gemini = com.example.model.AiProviderPreset.GEMINI

        assertEquals("openai/gpt-oss-20b", groq.defaultModel)
        assertEquals("gpt-oss-120b", cerebras.defaultModel)
        assertEquals("gemini-2.5-flash-lite", gemini.defaultModel)

        assertEquals("openai/gpt-oss-20b", com.example.model.AiProviderPreset.sanitizeModelId(groq, "llama-3.1-8b-instant"))
        assertEquals("openai/gpt-oss-20b", com.example.model.AiProviderPreset.sanitizeModelId(groq, "llama-3.3-70b-versatile"))
        assertEquals("openai/gpt-oss-20b", com.example.model.AiProviderPreset.sanitizeModelId(groq, "qwen-qwq-32b"))
        assertEquals("gpt-oss-120b", com.example.model.AiProviderPreset.sanitizeModelId(cerebras, "llama-3.3-70b"))
        assertEquals("gemini-2.5-flash-lite", com.example.model.AiProviderPreset.sanitizeModelId(gemini, "gemini-3.8-flash"))
        assertEquals("gemini-2.5-flash-lite", com.example.model.AiProviderPreset.sanitizeModelId(gemini, "gemini-3.5-flash-lite"))
        assertEquals("qwen/qwen3.8-27b", com.example.model.AiProviderPreset.sanitizeModelId(groq, "qwen/qwen3.8-27b"))

        assertEquals(gemini, com.example.model.AiProviderPreset.fromPersistedName("GEMINI_FLASH"))
        assertEquals(gemini, com.example.model.AiProviderPreset.fromPersistedName("GEMINI_LITE"))
    }

    @Test
    fun `resolveModelsEndpoint maps provider chat endpoints to live models URLs`() {
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            com.example.engine.SmartAiTranslationProvider.resolveModelsEndpoint(
                TranslationConfig(aiPreset = com.example.model.AiProviderPreset.GROQ)
            )
        )
        assertEquals(
            "https://api.cerebras.ai/v1/models",
            com.example.engine.SmartAiTranslationProvider.resolveModelsEndpoint(
                TranslationConfig(aiPreset = com.example.model.AiProviderPreset.CEREBRAS)
            )
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            com.example.engine.SmartAiTranslationProvider.resolveModelsEndpoint(
                TranslationConfig(aiPreset = com.example.model.AiProviderPreset.OPENROUTER)
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            com.example.engine.SmartAiTranslationProvider.resolveModelsEndpoint(
                TranslationConfig(aiPreset = com.example.model.AiProviderPreset.GEMINI)
            )
        )
        assertEquals(
            "https://my-proxy.example.com/v1/models",
            com.example.engine.SmartAiTranslationProvider.resolveModelsEndpoint(
                TranslationConfig(
                    aiPreset = com.example.model.AiProviderPreset.OPENAI_COMPATIBLE,
                    aiEndpointUrl = "https://my-proxy.example.com/v1"
                )
            )
        )
    }

    @Test
    fun `self OCR guard rejects 85pct similar Turkish overlay text and auto clear wipes blur state`() = runTest {
        val ai = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.SMART_AI
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                return "Antik mühür kırılıyor, karanlık köyü yutmadan önce anahtarı bul!"
            }
            override fun close() = Unit
        }
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.SMART_AI to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.SMART_AI
        )

        pipeline.processRecognizedResult(
            simpleResult("The ancient seal is breaking, find the key before darkness consumes the village!"),
            null,
            config
        )
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.calls)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertFalse(pipeline.isAwaitingBlankClear())

        // Minor OCR variation (>= 85% match) of our own Turkish blur text must be rejected without calling AI
        pipeline.processRecognizedResult(
            simpleResult("Antik mühür kırılıyor, karanlık köyü yutmadan önce anahtarı buI!"),
            null,
            config
        )
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(1, ai.calls)
        assertEquals(1L, pipeline.output.value.framesSkipped)

        // Blank frames trigger awaiting-clear state and wipe sourceText + translatedText + inPlaceBlocks at threshold 3
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        assertTrue(pipeline.isAwaitingBlankClear())
        pipeline.processRecognizedResult(simpleResult(""), null, config)
        assertTrue(pipeline.isAwaitingBlankClear())
        pipeline.processRecognizedResult(simpleResult(""), null, config)

        assertFalse(pipeline.isAwaitingBlankClear())
        assertEquals("", pipeline.output.value.sourceText)
        assertEquals("", pipeline.output.value.translatedText)
        assertTrue(pipeline.output.value.inPlaceBlocks.isEmpty())
    }

    @Test
    fun `subtitle color presets and custom hex parser handle valid and invalid inputs`() {
        val cyan = com.example.model.SubtitleColorPreset.NEON_CYAN
        assertEquals(cyan, com.example.model.SubtitleColorPreset.matchingOrNull(cyan.argb))
        assertEquals("#22D3EE", com.example.model.SubtitleColorPreset.formatHex(cyan.argb))

        val parsed = com.example.model.SubtitleColorPreset.parseHexColorOrNull("  #fde047 ")
        assertEquals(0xFFFDE047.toInt(), parsed)
        assertEquals(0xFF00E5FF.toInt(), com.example.model.SubtitleColorPreset.parseHexColorOrNull("00e5ff"))
        assertEquals(null, com.example.model.SubtitleColorPreset.parseHexColorOrNull("#12345"))
        assertEquals(null, com.example.model.SubtitleColorPreset.parseHexColorOrNull("#ZZZZZZ"))

        assertEquals(2, com.example.model.ClassicTextFitMode.entries.size)
        assertTrue(com.example.model.ClassicTextFitMode.entries.contains(com.example.model.ClassicTextFitMode.WRAP_AND_EXPAND))
        assertTrue(com.example.model.ClassicTextFitMode.entries.contains(com.example.model.ClassicTextFitMode.AUTO_FIT_SHRINK))
    }

    @Test
    fun `google translate url uses gtx endpoint with auto source and utf8 encoding`() {
        val url = com.example.engine.FreeWebTranslationProvider.buildGoogleTranslateUrl(
            text = "Hello, shadow & light! Çağırdı",
            targetLangCode = TranslateLanguage.TURKISH
        )
        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=tr&dt=t&q="))
        assertTrue(url.contains("%C3%87a%C4%9F%C4%B1rd%C4%B1"))
    }

    @Test
    fun `google web mode never falls back to offline ML kit on HTTP or network error`() = runTest {
        val offline = FakeProvider(TranslationEngineMode.OFFLINE_LOCAL, "BROKEN_LOCAL")
        val failingGoogle = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                throw java.io.IOException("Google Hatası: 403")
            }
            override fun close() = Unit
        }
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offline,
                TranslationEngineMode.GOOGLE_FREE to failingGoogle
            ),
            cacheStore = InMemoryCacheStore()
        )
        val googleConfig = TranslationConfig(
            debounceMs = 200L,
            engineMode = TranslationEngineMode.GOOGLE_FREE
        )

        pipeline.processRecognizedResult(simpleResult("The merchant rested until morning."), null, googleConfig)
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(1, failingGoogle.calls)
        assertEquals(0, offline.translateCalls)
        assertEquals("", pipeline.output.value.translatedText)
        assertEquals("Google Hatası: 403", pipeline.output.value.errorMessage)
        assertEquals(TranslationEngineMode.GOOGLE_FREE, pipeline.output.value.resultEngineMode)
    }

    @Test
    fun `engine mode source tags and AI chip visibility helpers identify each engine accurately`() {
        assertEquals("[Google Web]", TranslationEngineMode.GOOGLE_FREE.formatSourceTag())
        assertEquals("[Bing Web]", TranslationEngineMode.BING_FREE.formatSourceTag())
        assertEquals("[Baidu Web]", TranslationEngineMode.BAIDU_FREE.formatSourceTag())
        assertEquals("[Yerel ML Kiti]", TranslationEngineMode.OFFLINE_LOCAL.formatSourceTag())
        assertEquals("[AI • Groq]", TranslationEngineMode.SMART_AI.formatSourceTag(com.example.model.AiProviderPreset.GROQ))
        assertEquals("[Hibrit]", TranslationEngineMode.HYBRID.formatSourceTag())

        assertFalse(TranslationEngineMode.GOOGLE_FREE.usesAiModel())
        assertFalse(TranslationEngineMode.BING_FREE.usesAiModel())
        assertFalse(TranslationEngineMode.BAIDU_FREE.usesAiModel())
        assertFalse(TranslationEngineMode.OFFLINE_LOCAL.usesAiModel())
        assertTrue(TranslationEngineMode.SMART_AI.usesAiModel())
        assertTrue(TranslationEngineMode.HYBRID.usesAiModel())
    }

    @Test
    fun `blur mode self OCR guard rejects 80pct similar echo and clears lastCompletedEntry so history stays clean`() = runTest {
        val ai = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                return "Kapıyı hemen aç"
            }
            override fun close() = Unit
        }
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.GOOGLE_FREE to ai),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.GOOGLE_FREE
        )

        pipeline.processRecognizedResult(
            simpleResult("Open the door now"),
            null,
            config
        )
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, ai.calls)
        assertEquals("Kapıyı hemen aç", pipeline.output.value.translatedText)
        assertEquals("Kapıyı hemen aç", pipeline.output.value.lastCompletedEntry?.translatedText)

        // 12 chars out of 15 match -> 80% exact threshold ("Kapıyı hemen ab")
        // Verify 80% threshold rejects loop, skips translator, preserves active text, and nulls lastCompletedEntry
        pipeline.processRecognizedResult(
            simpleResult("Kapıyı hemen ab"),
            null,
            config
        )
        advanceTimeBy(300L)
        runCurrent()

        assertEquals(1, ai.calls)
        assertEquals("Kapıyı hemen aç", pipeline.output.value.translatedText)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertEquals(1L, pipeline.output.value.framesSkipped)
        assertEquals(null, pipeline.output.value.lastCompletedEntry)
    }

    @Test
    fun `blur mode rejects Turkish target-language echo without calling translator or adding history`() = runTest {
        val web = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                return "Karanlık ormandan hemen kaçmalıyız"
            }
            override fun close() = Unit
        }
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.GOOGLE_FREE to web),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            sourceLangCode = TranslateLanguage.ENGLISH,
            targetLangCode = TranslateLanguage.TURKISH,
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.GOOGLE_FREE
        )

        pipeline.processRecognizedResult(simpleResult("We must escape the dark forest immediately"), null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, web.calls)
        assertEquals("Karanlık ormandan hemen kaçmalıyız", pipeline.output.value.translatedText)

        // Completely different fragmented Turkish OCR read from blurred overlay -> blocked by Turkish language guard
        pipeline.processRecognizedResult(simpleResult("Güçlü şövalye ışığı"), null, config)
        advanceTimeBy(300L)
        runCurrent()

        assertEquals(1, web.calls)
        assertEquals("Karanlık ormandan hemen kaçmalıyız", pipeline.output.value.translatedText)
        assertEquals(null, pipeline.output.value.lastCompletedEntry)
    }

    @Test
    fun `blur mode rejects diacritic-dropped and partial token-overlap Turkish echoes`() = runTest {
        val web = object : TranslationProvider {
            override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE
            var calls = 0
            override suspend fun isReady(config: TranslationConfig): Boolean = true
            override suspend fun translate(text: String, config: TranslationConfig): String {
                calls++
                return "Şimdi güçlü şövalye ışığını aç!"
            }
            override fun close() = Unit
        }
        val pipeline = SubtitlePipeline(
            scope = this,
            ocrEngine = FakeOcrEngine(),
            providers = mapOf(TranslationEngineMode.GOOGLE_FREE to web),
            cacheStore = InMemoryCacheStore()
        )
        val config = TranslationConfig(
            sourceLangCode = TranslateLanguage.ENGLISH,
            targetLangCode = TranslateLanguage.TURKISH,
            debounceMs = 200L,
            displayMode = DisplayMode.IN_PLACE_BLUR,
            engineMode = TranslationEngineMode.GOOGLE_FREE
        )

        pipeline.processRecognizedResult(simpleResult("Turn on the mighty knight's light now!"), null, config)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, web.calls)

        // ML Kit Latin OCR often drops all Turkish diacritics ("ş->s, ü->u, ğ->g, ö->o, ı->i"):
        // zero diacritics remain AND raw similarity < 80%, yet folded match is 100% -> must be blocked!
        pipeline.processRecognizedResult(simpleResult("Simdi guclu sovalye isigini ac!"), null, config)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(1, web.calls)
        assertEquals(null, pipeline.output.value.lastCompletedEntry)

        // Partial read + surrounding UI noise sharing >=60% folded tokens -> must also be blocked!
        pipeline.processRecognizedResult(simpleResult("HP 99 guclu sovalye isigini"), null, config)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(1, web.calls)
        assertEquals(null, pipeline.output.value.lastCompletedEntry)
    }

    @Test
    fun `blur backdrop style defaults preserve dark frosted and expose all 3 options`() {
        val defaults = TranslationConfig()
        assertEquals(com.example.model.BlurBackdropStyle.DARK_FROSTED, defaults.inPlaceBlurStyle)
        assertEquals(0.70f, defaults.inPlaceBlurIntensity, 0.001f)
        assertEquals(3, com.example.model.BlurBackdropStyle.entries.size)
        assertTrue(com.example.model.BlurBackdropStyle.entries.contains(com.example.model.BlurBackdropStyle.PURE_TEXT))
        assertTrue(com.example.model.BlurBackdropStyle.entries.contains(com.example.model.BlurBackdropStyle.DARK_FROSTED))
        assertTrue(com.example.model.BlurBackdropStyle.entries.contains(com.example.model.BlurBackdropStyle.REAL_BLUR))
    }
}
