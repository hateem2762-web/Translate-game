package com.example.engine

import android.util.Log
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
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
 * LibreTranslate provider (https://libretranslate.com).
 *
 * Works with any LibreTranslate server: the public instance (needs an API key), or a
 * self-hosted instance (no key needed, can be plain `http://` on the LAN).
 * Several lines are sent in a single request by passing `q` as a JSON array.
 */
class LibreTranslateProvider : TranslationProvider {

    override val mode: TranslationEngineMode = TranslationEngineMode.LIBRETRANSLATE

    private val quotaCooldownUntilMs = AtomicLong(0L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean {
        return config.libreEndpointUrl.isNotBlank()
    }

    suspend fun testConnection(text: String, config: TranslationConfig): String {
        return translateBatchInternal(listOf(text), config, bypassCooldown = true).firstOrNull().orEmpty()
    }

    override suspend fun translate(text: String, config: TranslationConfig): String {
        if (text.isBlank() || config.sourceLangCode == config.targetLangCode) return text
        return translateBatchInternal(listOf(text), config, bypassCooldown = false).firstOrNull().orEmpty()
    }

    override suspend fun translateBatch(texts: List<String>, config: TranslationConfig): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (config.sourceLangCode == config.targetLangCode) return texts
        val nonBlank = texts.indices.filter { texts[it].isNotBlank() }
        if (nonBlank.isEmpty()) return texts

        val translated = translateBatchInternal(nonBlank.map { texts[it] }, config, bypassCooldown = false)
        val output = texts.toMutableList()
        nonBlank.forEachIndexed { subsetIdx, origIdx ->
            output[origIdx] = translated.getOrElse(subsetIdx) { texts[origIdx] }
        }
        return output
    }

    private suspend fun translateBatchInternal(
        texts: List<String>,
        config: TranslationConfig,
        bypassCooldown: Boolean
    ): List<String> {
        val endpoint = resolveEndpoint(config.libreEndpointUrl)
            ?: throw IllegalStateException("LibreTranslate sunucu adresi girilmemiş veya geçersiz.")

        if (!bypassCooldown) {
            val remainingMs = quotaCooldownUntilMs.get() - System.currentTimeMillis()
            if (remainingMs > 0L) {
                val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                throw AiQuotaExceededException(
                    "LibreTranslate hız/kota beklemesi aktif (${sec}s). Hızlı Yerel moda geçebilirsiniz."
                )
            }
        }

        val apiKey = config.libreApiKey.trim()
        return withContext(Dispatchers.IO) {
            try {
                request(endpoint, texts, config.sourceLangCode, config.targetLangCode, apiKey, chineseVariant = null)
            } catch (e: LibreLanguageException) {
                // Newer LibreTranslate builds name Chinese "zh-Hans" instead of "zh".
                if (config.sourceLangCode == "zh" || config.targetLangCode == "zh") {
                    request(endpoint, texts, config.sourceLangCode, config.targetLangCode, apiKey, chineseVariant = "zh-Hans")
                } else {
                    throw IOException(e.message)
                }
            }
        }
    }

    private class LibreLanguageException(message: String) : IOException(message)

    private fun request(
        endpoint: String,
        texts: List<String>,
        sourceCode: String,
        targetCode: String,
        apiKey: String,
        chineseVariant: String?
    ): List<String> {
        fun map(code: String) = if (code == "zh" && chineseVariant != null) chineseVariant else code

        val payload = JSONObject().apply {
            put("q", JSONArray(texts))
            put("source", map(sourceCode))
            put("target", map(targetCode))
            put("format", "text")
            if (apiKey.isNotEmpty()) put("api_key", apiKey)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) handleHttpError(response.code, rawBody)

            quotaCooldownUntilMs.set(0L)
            val json = JSONObject(rawBody)
            val translatedNode = json.opt("translatedText")
                ?: throw IOException("LibreTranslate boş yanıt döndürdü.")

            val parsed = when (translatedNode) {
                is JSONArray -> (0 until translatedNode.length()).map { translatedNode.optString(it).trim() }
                else -> listOf(translatedNode.toString().trim())
            }
            if (parsed.size != texts.size) {
                throw IOException("LibreTranslate yanıtındaki satır sayısı eşleşmedi.")
            }
            return parsed
        }
    }

    private fun handleHttpError(code: Int, rawBody: String): Nothing {
        val apiMessage = runCatching {
            JSONObject(rawBody).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        Log.e(LIBRE_TAG, "LibreTranslate HTTP $code: ${apiMessage ?: rawBody}")

        when (code) {
            400 -> {
                if (apiMessage?.contains("not supported", ignoreCase = true) == true ||
                    apiMessage?.contains("language", ignoreCase = true) == true
                ) {
                    throw LibreLanguageException("LibreTranslate dil hatası: $apiMessage")
                }
                throw IOException("LibreTranslate (400): ${apiMessage ?: "Geçersiz istek."}")
            }
            401, 403 -> throw IOException(
                "LibreTranslate API anahtarı gerekli veya geçersiz ($code). " +
                    (apiMessage ?: "Anahtar girin ya da kendi sunucunuzu kullanın.")
            )
            429 -> {
                quotaCooldownUntilMs.set(System.currentTimeMillis() + QUOTA_COOLDOWN_MS)
                throw AiQuotaExceededException(
                    "LibreTranslate hız sınırı aşıldı (429). Kısa süre bekleyin veya kendi sunucunuzu kullanın."
                )
            }
            else -> throw IOException(
                if (apiMessage != null) "LibreTranslate ($code): $apiMessage" else "LibreTranslate hatası ($code)"
            )
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val QUOTA_COOLDOWN_MS = 15_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Turns whatever the user typed (`libretranslate.com`, `http://192.168.1.5:5000/`,
         * `https://host/translate`) into the full `.../translate` URL, or null if empty.
         */
        fun resolveEndpoint(raw: String): String? {
            var url = raw.trim()
            if (url.isEmpty()) return null
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "https://$url"
            }
            url = url.trimEnd('/')
            if (!url.endsWith("/translate", ignoreCase = true)) url += "/translate"
            return url
        }
    }
}
