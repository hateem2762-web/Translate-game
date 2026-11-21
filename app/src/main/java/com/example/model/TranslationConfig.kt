package com.example.model

import com.google.mlkit.nl.translate.TranslateLanguage

enum class DisplayMode(val shortLabelTr: String) {
    CLASSIC_BOX("Kutu"),
    IN_PLACE_BLUR("Doğal")
}

enum class TranslationEngineMode(val shortLabelTr: String) {
    OFFLINE_LOCAL("Yerel"),
    SMART_AI("AI"),
    DEEPL("DeepL")
}

enum class AiProviderPreset(
    val displayName: String,
    val shortLabelTr: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresUserApiKey: Boolean
) {
    GEMINI_FLASH(
        displayName = "Gemini 3.8 Flash (Anahtarsız)",
        shortLabelTr = "Flash",
        defaultBaseUrl = "firebase-ai-logic",
        defaultModel = "gemini-3.8-flash",
        requiresUserApiKey = false
    ),
    GEMINI_LITE(
        displayName = "Gemini 3.5 Flash-Lite (Kota Dostu)",
        shortLabelTr = "Flash-Lite",
        defaultBaseUrl = "firebase-ai-logic",
        defaultModel = "gemini-3.5-flash-lite",
        requiresUserApiKey = false
    ),
    OPENAI_COMPATIBLE(
        displayName = "OpenAI-Uyumlu (Özel Anahtar)",
        shortLabelTr = "Özel API",
        defaultBaseUrl = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.1-8b-instant",
        requiresUserApiKey = true
    )
}

enum class SupportedLanguage(
    val mlKitCode: String,
    val displayNameTr: String,
    val shortLabelTr: String,
    val shortCode: String,
    val englishName: String
) {
    ENGLISH(TranslateLanguage.ENGLISH, "İngilizce", "İngilizce", "EN", "English"),
    CHINESE(
        TranslateLanguage.CHINESE,
        "Çince (Basitleştirilmiş / Geleneksel)",
        "Çince",
        "ZH",
        "Chinese"
    ),
    TURKISH(TranslateLanguage.TURKISH, "Türkçe", "Türkçe", "TR", "Turkish"),
    GERMAN(TranslateLanguage.GERMAN, "Almanca", "Almanca", "DE", "German"),
    FRENCH(TranslateLanguage.FRENCH, "Fransızca", "Fransızca", "FR", "French"),
    SPANISH(TranslateLanguage.SPANISH, "İspanyolca", "İspanyolca", "ES", "Spanish"),
    ITALIAN(TranslateLanguage.ITALIAN, "İtalyanca", "İtalyanca", "IT", "Italian"),
    PORTUGUESE(TranslateLanguage.PORTUGUESE, "Portekizce", "Portekizce", "PT", "Portuguese");

    companion object {
        fun fromCode(code: String): SupportedLanguage {
            return entries.firstOrNull { it.mlKitCode == code } ?: ENGLISH
        }
    }
}

data class TranslationConfig(
    val sourceLangCode: String = TranslateLanguage.ENGLISH,
    val targetLangCode: String = TranslateLanguage.TURKISH,
    val debounceMs: Long = 450L,
    val scanIntervalMs: Long = 300L,
    val overlayOpacity: Float = 0.86f,
    val showOriginalText: Boolean = false,
    val displayMode: DisplayMode = DisplayMode.CLASSIC_BOX,
    val engineMode: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL,
    val fontSizeSp: Float = 18f,
    // Fine-tuned In-Place Blur geometry (in dp)
    val inPlacePaddingXdp: Float = 10f,
    val inPlacePaddingYdp: Float = 5f,
    val inPlaceCornerRadiusDp: Float = 12f,
    val inPlaceOffsetXdp: Float = 0f,
    val inPlaceOffsetYdp: Float = 0f,
    // Resizable & persisted Classic Box dimensions and background-only opacity (0f..1f)
    val classicBoxWidthDp: Int = 340,
    val classicBoxHeightDp: Int = 136,
    val classicBoxX: Int = -1,
    val classicBoxY: Int = -1,
    val classicBoxOpacity: Float = 0.86f,
    val aiPreset: AiProviderPreset = AiProviderPreset.GEMINI_FLASH,
    val aiApiKey: String = "",
    val aiEndpointUrl: String = AiProviderPreset.GEMINI_FLASH.defaultBaseUrl,
    val aiModelName: String = AiProviderPreset.GEMINI_FLASH.defaultModel
)
