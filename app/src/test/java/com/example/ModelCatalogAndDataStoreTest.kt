package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsDataStore
import com.example.engine.SmartAiTranslationProvider
import com.example.model.AiProviderPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelCatalogAndDataStoreTest {

    @Test
    fun `parseOpenAiModelsJson filters non-text and deprecated models while preserving curated defaults first`() {
        val sampleJson = """
            {
              "data": [
                {"id": "whisper-large-v3"},
                {"id": "llama-3.3-70b-versatile"},
                {"id": "text-embedding-3-small"},
                {"id": "openai/gpt-oss-20b"},
                {"id": "qwen/qwen3-32b"},
                {"id": "meta-llama/llama-4-scout-17b-16e-instruct"}
              ]
            }
        """.trimIndent()

        val parsed = SmartAiTranslationProvider.parseOpenAiModelsJson(sampleJson, AiProviderPreset.GROQ)
        val ids = parsed.map { it.id }

        // Curated Groq models remain first
        assertEquals("openai/gpt-oss-20b", ids.first())
        assertTrue(ids.contains("qwen/qwen3.8-27b"))
        assertTrue(ids.contains("openai/gpt-oss-120b"))
        // Fresh live models added
        assertTrue(ids.contains("qwen/qwen3-32b"))
        assertTrue(ids.contains("meta-llama/llama-4-scout-17b-16e-instruct"))
        // Audio, embedding, and deprecated models filtered out
        assertFalse(ids.contains("whisper-large-v3"))
        assertFalse(ids.contains("text-embedding-3-small"))
        assertFalse(ids.contains("llama-3.3-70b-versatile"))
    }

    @Test
    fun `parseGeminiModelsJson keeps generateContent models and strips models prefix`() {
        val sampleJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-pro",
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/text-embedding-004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/gemini-3.8-flash",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val parsed = SmartAiTranslationProvider.parseGeminiModelsJson(sampleJson, AiProviderPreset.GEMINI)
        val ids = parsed.map { it.id }

        assertEquals("gemini-2.5-flash-lite", ids.first())
        assertTrue(ids.contains("gemini-2.5-flash"))
        assertTrue(ids.contains("gemini-2.5-pro"))
        assertFalse(ids.contains("text-embedding-004"))
        assertFalse(ids.contains("gemini-3.8-flash"))
    }

    @Test
    fun `SettingsDataStore isolates API keys and models strictly per provider`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.updateAiPreset(AiProviderPreset.GROQ)
        store.updateAiApiKey(AiProviderPreset.GROQ, "gsk_only_groq_123")
        store.updateAiModelName(AiProviderPreset.GROQ, "qwen/qwen3.8-27b")

        store.updateAiPreset(AiProviderPreset.CEREBRAS)
        store.updateAiApiKey(AiProviderPreset.CEREBRAS, "csk_only_cerebras_456")

        store.updateAiPreset(AiProviderPreset.GEMINI)
        store.updateAiApiKey(AiProviderPreset.GEMINI, "AIza_only_gemini_789")
        store.updateAiModelName(AiProviderPreset.GEMINI, "gemini-2.5-flash")

        val config = store.configFlow.first()
        assertEquals(AiProviderPreset.GEMINI, config.aiPreset)
        assertEquals("AIza_only_gemini_789", config.aiApiKey)
        assertEquals("gemini-2.5-flash", config.aiModelName)

        // Switching or inspecting other providers never leaks Gemini key/model
        assertEquals("gsk_only_groq_123", config.apiKeyFor(AiProviderPreset.GROQ))
        assertEquals("csk_only_cerebras_456", config.apiKeyFor(AiProviderPreset.CEREBRAS))
        assertEquals("AIza_only_gemini_789", config.apiKeyFor(AiProviderPreset.GEMINI))
        assertEquals("", config.apiKeyFor(AiProviderPreset.OPENAI_COMPATIBLE))

        assertEquals("qwen/qwen3.8-27b", config.modelFor(AiProviderPreset.GROQ))
        assertEquals("gpt-oss-120b", config.modelFor(AiProviderPreset.CEREBRAS))
        assertEquals("gemini-2.5-flash", config.modelFor(AiProviderPreset.GEMINI))
    }

    @Test
    fun `processFrame clears In-Place Blur when static blank frames follow translated subtitle`(): Unit = runBlocking {
        val subtitleBitmap = android.graphics.Bitmap.createBitmap(120, 40, android.graphics.Bitmap.Config.ARGB_8888)
        subtitleBitmap.eraseColor(android.graphics.Color.WHITE)
        val blankBitmap = android.graphics.Bitmap.createBitmap(120, 40, android.graphics.Bitmap.Config.ARGB_8888)
        blankBitmap.eraseColor(android.graphics.Color.BLACK)

        var currentOcr = com.example.engine.OcrResult(
            fullText = "Hold the gate!",
            blocks = listOf(com.example.engine.OcrBlock("Hold the gate!", com.example.model.RoiRect(10, 8, 90, 20)))
        )
        val fakeOcr = object : com.example.engine.OcrEngine {
            override suspend fun recognize(bitmap: android.graphics.Bitmap, sourceLangCode: String) = currentOcr
            override fun close() = Unit
        }
        val fakeAi = object : com.example.engine.TranslationProvider {
            override val mode = com.example.model.TranslationEngineMode.SMART_AI
            override suspend fun isReady(config: com.example.model.TranslationConfig) = true
            override suspend fun translate(text: String, config: com.example.model.TranslationConfig) = "Kapıyı tut!"
            override fun close() = Unit
        }
        val emptyCache = object : com.example.data.repository.TranslationCacheStore {
            override suspend fun get(
                engineMode: com.example.model.TranslationEngineMode,
                sourceLang: String,
                targetLang: String,
                normalizedSource: String
            ): String? = null
            override suspend fun put(
                engineMode: com.example.model.TranslationEngineMode,
                sourceLang: String,
                targetLang: String,
                normalizedSource: String,
                translated: String
            ) = Unit
            override suspend fun clear() = Unit
        }

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val pipeline = com.example.engine.SubtitlePipeline(
            scope = scope,
            ocrEngine = fakeOcr,
            providers = mapOf(com.example.model.TranslationEngineMode.SMART_AI to fakeAi),
            cacheStore = emptyCache
        )
        val cfg = com.example.model.TranslationConfig(
            debounceMs = 50L,
            displayMode = com.example.model.DisplayMode.IN_PLACE_BLUR,
            engineMode = com.example.model.TranslationEngineMode.SMART_AI
        )

        pipeline.processFrame(subtitleBitmap, cfg)
        kotlinx.coroutines.delay(140L)
        assertEquals("Kapıyı tut!", pipeline.output.value.translatedText)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)

        // Dialogue disappears -> 3 identical black frames follow (RoiFrameDiffer would return false on #2/#3 without bypassDiffer)
        currentOcr = com.example.engine.OcrResult("", emptyList())
        pipeline.processFrame(blankBitmap, cfg)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertTrue(pipeline.isAwaitingBlankClear())

        pipeline.processFrame(blankBitmap, cfg)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertTrue(pipeline.isAwaitingBlankClear())

        pipeline.processFrame(blankBitmap, cfg)
        assertTrue(pipeline.output.value.inPlaceBlocks.isEmpty())
        assertEquals("", pipeline.output.value.translatedText)
        assertFalse(pipeline.isAwaitingBlankClear())

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `RoiFrameDiffer ignores changes inside masked blur rect and detects outer edge change`() {
        val differ = com.example.engine.RoiFrameDiffer()
        val maskRect = com.example.model.RoiRect(x = 30, y = 20, width = 140, height = 60)
        val masked = listOf(maskRect)

        fun buildFrame(bgColor: Int, insideMaskColor: Int): android.graphics.Bitmap {
            val bmp = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(bgColor)
            for (y in 20..80) {
                for (x in 30..170) {
                    bmp.setPixel(x, y, insideMaskColor)
                }
            }
            return bmp
        }

        // 1. Initial call establishes baseline -> returns false (no immediate flicker)
        val baseFrame = buildFrame(bgColor = android.graphics.Color.rgb(16, 20, 30), insideMaskColor = android.graphics.Color.WHITE)
        assertFalse(differ.hasUnmaskedVisualChange(baseFrame, masked, maskSignature = 101))

        // 2. Pixels strictly INSIDE the blur box change drastically, outside stays identical -> MUST return false
        val innerChangedFrame = buildFrame(bgColor = android.graphics.Color.rgb(16, 20, 30), insideMaskColor = android.graphics.Color.CYAN)
        assertFalse(differ.hasUnmaskedVisualChange(innerChangedFrame, masked, maskSignature = 101))

        // 3. Changing maskSignature re-establishes baseline without triggering false change
        assertFalse(differ.hasUnmaskedVisualChange(innerChangedFrame, masked, maskSignature = 202))

        // 4. Single stray outer sparkle/particle must NOT falsely trigger stealth capture blink
        val minorSparkleFrame = buildFrame(bgColor = android.graphics.Color.rgb(16, 20, 30), insideMaskColor = android.graphics.Color.CYAN)
        minorSparkleFrame.setPixel(6, 6, android.graphics.Color.WHITE)
        assertFalse(differ.hasUnmaskedVisualChange(minorSparkleFrame, masked, maskSignature = 202))

        // 5. Outer unmasked background / edge changes -> MUST return true
        val outerChangedFrame = buildFrame(bgColor = android.graphics.Color.rgb(210, 210, 210), insideMaskColor = android.graphics.Color.CYAN)
        assertTrue(differ.hasUnmaskedVisualChange(outerChangedFrame, masked, maskSignature = 202))
    }

    @Test
    fun `SettingsDataStore persists subtitle text color and classic fit mode`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.updateSubtitleTextColorArgb(com.example.model.SubtitleColorPreset.CINEMA_GOLD.argb)
        store.updateClassicTextFitMode(com.example.model.ClassicTextFitMode.AUTO_FIT_SHRINK)

        val config = store.configFlow.first()
        assertEquals(com.example.model.SubtitleColorPreset.CINEMA_GOLD.argb, config.subtitleTextColorArgb)
        assertEquals(com.example.model.ClassicTextFitMode.AUTO_FIT_SHRINK, config.classicTextFitMode)

        store.updateSubtitleTextColorArgb(0xFF93C5FD.toInt())
        store.updateClassicTextFitMode(com.example.model.ClassicTextFitMode.WRAP_AND_EXPAND)

        val updated = store.configFlow.first()
        assertEquals(0xFF93C5FD.toInt(), updated.subtitleTextColorArgb)
        assertEquals(com.example.model.ClassicTextFitMode.WRAP_AND_EXPAND, updated.classicTextFitMode)
    }

    @Test
    fun `SettingsDataStore persists floating bubble behavior and all 5 themes`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        assertEquals(2, com.example.model.FloatingBubbleBehavior.entries.size)
        assertEquals(5, com.example.model.FloatingBubbleTheme.entries.size)

        store.updateBubbleBehavior(com.example.model.FloatingBubbleBehavior.DOCK_HALF_CIRCLE)
        for (theme in com.example.model.FloatingBubbleTheme.entries) {
            store.updateBubbleTheme(theme)
            val cfg = store.configFlow.first()
            assertEquals(com.example.model.FloatingBubbleBehavior.DOCK_HALF_CIRCLE, cfg.bubbleBehavior)
            assertEquals(theme, cfg.bubbleTheme)
        }

        store.updateBubbleBehavior(com.example.model.FloatingBubbleBehavior.HIDE_WHEN_ACTIVE)
        val reverted = store.configFlow.first()
        assertEquals(com.example.model.FloatingBubbleBehavior.HIDE_WHEN_ACTIVE, reverted.bubbleBehavior)
    }

    @Test
    fun `FreeWebTranslationProvider and engine modes are fully configured`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)

        store.updateEngineMode(com.example.model.TranslationEngineMode.GOOGLE_FREE)
        var config = store.configFlow.first()
        assertEquals(com.example.model.TranslationEngineMode.GOOGLE_FREE, config.engineMode)

        store.updateEngineMode(com.example.model.TranslationEngineMode.BING_FREE)
        config = store.configFlow.first()
        assertEquals(com.example.model.TranslationEngineMode.BING_FREE, config.engineMode)

        store.updateEngineMode(com.example.model.TranslationEngineMode.BAIDU_FREE)
        config = store.configFlow.first()
        assertEquals(com.example.model.TranslationEngineMode.BAIDU_FREE, config.engineMode)
    }

    @Test
    fun `google translate response parser concatenates all JSONArray(0) segments and preserves Turkish UTF-8`() {
        val sampleJson = """
            [
              [
                ["İş adamı şimdi biraz dinlendi. ", "The businessman rested a bit now.", null, null, 1],
                ["Sabah erkenden güneş doğarken yola çıktı! ", "He set off early at sunrise!", null, null, 1],
                ["Şehirde ğüşıöçĞÜŞİÖÇ sesleri yankılandı.", "Sounds echoed across the city.", null, null, 1]
              ],
              null,
              "en"
            ]
        """.trimIndent()

        val parsed = com.example.engine.FreeWebTranslationProvider.parseGoogleResponse(sampleJson)
        assertTrue(parsed.contains("İş adamı şimdi biraz dinlendi."))
        assertTrue(parsed.contains("Sabah erkenden güneş doğarken yola çıktı!"))
        assertTrue(parsed.contains("Şehirde ğüşıöçĞÜŞİÖÇ sesleri yankılandı."))
    }
}
