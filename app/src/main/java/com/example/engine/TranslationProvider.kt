package com.example.engine

import android.util.Log
import com.example.model.AiProviderPreset
import com.example.model.SupportedLanguage
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TranslationProvider"

class AiQuotaExceededException(message: String) : IOException(message)

interface TranslationProvider {
    val mode: TranslationEngineMode
    suspend fun isReady(config: TranslationConfig): Boolean
    suspend fun translate(text: String, config: TranslationConfig): String
    suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        return texts.map { translate(it, config) }
    }
    fun close()
}

class OfflineMlKitTranslationProvider(
    private val mlKitEngine: TranslatorEngine
) : TranslationProvider {
    override val mode: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL

    override suspend fun isReady(config: TranslationConfig): Boolean {
        return mlKitEngine.isModelReady(config.sourceLangCode, config.targetLangCode)
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        return mlKitEngine.translate(text, config.sourceLangCode, config.targetLangCode)
    }

    override fun close() {
        mlKitEngine.close()
    }
}

class SmartAiTranslationProvider : TranslationProvider {
    override val mode: TranslationEngineMode = TranslationEngineMode.SMART_AI

    private val geminiFlashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiProviderPreset.GEMINI_FLASH.defaultModel,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 512
            }
        )
    }

    private val geminiLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiProviderPreset.GEMINI_LITE.defaultModel,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 512
            }
        )
    }

    private val quotaCooldownUntilMs = AtomicLong(0L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean {
        return when (config.aiPreset) {
            AiProviderPreset.GEMINI_FLASH,
            AiProviderPreset.GEMINI_LITE -> true
            AiProviderPreset.OPENAI_COMPATIBLE ->
                config.aiApiKey.isNotBlank() && isValidHttpsUrl(config.aiEndpointUrl)
        }
    }

    suspend fun testConnection(text: String, config: TranslationConfig): String {
        return translateInternal(text, config, bypassCooldown = true)
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        return translateInternal(text, config, bypassCooldown = false)
    }

    override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translate(texts.first(), config))
        if (config.sourceLangCode == config.targetLangCode) return texts

        enforceCooldownIfActive(bypassCooldown = false)

        val sourceName = SupportedLanguage.fromCode(config.sourceLangCode).englishName
        val targetName = SupportedLanguage.fromCode(config.targetLangCode).englishName
        val systemPrompt = buildSystemPrompt(sourceName, targetName, isBatch = true)

        val numberedInput = texts.mapIndexed { idx, line -> "[${idx + 1}] $line" }.joinToString("\n")
        val rawOutput = dispatchAiPrompt(systemPrompt, numberedInput, config)
        val parsed = parseNumberedBatchOutput(rawOutput, texts.size)
        return parsed ?: texts.map { translate(it, config) }
    }

    private suspend fun translateInternal(
        text: String,
        config: TranslationConfig,
        bypassCooldown: Boolean
    ): String {
        if (text.isBlank() || config.sourceLangCode == config.targetLangCode) return text
        enforceCooldownIfActive(bypassCooldown)

        val sourceName = SupportedLanguage.fromCode(config.sourceLangCode).englishName
        val targetName = SupportedLanguage.fromCode(config.targetLangCode).englishName

        val systemPrompt = buildSystemPrompt(sourceName, targetName, isBatch = false)
        return dispatchAiPrompt(systemPrompt, text, config)
    }

    private fun buildSystemPrompt(sourceName: String, targetName: String, isBatch: Boolean): String {
        val chineseGuidance = if (sourceName == "Chinese") {
            " The source is Simplified or Traditional Chinese game dialogue without spaces—interpret Chinese idioms, wuxia/fantasy/RPG terms, and UI phrases naturally."
        } else {
            ""
        }
        return if (isBatch) {
            "You are a professional video game dialogue and UI translator. " +
                "Translate each numbered $sourceName line into natural, idiomatic $targetName " +
                "matching the game's tone.$chineseGuidance Keep each line concise for subtitle boxes. " +
                "Return ONLY the exact same numbered format ([1] ..., [2] ...) with no extra text."
        } else {
            "You are a professional video game dialogue and UI translator. " +
                "Translate the user's $sourceName game text into natural, idiomatic $targetName " +
                "matching the game's tone.$chineseGuidance Keep it concise so it fits cleanly inside subtitle boxes. " +
                "Output ONLY the translated text with no quotes, labels, or explanations."
        }
    }

    private suspend fun dispatchAiPrompt(
        systemPrompt: String,
        userContent: String,
        config: TranslationConfig
    ): String {
        return when (config.aiPreset) {
            AiProviderPreset.GEMINI_FLASH -> callFirebaseGemini(
                systemPrompt = systemPrompt,
                text = userContent,
                preferLite = false
            )
            AiProviderPreset.GEMINI_LITE -> callFirebaseGemini(
                systemPrompt = systemPrompt,
                text = userContent,
                preferLite = true
            )
            AiProviderPreset.OPENAI_COMPATIBLE -> {
                val apiKey = config.aiApiKey.trim()
                if (apiKey.isEmpty()) {
                    throw IllegalStateException("Özel API uç noktası için API anahtarı girin.")
                }
                val endpoint = config.aiEndpointUrl.trim().ifEmpty { config.aiPreset.defaultBaseUrl }
                if (!isValidHttpsUrl(endpoint)) {
                    throw IllegalArgumentException("Geçerli bir HTTPS API uç noktası girin.")
                }
                val model = config.aiModelName.trim().ifEmpty { config.aiPreset.defaultModel }
                withContext(Dispatchers.IO) {
                    callOpenAiCompatible(endpoint, model, apiKey, systemPrompt, userContent)
                }
            }
        }
    }

    private suspend fun callFirebaseGemini(
        systemPrompt: String,
        text: String,
        preferLite: Boolean
    ): String {
        val prompt = "$systemPrompt\n\nGame text:\n$text"
        val primaryModel = if (preferLite) geminiLiteModel else geminiFlashModel

        try {
            return generateWithModel(primaryModel, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preferLite && isQuotaError(e)) {
                Log.w(TAG, "gemini-3.8-flash quota hit, falling back to gemini-3.5-flash-lite", e)
                try {
                    return generateWithModel(geminiLiteModel, prompt)
                } catch (fallbackEx: CancellationException) {
                    throw fallbackEx
                } catch (fallbackEx: Exception) {
                    throw mapFirebaseException(fallbackEx)
                }
            }
            throw mapFirebaseException(e)
        }
    }

    private suspend fun generateWithModel(
        model: com.google.firebase.ai.GenerativeModel,
        prompt: String
    ): String {
        val response = withTimeout(60_000L) {
            model.generateContent(prompt)
        }
        val result = response.text?.trim().orEmpty()
        if (result.isBlank()) {
            throw IOException("Gemini AI boş yanıt döndürdü.")
        }
        quotaCooldownUntilMs.set(0L)
        return result
    }

    private fun mapFirebaseException(e: Exception): IOException {
        if (e is TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI translation timed out", e)
            return IOException("Gemini AI isteği zaman aşımına uğradı.")
        }
        Log.e(TAG, "Firebase AI translation failed", e)
        if (isQuotaError(e)) {
            quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
            return AiQuotaExceededException(
                "Gemini ücretsiz/proje kotası doldu (429). Bir süre bekleyin, Hızlı Yerel (ML Kit) kotasız moda geçin veya Özel API (ör. Groq) kullanın."
            )
        }
        return IOException(e.localizedMessage ?: "Gemini AI çevirisi başarısız.")
    }

    private fun enforceCooldownIfActive(bypassCooldown: Boolean) {
        if (bypassCooldown) return
        val remainingMs = quotaCooldownUntilMs.get() - System.currentTimeMillis()
        if (remainingMs > 0L) {
            val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
            throw AiQuotaExceededException(
                "Gemini kota beklemesi aktif (${sec}s). Kotasız kullanım için Hızlı Yerel (ML Kit) moda geçebilirsiniz."
            )
        }
    }

    private fun isQuotaError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is QuotaExceededException) return true
            val msg = cur.message.orEmpty().lowercase()
            if (msg.contains("quota") ||
                msg.contains("429") ||
                msg.contains("resource_exhausted") ||
                msg.contains("rate limit") ||
                msg.contains("billing")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun callOpenAiCompatible(
        endpointUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        text: String
    ): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 512)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = extractApiError(response.code, rawBody)
                if (response.code == 429) {
                    quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                    throw AiQuotaExceededException("$errMsg — Kota doldu, bekleyin veya Yerel moda geçin.")
                }
                throw IOException(errMsg)
            }
            quotaCooldownUntilMs.set(0L)
            val json = JSONObject(rawBody)
            val choices = json.optJSONArray("choices")
                ?: throw IOException("AI yanıtı boş döndü.")
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("AI mesajı bulunamadı.")
            return msg.optString("content").trim()
        }
    }

    private fun parseNumberedBatchOutput(rawOutput: String, expectedCount: Int): List<String>? {
        val lines = rawOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val byIndex = mutableMapOf<Int, String>()
        val pattern = Regex("""^\[(\d+)\]\s*(.+)$""")
        for (line in lines) {
            val match = pattern.matchEntire(line)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim()
                if (index != null && index in 1..expectedCount && text.isNotEmpty()) {
                    byIndex[index - 1] = text
                }
            }
        }
        if (byIndex.size == expectedCount) {
            return (0 until expectedCount).map { byIndex.getValue(it) }
        }
        if (lines.size == expectedCount) {
            return lines.map { it.replace(Regex("""^\[\d+\]\s*"""), "").trim() }
        }
        return null
    }

    private fun extractApiError(code: Int, rawBody: String): String {
        val parsed = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) {
            "AI API ($code): $parsed"
        } else {
            "AI API hatası ($code)"
        }
    }

    private fun isValidHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) && trimmed.length > 10
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val QUOTA_COOLDOWN_MS = 20_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
