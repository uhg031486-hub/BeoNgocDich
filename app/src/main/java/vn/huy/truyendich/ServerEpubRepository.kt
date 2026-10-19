package vn.huy.truyendich

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteEpub(val id: String, val name: String, val completed: Int, val total: Int)

class ServerEpubRepository(private val context: Context, private val settings: TranslationSettings) {
    fun list(): List<RemoteEpub> {
        val raw = request("/v1/epub/jobs") ?: return emptyList()
        val jobs = JSONObject(raw).getJSONArray("jobs").let { array -> List(array.length()) { array.getJSONObject(it) } }
        return jobs.map { RemoteEpub(it.getString("id"), it.optString("name", "truyen.epub"), it.optInt("completed"), it.optInt("total")) }
    }

    fun download(id: String, output: java.io.OutputStream) {
        val connection = open("/v1/epub/jobs/$id/download") ?: error("Không kết nối được server.")
        try {
            if (connection.responseCode !in 200..299) error("Server không có EPUB này.")
            BufferedInputStream(connection.inputStream).use { input -> input.copyTo(output) }
        } finally { connection.disconnect() }
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
            return RemoteEpub(row.getString("id"), row.optString("name", name), row.optInt("completed"), row.optInt("total"))
        } finally { connection.disconnect() }
    }

    private fun request(path: String): String? {
        val connection = open(path) ?: return null
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        } finally { connection.disconnect() }
    }

    private fun open(path: String, method: String = "GET"): HttpURLConnection? {
        // Funnel is public HTTPS and therefore works for phones without Tailscale.
        // Ignore legacy HTTP Tailscale settings: Android blocks cleartext traffic.
        val urls = listOf(settings.funnelUrl, settings.tailscaleUrl)
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .map { it.trimEnd('/') + path }
            .distinct()
        for (url in urls) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000; readTimeout = 90_000; requestMethod = method
                    setRequestProperty("Authorization", "Bearer ${settings.serverKey}")
                }
                // GET can connect now to fall back from Tailscale. POST must wait
                // until its EPUB body is written, otherwise Android sends an empty POST.
                if (method == "GET") connection.responseCode
                return connection
            } catch (_: Exception) { }
        }
        return null
    }
}
