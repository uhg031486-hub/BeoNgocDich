package vn.huy.truyendich

import android.content.Context
import android.net.Uri
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.max
import kotlin.math.min

data class TranslationOutput(
    val file: File,
    val suggestedName: String,
    val mimeType: String,
    val completedChapters: Int = 1,
    val totalChapters: Int = 1,
    val paused: Boolean = false
) {
    val isComplete: Boolean get() = completedChapters == totalChapters
}

class BookTranslator(private val context: Context) {
    fun translate(
        inputUri: Uri,
        inputName: String,
        settings: TranslationSettings,
        cancelled: () -> Boolean,
        pauseRequested: () -> Boolean,
        progress: (Int, Int, String) -> Unit
    ): TranslationOutput {
        val input = copyInput(inputUri, inputName)
        val normalizedName = inputName.ifBlank { "truyen.epub" }
        val localBookId = sha256(input)
        val bookName = normalizedName.substringBeforeLast('.', normalizedName)
        val serverBook = VbookInterop.serverBook(settings, localBookId, bookName)
        val memoryStore = StoryMemoryStore(context)
        val memory = memoryStore.loadMergedWithGlobal(localBookId)
        val sync = ServerSync(context, settings)
        sync.flush()
        sync.syncMemory(serverBook, memory)
        memoryStore.save(localBookId, memory)

        val executor = Executors.newFixedThreadPool(settings.concurrency.coerceIn(1, 50))
        try {
            return if (normalizedName.endsWith(".epub", true) || isZip(input)) {
                translateEpub(input, normalizedName, localBookId, serverBook, settings, memory, memoryStore, sync, executor, cancelled, pauseRequested, progress)
            } else {
                translateText(input, normalizedName, localBookId, serverBook, settings, memory, memoryStore, sync, executor, cancelled, pauseRequested, progress)
            }
        } finally {
            executor.shutdownNow()
            input.delete()
        }
    }

    private fun translateText(
        input: File,
        inputName: String,
        localBookId: String,
        serverBook: ServerBook,
        settings: TranslationSettings,
        memory: StoryMemory,
        memoryStore: StoryMemoryStore,
        sync: ServerSync,
        executor: ExecutorService,
        cancelled: () -> Boolean,
        pauseRequested: () -> Boolean,
        progress: (Int, Int, String) -> Unit
    ): TranslationOutput {
        ensureActive(cancelled)
        val source = input.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val chunks = splitTextIntoChunks(source)
        val totalChunks = chunks.size
        val checkpointDirectory = checkpointDirectory(localBookId)

        val translatedChunks = arrayOfNulls<String>(totalChunks)
        // Load checkpoints if available
        for (i in 0 until totalChunks) {
            loadCheckpoint(checkpointDirectory, "txt_chunk_$i")?.let { bytes ->
                translatedChunks[i] = String(bytes, Charsets.UTF_8)
            }
        }

        var done = translatedChunks.count { it != null }
        var failed = 0
        val workers = settings.concurrency.coerceIn(1, 50)
        val pendingIndices = (0 until totalChunks).filter { translatedChunks[it] == null }
        val batches = pendingIndices.chunked(workers)
        var paused = false

        val outputFile = File.createTempFile("translated-", ".txt", context.cacheDir)

        fun saveCurrentOutput() {
            val content = StringBuilder()
            for (i in 0 until totalChunks) {
                content.append(translatedChunks[i] ?: chunks[i])
            }
            outputFile.writeText(content.toString(), Charsets.UTF_8)
        }

        saveCurrentOutput()

        if (done > 0) {
            progress(done, totalChunks, "Khôi phục checkpoint: đã có $done/$totalChunks đoạn, chỉ dịch phần còn lại.")
        } else {
            progress(0, totalChunks, "Bắt đầu dịch TXT: $totalChunks đoạn, tối đa $workers luồng...")
        }

        for ((batchIndex, batch) in batches.withIndex()) {
            if (pauseRequested()) {
                paused = true
                break
            }
            ensureActive(cancelled)
            progress(
                done,
                totalChunks,
                "Đang dịch lô ${batchIndex + 1}/${batches.size}: ${batch.size} đoạn, tối đa $workers luồng..."
            )
            val completion = ExecutorCompletionService<TextChunkAttempt>(executor)
            batch.forEach { chunkIndex ->
                val chunkText = chunks[chunkIndex]
                completion.submit(Callable {
                    try {
                        ensureActive(cancelled)
                        val client = RouterClient(settings, memory)
                        val rawTranslated = client.translateText(chunkText)
                        val translated = normalizeChunkEnding(chunkText, rawTranslated)
                        TextChunkAttempt(chunkIndex, translated)
                    } catch (error: Exception) {
                        TextChunkAttempt(chunkIndex, error = error)
                    }
                })
            }

            repeat(batch.size) {
                ensureActive(cancelled)
                val completed = completion.take().get()
                val translated = completed.translation
                if (translated == null) {
                    failed += 1
                    progress(done, totalChunks, "Không dịch được đoạn ${completed.index + 1} (${completed.error?.message ?: "lỗi mạng"}); giữ nguyên đoạn gốc.")
                    return@repeat
                }

                saveCheckpoint(checkpointDirectory, "txt_chunk_${completed.index}", translated.toByteArray(Charsets.UTF_8))
                translatedChunks[completed.index] = translated
                done += 1

                val chunkSource = chunks[completed.index]
                runCatching {
                    updateMemory(
                        RouterClient(settings, memory),
                        chunkSource,
                        localBookId,
                        serverBook,
                        memory,
                        memoryStore,
                        sync
                    )
                }
                runCatching {
                    sync.syncChapterWithMemory(
                        SyncRecord(
                            key = VbookInterop.chapterKey(serverBook, chunkSource, settings) + "_c${completed.index}",
                            kind = "chapter",
                            bookId = serverBook.bookId,
                            bookName = serverBook.bookName,
                            source = serverBook.source,
                            text = chunkSource.take(MAX_SYNC_TEXT),
                            translatedText = translated.take(MAX_SYNC_TEXT),
                            model = settings.model
                        ),
                        serverBook,
                        memory
                    )
                }
                progress(done, totalChunks, "Đã dịch $done/$totalChunks đoạn; đang lưu tiến trình...")
            }

            // Progressively persist translated text
            saveCurrentOutput()
        }

        if (done == 0 && totalChunks > 0 && !paused) {
            throw TranslationException(
                "Không có đoạn TXT nào dịch thành công. 9Router đã lỗi ở $failed/$totalChunks đoạn; không xuất TXT nguyên văn."
            )
        }

        saveCurrentOutput()

        val complete = done == totalChunks
        val name = if (complete && !paused) "${serverBook.bookName}.vi.txt" else "${serverBook.bookName}.partial.vi.txt"
        progress(
            done,
            totalChunks,
            if (complete && !paused) "Đã dịch xong toàn bộ TXT ($done đoạn)." else "Đã xuất TXT tạm: $done/$totalChunks đoạn dịch xong."
        )
        return TranslationOutput(outputFile, name, "text/plain", done, totalChunks, paused)
    }

    private fun translateEpub(
        input: File,
        inputName: String,
        localBookId: String,
        serverBook: ServerBook,
        settings: TranslationSettings,
        memory: StoryMemory,
        memoryStore: StoryMemoryStore,
        sync: ServerSync,
        executor: ExecutorService,
        cancelled: () -> Boolean,
        pauseRequested: () -> Boolean,
        progress: (Int, Int, String) -> Unit
    ): TranslationOutput {
        val output = File.createTempFile("translated-", ".epub", context.cacheDir)
        ZipFile(input).use { zip ->
            val chapterPaths = epubChapterPaths(zip)
            if (chapterPaths.isEmpty()) throw TranslationException("Không tìm thấy chương XHTML trong EPUB.")
            val entries = mutableListOf<ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) entries += enumeration.nextElement()
            val chapters = chapterPaths.toSet()

            val chapterBytes = chapterPaths.associateWith { path ->
                zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
            }
            val firstChapter = settings.chapterStart.coerceIn(1, chapterPaths.size)
            val lastChapter = (if (settings.chapterEnd == 0) chapterPaths.size else settings.chapterEnd)
                .coerceIn(firstChapter, chapterPaths.size)
            val requestedPaths = chapterPaths.subList(firstChapter - 1, lastChapter)
            val translations = linkedMapOf<String, XhtmlTranslation>()
            val checkpointDirectory = checkpointDirectory(localBookId)
            requestedPaths.forEach { path ->
                loadCheckpoint(checkpointDirectory, path)?.let { bytes ->
                    translations[path] = XhtmlTranslation(bytes, "", "")
                }
            }
            var done = translations.size
            var failed = 0
            val workers = settings.concurrency.coerceIn(1, 50)
            val pending = requestedPaths.filterNot(translations::containsKey)
            val batches = pending.chunked(workers)
            var paused = false

            if (done > 0) {
                progress(done, requestedPaths.size, "Khôi phục checkpoint: đã có $done/${requestedPaths.size} chương, chỉ dịch phần còn lại.")
            }

            for ((batchIndex, batch) in batches.withIndex()) {
                if (pauseRequested()) {
                    paused = true
                    break
                }
                ensureActive(cancelled)
                progress(
                    done,
                    requestedPaths.size,
                    "Đang dịch lô ${batchIndex + 1}/${batches.size}: ${batch.size} chương, tối đa $workers luồng..."
                )
                val completion = ExecutorCompletionService<ChapterAttempt>(executor)
                batch.forEach { path ->
                    completion.submit(Callable {
                        try {
                            ensureActive(cancelled)
                            val chapterClient = RouterClient(settings, memory)
                            val translated = ChapterTranslator(chapterClient, settings.charsPerRequest, executor, cancelled, false)
                                .translateXhtml(chapterBytes.getValue(path))
                            ChapterAttempt(path, translated)
                        } catch (error: Exception) {
                            ChapterAttempt(path, error = error)
                        }
                    })
                }
                repeat(batch.size) {
                    ensureActive(cancelled)
                    val completed = completion.take().get()
                    val translated = completed.translation
                    if (translated == null) {
                        failed += 1
                        progress(done, requestedPaths.size, "Không dịch được một chương (${completed.error?.message ?: "lỗi mạng"}); sẽ giữ nguyên chương đó để chạy tiếp lần sau.")
                        return@repeat
                    }

                    // Persist first so a failed memory/sync request cannot lose a completed chapter.
                    saveCheckpoint(checkpointDirectory, completed.path, translated.bytes)
                    translations[completed.path] = translated
                    done += 1

                    runCatching {
                        updateMemory(
                            RouterClient(settings, memory),
                            translated.sourceText,
                            localBookId,
                            serverBook,
                            memory,
                            memoryStore,
                            sync
                        )
                    }
                    runCatching {
                        sync.syncChapterWithMemory(SyncRecord(
                            key = VbookInterop.chapterKey(serverBook, translated.sourceText, settings),
                            kind = "chapter",
                            bookId = serverBook.bookId,
                            bookName = serverBook.bookName,
                            source = serverBook.source,
                            text = translated.sourceText.take(MAX_SYNC_TEXT),
                            translatedText = translated.translatedText.take(MAX_SYNC_TEXT),
                            model = settings.model
                        ), serverBook, memory)
                    }
                    progress(done, requestedPaths.size, "Đã dịch $done/${requestedPaths.size} chương; checkpoint và đồng bộ đang được lưu.")
                }
            }

            if (translations.isEmpty() && requestedPaths.isNotEmpty() && !paused) {
                throw TranslationException(
                    "Không có chương EPUB nào dịch thành công. 9Router đã lỗi ở ${failed}/${requestedPaths.size} chương; không xuất EPUB nguyên văn."
                )
            }

            ZipOutputStream(FileOutputStream(output)).use { target ->
                zip.getEntry("mimetype")?.let { writeMimetype(zip, it, target) }
                entries.forEach { entry ->
                    ensureActive(cancelled)
                    if (entry.name == "mimetype") return@forEach
                    val content = if (entry.name in chapters) {
                        translations[entry.name]?.bytes
                            ?: zip.getInputStream(entry).use { it.readBytes() }
                    } else {
                        if (entry.isDirectory) ByteArray(0) else zip.getInputStream(entry).use { it.readBytes() }
                    }
                    writeEntry(target, entry, content)
                }
            }
            val complete = translations.size == requestedPaths.size
            val rangeSuffix = if (requestedPaths.size == chapterPaths.size) "" else ".chap-$firstChapter-$lastChapter"
            val name = if (complete && !paused) "${serverBook.bookName}$rangeSuffix.vi.epub" else "${serverBook.bookName}$rangeSuffix.partial.vi.epub"
            progress(
                translations.size,
                requestedPaths.size,
                if (complete && !paused) "Đã dịch xong phạm vi EPUB." else "Đã xuất EPUB tạm: ${translations.size}/${requestedPaths.size} chương dịch xong, $failed chương sẽ chạy lại lần sau."
            )
            return TranslationOutput(output, name, "application/epub+zip", translations.size, requestedPaths.size, paused)
        }
    }

    private fun checkpointDirectory(bookId: String): File =
        File(context.filesDir, "translation-checkpoints/$bookId").apply { mkdirs() }

    private fun checkpointFile(directory: File, chapterPath: String): File {
        val part = File(directory, "${sha256(chapterPath)}.part")
        if (part.isFile && part.length() > 0L) return part
        val legacy = File(directory, "${sha256(chapterPath)}.xhtml")
        if (legacy.isFile && legacy.length() > 0L) return legacy
        return part
    }

    private fun splitTextIntoChunks(text: String, minChunkChars: Int = 3000, maxChunkChars: Int = 4000): List<String> {
        if (text.length <= maxChunkChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= maxChunkChars) {
                chunks += text.substring(start)
                break
            }
            val targetEnd = min(text.length, start + maxChunkChars)
            val minEnd = start + minChunkChars

            val newlineIndex = text.lastIndexOf('\n', targetEnd - 1)
            var end = if (newlineIndex >= minEnd) {
                newlineIndex + 1
            } else {
                val punctuation = listOf('。', '！', '？', '.', '!', '?', ';', '；')
                val pIndex = punctuation.map { text.lastIndexOf(it, targetEnd - 1) }.maxOrNull() ?: -1
                if (pIndex >= minEnd) {
                    pIndex + 1
                } else if (newlineIndex > start) {
                    newlineIndex + 1
                } else {
                    val spaceIndex = listOf(' ', '\t', '\r').map { text.lastIndexOf(it, targetEnd - 1) }.maxOrNull() ?: -1
                    if (spaceIndex > start) {
                        spaceIndex + 1
                    } else {
                        targetEnd
                    }
                }
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    private fun normalizeChunkEnding(source: String, translated: String): String {
        val trailingNewlines = source.takeLastWhile { it == '\n' || it == '\r' }
        val clean = translated.trimEnd('\r', '\n')
        return clean + trailingNewlines
    }

    private fun loadCheckpoint(directory: File, chapterPath: String): ByteArray? =
        checkpointFile(directory, chapterPath).takeIf { it.isFile && it.length() > 0L }?.readBytes()

    private fun saveCheckpoint(directory: File, chapterPath: String, bytes: ByteArray) {
        val destination = checkpointFile(directory, chapterPath)
        if (destination.isFile && destination.length() > 0L) return
        val temporary = File(directory, ".${destination.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = false)
            temporary.delete()
        }
    }

    private fun updateMemory(
        client: RouterClient,
        sourceText: String,
        localBookId: String,
        serverBook: ServerBook,
        memory: StoryMemory,
        memoryStore: StoryMemoryStore,
        sync: ServerSync
    ) {
        if (sourceText.isBlank()) return
        memory.merge(client.extractStoryMemory(sourceText))
        memoryStore.save(localBookId, memory)
        sync.syncMemory(serverBook, memory)
        memoryStore.save(localBookId, memory)
    }

    private fun epubChapterPaths(zip: ZipFile): List<String> {
        val container = zip.getEntry("META-INF/container.xml")
            ?: throw TranslationException("EPUB thiếu META-INF/container.xml.")
        val containerDocument = parseXml(zip.getInputStream(container).use { it.readBytes() })
        val rootFile = descendants(containerDocument, "rootfile").firstOrNull()
            ?.getAttribute("full-path")?.trim().orEmpty()
        if (rootFile.isBlank()) throw TranslationException("Không xác định được OPF của EPUB.")
        val opfEntry = zip.getEntry(rootFile) ?: throw TranslationException("Không mở được OPF: $rootFile")
        val opf = parseXml(zip.getInputStream(opfEntry).use { it.readBytes() })
        val manifest = mutableMapOf<String, String>()
        val validItems = mutableSetOf<String>()
        descendants(opf, "item").forEach { item ->
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type").lowercase()
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = resolvePath(rootFile, href)
                if (mediaType.contains("xhtml") || mediaType.contains("html")) validItems += id
            }
        }
        val ordered = descendants(opf, "itemref").mapNotNull { manifest[it.getAttribute("idref")] }
            .filter { zip.getEntry(it) != null }
        if (ordered.isNotEmpty()) return ordered.distinct()
        return validItems.mapNotNull { manifest[it] }.filter { zip.getEntry(it) != null }.distinct()
    }

    private fun resolvePath(opfPath: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?').replace('\\', '/')
        val base = opfPath.substringBeforeLast('/', "")
        val resolved = java.net.URI(null, null, "/$base/$cleanHref", null).normalize().path
        return resolved.removePrefix("/")
    }

    private fun descendants(document: Document, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if ((element.localName ?: element.nodeName.substringAfterLast(':')).equals(tag, true)) result += element
        }
        return result
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (error: Exception) {
            throw TranslationException("Một chương XHTML không phải XML hợp lệ: ${error.message?.take(160)}")
        }
    }

    private fun writeMimetype(zip: ZipFile, entry: ZipEntry, target: ZipOutputStream) {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val crc = CRC32().apply { update(bytes) }
        val outputEntry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        target.putNextEntry(outputEntry)
        target.write(bytes)
        target.closeEntry()
    }

    private fun writeEntry(target: ZipOutputStream, source: ZipEntry, content: ByteArray) {
        val entry = ZipEntry(source.name).apply { time = source.time }
        target.putNextEntry(entry)
        if (!source.isDirectory) target.write(content)
        target.closeEntry()
    }

    private fun copyInput(uri: Uri, name: String): File {
        val suffix = "." + name.substringAfterLast('.', "input")
        val file = File.createTempFile("source-", suffix, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        } ?: throw TranslationException("Không thể mở tệp đã chọn.")
        return file
    }

    private fun isZip(file: File): Boolean = FileInputStream(file).use { stream ->
        stream.read() == 'P'.code && stream.read() == 'K'.code
    }

    private fun ensureActive(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Đã hủy tác vụ.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SYNC_TEXT = 900_000
    }
}

private data class ChapterAttempt(
    val path: String,
    val translation: XhtmlTranslation? = null,
    val error: Exception? = null
)

private data class TextChunkAttempt(
    val index: Int,
    val translation: String? = null,
    val error: Exception? = null
)

private data class XhtmlTranslation(val bytes: ByteArray, val sourceText: String, val translatedText: String)
private data class TextPiece(val node: Node?, val value: String)

private class ChapterTranslator(
    private val client: RouterClient,
    private val maxRequestChars: Int,
    private val executor: ExecutorService,
    private val cancelled: () -> Boolean,
    private val parallelSegments: Boolean = true
) {
    fun translatePlain(source: String): String = translateValues(splitText(source)).joinToString(separator = "")

    fun translateXhtml(source: ByteArray): XhtmlTranslation {
        val document = parseXml(source)
        val pieces = mutableListOf<TextPiece>()
        collectText(document.documentElement, false, pieces)
        if (pieces.isEmpty()) return XhtmlTranslation(source, "", "")
        val values = translateValues(pieces.flatMap { piece -> splitText(piece.value).map { TextPiece(piece.node, it) } }.map { it.value })
        val splitPieces = pieces.flatMap { piece -> splitText(piece.value).map { TextPiece(piece.node, it) } }
        val nodeValues = LinkedHashMap<Node, StringBuilder>()
        splitPieces.forEachIndexed { index, piece ->
            val node = piece.node ?: return@forEachIndexed
            nodeValues.getOrPut(node) { StringBuilder() }.append(values[index])
        }
        nodeValues.forEach { (node, value) -> node.nodeValue = value.toString() }
        val output = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        transformer.transform(DOMSource(document), StreamResult(output))
        return XhtmlTranslation(output.toByteArray(), pieces.joinToString("\n") { it.value }, values.joinToString(""))
    }

    fun sourceText(source: ByteArray): String {
        val document = parseXml(source)
        val pieces = mutableListOf<TextPiece>()
        collectText(document.documentElement, false, pieces)
        return pieces.joinToString("\n") { it.value }
    }

    private fun translateValues(values: List<String>): List<String> {
        val groups = mutableListOf<MutableList<Int>>()
        var group = mutableListOf<Int>()
        var size = 0
        values.forEachIndexed { index, value ->
            if (group.isNotEmpty() && size + value.length > maxRequestChars) {
                groups += group
                group = mutableListOf()
                size = 0
            }
            group += index
            size += value.length
        }
        if (group.isNotEmpty()) groups += group
        val output = MutableList(values.size) { "" }
        if (!parallelSegments) {
            groups.forEach { indexes ->
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Đã hủy tác vụ.")
                val translated = client.translateSegments(indexes.map(values::get))
                indexes.forEachIndexed { index, originalIndex -> output[originalIndex] = translated[index] }
            }
        } else {
            val futures: List<Future<Pair<List<Int>, List<String>>>> = groups.map { indexes ->
                executor.submit(Callable {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Đã hủy tác vụ.")
                    indexes to client.translateSegments(indexes.map(values::get))
                })
            }
            futures.forEach { future ->
                val (indexes, translated) = future.get()
                indexes.forEachIndexed { index, originalIndex -> output[originalIndex] = translated[index] }
            }
        }
        return output
    }

    private fun splitText(value: String): List<String> {
        if (value.length <= maxRequestChars) return listOf(value)
        val output = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = min(value.length, start + maxRequestChars)
            if (end < value.length) {
                val boundary = listOf('\n', '.', '!', '?', '。', '！', '？', ' ').maxOf { marker -> value.lastIndexOf(marker, end - 1) }
                if (boundary > start + maxRequestChars / 2) end = boundary + 1
            }
            output += value.substring(start, end)
            start = end
        }
        return output
    }

    private fun collectText(node: Node, skipped: Boolean, output: MutableList<TextPiece>) {
        val nodeName = (node.localName ?: node.nodeName.substringAfterLast(':')).lowercase()
        val nextSkipped = skipped || nodeName in setOf("script", "style", "code", "pre", "svg", "math")
        if (node.nodeType == Node.TEXT_NODE && !nextSkipped && node.nodeValue.any { !it.isWhitespace() }) {
            output += TextPiece(node, node.nodeValue)
        }
        var child = node.firstChild
        while (child != null) {
            collectText(child, nextSkipped, output)
            child = child.nextSibling
        }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (error: Exception) {
            throw TranslationException("Một chương XHTML không phải XML hợp lệ: ${error.message?.take(160)}")
        }
    }
}
