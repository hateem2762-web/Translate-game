package com.example.engine

import android.util.Log
import com.example.BuildConfig
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

private const val DEEPL_TAG = "DeepLTranslationProvider"

/**
 * High-accuracy DeepL API v2 translation provider.
 * Reads the API key securely from BuildConfig.DEEPL_API_KEY (injected via AI Studio Secrets / .env)
 * and automatically selects the Free (`:fx` -> api-free.deepl.com) or Pro (`api.deepl.com`) endpoint.
 * Supports native multi-line batching in a single HTTP request.
 */
class DeepLTranslationProvider(
    private val apiKeySupplier: () -> String = { BuildConfig.DEEPL_API_KEY }
) : TranslationProvider {

    override val mode: TranslationEngineMode = TranslationEngineMode.DEEPL

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
        return isValidKey(apiKeySupplier())
    }

    suspend fun testConnection(text: String, config: TranslationConfig): String {
        val results = translateBatchInternal(listOf(text), config, bypassCooldown = true)
        return results.firstOrNull().orEmpty()
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        if (text.isBlank() || config.sourceLangCode == config.targetLangCode) return text
        val results = translateBatchInternal(listOf(text), config, bypassCooldown = false)
        return results.firstOrNull().orEmpty()
    }

    override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (config.sourceLangCode == config.targetLangCode) return texts
        val nonBlankIndices = texts.indices.filter { texts[it].isNotBlank() }
        if (nonBlankIndices.isEmpty()) return texts

        val toTranslate = nonBlankIndices.map { texts[it] }
        val translatedSubset = translateBatchInternal(toTranslate, config, bypassCooldown = false)

        val output = texts.toMutableList()
        nonBlankIndices.forEachIndexed { subsetIdx, origIdx ->
            output[origIdx] = translatedSubset.getOrElse(subsetIdx) { texts[origIdx] }
        }
        return output
    }

    private suspend fun translateBatchInternal(
        texts: List<String>,
        config: TranslationConfig,
        bypassCooldown: Boolean
    ): List<String> {
        val rawKey = apiKeySupplier().trim()
        if (!isValidKey(rawKey)) {
            throw IllegalStateException(
                "DeepL API anahtarı tanımlanmamış. AI Studio Secrets panelinden DEEPL_API_KEY ekleyin."
            )
        }
        if (!bypassCooldown) {
            val remainingMs = quotaCooldownUntilMs.get() - System.currentTimeMillis()
            if (remainingMs > 0L) {
                val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                throw AiQuotaExceededException(
                    "DeepL kota/hız beklemesi aktif (${sec}s). Kotasız kullanım için Hızlı Yerel moda geçebilirsiniz."
                )
            }
        }

        val endpoint = resolveEndpoint(rawKey)
        val sourceCode = mapSourceLang(config.sourceLangCode)
        val targetCode = mapTargetLang(config.targetLangCode)

        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("text", JSONArray(texts))
                put("source_lang", sourceCode)
                put("target_lang", targetCode)
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "DeepL-Auth-Key $rawKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    handleHttpError(response.code, rawBody)
                }

                quotaCooldownUntilMs.set(0L)
                val json = JSONObject(rawBody)
                val translations = json.optJSONArray("translations")
                    ?: throw IOException("DeepL boş yanıt döndürdü.")

                val parsed = mutableListOf<String>()
                for (i in 0 until translations.length()) {
                    val item = translations.optJSONObject(i)
                    parsed.add(item?.optString("text")?.trim().orEmpty())
                }
                if (parsed.size != texts.size) {
                    throw IOException("DeepL yanıtındaki satır sayısı eşleşmedi.")
                }
                parsed
            }
        }
    }

    private fun handleHttpError(code: Int, rawBody: String): Nothing {
        val apiMessage = runCatching {
            JSONObject(rawBody).optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        Log.e(DEEPL_TAG, "DeepL HTTP $code: ${apiMessage ?: rawBody}")

        when (code) {
            403 -> throw IOException(
                "DeepL API anahtarı geçersiz veya yetkisiz (403). Secrets panelindeki DEEPL_API_KEY değerini kontrol edin."
            )
            456 -> {
                quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                throw AiQuotaExceededException(
                    "DeepL aylık karakter kotası doldu (456). Hızlı Yerel (ML Kit) moda geçebilirsiniz."
                )
            }
            429 -> {
                quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                throw AiQuotaExceededException(
                    "DeepL hız sınırı aşıldı (429). Kısa süre bekleyin veya Hızlı Yerel moda geçin."
                )
            }
            else -> throw IOException(
                if (apiMessage != null) "DeepL ($code): $apiMessage" else "DeepL API hatası ($code)"
            )
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        const val DEFAULT_PLACEHOLDER = "DEEPL_API_KEY_DEFAULT_VALUE"
        private const val ENDPOINT_FREE = "https://api-free.deepl.com/v2/translate"
        private const val ENDPOINT_PRO = "https://api.deepl.com/v2/translate"
        private const val QUOTA_COOLDOWN_MS = 20_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isValidKey(rawKey: String?): Boolean {
            val trimmed = rawKey?.trim().orEmpty()
            return trimmed.isNotBlank() && trimmed != DEFAULT_PLACEHOLDER
        }

        fun isApiKeyConfigured(): Boolean = isValidKey(BuildConfig.DEEPL_API_KEY)

        fun isFreeTierKey(rawKey: String = BuildConfig.DEEPL_API_KEY): Boolean {
            return rawKey.trim().endsWith(":fx", ignoreCase = true)
        }

        fun resolveEndpoint(rawKey: String): String {
            return if (isFreeTierKey(rawKey)) ENDPOINT_FREE else ENDPOINT_PRO
        }

        fun mapSourceLang(mlKitCode: String): String {
            return when (mlKitCode) {
                TranslateLanguage.ENGLISH -> "EN"
                TranslateLanguage.CHINESE -> "ZH"
                TranslateLanguage.TURKISH -> "TR"
                TranslateLanguage.GERMAN -> "DE"
                TranslateLanguage.FRENCH -> "FR"
                TranslateLanguage.SPANISH -> "ES"
                TranslateLanguage.ITALIAN -> "IT"
                TranslateLanguage.PORTUGUESE -> "PT"
                else -> SupportedLanguage.fromCode(mlKitCode).shortCode.uppercase()
            }
        }

        fun mapTargetLang(mlKitCode: String): String {
            return when (mlKitCode) {
                TranslateLanguage.ENGLISH -> "EN-US"
                TranslateLanguage.CHINESE -> "ZH"
                TranslateLanguage.TURKISH -> "TR"
                TranslateLanguage.GERMAN -> "DE"
                TranslateLanguage.FRENCH -> "FR"
                TranslateLanguage.SPANISH -> "ES"
                TranslateLanguage.ITALIAN -> "IT"
                TranslateLanguage.PORTUGUESE -> "PT-PT"
                else -> SupportedLanguage.fromCode(mlKitCode).shortCode.uppercase()
            }
        }
    }
}
