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
    val fontSize: SubtitleFontSize = SubtitleFontSize.NORMAL,
    val currentSourceText: String = "",
    val currentTranslatedText: String = "",
    val inPlaceBlocks: List<InPlaceBlock> = emptyList(),
    val isStabilizing: Boolean = false,
    val isRoiLocked: Boolean = false,
    val lastFromCache: Boolean = false,
    val lastLatencyMs: Long = 0L,
    val framesProcessed: Long = 0L,
    val framesSkippedSameText: Long = 0L,
    val cacheHits: Long = 0L,
    val errorMessage: String? = null,
    val recentEntries: List<SubtitleEntry> = emptyList()
)
