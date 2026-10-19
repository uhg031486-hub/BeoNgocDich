package vn.huy.truyendich

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TxtToEpub {
    data class Chapter(val title: String, val paragraphs: List<String>)

    private val CHAPTER_REGEX = Pattern.compile(
        "^\\s*(Chương|Hồi|Tiết|Chapter|第[0-9一二三四五六七八九十百千万]+[章回节]|第\\s*\\d+\\s*章).{0,50}$",
        Pattern.CASE_INSENSITIVE
    )

    fun convert(inputStream: InputStream, title: String, outputFile: File) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val chapters = mutableListOf<Chapter>()
        var currentTitle = "Mở đầu"
        val currentParas = mutableListOf<String>()
        var currentLen = 0
        var isFirstLine = true

        reader.useLines { lines ->
            for (rawLine in lines) {
                var line = rawLine
                if (isFirstLine) {
                    line = line.removePrefix("\uFEFF")
                    isFirstLine = false
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val isChapterHeader = CHAPTER_REGEX.matcher(trimmed).matches()
                if (isChapterHeader || (currentLen >= 3500 && currentParas.size >= 5)) {
                    if (currentParas.isNotEmpty()) {
                        chapters.add(Chapter(currentTitle, ArrayList(currentParas)))
                        currentParas.clear()
                        currentLen = 0
                    }
                    if (isChapterHeader) {
                        currentTitle = trimmed
                        continue
                    } else {
                        currentTitle = "Phần ${chapters.size + 1}"
                    }
                }
                currentParas.add(trimmed)
                currentLen += trimmed.length
            }
        }

        if (currentParas.isNotEmpty()) {
            chapters.add(Chapter(currentTitle, ArrayList(currentParas)))
        }
        if (chapters.isEmpty()) {
            chapters.add(Chapter("Chương 1", listOf("Nội dung trống")))
        }

        FileOutputStream(outputFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                // 1. mimetype (MUST be first entry and STORED uncompressed)
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(mimeBytes) }
                val mimeEntry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    this.crc = crc.value
                }
                zos.putNextEntry(mimeEntry)
                zos.write(mimeBytes)
                zos.closeEntry()

                // 2. META-INF/container.xml
                val containerXml = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent().toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(containerXml)
                zos.closeEntry()

                // 3. Chapters
                val manifestItems = StringBuilder()
                val spineItems = StringBuilder()

                chapters.forEachIndexed { idx, chap ->
                    val id = "c${idx + 1}"
                    val href = "$id.xhtml"
                    manifestItems.append("""<item id="$id" href="$href" media-type="application/xhtml+xml"/>""")
                    spineItems.append("""<itemref idref="$id"/>""")

                    val bodyBuilder = StringBuilder()
                    bodyBuilder.append("<h2>").append(escapeXml(chap.title)).append("</h2>\n")
                    for (p in chap.paragraphs) {
                        bodyBuilder.append("<p>").append(escapeXml(p)).append("</p>\n")
                    }

                    val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escapeXml(chap.title)}</title></head>
<body>
$bodyBuilder</body>
</html>""".toByteArray(Charsets.UTF_8)

                    zos.putNextEntry(ZipEntry("OEBPS/$href"))
                    zos.write(xhtml)
                    zos.closeEntry()
                }

                // 4. OEBPS/content.opf
                val safeTitle = escapeXml(title)
                val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$safeTitle</dc:title>
    <dc:language>vi</dc:language>
  </metadata>
  <manifest>
    $manifestItems
  </manifest>
  <spine>
    $spineItems
  </spine>
</package>""".toByteArray(Charsets.UTF_8)

                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zos.write(opf)
                zos.closeEntry()
            }
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
