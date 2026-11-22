package com.example.engine

import android.util.Log
import com.example.model.SupportedLanguage
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val LIBRE_TAG = "LibreTranslateProvider"

/**
 * Open-source LibreTranslate provider supporting public and self-hosted LibreTranslate instances.
 * Normalizes endpoint URLs cleanly (auto-prepending `https://` when omitted and appending `/translate`),
 * supports optional API keys for hosted instances, and handles both single and batch array payloads.
 */
class LibreTranslateProvider : TranslationProvider {

    override val mode: TranslationEngineMode = TranslationEngineMode.LIBRETRANSLATE

    private val rateLimitCooldownUntilMs = AtomicLong(0L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean {
        return resolveEndpoint(config.libreEndpointUrl) != null
    }

    suspend fun testConnection(text: String, config: TranslationConfig): String {
        return translateInternal(listOf(text), config, bypassCooldown = true).firstOrNull().orEmpty()
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        if (text.isBlank() || config.sourceLangCode == config.targetLangCode) return text
        return translateInternal(listOf(text), config, bypassCooldown = false).firstOrNull().orEmpty()
    }

    override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (config.sourceLangCode == config.targetLangCode) return texts
        val nonBlankIndices = texts.indices.filter { texts[it].isNotBlank() }
        if (nonBlankIndices.isEmpty()) return texts

        val toTranslate = nonBlankIndices.map { texts[it] }
        val translatedSubset = translateInternal(toTranslate, config, bypassCooldown = false)

        val output = texts.toMutableList()
        nonBlankIndices.forEachIndexed { subsetIdx, origIdx ->
            output[origIdx] = translatedSubset.getOrElse(subsetIdx) { texts[origIdx] }
        }
        return output
    }

    private suspend fun translateInternal(
        texts: List<String>,
        config: TranslationConfig,
        bypassCooldown: Boolean
    ): List<String> {
        val endpoint = resolveEndpoint(config.libreEndpointUrl)
            ?: throw IllegalArgumentException(
                "Geçerli bir LibreTranslate sunucu adresi girin (örn. https://libretranslate.com veya http://192.168.1.10:5000)."
            )

        if (!bypassCooldown) {
            val remainingMs = rateLimitCooldownUntilMs.get() - System.currentTimeMillis()
            if (remainingMs > 0L) {
                val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                throw AiQuotaExceededException(
                    "LibreTranslate hız beklemesi aktif (${sec}s). Kendi sunucunuzu girebilir veya Hızlı Yerel kullanabilirsiniz."
                )
            }
        }

        val sourceCode = mapLangCode(config.sourceLangCode)
        val targetCode = mapLangCode(config.targetLangCode)
        val apiKey = config.libreApiKey.trim()

        return withContext(Dispatchers.IO) {
            if (texts.size == 1) {
                listOf(executeSingleRequest(endpoint, texts.first(), sourceCode, targetCode, apiKey))
            } else {
                try {
                    executeArrayBatchRequest(endpoint, texts, sourceCode, targetCode, apiKey)
                } catch (batchErr: IOException) {
                    if (batchErr is AiQuotaExceededException) throw batchErr
                    Log.w(LIBRE_TAG, "Batch request rejected by server, falling back to single lines", batchErr)
                    texts.map { line ->
                        executeSingleRequest(endpoint, line, sourceCode, targetCode, apiKey)
                    }
                }
            }
        }
    }

    private fun executeSingleRequest(
        endpoint: String,
        text: String,
        sourceCode: String,
        targetCode: String,
        apiKey: String
    ): String {
        val payload = JSONObject().apply {
            put("q", text)
            put("source", sourceCode)
            put("target", targetCode)
            put("format", "text")
            if (apiKey.isNotEmpty()) {
                put("api_key", apiKey)
            }
        }
        val json = postJson(endpoint, payload)
        val translated = json.optString("translatedText").trim()
        if (translated.isBlank()) {
            throw IOException("LibreTranslate boş yanıt döndürdü.")
        }
        return translated
    }

    private fun executeArrayBatchRequest(
        endpoint: String,
        texts: List<String>,
        sourceCode: String,
        targetCode: String,
        apiKey: String
    ): List<String> {
        val payload = JSONObject().apply {
            put("q", JSONArray(texts))
            put("source", sourceCode)
            put("target", targetCode)
            put("format", "text")
            if (apiKey.isNotEmpty()) {
                put("api_key", apiKey)
            }
        }
        val json = postJson(endpoint, payload)
        val arr = json.optJSONArray("translatedText")
            ?: throw IOException("LibreTranslate toplu yanıtı dizi formatında değil.")
        if (arr.length() != texts.size) {
            throw IOException("LibreTranslate yanıtındaki satır sayısı eşleşmedi.")
        }
        return (0 until arr.length()).map { idx -> arr.optString(idx).trim() }
    }

    private fun postJson(endpoint: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                handleHttpError(response.code, rawBody)
            }
            rateLimitCooldownUntilMs.set(0L)
            return runCatching { JSONObject(rawBody) }.getOrElse {
                throw IOException("LibreTranslate geçersiz JSON yanıtı döndürdü.")
            }
        }
    }

    private fun handleHttpError(code: Int, rawBody: String): Nothing {
        val apiError = runCatching {
            JSONObject(rawBody).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        Log.e(LIBRE_TAG, "LibreTranslate HTTP $code: ${apiError ?: rawBody}")

        when (code) {
            400 -> throw IOException(
                apiError?.let { "LibreTranslate (400): $it" }
                    ?: "LibreTranslate isteği geçersiz (400)."
            )
            403 -> throw IOException(
                "LibreTranslate API anahtarı istiyor veya anahtar geçersiz (403). Kendi sunucunuzda anahtar gerekmez; açık sunucuda API anahtarı girin."
            )
            429 -> {
                rateLimitCooldownUntilMs.set(System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS)
                throw AiQuotaExceededException(
                    "LibreTranslate istek sınırı (429). Kısa süre bekleyin, kendi sunucunuzu girin veya Hızlı Yerel kullanın."
                )
            }
            else -> throw IOException(
                if (apiError != null) "LibreTranslate ($code): $apiError" else "LibreTranslate sunucu hatası ($code)"
            )
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val RATE_LIMIT_COOLDOWN_MS = 20_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun resolveEndpoint(rawUrl: String): String? {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val normalized = if (withScheme.endsWith("/translate", ignoreCase = true)) {
                withScheme
            } else {
                "$withScheme/translate"
            }
            return normalized.takeIf { it.length > 12 }
        }

        fun mapLangCode(mlKitCode: String): String {
            return when (mlKitCode) {
                TranslateLanguage.ENGLISH -> "en"
                TranslateLanguage.CHINESE -> "zh"
                TranslateLanguage.TURKISH -> "tr"
                TranslateLanguage.GERMAN -> "de"
                TranslateLanguage.FRENCH -> "fr"
                TranslateLanguage.SPANISH -> "es"
                TranslateLanguage.ITALIAN -> "it"
                TranslateLanguage.PORTUGUESE -> "pt"
                else -> SupportedLanguage.fromCode(mlKitCode).shortCode.lowercase()
            }
        }
    }
}
