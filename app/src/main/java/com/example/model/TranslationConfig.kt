package com.example.model

import com.google.mlkit.nl.translate.TranslateLanguage

enum class DisplayMode(val shortLabelTr: String) {
    CLASSIC_BOX("Kutu"),
    IN_PLACE_BLUR("Doğal")
}

enum class FloatingBubbleBehavior(
    val labelTr: String,
    val descriptionTr: String
) {
    HIDE_WHEN_ACTIVE(
        labelTr = "Balonu Gizle (X ile Dön)",
        descriptionTr = "Mod seçilince balon kaybolur; aktif moddaki X tuşuna basınca balon geri gelir."
    ),
    DOCK_HALF_CIRCLE(
        labelTr = "Yarım Yuvarlak (<) Dock",
        descriptionTr = "Mod seçilince balon sağ alt kenara küçük bir < yarım yuvarlağı olarak yapışır."
    )
}

enum class FloatingBubbleTheme(
    val labelTr: String,
    val subtitleTr: String
) {
    KWAI("Kwai", "Canlı kontrast & enerjik rozet"),
    SADE("Sade", "Mat, temiz & minimalist"),
    NEON("Neon", "Koyu zemin & parlak glow"),
    KEDI("Kedi", "Kulaklı, bıyıklı & patili"),
    KAPLUMBAGA("Kaplumbağa", "Kabuk deseni & zeytin yeşili")
}

enum class ClassicTextFitMode(val labelTr: String, val descriptionTr: String) {
    WRAP_AND_EXPAND(
        labelTr = "Satır Kaydır & Genişlet",
        descriptionTr = "Metin kutu genişliğini aşarsa alt satıra geçer ve kutu yüksekliği kaydırmaya gerek kalmadan otomatik esner."
    ),
    AUTO_FIT_SHRINK(
        labelTr = "Otomatik Küçült",
        descriptionTr = "Metin mevcut sınırları aşarsa alt satıra bölmek yerine yazı boyutunu kutuya sığana kadar küçültür (min 11sp)."
    )
}

enum class SubtitleColorPreset(val labelTr: String, val argb: Int, val hex: String) {
    PURE_WHITE("Saf Beyaz", 0xFFFFFFFF.toInt(), "#FFFFFF"),
    CINEMA_GOLD("Altın Sarısı", 0xFFFFD54F.toInt(), "#FFD54F"),
    NEON_CYAN("Neon Camgöbeği", 0xFF22D3EE.toInt(), "#22D3EE"),
    LIGHT_GREEN("Açık Yeşil", 0xFF6EE7B7.toInt(), "#6EE7B7"),
    PASTEL_PINK("Pastel Pembe", 0xFFF9A8D4.toInt(), "#F9A8D4");

    companion object {
        fun matchingOrNull(argb: Int): SubtitleColorPreset? {
            return entries.firstOrNull { (it.argb and 0x00FFFFFF) == (argb and 0x00FFFFFF) }
        }

        fun parseHexColorOrNull(raw: String): Int? {
            val cleaned = raw.trim().removePrefix("#")
            if (cleaned.length != 6 && cleaned.length != 8) return null
            val parsed = cleaned.toLongOrNull(16) ?: return null
            val rgb = (parsed and 0x00FFFFFFL).toInt()
            return 0xFF000000.toInt() or rgb
        }

        fun formatHex(argb: Int): String {
            return String.format("#%06X", argb and 0x00FFFFFF)
        }
    }
}

enum class TranslationEngineMode(
    val shortLabelTr: String,
    val badgeLabelTr: String,
    val fullTitleTr: String,
    val accentHex: String
) {
    GOOGLE_FREE("Google", "Google Web", "Google Çeviri", "#34D399"),
    BING_FREE("Bing", "Bing Web", "Bing Çeviri", "#38BDF8"),
    BAIDU_FREE("Baidu", "Baidu Web", "Baidu Çeviri", "#818CF8"),
    OFFLINE_LOCAL("Yerel", "Yerel ML Kiti", "Çevrimdışı ML Kit", "#22D3EE"),
    SMART_AI("AI", "Akıllı AI", "Yapay Zeka (AI)", "#F59E0B"),
    HYBRID("Hibrit", "Hibrit", "Hibrit (Yerel+AI)", "#A78BFA");

    fun usesAiModel(): Boolean = this == SMART_AI || this == HYBRID

    fun isWebEngine(): Boolean = this == GOOGLE_FREE || this == BING_FREE || this == BAIDU_FREE

    fun formatSourceTag(aiPreset: AiProviderPreset? = null): String {
        return when (this) {
            SMART_AI -> if (aiPreset != null) "[AI • ${aiPreset.shortLabelTr}]" else "[Akıllı AI]"
            else -> "[$badgeLabelTr]"
        }
    }
}

data class AiModelOption(
    val id: String,
    val label: String = id
)

enum class AiProviderPreset(
    val displayName: String,
    val shortLabelTr: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresUserApiKey: Boolean,
    val storageKeySuffix: String,
    val curatedModels: List<AiModelOption>
) {
    GROQ(
        displayName = "Groq (Ücretsiz & Ultra Hızlı)",
        shortLabelTr = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-20b",
        requiresUserApiKey = true,
        storageKeySuffix = "groq",
        curatedModels = listOf(
            AiModelOption("openai/gpt-oss-20b", "openai/gpt-oss-20b (Varsayılan • Ultra Hızlı)"),
            AiModelOption("qwen/qwen3.8-27b", "qwen/qwen3.8-27b (En İyi Çince & Oyun Çevirisi)"),
            AiModelOption("openai/gpt-oss-120b", "openai/gpt-oss-120b (Yüksek Kalite)")
        )
    ),
    CEREBRAS(
        displayName = "Cerebras (Ücretsiz & Ultra Hızlı)",
        shortLabelTr = "Cerebras",
        defaultBaseUrl = "https://api.cerebras.ai/v1/chat/completions",
        defaultModel = "gpt-oss-120b",
        requiresUserApiKey = true,
        storageKeySuffix = "cerebras",
        curatedModels = listOf(
            AiModelOption("gpt-oss-120b", "gpt-oss-120b (Varsayılan • Ultra Hızlı)"),
            AiModelOption("qwen-3.8-27b", "qwen-3.8-27b (Çince & Oyun Çevirisi)")
        )
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortLabelTr = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "openai/gpt-oss-20b:free",
        requiresUserApiKey = true,
        storageKeySuffix = "openrouter",
        curatedModels = listOf(
            AiModelOption("openai/gpt-oss-20b:free", "openai/gpt-oss-20b:free (Varsayılan • Ücretsiz)"),
            AiModelOption("qwen/qwen3.8-27b:free", "qwen/qwen3.8-27b:free (Çince & Oyun Çevirisi)")
        )
    ),
    GEMINI(
        displayName = "Google Gemini (Düşünme=0, Min Gecikme)",
        shortLabelTr = "Gemini",
        defaultBaseUrl = "firebase-ai-logic",
        defaultModel = "gemini-2.5-flash-lite",
        requiresUserApiKey = false,
        storageKeySuffix = "gemini",
        curatedModels = listOf(
            AiModelOption("gemini-2.5-flash-lite", "gemini-2.5-flash-lite (Varsayılan • En Hızlı)"),
            AiModelOption("gemini-2.5-flash", "gemini-2.5-flash (Yüksek Kalite)")
        )
    ),
    OPENAI_COMPATIBLE(
        displayName = "Özel OpenAI Uyumlu Uç Nokta",
        shortLabelTr = "Özel API",
        defaultBaseUrl = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
        requiresUserApiKey = true,
        storageKeySuffix = "custom",
        curatedModels = listOf(
            AiModelOption("gpt-4o-mini", "gpt-4o-mini (Varsayılan)"),
            AiModelOption("gpt-4.1-mini", "gpt-4.1-mini")
        )
    );

    companion object {
        val DEPRECATED_MODEL_IDS = setOf(
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile",
            "llama-3.3-70b",
            "llama3.1-8b",
            "qwen-qwq-32b",
            "qwen-3-32b",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite"
        )

        fun fromPersistedName(raw: String?): AiProviderPreset {
            return when (raw) {
                "GEMINI_FLASH", "GEMINI_LITE", "GEMINI" -> GEMINI
                null -> GEMINI
                else -> runCatching { valueOf(raw) }.getOrDefault(GEMINI)
            }
        }

        fun sanitizeModelId(preset: AiProviderPreset, rawModel: String?): String {
            val trimmed = rawModel?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed in DEPRECATED_MODEL_IDS) {
                return preset.defaultModel
            }
            return trimmed
        }
    }
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
    val debounceMs: Long = 350L,
    val scanIntervalMs: Long = 350L,
    val overlayOpacity: Float = 0.86f,
    val showOriginalText: Boolean = false,
    val displayMode: DisplayMode = DisplayMode.CLASSIC_BOX,
    val engineMode: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL,
    val fontSizeSp: Float = 18f,
    val subtitleTextColorArgb: Int = SubtitleColorPreset.PURE_WHITE.argb,
    // Fine-tuned In-Place Blur geometry (in dp)
    val inPlacePaddingXdp: Float = 10f,
    val inPlacePaddingYdp: Float = 5f,
    val inPlaceCornerRadiusDp: Float = 12f,
    val inPlaceOffsetXdp: Float = 0f,
    val inPlaceOffsetYdp: Float = 0f,
    // Resizable & persisted Classic Box dimensions, text fitting behavior, and background-only opacity (0f..1f)
    val classicBoxWidthDp: Int = 340,
    val classicBoxHeightDp: Int = 136,
    val classicBoxX: Int = -1,
    val classicBoxY: Int = -1,
    val classicBoxOpacity: Float = 0.86f,
    val classicTextFitMode: ClassicTextFitMode = ClassicTextFitMode.WRAP_AND_EXPAND,
    val bubbleBehavior: FloatingBubbleBehavior = FloatingBubbleBehavior.HIDE_WHEN_ACTIVE,
    val bubbleTheme: FloatingBubbleTheme = FloatingBubbleTheme.NEON,
    val aiPreset: AiProviderPreset = AiProviderPreset.GROQ,
    val aiApiKey: String = "",
    val aiApiKeys: Map<AiProviderPreset, String> = emptyMap(),
    val aiEndpointUrl: String = aiPreset.defaultBaseUrl,
    val aiModelName: String = aiPreset.defaultModel,
    val aiModelsByPreset: Map<AiProviderPreset, String> = emptyMap(),
    val aiEndpointsByPreset: Map<AiProviderPreset, String> = emptyMap()
) {
    fun apiKeyFor(preset: AiProviderPreset): String {
        return aiApiKeys[preset] ?: if (preset == aiPreset) aiApiKey else ""
    }

    fun modelFor(preset: AiProviderPreset): String {
        val raw = aiModelsByPreset[preset] ?: if (preset == aiPreset) aiModelName else preset.defaultModel
        return AiProviderPreset.sanitizeModelId(preset, raw)
    }

    fun endpointFor(preset: AiProviderPreset): String {
        return aiEndpointsByPreset[preset]?.takeIf { it.isNotBlank() }
            ?: if (preset == aiPreset && aiEndpointUrl.isNotBlank()) aiEndpointUrl else preset.defaultBaseUrl
    }
}
