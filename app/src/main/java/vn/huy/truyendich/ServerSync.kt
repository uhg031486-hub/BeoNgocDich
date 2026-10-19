package vn.huy.truyendich

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class SyncRecord(
    val key: String,
    val kind: String,
    val bookId: String,
    val bookName: String,
    val source: String,
    val text: String,
    val translatedText: String,
    val model: String
) {
    fun json(): JSONObject = JSONObject()
        .put("key", key)
        .put("kind", kind)
        .put("book_id", bookId)
        .put("book_name", bookName)
        .put("source", source)
        .put("text", text)
        .put("translated_text", translatedText)
        .put("model", model)

    companion object {
        fun fromJson(json: JSONObject) = SyncRecord(
            json.optString("key"), json.optString("kind"), json.optString("book_id"),
            json.optString("book_name"), json.optString("source"), json.optString("text"),
            json.optString("translated_text"), json.optString("model")
        )
    }
}

class ServerSync(private val context: Context, private val settings: TranslationSettings) {
    fun flush() {
        if (!settings.syncEnabled) return
        val pending = outbox()
        if (pending.isEmpty()) return
        val stillPending = pending.filterNot { upload(it) }
        writeOutbox(stillPending)
    }

    fun syncChapter(record: SyncRecord) {
        if (!settings.syncEnabled) return
        if (!upload(record)) {
            val pending = outbox().filterNot { it.key == record.key }.toMutableList()
            pending += record
            writeOutbox(pending)
        }
    }

    /** Keep the server's glossary/relations ahead of every stored chapter. */
    fun syncChapterWithMemory(record: SyncRecord, book: ServerBook, memory: StoryMemory) {
        if (!settings.syncEnabled) return
        syncMemory(book, memory)
        syncChapter(record)
    }

    fun syncMemory(book: ServerBook, memory: StoryMemory) {
        if (!settings.syncEnabled) return
        val namesKey = "${book.memoryScope}:names"
        val relationsKey = "${book.memoryScope}:relations"
        getTranslation(namesKey)?.let { parseMemoryPayload(it)?.let(memory::mergeRemote) }
        getTranslation(relationsKey)?.let { parseMemoryPayload(it)?.let(memory::mergeRemote) }
        if (!memory.dirty) return

        val names = memory.namesPayload().toString()
        val relations = memory.relationsPayload().toString()
        val namesStored = post("/v1/translations/put", SyncRecord(
            namesKey, "character-glossary", book.bookId, book.bookName, book.source, names, names, settings.model
        ).json().put("merge", true))?.optBoolean("stored") == true
        val relationsStored = post("/v1/translations/put", SyncRecord(
            relationsKey, "relationship-memory", book.bookId, book.bookName, book.source, relations, relations, settings.model
        ).json().put("merge", true))?.optBoolean("stored") == true
        if (namesStored && relationsStored) memory.markSynced()
    }

    private fun upload(record: SyncRecord): Boolean = post("/v1/translations/put", record.json())?.optBoolean("stored") == true

    private fun getTranslation(key: String): JSONObject? {
        val response = request("/v1/translations/get?key=${java.net.URLEncoder.encode(key, "UTF-8")}", null) ?: return null
        return if (response.optBoolean("found")) response.optJSONObject("translation") else null
    }

    private fun parseMemoryPayload(record: JSONObject): JSONObject? {
        val text = record.optString("translated_text", record.optString("text"))
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun post(path: String, body: JSONObject): JSONObject? = request(path, body)

    private fun request(path: String, body: JSONObject?): JSONObject? {
        routes().forEach { base ->
            try {
                val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (body == null) "GET" else "POST"
                    connectTimeout = if (base == cleanUrl(settings.tailscaleUrl)) 1_800 else 7_000
                    readTimeout = 20_000
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    if (settings.serverKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${settings.serverKey}")
                }
                try {
                    if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                    val status = connection.responseCode
                    val raw = readFully(if (status in 200..299) connection.inputStream else connection.errorStream)
                    if (status in 200..299) return JSONObject(raw)
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // The next route is the Funnel fallback.
            }
        }
        return null
    }

    private fun routes(): List<String> = listOf(cleanUrl(settings.tailscaleUrl), cleanUrl(settings.funnelUrl)).filter { it.isNotBlank() }.distinct()

    private fun cleanUrl(value: String): String = value.trim().trimEnd('/')

    private fun outbox(): List<SyncRecord> {
        val file = File(context.filesDir, OUTBOX_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val list = JSONArray(file.readText(Charsets.UTF_8))
            List(list.length()) { SyncRecord.fromJson(list.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeOutbox(records: List<SyncRecord>) {
        val file = File(context.filesDir, OUTBOX_FILE)
        file.writeText(JSONArray(records.map { it.json() }).toString(), Charsets.UTF_8)
    }

    private fun readFully(input: InputStream?): String {
        if (input == null) return ""
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
    }

    private companion object {
        const val OUTBOX_FILE = "translation-sync-outbox.json"
    }
}
