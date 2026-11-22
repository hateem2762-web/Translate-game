package com.example.engine

import android.util.Log
import com.example.model.SupportedLanguage
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "FreeWebProvider"

class FreeWebTranslationProvider(
    private val offlineFallback: TranslationProvider,
    private val aiFallback: TranslationProvider
) : TranslationProvider {

    override val mode: TranslationEngineMode = TranslationEngineMode.GOOGLE_FREE

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isReady(config: TranslationConfig): Boolean = true

    override suspend fun translate(text: String, config: TranslationConfig): String {
        if (text.isBlank() || config.sourceLangCode == config.targetLangCode) return text

        val requestedMode = config.engineMode
        val primaryEngine = when (requestedMode) {
            TranslationEngineMode.BING_FREE -> WebEngine.BING
            TranslationEngineMode.BAIDU_FREE -> WebEngine.BAIDU
            else -> WebEngine.GOOGLE
        }

        // Automatic Fallback Cascade: Primary Web -> Other Web Engines -> AI -> Local ML Kit
        val cascade = mutableListOf<WebEngine>().apply {
            add(primaryEngine)
            WebEngine.entries.forEach { if (it != primaryEngine) add(it) }
        }

        for (engine in cascade) {
            try {
                val result = translateViaWeb(text, config, engine)
                if (result.isNotBlank()) {
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} web translation failed: ${e.localizedMessage}, trying next fallback...")
            }
        }

        // Fallback to Smart AI if available
        try {
            val aiResult = aiFallback.translate(text, config)
            if (aiResult.isNotBlank()) return aiResult
        } catch (e: Exception) {
            Log.w(TAG, "AI fallback failed: ${e.localizedMessage}")
        }

        // Ultimate Fallback to Offline ML Kit
        return runCatching {
            offlineFallback.translate(text, config)
        }.getOrDefault(text)
    }

    private fun translateViaWeb(text: String, config: TranslationConfig, engine: WebEngine): String {
        val sl = mapLangCode(config.sourceLangCode, engine)
        val tl = mapLangCode(config.targetLangCode, engine)
        val encoded = URLEncoder.encode(text, "UTF-8")

        val url = when (engine) {
            WebEngine.GOOGLE ->
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&hl=tr&dt=t&q=$encoded"
            WebEngine.BING, WebEngine.BAIDU ->
                "https://api.mymemory.translated.net/get?q=$encoded&langpair=$sl|$tl"
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (engine == WebEngine.GOOGLE) {
            requestBuilder.addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
            )
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return when (engine) {
                WebEngine.GOOGLE -> parseGoogleResponse(body)
                WebEngine.BING, WebEngine.BAIDU -> parseMyMemoryResponse(body)
            }
        }
    }

    private fun parseGoogleResponse(rawJson: String): String {
        val jsonArray = JSONArray(rawJson)
        val sentences = jsonArray.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            val item = sentences.optJSONArray(i)
            if (item != null) {
                val part = item.optString(0)
                if (part.isNotBlank()) {
                    sb.append(part)
                }
            }
        }
        return naturalizeTranslation(sb.toString())
    }

    private fun parseMyMemoryResponse(rawJson: String): String {
        val json = JSONObject(rawJson)
        val responseData = json.optJSONObject("responseData")
        val translated = responseData?.optString("translatedText").orEmpty().trim()
        if (translated.isBlank() || translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
            throw IOException("Geçersiz veya boş web çeviri yanıtı.")
        }
        return naturalizeTranslation(translated)
    }

    private fun naturalizeTranslation(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,._!?;:])"), "$1")
            .trim()
        if (text.isNotEmpty()) {
            val firstChar = text.first()
            if (firstChar.isLowerCase() && firstChar.isLetter()) {
                text = text.replaceFirstChar { it.uppercaseChar() }
            }
        }
        return text
    }

    private fun mapLangCode(mlKitCode: String, engine: WebEngine): String {
        return when (engine) {
            WebEngine.GOOGLE -> when (mlKitCode) {
                SupportedLanguage.CHINESE.mlKitCode -> "zh-CN"
                else -> mlKitCode.take(2).lowercase()
            }
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

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private enum class WebEngine {
        GOOGLE, BING, BAIDU
    }
}
