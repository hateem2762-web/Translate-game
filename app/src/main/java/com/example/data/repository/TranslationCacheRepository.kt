package com.example.data.repository

import android.util.LruCache
import com.example.data.local.TranslationCacheDao
import com.example.data.local.TranslationCacheEntity
import com.example.model.TranslationEngineMode
import kotlinx.coroutines.flow.Flow

interface TranslationCacheStore {
    suspend fun get(
        engineMode: TranslationEngineMode,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String
    ): String?

    suspend fun put(
        engineMode: TranslationEngineMode,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String,
        translated: String
    )

    suspend fun clear()
}

class TranslationCacheRepository(
    private val dao: TranslationCacheDao
) : TranslationCacheStore {

    private val memoryCache = LruCache<String, String>(256)

    fun observeCacheCount(): Flow<Int> = dao.observeCount()

    override suspend fun get(
        engineMode: TranslationEngineMode,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String
    ): String? {
        val key = buildKey(engineMode, sourceLang, targetLang, normalizedSource)
        memoryCache.get(key)?.let { return it }

        val entity = dao.getByKey(key) ?: return null
        memoryCache.put(key, entity.translatedText)
        return entity.translatedText
    }

    override suspend fun put(
        engineMode: TranslationEngineMode,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String,
        translated: String
    ) {
        val key = buildKey(engineMode, sourceLang, targetLang, normalizedSource)
        memoryCache.put(key, translated)
        dao.upsert(
            TranslationCacheEntity(
                cacheKey = key,
                sourceLang = sourceLang,
                targetLang = targetLang,
                sourceText = normalizedSource,
                translatedText = translated,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun clear() {
        memoryCache.evictAll()
        dao.clearAll()
    }

    private fun buildKey(
        engineMode: TranslationEngineMode,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String
    ): String {
        return "${engineMode.name}:$sourceLang->$targetLang::${normalizedSource.lowercase()}"
    }
}
