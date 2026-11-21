package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
import com.example.model.RoiRect
import com.example.model.SubtitleFontSize
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sublens_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val DEBOUNCE_MS = longPreferencesKey("debounce_ms")
        val SCAN_INTERVAL_MS = longPreferencesKey("scan_interval_ms")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val SHOW_ORIGINAL = booleanPreferencesKey("show_original")

        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val AI_PRESET = stringPreferencesKey("ai_preset")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_ENDPOINT_URL = stringPreferencesKey("ai_endpoint_url")
        val AI_MODEL_NAME = stringPreferencesKey("ai_model_name")

        val ROI_X = intPreferencesKey("roi_x")
        val ROI_Y = intPreferencesKey("roi_y")
        val ROI_W = intPreferencesKey("roi_w")
        val ROI_H = intPreferencesKey("roi_h")
    }

    val configFlow: Flow<TranslationConfig> = context.dataStore.data.map { prefs ->
        val preset = runCatching {
            AiProviderPreset.valueOf(prefs[Keys.AI_PRESET] ?: AiProviderPreset.GEMINI_FLASH.name)
        }.getOrDefault(AiProviderPreset.GEMINI_FLASH)

        val displayMode = runCatching {
            DisplayMode.valueOf(prefs[Keys.DISPLAY_MODE] ?: DisplayMode.CLASSIC_BOX.name)
        }.getOrDefault(DisplayMode.CLASSIC_BOX)

        val engineMode = runCatching {
            TranslationEngineMode.valueOf(prefs[Keys.ENGINE_MODE] ?: TranslationEngineMode.OFFLINE_LOCAL.name)
        }.getOrDefault(TranslationEngineMode.OFFLINE_LOCAL)

        val fontSize = runCatching {
            SubtitleFontSize.valueOf(prefs[Keys.FONT_SIZE] ?: SubtitleFontSize.NORMAL.name)
        }.getOrDefault(SubtitleFontSize.NORMAL)

        TranslationConfig(
            sourceLangCode = prefs[Keys.SOURCE_LANG] ?: TranslateLanguage.ENGLISH,
            targetLangCode = prefs[Keys.TARGET_LANG] ?: TranslateLanguage.TURKISH,
            debounceMs = prefs[Keys.DEBOUNCE_MS] ?: 450L,
            scanIntervalMs = prefs[Keys.SCAN_INTERVAL_MS] ?: 300L,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: 0.86f,
            showOriginalText = prefs[Keys.SHOW_ORIGINAL] ?: false,
            displayMode = displayMode,
            engineMode = engineMode,
            fontSize = fontSize,
            aiPreset = preset,
            aiApiKey = prefs[Keys.AI_API_KEY] ?: "",
            aiEndpointUrl = prefs[Keys.AI_ENDPOINT_URL] ?: preset.defaultBaseUrl,
            aiModelName = prefs[Keys.AI_MODEL_NAME] ?: preset.defaultModel
        )
    }

    fun observeRoi(screenWidth: Int, screenHeight: Int): Flow<RoiRect> =
        context.dataStore.data.map { prefs ->
            val defaultRoi = RoiRect.defaultForScreen(screenWidth, screenHeight)
            val x = prefs[Keys.ROI_X] ?: defaultRoi.x
            val y = prefs[Keys.ROI_Y] ?: defaultRoi.y
            val w = prefs[Keys.ROI_W] ?: defaultRoi.width
            val h = prefs[Keys.ROI_H] ?: defaultRoi.height
            RoiRect(x, y, w, h).clampToScreen(screenWidth, screenHeight)
        }

    suspend fun updateDisplayMode(mode: DisplayMode) {
        context.dataStore.edit { it[Keys.DISPLAY_MODE] = mode.name }
    }

    suspend fun updateEngineMode(mode: TranslationEngineMode) {
        context.dataStore.edit { it[Keys.ENGINE_MODE] = mode.name }
    }

    suspend fun updateFontSize(fontSize: SubtitleFontSize) {
        context.dataStore.edit { it[Keys.FONT_SIZE] = fontSize.name }
    }

    suspend fun updateAiPreset(preset: AiProviderPreset) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_PRESET] = preset.name
            prefs[Keys.AI_ENDPOINT_URL] = preset.defaultBaseUrl
            prefs[Keys.AI_MODEL_NAME] = preset.defaultModel
        }
    }

    suspend fun updateAiApiKey(apiKey: String) {
        context.dataStore.edit { it[Keys.AI_API_KEY] = apiKey }
    }

    suspend fun updateAiEndpointUrl(endpointUrl: String) {
        context.dataStore.edit { it[Keys.AI_ENDPOINT_URL] = endpointUrl }
    }

    suspend fun updateAiModelName(modelName: String) {
        context.dataStore.edit { it[Keys.AI_MODEL_NAME] = modelName }
    }

    suspend fun updateSourceLanguage(code: String) {
        context.dataStore.edit { it[Keys.SOURCE_LANG] = code }
    }

    suspend fun updateTargetLanguage(code: String) {
        context.dataStore.edit { it[Keys.TARGET_LANG] = code }
    }

    suspend fun updateDebounceMs(debounceMs: Long) {
        context.dataStore.edit { it[Keys.DEBOUNCE_MS] = debounceMs }
    }

    suspend fun updateScanIntervalMs(scanIntervalMs: Long) {
        context.dataStore.edit { it[Keys.SCAN_INTERVAL_MS] = scanIntervalMs }
    }

    suspend fun updateOverlayOpacity(opacity: Float) {
        context.dataStore.edit { it[Keys.OVERLAY_OPACITY] = opacity }
    }

    suspend fun updateShowOriginalText(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_ORIGINAL] = show }
    }

    suspend fun saveRoi(roi: RoiRect) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROI_X] = roi.x
            prefs[Keys.ROI_Y] = roi.y
            prefs[Keys.ROI_W] = roi.width
            prefs[Keys.ROI_H] = roi.height
        }
    }

    suspend fun resetRoi(screenWidth: Int, screenHeight: Int): RoiRect {
        val defaultRoi = RoiRect.defaultForScreen(screenWidth, screenHeight)
        saveRoi(defaultRoi)
        return defaultRoi
    }
}
