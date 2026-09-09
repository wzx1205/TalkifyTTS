package com.github.lonepheasantwarrior.talkify.book.epub

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * EPUB 解析结果
 *
 * @param bookId 内容哈希（重导入同一本书可复用，重新扫描可覆盖）
 * @param title 书名（OPF dc:title，缺失时回退首章标题）
 * @param chapters 按 spine 顺序的章节纯文本
 */
data class EpubBook(
    val bookId: String,
    val title: String,
    val chapters: List<String>
)

/**
 * 轻量 EPUB 解析器（零依赖，zip + 正则提文本）
 *
 * 只服务「全书角色扫描」：按 OPF spine 顺序抽取 XHTML 纯文本，
 * 不保留样式/图片。足够健壮：container.xml 或 OPF 异常时回退
 * 「全部 xhtml/html 按 zip 内文件名排序」。
 */
object EpubParser {

    private val DOC_EXT = Regex("\\.(xhtml|html|htm)$", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): EpubBook {
        val entries = readZipEntries(input)
        require(entries.isNotEmpty()) { "EPUB 为空或不是合法 zip" }

        val opfPath = findOpfPath(entries)
        val docPaths = orderedDocPaths(entries, opfPath)

        val chapters = docPaths.mapNotNull { path ->
            val bytes = entries[path] ?: return@mapNotNull null
            val text = xhtmlToText(String(bytes, Charsets.UTF_8))
            if (text.length < 20) null else text
        }
        require(chapters.isNotEmpty()) { "EPUB 中未找到有效章节内容" }

        val title = entries[opfPath]?.let { extractTitle(String(it, Charsets.UTF_8)) }
            ?: extractFirstHeading(chapters.first())
            ?: "未命名"

        return EpubBook(
            bookId = contentHash(title, chapters),
            title = title,
            chapters = chapters
        )
    }

    // ---- zip ----

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val wanted = Regex(
            "(^META-INF/container\\.xml$|\\.opf$|\\.(xhtml|html|htm)$)",
            RegexOption.IGNORE_CASE
        )
        val map = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 72MB 级 EPUB 的图片/字体占大头，按扩展名先过滤再读，避免整本载入内存
                if (!entry.isDirectory && wanted.containsMatchIn(entry.name)) {
                    map[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        return map
    }

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val container = entries["META-INF/container.xml"] ?: return null
        val m = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']")
            .find(String(container, Charsets.UTF_8))
        return m?.groupValues?.get(1)
    }

    /** spine 顺序；OPF 缺失/解析失败回退文件名排序 */
    private fun orderedDocPaths(entries: Map<String, ByteArray>, opfPath: String?): List<String> {
        val allDocs = entries.keys.filter { DOC_EXT.containsMatchIn(it) }
        if (opfPath == null) return allDocs.sorted()

        val opf = entries[opfPath]?.let { String(it, Charsets.UTF_8) } ?: return allDocs.sorted()
        val opfDir = opf.substringBeforeLast('/', "")

        val hrefById = HashMap<String, String>()
        Regex("<item\\b[^>]*/?>").findAll(opf).forEach { tag ->
            val id = attr(tag.value, "id")
            val href = attr(tag.value, "href")
            if (id != null && href != null) hrefById[id] = href
        }

        val spineIds = Regex("<itemref\\b[^>]*/?>").findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .toList()

        val ordered = spineIds.mapNotNull { id ->
            hrefById[id]?.let { resolvePath(opfDir, it) }
        }.filter { DOC_EXT.containsMatchIn(it) }

        // spine 外散落的文档（封面页等）附在末尾，避免漏扫
        val rest = allDocs.filter { it !in ordered }.sorted()
        return ordered + rest
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val decoded = href.replace("%20", " ").trimStart('/')
        return if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
    }

    // ---- XHTML → 纯文本 ----

    internal fun xhtmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace(Regex("[\\t\\u3000]+"), " ")
        s = s.replace(Regex(" ?\n ?"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String {
        var r = s
        r = r.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        r = r.replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toInt().toChar().toString()
        }
        return r
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&mdash;", "—")
            .replace("&hellip;", "…")
    }

    // ---- 元数据 ----

    private fun extractTitle(opf: String?): String? {
        opf ?: return null
        val m = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(opf) ?: return null
        return decodeEntities(m.groupValues[1]).trim().take(64).ifEmpty { null }
    }

    private fun extractFirstHeading(firstChapter: String): String? {
        val m = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>").find(firstChapter) ?: return null
        val text = xhtmlToText(m.groupValues[1])
        return text.take(64).ifEmpty { null }
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag) ?: return null
        return m.groupValues[1]
    }

    private fun contentHash(title: String, chapters: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(title.toByteArray(Charsets.UTF_8))
        chapters.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
