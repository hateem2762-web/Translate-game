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
                val raw = OcrTextPreprocessor.clean(block.text, isChinese)
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
            OcrTextPreprocessor.joinParagraphLines(sortedBlocks.map { it.text })
        }
        return OcrResult(fullText = combined, blocks = sortedBlocks)
    }

    override fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
    }

    companion object {
        fun cleanBlockText(rawText: String, isChinese: Boolean): String {
            return OcrTextPreprocessor.clean(rawText, isChinese)
        }

        fun joinChineseBlocks(blocks: List<String>): String {
            return OcrTextPreprocessor.joinChineseBlocks(blocks)
        }
    }
}

/**
 * Cleans raw OCR output before feeding local ML Kit or cloud AI engines:
 * - Merges intra-paragraph line breaks (`\n`) and hyphenated word wraps into coherent sentences
 *   so local translation never fractures broken lines.
 * - Strips stray game-UI OCR artifacts (isolated pipes `|`, boxes `■□`, tildes `~`, stray brackets).
 * - Normalizes whitespace and CJK spacing.
 */
object OcrTextPreprocessor {
    private val HYPHEN_LINE_WRAP = Regex("([\\p{L}\\p{N}])-\\s*[\\r\\n]+\\s*([\\p{L}\\p{N}])")
    private val LINE_BREAKS = Regex("[\\r\\n]+")
    private val MULTI_WHITESPACE = Regex("\\s+")
    private val ARTIFACT_CHARS = Regex("[|¦~^_■□◆◇●○★☆※¤§]+")
    private val STANDALONE_NOISE_TOKEN = Regex("(?:^|\\s)[^\\p{L}\\p{N}\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF.,!?;:'\"()\\-—–$€£¥₺]+(?=\\s|$)")
    private val FANCY_DOUBLE_QUOTES = Regex("[“”«»]")
    private val FANCY_SINGLE_QUOTES = Regex("[‘’`]")
    private val REPEATED_PUNCT = Regex("([.,!?])\\1{3,}")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([.,!?;:])")
    private val SPACE_BETWEEN_CJK = Regex("(?<=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])\\s+(?=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])")

    fun clean(rawText: String, isChinese: Boolean = false): String {
        if (rawText.isBlank()) return ""
        var text = rawText.trim()
        if (!isChinese) {
            text = text.replace(HYPHEN_LINE_WRAP, "$1$2")
        }
        text = text.replace(LINE_BREAKS, if (isChinese) "" else " ")
            .replace(FANCY_DOUBLE_QUOTES, "\"")
            .replace(FANCY_SINGLE_QUOTES, "'")
            .replace(ARTIFACT_CHARS, " ")
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(STANDALONE_NOISE_TOKEN, " ")
            .replace(REPEATED_PUNCT) { match ->
                val ch = match.groupValues[1]
                if (ch == ".") "..." else ch
            }
            .replace(MULTI_WHITESPACE, " ")
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .trim()

        if (isChinese) {
            text = text.replace(SPACE_BETWEEN_CJK, "").trim()
        }
        return text
    }

    fun joinParagraphLines(lines: List<String>, isChinese: Boolean = false): String {
        if (lines.isEmpty()) return ""
        val cleaned = lines.map { clean(it, isChinese) }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in cleaned.indices) {
            val line = cleaned[i]
            sb.append(line)
            if (i < cleaned.size - 1) {
                val endsWithPunct = line.matches(Regex(".*[.!\\?。！？]$"))
                if (!endsWithPunct) {
                    if (!isChinese) sb.append(" ")
                } else {
                    sb.append(" ")
                }
            }
        }
        return sb.toString().replace(MULTI_WHITESPACE, " ").trim()
    }

    fun joinChineseBlocks(blocks: List<String>): String {
        if (blocks.isEmpty()) return ""
        return blocks.joinToString(separator = "") { clean(it, isChinese = true) }
            .replace(SPACE_BETWEEN_CJK, "")
            .trim()
    }
}
