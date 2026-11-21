package com.example.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

interface TranslatorEngine {
    suspend fun isModelReady(sourceLang: String, targetLang: String): Boolean
    suspend fun downloadModelsIfNeeded(sourceLang: String, targetLang: String, requireWifi: Boolean = false)
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
    fun close()
}

class MlKitTranslatorEngine : TranslatorEngine {

    private var cachedKey: String? = null
    private var cachedTranslator: Translator? = null

    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang->$targetLang"
        if (key == cachedKey && cachedTranslator != null) {
            return cachedTranslator!!
        }
        cachedTranslator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
        return Translation.getClient(options).also {
            cachedKey = key
            cachedTranslator = it
        }
    }

    override suspend fun isModelReady(sourceLang: String, targetLang: String): Boolean {
        if (sourceLang == targetLang) return true
        val modelManager = RemoteModelManager.getInstance()
        val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitResult()
        val codes = downloaded.map { it.language }.toSet()
        return codes.contains(sourceLang) && codes.contains(targetLang)
    }

    override suspend fun downloadModelsIfNeeded(
        sourceLang: String,
        targetLang: String,
        requireWifi: Boolean
    ) {
        if (sourceLang == targetLang) return
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        translator.downloadModelIfNeeded(conditions).awaitResult()
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank() || sourceLang == targetLang) return text
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        // Ensure model is available before translating; fast no-op when already downloaded.
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
        return translator.translate(text).awaitResult()
    }

    override fun close() {
        cachedTranslator?.close()
        cachedTranslator = null
        cachedKey = null
    }
}
