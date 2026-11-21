package com.example.engine

import android.graphics.Bitmap
import com.example.model.RoiRect
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(
    val text: String,
    val boundingBox: RoiRect
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>
)

interface OcrEngine {
    suspend fun recognize(
        bitmap: Bitmap,
        sourceLangCode: String = TranslateLanguage.ENGLISH
    ): OcrResult

    fun close()
}

class MlKitOcrEngine : OcrEngine {
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap, sourceLangCode: String): OcrResult {
        val isChinese = sourceLangCode == TranslateLanguage.CHINESE
        val recognizer = if (isChinese) chineseRecognizer else latinRecognizer
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).awaitResult()
        if (result.textBlocks.isEmpty()) {
            return OcrResult(fullText = "", blocks = emptyList())
        }

        val sortedBlocks = result.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .mapNotNull { block ->
                val raw = cleanBlockText(block.text, isChinese)
                val rect = block.boundingBox
                if (raw.isBlank() || rect == null) {
                    null
                } else {
                    val clamped = RoiRect(
                        x = rect.left,
                        y = rect.top,
                        width = rect.width(),
                        height = rect.height()
                    ).clampToScreen(bitmap.width, bitmap.height, minWidth = 12, minHeight = 10)
                    OcrBlock(text = raw, boundingBox = clamped)
                }
            }

        val combined = if (isChinese) {
            joinChineseBlocks(sortedBlocks.map { it.text })
        } else {
            sortedBlocks.joinToString(separator = " ") { it.text }.trim()
        }
        return OcrResult(fullText = combined, blocks = sortedBlocks)
    }

    override fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
    }

    companion object {
        private val NEWLINE_OR_SPACES = Regex("\\s+")
        private val SPACE_BETWEEN_CJK = Regex("(?<=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])\\s+(?=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])")

        fun cleanBlockText(rawText: String, isChinese: Boolean): String {
            val collapsed = rawText.trim().replace(NEWLINE_OR_SPACES, " ")
            return if (isChinese) {
                collapsed.replace(SPACE_BETWEEN_CJK, "").trim()
            } else {
                collapsed
            }
        }

        fun joinChineseBlocks(blocks: List<String>): String {
            if (blocks.isEmpty()) return ""
            return blocks.joinToString(separator = "") { it.trim() }
                .replace(SPACE_BETWEEN_CJK, "")
                .trim()
        }
    }
}
