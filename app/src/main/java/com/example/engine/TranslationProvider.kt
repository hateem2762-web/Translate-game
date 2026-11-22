package com.example.engine

import android.content.Context
import android.util.Log
import com.example.ai.ensureFirebaseInitialized
import com.example.model.AiModelOption
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

class SmartAiTranslationProvider(
    context: Context
) : TranslationProvider {
    private val appContext = context.applicationContext ?: context
    override val mode: TranslationEngineMode = TranslationEngineMode.SMART_AI

    private val geminiFlashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash",
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
    }

    private val geminiLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
    }

    private val quotaCooldownUntilMs = AtomicLong(0L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val geminiRestClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean {
        val isolatedKey = config.apiKeyFor(config.aiPreset).trim()
        return when (config.aiPreset) {
            AiProviderPreset.GEMINI ->
                isolatedKey.isNotBlank() || ensureFirebaseInitialized(appContext)
            AiProviderPreset.GROQ,
            AiProviderPreset.CEREBRAS,
            AiProviderPreset.OPENROUTER,
            AiProviderPreset.OPENAI_COMPATIBLE ->
                isolatedKey.isNotBlank() && isValidHttpsUrl(resolveChatEndpoint(config))
        }
    }

    suspend fun testConnection(text: String, config: TranslationConfig): String {
        return translateInternal(text, config, bypassCooldown = true)
    }

    /**
     * Fetches live available models from the active provider's `/models` endpoint using its
     * isolated API key, merged cleanly with curated default options.
     */
    suspend fun fetchAvailableModels(config: TranslationConfig): List<AiModelOption> {
        val preset = config.aiPreset
        val apiKey = config.apiKeyFor(preset).trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("${preset.shortLabelTr} modellerini getirmek için önce API anahtarını girin.")
        }

        return withContext(Dispatchers.IO) {
            when (preset) {
                AiProviderPreset.GEMINI -> {
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                        .addHeader("x-goog-api-key", apiKey)
                        .get()
                        .build()
                    geminiRestClient.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val apiMsg = runCatching {
                                JSONObject(raw).optJSONObject("error")?.optString("message")
                            }.getOrNull()
                            throw IOException(
                                "Gemini model listesi alınamadı (${resp.code}): ${apiMsg.orEmpty()}".trim()
                            )
                        }
                        parseGeminiModelsJson(raw, preset)
                    }
                }
                AiProviderPreset.GROQ,
                AiProviderPreset.CEREBRAS,
                AiProviderPreset.OPENROUTER,
                AiProviderPreset.OPENAI_COMPATIBLE -> {
                    val modelsUrl = resolveModelsEndpoint(config)
                    if (!isValidHttpsUrl(modelsUrl)) {
                        throw IllegalArgumentException("Geçerli bir HTTPS uç noktası girin.")
                    }
                    val reqBuilder = Request.Builder()
                        .url(modelsUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get()
                    if (modelsUrl.contains("openrouter.ai", ignoreCase = true)) {
                        reqBuilder.addHeader("X-Title", "SubLens")
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw IOException(extractApiError(resp.code, raw))
                        }
                        parseOpenAiModelsJson(raw, preset)
                    }
                }
            }
        }
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        return translateInternal(text, config, bypassCooldown = false)
    }

    override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translate(texts.first(), config))
        if (config.sourceLangCode == config.targetLangCode) return texts

        enforceCooldownIfActive(bypassCooldown = false)

        val targetLangTr = SupportedLanguage.fromCode(config.targetLangCode).displayNameTr
        val systemPrompt = buildSystemPrompt(targetLangTr = targetLangTr, isBatch = true)

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

        val targetLangTr = SupportedLanguage.fromCode(config.targetLangCode).displayNameTr
        val systemPrompt = buildSystemPrompt(targetLangTr = targetLangTr, isBatch = false)
        return dispatchAiPrompt(systemPrompt, text, config)
    }

    private suspend fun dispatchAiPrompt(
        systemPrompt: String,
        userContent: String,
        config: TranslationConfig
    ): String {
        val isolatedApiKey = config.apiKeyFor(config.aiPreset).trim()
        return when (config.aiPreset) {
            AiProviderPreset.GEMINI -> callGemini(
                systemPrompt = systemPrompt,
                text = userContent,
                apiKey = isolatedApiKey,
                modelId = AiProviderPreset.sanitizeModelId(AiProviderPreset.GEMINI, config.aiModelName)
            )
            AiProviderPreset.GROQ,
            AiProviderPreset.CEREBRAS,
            AiProviderPreset.OPENROUTER,
            AiProviderPreset.OPENAI_COMPATIBLE -> {
                if (isolatedApiKey.isEmpty()) {
                    throw IllegalStateException("${config.aiPreset.shortLabelTr} için API anahtarı girin.")
                }
                val endpoint = resolveChatEndpoint(config)
                if (!isValidHttpsUrl(endpoint)) {
                    throw IllegalArgumentException("Geçerli bir HTTPS API uç noktası girin.")
                }
                val model = AiProviderPreset.sanitizeModelId(config.aiPreset, config.aiModelName)
                withContext(Dispatchers.IO) {
                    callOpenAiCompatible(endpoint, model, isolatedApiKey, systemPrompt, userContent)
                }
            }
        }
    }

    private suspend fun callGemini(
        systemPrompt: String,
        text: String,
        apiKey: String,
        modelId: String
    ): String {
        if (apiKey.isNotEmpty()) {
            return callGeminiRest(systemPrompt, text, apiKey, modelId)
        }
        val preferLite = modelId.contains("lite", ignoreCase = true)
        return callFirebaseGemini(systemPrompt, text, preferLite)
    }

    private suspend fun callGeminiRest(
        systemPrompt: String,
        text: String,
        apiKey: String,
        model: String
    ): String {
        val preferLite = model.contains("lite", ignoreCase = true)
        try {
            return withContext(Dispatchers.IO) {
                try {
                    requestGeminiRest(model, apiKey, systemPrompt, text, disableThinking = true)
                } catch (e: IOException) {
                    if (e.message?.contains("thinking", ignoreCase = true) == true) {
                        requestGeminiRest(model, apiKey, systemPrompt, text, disableThinking = false)
                    } else {
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiQuotaExceededException) {
            val liteModel = AiProviderPreset.GEMINI.defaultModel
            if (!preferLite && model != liteModel) {
                Log.w(TAG, "$model quota hit, falling back to $liteModel")
                return withContext(Dispatchers.IO) {
                    requestGeminiRest(liteModel, apiKey, systemPrompt, text, disableThinking = false)
                }
            }
            throw e
        }
    }

    private fun requestGeminiRest(
        model: String,
        apiKey: String,
        systemPrompt: String,
        text: String,
        disableThinking: Boolean
    ): String {
        val genConfig = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        if (disableThinking) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            )
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        geminiRestClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMsg = runCatching {
                    JSONObject(rawBody).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                Log.e(TAG, "Gemini REST HTTP ${response.code}: ${apiMsg ?: rawBody}")
                when (response.code) {
                    429 -> {
                        quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                        throw AiQuotaExceededException(
                            "Gemini kotası doldu (429). Groq/Cerebras seçin, Hızlı Yerel / LibreTranslate moduna geçin veya bekleyin."
                        )
                    }
                    400, 401, 403 -> throw IOException(
                        "Gemini API isteği/anahtarı reddedildi (${response.code}): ${apiMsg.orEmpty()}".trim()
                    )
                    404 -> throw IOException(
                        "Gemini modeli bulunamadı: \"$model\". Modelleri Yenile butonuna basıp güncel bir model seçin."
                    )
                    else -> throw IOException("Gemini API hatası (${response.code}): ${apiMsg.orEmpty()}".trim())
                }
            }

            val json = JSONObject(rawBody)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val result = buildString {
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
            }.trim()
            if (result.isBlank()) {
                val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw IOException(
                    if (blocked.isNotBlank()) "Gemini isteği engelledi ($blocked)." else "Gemini AI boş yanıt döndürdü."
                )
            }
            quotaCooldownUntilMs.set(0L)
            return result
        }
    }

    private suspend fun callFirebaseGemini(
        systemPrompt: String,
        text: String,
        preferLite: Boolean
    ): String {
        if (!ensureFirebaseInitialized(appContext)) {
            throw IOException(
                "Firebase başlatılamadı (google-services.json eksik). Groq/Cerebras veya Gemini API anahtarı girebilirsiniz."
            )
        }
        val prompt = "$systemPrompt\n\n$text"
        val primaryModel = if (preferLite) geminiLiteModel else geminiFlashModel

        try {
            return generateWithModel(primaryModel, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preferLite && isQuotaError(e)) {
                Log.w(TAG, "gemini-2.5-flash quota hit, falling back to gemini-2.5-flash-lite", e)
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
                "Gemini ücretsiz/proje kotası doldu (429). Groq/Cerebras (ücretsiz) veya Hızlı Yerel (ML Kit) kullanabilirsiniz."
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
                "AI kota beklemesi aktif (${sec}s). Groq/Cerebras veya Hızlı Yerel (ML Kit) moda geçebilirsiniz."
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

    /**
     * Stateless single-turn OpenAI-compatible Chat Completions call (Groq / Cerebras / OpenRouter / Custom).
     * Sends no prior conversation history and caps `max_tokens = 120` for sub-500ms latency.
     */
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
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("stream", false)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
        }

        val builder = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (endpointUrl.contains("openrouter.ai", ignoreCase = true)) {
            builder.addHeader("X-Title", "SubLens")
        }

        client.newCall(builder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = extractApiError(response.code, rawBody)
                if (response.code == 429) {
                    quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                    throw AiQuotaExceededException("$errMsg — Hız limiti doldu, kısa süre bekleyin veya Yerel moda geçin.")
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
        val pattern = Regex("""^(?:\[(\d+)]|(\d+)[.)])\s*(.+)$""")
        for (line in lines) {
            val match = pattern.matchEntire(line)
            if (match != null) {
                val index = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
                val text = match.groupValues[3].trim()
                if (index != null && index in 1..expectedCount && text.isNotEmpty()) {
                    byIndex[index - 1] = text
                }
            }
        }
        if (byIndex.size == expectedCount) {
            return (0 until expectedCount).map { byIndex.getValue(it) }
        }
        if (lines.size == expectedCount) {
            return lines.map { it.replace(Regex("""^(?:\[\d+]|\d+[.)])\s*"""), "").trim() }
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
        const val MAX_OUTPUT_TOKENS = 120
        private const val QUOTA_COOLDOWN_MS = 45_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NON_TEXT_MODEL_KEYWORDS = listOf(
            "whisper", "tts", "embed", "moderation", "guard", "safeguard",
            "orpheus", "imagen", "veo", "aqa"
        )

        fun buildSystemPrompt(targetLangTr: String, isBatch: Boolean): String {
            return if (isBatch) {
                "Sen bir oyun altyazı çevirmenisin. Numaralı her satırı sadece doğal ve akıcı şekilde $targetLangTr diline çevir ([1] ..., [2] ...), başka hiçbir şey yazma."
            } else {
                "Sen bir oyun altyazı çevirmenisin. Sadece verilen metni doğal ve akıcı şekilde $targetLangTr diline çevir, başka hiçbir şey yazma."
            }
        }

        fun resolveChatEndpoint(config: TranslationConfig): String {
            val raw = config.aiEndpointUrl.trim().ifEmpty { config.aiPreset.defaultBaseUrl }.trimEnd('/')
            if (raw.isEmpty() || raw == "firebase-ai-logic") return raw
            return when {
                raw.endsWith("/chat/completions", ignoreCase = true) -> raw
                raw.endsWith("/v1", ignoreCase = true) ||
                    raw.endsWith("/openai/v1", ignoreCase = true) ||
                    raw.endsWith("/api/v1", ignoreCase = true) ->
                    "$raw/chat/completions"
                else -> raw
            }
        }

        fun resolveModelsEndpoint(config: TranslationConfig): String {
            if (config.aiPreset == AiProviderPreset.GEMINI) {
                return "https://generativelanguage.googleapis.com/v1beta/models"
            }
            val chat = resolveChatEndpoint(config).trimEnd('/')
            return when {
                chat.endsWith("/chat/completions", ignoreCase = true) ->
                    chat.removeSuffix("/chat/completions") + "/models"
                chat.endsWith("/completions", ignoreCase = true) ->
                    chat.removeSuffix("/completions") + "/models"
                chat.endsWith("/models", ignoreCase = true) -> chat
                else -> "$chat/models"
            }
        }

        fun parseOpenAiModelsJson(rawJson: String, preset: AiProviderPreset): List<AiModelOption> {
            val data = JSONObject(rawJson).optJSONArray("data") ?: return preset.curatedModels
            val curatedById = preset.curatedModels.associateBy { it.id }
            val fetchedIds = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isEmpty()) continue
                if (id in AiProviderPreset.DEPRECATED_MODEL_IDS) continue
                val lower = id.lowercase()
                if (NON_TEXT_MODEL_KEYWORDS.any { lower.contains(it) }) continue
                fetchedIds.add(id)
            }
            val merged = LinkedHashMap<String, AiModelOption>()
            preset.curatedModels.forEach { merged[it.id] = it }
            fetchedIds.sorted().forEach { id ->
                if (!merged.containsKey(id)) {
                    merged[id] = curatedById[id] ?: AiModelOption(id = id, label = id)
                }
            }
            return merged.values.toList()
        }

        fun parseGeminiModelsJson(rawJson: String, preset: AiProviderPreset): List<AiModelOption> {
            val models = JSONObject(rawJson).optJSONArray("models") ?: return preset.curatedModels
            val curatedById = preset.curatedModels.associateBy { it.id }
            val fetchedIds = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val obj = models.optJSONObject(i) ?: continue
                val methods = obj.optJSONArray("supportedGenerationMethods")
                var supportsGenerate = false
                if (methods != null) {
                    for (m in 0 until methods.length()) {
                        if (methods.optString(m) == "generateContent") {
                            supportsGenerate = true
                            break
                        }
                    }
                }
                if (!supportsGenerate) continue
                val rawName = obj.optString("name").trim()
                val id = rawName.removePrefix("models/")
                if (id.isEmpty() || id in AiProviderPreset.DEPRECATED_MODEL_IDS) continue
                val lower = id.lowercase()
                if (NON_TEXT_MODEL_KEYWORDS.any { lower.contains(it) }) continue
                fetchedIds.add(id)
            }
            val merged = LinkedHashMap<String, AiModelOption>()
            preset.curatedModels.forEach { merged[it.id] = it }
            fetchedIds.sorted().forEach { id ->
                if (!merged.containsKey(id)) {
                    merged[id] = curatedById[id] ?: AiModelOption(id = id, label = id)
                }
            }
            return merged.values.toList()
        }
    }
}
