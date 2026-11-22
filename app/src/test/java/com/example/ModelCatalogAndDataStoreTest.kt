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
}
