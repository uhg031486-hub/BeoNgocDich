package vn.huy.truyendich

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteEpub(
    val id: String,
    val name: String,
    val title: String = "",
    val author: String = "",
    val completed: Int,
    val total: Int,
    val status: String = "running",
    val message: String = "",
    val model: String = "",
    val coverUrl: String = "",
    val hasCover: Boolean = false,
    val category: String = "",
    val source: String = "",
    val updatedAt: Long = 0L
) {
    val isAutoTranslated: Boolean
        get() = id.startsWith("timo_", ignoreCase = true) ||
                name.startsWith("timo_", ignoreCase = true) ||
                source.contains("timotxt", ignoreCase = true)

    val isUploaded: Boolean
        get() = !isAutoTranslated

    val originLabel: String
        get() = if (isAutoTranslated) "🤖 Tự động dịch" else "📤 Tải lên"

    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            name.contains("thosan") || name.contains("tho_san") || name.contains("Thợ Săn") -> "Thợ Săn Mỹ Thực"
            name.contains("cyber") || name.contains("boss") || name.contains("Xuyên Vào Game") -> "Xuyên Vào Game Cyber Hóa Ra BOSS Thành Công Thượng Vị"
            name.contains("nhiet_huyet") || name.contains("lam_cau") || id.contains("0101390572") -> "Nhiệt Huyết Lam Cầu"
            name.contains("末日堡垒") || id.contains("3da2214b") -> "Mạt Nhật Bảo Lũy: Ta Có Một Trái Tim Mồi Lửa"
            name.contains("诸葛亮") || id.contains("28623017") -> "Gia Cát Lượng: Xui Xẻo, Ta Thành Thế Thân Rồi!"
            else -> name.removeSuffix(".epub").removeSuffix(".txt").replace('_', ' ').replace('-', ' ').trim()
        }

    val displayAuthor: String
        get() = if (author.isNotBlank()) "Tác giả: $author" else ""

    val isComplete: Boolean
        get() = status.equals("complete", ignoreCase = true) || (total > 0 && completed >= total)

    val progressPercent: Int
        get() = if (total > 0) ((completed.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0

    val genre: String
        get() {
            if (category.isNotBlank()) return category
            val combined = (displayTitle + " " + name).lowercase()
            return when {
                combined.contains("tiên") || combined.contains("tu chân") || combined.contains("đan đạo") || combined.contains("kiếm tiên") -> "Tiên Hiệp"
                combined.contains("long") || combined.contains("cự thú") || combined.contains("ma nữ") || combined.contains("dị giới") || combined.contains("dị thế") || combined.contains("ma pháp") || combined.contains("đấu khí") -> "Huyền Huyễn"
                combined.contains("y đạo") || combined.contains("quan đồ") || combined.contains("đô thị") || combined.contains("tổng tài") || combined.contains("hào môn") || combined.contains("bác sĩ") || combined.contains("trọng sinh") -> "Đô Thị"
                combined.contains("cyber") || combined.contains("mạt thế") || combined.contains("bảo lũy") || combined.contains("khoa huyễn") || combined.contains("tận thế") || combined.contains("tương lai") || combined.contains("cơ giáp") -> "Khoa Huyễn"
                combined.contains("lam cầu") || combined.contains("bóng rổ") || combined.contains("n-b-a") || combined.contains("nba") || combined.contains("võng du") || combined.contains("game") -> "Võng Du / Thể Thao"
                combined.contains("cẩm y") || combined.contains("gia cát") || combined.contains("tây du") || combined.contains("tam quốc") || combined.contains("triều đình") || combined.contains("lịch sử") -> "Lịch Sử / Quân Sự"
                combined.contains("đồng nhân") || combined.contains("hỏa ảnh") || combined.contains("fanfic") -> "Đồng Nhân"
                else -> "Khác"
            }
        }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("title", title)
            put("author", author)
            put("completed", completed)
            put("total", total)
            put("status", status)
            put("message", message)
            put("model", model)
            put("cover_url", coverUrl)
            put("has_cover", hasCover)
            put("category", category)
            put("source", source)
            put("updated_at", updatedAt)
        }
    }
}

class ServerEpubRepository(private val context: Context, private val settings: TranslationSettings) {

    companion object {
        private val coverCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) { // 30MB in-memory cache
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    private val cacheFile: File
        get() = File(context.filesDir, "server_epubs_cache.json")

    private val coversDir: File
        get() = File(context.cacheDir, "covers").apply { if (!exists()) mkdirs() }

    fun getCachedList(): List<RemoteEpub> {
        return try {
            if (!cacheFile.exists() || cacheFile.length() == 0L) return emptyList()
            val text = cacheFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(text)
            List(jsonArray.length()) { parseJob(jsonArray.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveToCache(books: List<RemoteEpub>) {
        try {
            val jsonArray = JSONArray()
            books.forEach { jsonArray.put(it.toJson()) }
            cacheFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun list(saveCache: Boolean = true): List<RemoteEpub> {
        val raw = request("/v1/epub/jobs")
        if (raw == null) {
            return getCachedList()
        }
        return try {
            val jobs = JSONObject(raw).getJSONArray("jobs").let { array ->
                List(array.length()) { parseJob(array.getJSONObject(it)) }
            }
            if (saveCache && jobs.isNotEmpty()) {
                saveToCache(jobs)
            }
            jobs
        } catch (_: Exception) {
            getCachedList()
        }
    }

    fun getJob(id: String): RemoteEpub? {
        val raw = request("/v1/epub/jobs/$id") ?: return null
        return parseJob(JSONObject(raw))
    }

    fun export(id: String): RemoteEpub? {
        val raw = request("/v1/epub/jobs/$id/export", "POST") ?: request("/v1/epub/jobs/$id/export", "GET") ?: return null
        return runCatching { parseJob(JSONObject(raw)) }.getOrNull()
    }

    fun download(id: String, output: java.io.OutputStream) {
        val connection = open("/v1/epub/jobs/$id/download") ?: error("Không kết nối được server.")
        try {
            if (connection.responseCode !in 200..299) error("Server không có EPUB này (mã lỗi ${connection.responseCode}).")
            BufferedInputStream(connection.inputStream, 65536).use { input ->
                input.copyTo(output, bufferSize = 65536)
            }
            output.flush()
        } finally { connection.disconnect() }
    }

    fun loadCoverBitmap(jobId: String): Bitmap? {
        // 1. Kiểm tra RAM cache
        coverCache.get(jobId)?.let { return it }

        // 2. Kiểm tra Disk Cache (Bộ nhớ máy)
        val diskFile = File(coversDir, "$jobId.jpg")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    coverCache.put(jobId, bitmap)
                    return bitmap
                }
            } catch (_: Exception) {}
        }

        // 3. Tải từ Server mạng nếu chưa có trong đệm
        val connection = open("/v1/epub/jobs/$jobId/cover") ?: return null
        return try {
            if (connection.responseCode in 200..299) {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    try {
                        FileOutputStream(diskFile).use { it.write(bytes) }
                    } catch (_: Exception) {}
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        coverCache.put(jobId, bitmap)
                    }
                    bitmap
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun upload(name: String, model: String, input: java.io.InputStream, length: Long): RemoteEpub {
        require(name.endsWith(".epub", true) || name.endsWith(".txt", true)) {
            "Chỉ hỗ trợ tệp định dạng EPUB hoặc TXT."
        }
        require(length in 1..80L * 1024 * 1024) { "Tệp phải có dung lượng tối đa 80 MB." }
        val connection = open("/v1/epub/upload?name=${URLEncoder.encode(name, "UTF-8")}", "POST")
            ?: error("Không kết nối được server.")
        try {
            val contentType = if (name.endsWith(".txt", true)) "text/plain; charset=utf-8" else "application/epub+zip"
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("X-Translation-Model", model)
            connection.doOutput = true; connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { output -> input.copyTo(output) }
            if (connection.responseCode !in 200..299) error("Server từ chối tệp (mã lỗi: ${connection.responseCode}).")
            val row = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            return parseJob(row)
        } finally { connection.disconnect() }
    }

    private fun parseJob(row: JSONObject): RemoteEpub {
        return RemoteEpub(
            id = row.getString("id"),
            name = row.optString("name", "truyen.epub"),
            title = row.optString("title", ""),
            author = row.optString("author", ""),
            completed = row.optInt("completed", 0),
            total = row.optInt("total", 0),
            status = row.optString("status", "running"),
            message = row.optString("message", ""),
            model = row.optString("model", ""),
            coverUrl = row.optString("cover_url", ""),
            hasCover = row.optBoolean("has_cover", false),
            category = row.optString("category", ""),
            source = row.optString("source", ""),
            updatedAt = row.optLong("updated_at", 0L)
        )
    }

    private fun request(path: String, method: String = "GET"): String? {
        val connection = open(path, method) ?: return null
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        } finally { connection.disconnect() }
    }

    private fun open(path: String, method: String = "GET"): HttpURLConnection? {
        val candidates = mutableListOf<String>()
        val funnel = settings.funnelUrl.trim().replace(":8443", "")
        if (funnel.isNotBlank()) candidates.add(funnel.trimEnd('/') + path)
        val tailscale = settings.tailscaleUrl.trim()
        if (tailscale.isNotBlank()) candidates.add(tailscale.trimEnd('/') + path)
        if (funnel.startsWith("https://smart-tts.tail4cee0d.ts.net")) {
            candidates.add("https://smart-tts.tail4cee0d.ts.net:8443" + path)
        }

        val urls = candidates.filter { it.startsWith("https://") }.distinct()
        for (url in urls) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = if (path.contains("/download")) 180_000 else 30_000
                    requestMethod = method
                    setRequestProperty("Authorization", "Bearer ${settings.serverKey}")
                }
                if (method == "GET") {
                    val code = connection.responseCode
                    if (code in 200..299) return connection
                    connection.disconnect()
                    continue
                }
                return connection
            } catch (_: Exception) { }
        }
        return null
    }
}
