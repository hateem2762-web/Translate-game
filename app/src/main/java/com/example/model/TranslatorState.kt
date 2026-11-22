package com.example.model

import android.graphics.Bitmap

enum class ServiceStatus {
    IDLE,
    RUNNING,
    PAUSED,
    ERROR
}

enum class ModelDownloadState {
    UNKNOWN,
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    ERROR
}

enum class OverlayHubStage {
    BUBBLE_IDLE,
    ACTIVE_MODE
}

data class InPlaceBlock(
    val boundingBox: RoiRect,
    val sourceText: String,
    val translatedText: String,
    val blurredPatch: Bitmap? = null,
    val sampledBgColor: Int = 0xDD090E17.toInt()
)

data class SubtitleEntry(
    val sourceText: String,
    val translatedText: String,
    val fromCache: Boolean,
    val latencyMs: Long,
    val engineMode: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TranslatorTelemetry(
    val status: ServiceStatus = ServiceStatus.IDLE,
    val displayMode: DisplayMode = DisplayMode.CLASSIC_BOX,
    val engineMode: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL,
    val fontSizeSp: Float = 18f,
    val subtitleTextColorArgb: Int = SubtitleColorPreset.PURE_WHITE.argb,
    val inPlacePaddingXdp: Float = 10f,
    val inPlacePaddingYdp: Float = 5f,
    val inPlaceCornerRadiusDp: Float = 12f,
    val inPlaceOffsetXdp: Float = 0f,
    val inPlaceOffsetYdp: Float = 0f,
    val classicBoxWidthDp: Int = 340,
    val classicBoxHeightDp: Int = 136,
    val classicBoxX: Int = -1,
    val classicBoxY: Int = -1,
    val classicBoxOpacity: Float = 0.86f,
    val classicTextFitMode: ClassicTextFitMode = ClassicTextFitMode.WRAP_AND_EXPAND,
    val bubbleBehavior: FloatingBubbleBehavior = FloatingBubbleBehavior.HIDE_WHEN_ACTIVE,
    val bubbleTheme: FloatingBubbleTheme = FloatingBubbleTheme.NEON,
    val overlayHubStage: OverlayHubStage = OverlayHubStage.BUBBLE_IDLE,
    val currentSourceText: String = "",
    val currentTranslatedText: String = "",
    val inPlaceBlocks: List<InPlaceBlock> = emptyList(),
    val isStabilizing: Boolean = false,
    val isAiRefining: Boolean = false,
    val isRoiLocked: Boolean = false,
    val lastFromCache: Boolean = false,
    val lastLatencyMs: Long = 0L,
    val framesProcessed: Long = 0L,
    val framesSkippedSameText: Long = 0L,
    val cacheHits: Long = 0L,
    val errorMessage: String? = null,
    val recentEntries: List<SubtitleEntry> = emptyList(),
    val aiPreset: AiProviderPreset = AiProviderPreset.GROQ,
    val aiModelName: String = AiProviderPreset.GROQ.defaultModel,
    val configuredPresets: Set<AiProviderPreset> = emptySet(),
    val availableModelsByPreset: Map<AiProviderPreset, List<AiModelOption>> = emptyMap()
)
