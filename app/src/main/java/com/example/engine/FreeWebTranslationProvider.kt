package com.example.engine

import android.util.Log
import com.example.model.SupportedLanguage
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "FreeWebProvider"

/**
 * Direct HTTP web translation provider for Google Translate (unofficial gtx NMT endpoint),
 * Bing, and Baidu web modes.
 *
 * IMPORTANT: Never falls back silently to offline ML Kit or AI. Any HTTP, network, or
 * parsing failure raises an explicit [IOException] with the real engine error so the user
 * immediately sees the root cause instead of broken local translations.
 */
class FreeWebTranslationProvider : TranslationProvider {

    override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean = true

    override suspend fun translate(text: String, config: TranslationConfig): String {
        if (text.isBlank()) return text
        if (config.sourceLangCode == config.targetLangCode &&
            config.engineMode != TranslationEngineMode.GOOGLE_FREE
        ) {
            return text
        }

        val engine = when (config.engineMode) {
            TranslationEngineMode.BING_FREE -> WebEngine.BING
            TranslationEngineMode.BAIDU_FREE -> WebEngine.BAIDU
            else -> WebEngine.GOOGLE
        }

        return withContext(Dispatchers.IO) {
            translateViaWebStrict(text, config, engine)
        }
    }

    private fun translateViaWebStrict(
        text: String,
        config: TranslationConfig,
        engine: WebEngine
    ): String {
        val url = when (engine) {
            WebEngine.GOOGLE -> buildGoogleTranslateUrl(text, config.targetLangCode)
            WebEngine.BING, WebEngine.BAIDU -> {
                val sl = mapLangCode(config.sourceLangCode, engine)
                val tl = mapLangCode(config.targetLangCode, engine)
                val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
                "https://api.mymemory.translated.net/get?q=$encoded&langpair=$sl|$tl"
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Charset", "UTF-8")
            .get()

        if (engine == WebEngine.GOOGLE) {
            requestBuilder.addHeader("User-Agent", MOBILE_CHROME_USER_AGENT)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                val body = String(bodyBytes, Charsets.UTF_8)
                if (!response.isSuccessful) {
                    Log.e(TAG, "${engine.displayName} HTTP ${response.code}: $body")
                    throw IOException("${engine.displayName} Hatası: ${response.code}")
                }
                return when (engine) {
                    WebEngine.GOOGLE -> parseGoogleResponse(body)
                    WebEngine.BING, WebEngine.BAIDU -> parseMyMemoryResponse(body, engine)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            if (msg.startsWith("${engine.displayName} Hatası:") ||
                msg.startsWith("${engine.displayName} Bağlantı Hatası")
            ) {
                throw e
            }
            Log.e(TAG, "${engine.displayName} connection failed", e)
            throw IOException(
                "${engine.displayName} Bağlantı Hatası: ${e.localizedMessage ?: "Sunucuya erişilemedi"}",
                e
            )
        } catch (e: Exception) {
            Log.e(TAG, "${engine.displayName} parse/unexpected failure", e)
            throw IOException(
                "${engine.displayName} Hatası: ${e.localizedMessage ?: "Beklenmeyen yanıt"}",
                e
            )
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    internal enum class WebEngine(val displayName: String) {
        GOOGLE("Google"),
        BING("Bing"),
        BAIDU("Baidu")
    }

    companion object {
        const val MOBILE_CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun buildGoogleTranslateUrl(text: String, targetLangCode: String): String {
            val tl = mapGoogleTargetLang(targetLangCode)
            val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
            return "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=$encoded"
        }

        fun parseGoogleResponse(rawJson: String): String {
            val jsonArray = try {
                JSONArray(rawJson)
            } catch (e: Exception) {
                throw IOException("Google Hatası: Geçersiz JSON yanıtı", e)
            }
            val sentences = jsonArray.optJSONArray(0)
                ?: throw IOException("Google Hatası: Beklenmeyen yanıt biçimi")
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i) ?: continue
                if (!item.isNull(0)) {
                    val part = item.optString(0, "")
                    if (part.isNotEmpty()) {
                        sb.append(part)
                    }
                }
            }
            val translated = naturalizeTranslation(sb.toString())
            if (translated.isBlank()) {
                throw IOException("Google Hatası: Boş çeviri yanıtı")
            }
            return translated
        }

        internal fun parseMyMemoryResponse(rawJson: String, engine: WebEngine): String {
            val json = try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                throw IOException("${engine.displayName} Hatası: Geçersiz JSON yanıtı", e)
            }
            val status = json.optInt("responseStatus", 200)
            if (status != 200) {
                val detail = json.optString("responseDetails").takeIf { it.isNotBlank() }
                throw IOException(
                    if (detail != null) "${engine.displayName} Hatası: $status ($detail)"
                    else "${engine.displayName} Hatası: $status"
                )
            }
            val responseData = json.optJSONObject("responseData")
            val translated = responseData?.optString("translatedText").orEmpty().trim()
            if (translated.isBlank() || translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
                throw IOException("${engine.displayName} Hatası: Kota veya boş yanıt")
            }
            return naturalizeTranslation(translated)
        }

        private fun naturalizeTranslation(raw: String): String {
            if (raw.isBlank()) return ""
            var text = raw.replace(Regex("[ \\t\\r]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex(" +([,.!?;:])"), "$1")
                .trim()
            if (text.isNotEmpty()) {
                val firstChar = text.first()
                if (firstChar.isLowerCase() && firstChar.isLetter()) {
                    text = text.replaceFirstChar { it.uppercaseChar() }
                }
            }
            return text
        }

        private fun mapGoogleTargetLang(mlKitCode: String): String {
            return when (mlKitCode) {
                SupportedLanguage.CHINESE.mlKitCode -> "zh-CN"
                else -> mlKitCode.take(2).lowercase().ifBlank { "tr" }
            }
        }

        private fun mapLangCode(mlKitCode: String, engine: WebEngine): String {
            return when (engine) {
                WebEngine.GOOGLE -> mapGoogleTargetLang(mlKitCode)
                WebEngine.BING -> when (mlKitCode) {
                    SupportedLanguage.CHINESE.mlKitCode -> "zh-Hans"
                    else -> mlKitCode.take(2).lowercase()
                }
                WebEngine.BAIDU -> when (mlKitCode) {
                    SupportedLanguage.CHINESE.mlKitCode -> "zh"
                    else -> mlKitCode.take(2).lowercase()
                }
            }
        }
    }
}
