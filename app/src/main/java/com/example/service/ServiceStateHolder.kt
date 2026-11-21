package com.example.service

import com.example.model.ServiceStatus
import com.example.model.SubtitleEntry
import com.example.model.TranslatorTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-local reactive bridge between ScreenTranslatorService and MainActivity UI.
 */
object ServiceStateHolder {
    private val _telemetry = MutableStateFlow(TranslatorTelemetry())
    val telemetry: StateFlow<TranslatorTelemetry> = _telemetry.asStateFlow()

    fun update(transform: (TranslatorTelemetry) -> TranslatorTelemetry) {
        _telemetry.update(transform)
    }

    fun pushCompletedEntry(entry: SubtitleEntry) {
        _telemetry.update { current ->
            val deduped = if (current.recentEntries.firstOrNull()?.sourceText == entry.sourceText &&
                current.recentEntries.firstOrNull()?.translatedText == entry.translatedText
            ) {
                current.recentEntries
            } else {
                (listOf(entry) + current.recentEntries).take(15)
            }
            current.copy(recentEntries = deduped)
        }
    }

    fun markStopped() {
        _telemetry.update { current ->
            current.copy(
                status = ServiceStatus.IDLE,
                isStabilizing = false,
                isRoiLocked = false,
                errorMessage = null
            )
        }
    }
}
