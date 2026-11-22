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

        // Dialogue disappears -> 2 identical black frames follow (RoiFrameDiffer would return false on frame #2 without bypassDiffer)
        currentOcr = com.example.engine.OcrResult("", emptyList())
        pipeline.processFrame(blankBitmap, cfg)
        assertEquals(1, pipeline.output.value.inPlaceBlocks.size)
        assertTrue(pipeline.isAwaitingBlankClear())

        pipeline.processFrame(blankBitmap, cfg)
        assertTrue(pipeline.output.value.inPlaceBlocks.isEmpty())
        assertEquals("", pipeline.output.value.translatedText)
        assertFalse(pipeline.isAwaitingBlankClear())

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
