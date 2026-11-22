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
 * Holds chronological session dialogue history (up to 30 entries) with consecutive deduplication.
 */
object ServiceStateHolder {
    private const val MAX_HISTORY_ENTRIES = 30
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val _telemetry = MutableStateFlow(TranslatorTelemetry())
    val telemetry: StateFlow<TranslatorTelemetry> = _telemetry.asStateFlow()

    fun update(transform: (TranslatorTelemetry) -> TranslatorTelemetry) {
        _telemetry.update(transform)
    }

    fun pushCompletedEntry(entry: SubtitleEntry) {
        val cleanSource = normalizeKey(entry.sourceText)
        val cleanTranslated = normalizeKey(entry.translatedText)
        if (cleanTranslated.isBlank()) return

        _telemetry.update { current ->
            val last = current.recentEntries.lastOrNull()
            if (last != null) {
                val sameSource = normalizeKey(last.sourceText) == cleanSource
                val sameTranslation = normalizeKey(last.translatedText) == cleanTranslated
                if (sameSource && !sameTranslation) {
                    // Hybrid refinement: replace the local draft entry smoothly with the refined AI translation
                    val replaced = current.recentEntries.dropLast(1) + entry
                    return@update current.copy(recentEntries = replaced)
                }
                if (sameSource || sameTranslation) {
                    return@update current
                }
            }
            val updated = (current.recentEntries + entry).takeLast(MAX_HISTORY_ENTRIES)
            current.copy(recentEntries = updated)
        }
    }

    fun clearHistory() {
        _telemetry.update { it.copy(recentEntries = emptyList()) }
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

    private fun normalizeKey(text: String): String {
        return text.trim().replace(WHITESPACE_REGEX, " ").lowercase()
    }
}
