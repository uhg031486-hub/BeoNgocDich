package vn.huy.truyendich

import org.json.JSONArray
import java.util.Locale

data class ServerBook(
    val bookId: String,
    val source: String,
    val bookName: String,
    val vbookCompatible: Boolean
) {
    val memoryScope: String
        get() = if (vbookCompatible) {
            "v231-story-v2:${VbookInterop.textContentHash(VbookInterop.jsonArray(bookId, source.lowercase()))}"
        } else {
            "android-story-v1:$bookId"
        }
}

object VbookInterop {
    fun serverBook(settings: TranslationSettings, localBookId: String, bookName: String): ServerBook {
        val vbookId = settings.vbookBookId.trim()
        val vbookSource = settings.vbookSource.trim()
        if (vbookId.isNotBlank() && vbookSource.isNotBlank()) {
            return ServerBook(vbookId, vbookSource, bookName, true)
        }
        return ServerBook(localBookId, "android-epub", bookName, false)
    }

    fun chapterKey(book: ServerBook, text: String, settings: TranslationSettings): String {
        if (!book.vbookCompatible) return "android-chapter:${sha256("${book.bookId}|${book.source}|$text")}" 
        val reasoning = if (settings.model == "kc/nvidia/nemotron-3.5-lightning:free") "none" else "minimal"
        val signature = jsonArray(
            "v23.1-story-memory-2",
            "original",
            settings.model,
            reasoning,
            0.2,
            settings.genre,
            settings.glossary,
            settings.styleNotes,
            true
        )
        return "v231-chapter:${textContentHash(jsonArray(
            book.bookId,
            book.source,
            textContentHash(text),
            textContentHash(signature)
        ))}"
    }

    fun textContentHash(value: String): String {
        val text = value.trim().replace("\r\n", "\n")
        var hash1 = 2166136261L.toInt()
        var hash2 = 0x811c9dc5.toInt()
        text.forEach { character ->
            val code = character.code
            // Match JavaScript's floating multiply followed by >>> 0 in V23.
            hash1 = ((hash1 xor code).toDouble() * 16777619.0).toLong().toInt()
            hash2 = (((hash2.toLong() and UINT32_MASK).toDouble() * 31.0) + code).toLong().toInt()
        }
        return String.format(Locale.US, "%08x%08x", hash1.toLong() and UINT32_MASK, hash2.toLong() and UINT32_MASK)
    }

    fun jsonArray(vararg values: Any): String = JSONArray(values.toList()).toString()

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val UINT32_MASK = 0xffffffffL
}
