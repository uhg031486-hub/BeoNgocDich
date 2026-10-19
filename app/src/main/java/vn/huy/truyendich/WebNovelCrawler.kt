package vn.huy.truyendich

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.regex.Pattern

data class CrawledChapter(val title: String, val content: String)

object WebNovelCrawler {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val CHAPTER_LINK_REGEX = Pattern.compile(
        "<a\\s+[^>]*href=[\"'](?<href>[^\"']+)[\"'][^>]*>(?<text>.*?)</a>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val CHAPTER_NAME_REGEX = Pattern.compile(
        "(Chương|Chapter|第[0-9一二三四五六七八九十百千万]+[章回节]|第\\s*\\d+\\s*章|^\\s*\\d+[\\.\\s\\:\\-])",
        Pattern.CASE_INSENSITIVE
    )

    private val NEXT_CHAPTER_REGEX = Pattern.compile(
        "<a\\s+[^>]*href=[\"'](?<href>[^\"']+)[\"'][^>]*>(.*?)(下一章|下一页|Chương sau|Next|Sau)(.*?)</a>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    fun crawl(
        context: Context,
        rawUrl: String,
        maxChapters: Int = 30,
        isCancelled: () -> Boolean,
        onProgress: (current: Int, total: Int, chapterTitle: String) -> Unit
    ): File {
        val targetUrl = rawUrl.trim()
        require(targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            "URL phải bắt đầu bằng http:// hoặc https://"
        }

        onProgress(0, 1, "Đang kết nối trang nguồn...")
        val (initialHtml, charset) = fetchHtml(targetUrl)
        if (isCancelled()) error("Đã hủy quá trình tải.")

        val bookTitle = extractBookTitle(initialHtml, targetUrl)
        val chapterLinks = extractChapterLinks(initialHtml, targetUrl)

        val chapters = mutableListOf<CrawledChapter>()

        if (chapterLinks.size >= 3) {
            // Table of Contents detected
            val linksToDownload = if (maxChapters > 0) chapterLinks.take(maxChapters) else chapterLinks
            val total = linksToDownload.size

            linksToDownload.forEachIndexed { index, link ->
                if (isCancelled()) error("Đã hủy quá trình tải.")
                onProgress(index + 1, total, link.second)

                try {
                    val (chapHtml, chapCharset) = fetchHtml(link.first)
                    val title = extractChapterTitle(chapHtml, link.second)
                    val body = extractChapterBody(chapHtml)
                    if (body.isNotBlank()) {
                        chapters.add(CrawledChapter(title, body))
                    }
                } catch (e: Exception) {
                    // If single chapter fails, record placeholder and continue
                    chapters.add(CrawledChapter(link.second, "[Không tải được chương này: ${e.message}]"))
                }
                Thread.sleep(100) // Brief pause to be polite to servers
            }
        } else {
            // Single chapter URL mode: follow "Next Chapter" links
            var currentUrl = targetUrl
            var currentHtml = initialHtml
            var count = 0
            val targetTotal = if (maxChapters > 0) maxChapters else 50

            while (count < targetTotal && currentUrl.isNotBlank()) {
                if (isCancelled()) error("Đã hủy quá trình tải.")
                count++
                val title = extractChapterTitle(currentHtml, "Chương $count")
                onProgress(count, targetTotal, title)

                val body = extractChapterBody(currentHtml)
                if (body.isNotBlank()) {
                    chapters.add(CrawledChapter(title, body))
                }

                val nextUrl = findNextChapterUrl(currentHtml, currentUrl)
                if (nextUrl == null || nextUrl == currentUrl || nextUrl.contains("javascript:")) break
                currentUrl = nextUrl

                Thread.sleep(100)
                try {
                    val (nextHtml, _) = fetchHtml(currentUrl)
                    currentHtml = nextHtml
                } catch (e: Exception) {
                    break
                }
            }
        }

        if (chapters.isEmpty()) {
            error("Không trích xuất được chương nào từ link này. Vui lòng kiểm tra lại URL hoặc cấu trúc trang.")
        }

        // Save to TXT file
        val safeFileName = bookTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifEmpty { "truyen_web_${System.currentTimeMillis()}" }
        val outputFile = File(context.cacheDir, "$safeFileName.txt")

        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            chapters.forEachIndexed { idx, ch ->
                writer.write(ch.title)
                writer.newLine()
                writer.newLine()
                writer.write(ch.content)
                writer.newLine()
                writer.newLine()
            }
        }

        return outputFile
    }

    private fun fetchHtml(urlString: String): Pair<String, Charset> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,vi;q=0.8,en;q=0.7")
        }

        val rawBytes = conn.inputStream.use { it.readBytes() }
        val contentType = conn.contentType ?: ""
        var charset = detectCharset(contentType, rawBytes)

        val html = String(rawBytes, charset)
        return Pair(html, charset)
    }

    private fun detectCharset(contentType: String, rawBytes: ByteArray): Charset {
        val headerMatcher = Pattern.compile("charset=([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType)
        if (headerMatcher.find()) {
            val name = headerMatcher.group(1)?.trim()
            if (!name.isNullOrBlank()) {
                runCatching { return Charset.forName(name) }
            }
        }

        // Peek first 2048 bytes for <meta charset="..."> or <meta http-equiv="Content-Type" content="...; charset=...">
        val peek = String(rawBytes.take(2048).toByteArray(), Charsets.US_ASCII)
        val metaMatcher = Pattern.compile("charset=[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(peek)
        if (metaMatcher.find()) {
            val name = metaMatcher.group(1)?.trim()
            if (!name.isNullOrBlank()) {
                runCatching { return Charset.forName(name) }
            }
        }

        return Charsets.UTF_8
    }

    private fun extractBookTitle(html: String, fallbackUrl: String): String {
        // Try og:title
        val ogMatcher = Pattern.compile("<meta\\s+[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogMatcher.find()) return cleanHtmlText(ogMatcher.group(1) ?: "")

        // Try <h1>
        val h1Matcher = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (h1Matcher.find()) {
            val t = cleanHtmlText(h1Matcher.group(1) ?: "")
            if (t.isNotBlank() && t.length <= 100) return t
        }

        // Try <title>
        val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (titleMatcher.find()) {
            val raw = cleanHtmlText(titleMatcher.group(1) ?: "")
            return raw.split(Regex("[-_|]"))[0].trim().ifBlank { "truyen_web" }
        }

        return "truyen_web_${System.currentTimeMillis()}"
    }

    private fun extractChapterLinks(html: String, baseUrl: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val matcher = CHAPTER_LINK_REGEX.matcher(html)
        val seenUrls = mutableSetOf<String>()

        while (matcher.find()) {
            val href = matcher.group("href")?.trim() ?: continue
            val text = cleanHtmlText(matcher.group("text") ?: "")

            if (CHAPTER_NAME_REGEX.matcher(text).find() || (text.isNotBlank() && text.length in 3..60 && results.size >= 2)) {
                val resolved = resolveUrl(baseUrl, href)
                if (resolved.isNotBlank() && !seenUrls.contains(resolved) && !resolved.endsWith(".css") && !resolved.endsWith(".js")) {
                    seenUrls.add(resolved)
                    results.add(Pair(resolved, text))
                }
            }
        }
        return results
    }

    private fun extractChapterTitle(html: String, fallback: String): String {
        val h1Matcher = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (h1Matcher.find()) {
            val t = cleanHtmlText(h1Matcher.group(1) ?: "")
            if (t.isNotBlank()) return t
        }
        return fallback
    }

    private fun extractChapterBody(html: String): String {
        // Look for typical content containers: id="content", class="content", id="chaptercontent", id="txt"
        val containerPatterns = listOf(
            "<div[^>]+id=[\"'](content|txt|chaptercontent|htmlContent|booktxt|chapterContent|readcontent)[\"'][^>]*>(?<bodycontent>.*?)</div>",
            "<div[^>]+class=[\"'](content|read-content|text-content|chapter-content)[\"'][^>]*>(?<bodycontent>.*?)</div>"
        )

        for (patternStr in containerPatterns) {
            val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
            if (matcher.find()) {
                val rawBody = matcher.group("bodycontent") ?: ""
                val cleaned = convertHtmlToText(rawBody)
                if (cleaned.length >= 100) return cleaned
            }
        }

        // Fallback: extract from largest text segment
        return convertHtmlToText(html)
    }

    private fun findNextChapterUrl(html: String, baseUrl: String): String? {
        val matcher = NEXT_CHAPTER_REGEX.matcher(html)
        if (matcher.find()) {
            val href = matcher.group("href")?.trim() ?: return null
            return resolveUrl(baseUrl, href)
        }
        return null
    }

    private fun convertHtmlToText(rawHtml: String): String {
        var s = rawHtml
        // Remove script, style, comments
        s = s.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        s = s.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Replace <br> and <p> with newlines
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")

        // Strip remaining HTML tags
        s = s.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        s = unescapeHtml(s)

        // Clean excess blank lines
        val lines = s.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Filter out common ad watermarks
            .filterNot { it.contains("69shu", true) || it.contains("uukanshu", true) || it.contains("biquge", true) && it.length < 40 }

        return lines.joinToString("\n\n")
    }

    private fun cleanHtmlText(text: String): String {
        return unescapeHtml(text.replace(Regex("<[^>]+>"), "")).trim()
    }

    private fun unescapeHtml(input: String): String {
        return input.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else match.value
            }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(relativeUrl).toString()
        } catch (_: Exception) {
            relativeUrl
        }
    }
}
