package com.ricardo.md2kindle.epub

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.OutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EpubMetadata(
    val title: String,
    val author: String = "",
    val language: String = "es",
    val identifier: String = "urn:uuid:${UUID.randomUUID()}",
    val modifiedEpochMillis: Long = System.currentTimeMillis(),
)

data class EpubAsset(
    val sourceName: String,
    val mediaType: String,
    val bytes: ByteArray,
)

data class EpubBook(
    val markdown: String,
    val metadata: EpubMetadata,
    val images: List<EpubAsset> = emptyList(),
    val cover: EpubAsset? = null,
)

data class EpubResult(
    val chapterCount: Int,
    val embeddedImageCount: Int,
    val warnings: List<String>,
)

class EpubGenerator {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .build()

    fun write(book: EpubBook, output: OutputStream): EpubResult {
        require(book.markdown.isNotBlank()) { "El documento Markdown está vacío." }
        require(book.metadata.title.isNotBlank()) { "El título no puede estar vacío." }

        val normalizedMarkdown = book.markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removePrefix("\uFEFF")

        val warnings = mutableListOf<String>()
        val chapters = splitIntoChapters(normalizedMarkdown, book.metadata.title)
        val parsedChapters = chapters.map { chapter ->
            ParsedChapter(chapter.title, parser.parse(chapter.markdown))
        }
        val imageBindings = resolveResources(parsedChapters, book.images, warnings)

        ZipOutputStream(output.buffered()).use { zip ->
            writeMimetype(zip)
            writeText(zip, "META-INF/container.xml", containerXml())
            writeText(zip, "EPUB/styles.css", stylesCss())

            book.cover?.let { cover ->
                writeBytes(zip, "EPUB/images/cover.${extensionFor(cover.mediaType)}", cover.bytes)
                writeText(
                    zip,
                    "EPUB/cover.xhtml",
                    coverXhtml(book.metadata.title, extensionFor(cover.mediaType)),
                )
            }

            imageBindings.forEach { binding ->
                    writeBytes(zip, "EPUB/${binding.targetName}", binding.asset.bytes)
            }

            parsedChapters.forEachIndexed { index, chapter ->
                writeText(
                    zip,
                    "EPUB/chapter_${index + 1}.xhtml",
                    chapterXhtml(
                        title = chapter.title,
                        body = renderer.render(chapter.document),
                        language = safeLanguage(book.metadata.language),
                    ),
                )
            }

            writeText(
                zip,
                "EPUB/nav.xhtml",
                navigationXhtml(book.metadata, chapters, book.cover != null),
            )
            writeText(zip, "EPUB/toc.ncx", tocNcx(book.metadata, chapters))
            writeText(
                zip,
                "EPUB/package.opf",
                packageOpf(
                    metadata = book.metadata,
                    chapters = chapters,
                    imageBindings = imageBindings,
                    cover = book.cover,
                ),
            )
        }

        return EpubResult(
            chapterCount = chapters.size,
            embeddedImageCount = imageBindings.size +
                if (book.cover != null) 1 else 0,
            warnings = warnings.distinct(),
        )
    }

    private fun resolveResources(
        chapters: List<ParsedChapter>,
        images: List<EpubAsset>,
        warnings: MutableList<String>,
    ): List<ImageBinding> {
        val supported = images.filter {
            it.mediaType in SUPPORTED_IMAGE_TYPES && it.bytes.isNotEmpty()
        }
        if (supported.size != images.size) {
            warnings += "Se omitieron imágenes vacías o con formato no compatible."
        }

        val usedAssets = mutableMapOf<String, ImageBinding>()
        chapters.forEach { chapter ->
            chapter.document.accept(object : AbstractVisitor() {
                override fun visit(image: Image) {
                    val rawTarget = image.destination.trim()
                    val alt = nodeText(image).ifBlank { "Imagen" }
                    if (rawTarget.isRemoteImage()) {
                        warnings += "Imagen remota omitida: $rawTarget"
                        replaceWithText(image, "$alt — imagen remota no incluida")
                        return
                    }

                    val lookup = normalizeLookupName(rawTarget)
                    val asset = supported.firstOrNull {
                        normalizeLookupName(it.sourceName) == lookup
                    }
                    if (asset == null) {
                        warnings += "Imagen no encontrada: $rawTarget"
                        replaceWithText(image, "$alt — imagen no encontrada")
                        return
                    }

                    val assetKey =
                        "${normalizeLookupName(asset.sourceName)}:${asset.bytes.contentHashCode()}"
                    val binding = usedAssets.getOrPut(assetKey) {
                        val index = usedAssets.size + 1
                        ImageBinding(
                            asset = asset,
                            targetName = "images/image_$index.${extensionFor(asset.mediaType)}",
                        )
                    }
                    image.destination = binding.targetName
                }

                override fun visit(link: Link) {
                    val target = link.destination.trim()
                    if (target.isUnpackagedLocalLink()) {
                        warnings += "Enlace local omitido: $target"
                        replaceWithText(link, nodeText(link))
                    } else {
                        visitChildren(link)
                    }
                }
            })
        }
        return usedAssets.values.toList()
    }

    private fun splitIntoChapters(markdown: String, fallbackTitle: String): List<Chapter> {
        val lines = markdown.lines()
        val headingIndexes = lines.mapIndexedNotNull { index, line ->
            H1_PATTERN.matchEntire(line.trim())?.let { index }
        }
        if (headingIndexes.isEmpty()) {
            return listOf(Chapter(fallbackTitle, markdown.trim()))
        }

        val chapters = mutableListOf<Chapter>()
        val prefix = lines.subList(0, headingIndexes.first()).joinToString("\n").trim()
        if (prefix.isNotBlank()) {
            chapters += Chapter("Introducción", prefix)
        }

        headingIndexes.forEachIndexed { position, start ->
            val end = headingIndexes.getOrNull(position + 1) ?: lines.size
            val heading = H1_PATTERN.matchEntire(lines[start].trim())
                ?.groupValues
                ?.get(1)
                ?.let(::plainHeading)
                ?.ifBlank { null }
                ?: "Capítulo ${position + 1}"
            chapters += Chapter(
                title = heading,
                markdown = lines.subList(start, end).joinToString("\n").trim(),
            )
        }
        return chapters.ifEmpty { listOf(Chapter(fallbackTitle, markdown.trim())) }
    }

    private fun packageOpf(
        metadata: EpubMetadata,
        chapters: List<Chapter>,
        imageBindings: List<ImageBinding>,
        cover: EpubAsset?,
    ): String {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(metadata.modifiedEpochMillis))
        val coverManifest = if (cover == null) {
            ""
        } else {
            """
            <item id="cover-image" href="images/cover.${extensionFor(cover.mediaType)}" media-type="${xml(cover.mediaType)}" properties="cover-image"/>
            <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>
            """.trimIndent()
        }
        val imageManifest = imageBindings.mapIndexed { index, binding ->
            """<item id="image-${index + 1}" href="${xml(binding.targetName)}" media-type="${xml(binding.asset.mediaType)}"/>"""
        }.joinToString("\n")
        val chapterManifest = chapters.indices.joinToString("\n") { index ->
            """<item id="chapter-${index + 1}" href="chapter_${index + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val coverSpine = if (cover == null) "" else """<itemref idref="cover-page" linear="no"/>"""
        val chapterSpine = chapters.indices.joinToString("\n") { index ->
            """<itemref idref="chapter-${index + 1}"/>"""
        }
        val creator = metadata.author.trim().takeIf { it.isNotEmpty() }?.let {
            """<dc:creator id="creator">${xml(it)}</dc:creator>"""
        }.orEmpty()
        val legacyCover = if (cover == null) "" else """<meta name="cover" content="cover-image"/>"""

        return xmlDocument(
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" prefix="rendition: http://www.idpf.org/vocab/rendition/#">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">${xml(metadata.identifier)}</dc:identifier>
                <dc:title>${xml(metadata.title)}</dc:title>
                $creator
                <dc:language>${xml(safeLanguage(metadata.language))}</dc:language>
                <meta property="dcterms:modified">$modified</meta>
                $legacyCover
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="styles" href="styles.css" media-type="text/css"/>
                $coverManifest
                $imageManifest
                $chapterManifest
              </manifest>
              <spine toc="ncx">
                $coverSpine
                $chapterSpine
              </spine>
            </package>
            """.trimIndent(),
        )
    }

    private fun navigationXhtml(
        metadata: EpubMetadata,
        chapters: List<Chapter>,
        hasCover: Boolean,
    ): String {
        val items = chapters.mapIndexed { index, chapter ->
            """<li><a href="chapter_${index + 1}.xhtml">${xml(chapter.title)}</a></li>"""
        }.joinToString("\n")
        val landmarks = if (hasCover) {
            """
            <nav epub:type="landmarks" hidden="hidden">
              <h2>Guía</h2>
              <ol>
                <li><a epub:type="cover" href="cover.xhtml">Portada</a></li>
              </ol>
            </nav>
            """.trimIndent()
        } else {
            ""
        }
        return xhtmlDocument(
            title = "Índice",
            language = safeLanguage(metadata.language),
            body = """
                <nav epub:type="toc" id="toc">
                  <h1>Índice</h1>
                  <ol>
                    $items
                  </ol>
                </nav>
                $landmarks
            """.trimIndent(),
            extraNamespace = """ xmlns:epub="http://www.idpf.org/2007/ops"""",
        )
    }

    private fun tocNcx(metadata: EpubMetadata, chapters: List<Chapter>): String {
        val navPoints = chapters.mapIndexed { index, chapter ->
            """
            <navPoint id="navPoint-${index + 1}" playOrder="${index + 1}">
              <navLabel><text>${xml(chapter.title)}</text></navLabel>
              <content src="chapter_${index + 1}.xhtml"/>
            </navPoint>
            """.trimIndent()
        }.joinToString("\n")
        return xmlDocument(
            """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head>
                <meta name="dtb:uid" content="${xml(metadata.identifier)}"/>
                <meta name="dtb:depth" content="1"/>
                <meta name="dtb:totalPageCount" content="0"/>
                <meta name="dtb:maxPageNumber" content="0"/>
              </head>
              <docTitle><text>${xml(metadata.title)}</text></docTitle>
              <navMap>
                $navPoints
              </navMap>
            </ncx>
            """.trimIndent(),
        )
    }

    private fun chapterXhtml(title: String, body: String, language: String): String =
        xhtmlDocument(title, language, """<main>$body</main>""")

    private fun coverXhtml(title: String, extension: String): String =
        xhtmlDocument(
            title = "Portada",
            language = "es",
            body = """
                <section class="cover">
                  <img src="images/cover.$extension" alt="Portada de ${xml(title)}"/>
                </section>
            """.trimIndent(),
        )

    private fun xhtmlDocument(
        title: String,
        language: String,
        body: String,
        extraNamespace: String = "",
    ): String = xmlDocument(
        """
        <html xmlns="http://www.w3.org/1999/xhtml"$extraNamespace xml:lang="${xml(language)}" lang="${xml(language)}">
          <head>
            <title>${xml(title)}</title>
            <meta charset="UTF-8"/>
            <link rel="stylesheet" type="text/css" href="styles.css"/>
          </head>
          <body>
            $body
          </body>
        </html>
        """.trimIndent(),
    )

    private fun containerXml(): String = xmlDocument(
        """
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent(),
    )

    private fun stylesCss(): String = """
        body {
          font-family: serif;
          line-height: 1.45;
          margin: 5%;
          text-align: left;
        }
        h1, h2, h3, h4, h5, h6 {
          font-family: sans-serif;
          line-height: 1.2;
          page-break-after: avoid;
        }
        h1 { page-break-before: always; }
        img { max-width: 100%; height: auto; }
        blockquote {
          border-left: 0.2em solid #777;
          margin-left: 0.5em;
          padding-left: 0.8em;
        }
        pre, code { font-family: monospace; }
        pre {
          white-space: pre-wrap;
          border: 1px solid #aaa;
          padding: 0.6em;
        }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #777; padding: 0.3em; }
        .cover {
          text-align: center;
          margin: 0;
          padding: 0;
        }
        .cover img { max-height: 95%; }
    """.trimIndent()

    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = EPUB_MIMETYPE.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            time = ZIP_TIME
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeText(zip: ZipOutputStream, path: String, value: String) =
        writeBytes(zip, path, value.toByteArray(Charsets.UTF_8))

    private fun writeBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = ZIP_TIME }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun plainHeading(value: String): String = value
        .replace(Regex("""\[(.+?)]\(.+?\)"""), "$1")
        .replace(Regex("""[*_`~]"""), "")
        .trim()

    private fun String.isRemoteImage(): Boolean =
        startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("data:", ignoreCase = true)

    private fun normalizeLookupName(value: String): String =
        Normalizer.normalize(
            decodePercentEncoding(
                value.trim()
                    .removeSurrounding("<", ">")
                    .substringBefore('#')
                    .substringBefore('?')
                    .replace('\\', '/')
                    .substringAfterLast('/'),
            ),
            Normalizer.Form.NFD,
        )
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()

    private fun decodePercentEncoding(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)

    private fun String.isUnpackagedLocalLink(): Boolean {
        if (isBlank() || startsWith("#")) return false
        if (startsWith("//")) return false
        val scheme = substringBefore(':', missingDelimiterValue = "")
        return scheme.isBlank() || scheme.equals("file", true) || scheme.equals("content", true)
    }

    private fun replaceWithText(node: Node, value: String) {
        node.insertBefore(Text(value.ifBlank { "Contenido omitido" }))
        node.unlink()
    }

    private fun nodeText(node: Node): String {
        val text = StringBuilder()
        fun collect(current: Node?) {
            var child = current
            while (child != null) {
                when (child) {
                    is Text -> text.append(child.literal)
                    is Code -> text.append(child.literal)
                    else -> collect(child.firstChild)
                }
                child = child.next
            }
        }
        collect(node.firstChild)
        return text.toString().trim()
    }

    private fun extensionFor(mediaType: String): String = when (mediaType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        else -> "png"
    }

    private fun safeLanguage(language: String): String =
        language.trim().lowercase().takeIf { it.matches(Regex("""[a-z]{2,3}(-[a-z0-9]{2,8})*""")) }
            ?: "es"

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlDocument(content: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + content

    private data class Chapter(val title: String, val markdown: String)
    private data class ParsedChapter(val title: String, val document: Node)

    private data class ImageBinding(
        val asset: EpubAsset,
        val targetName: String,
    )

    companion object {
        const val EPUB_MIMETYPE = "application/epub+zip"
        private const val ZIP_TIME = 315_532_800_000L
        private val H1_PATTERN = Regex("""^#\s+(.+?)\s*#*\s*$""")
        private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif")
    }
}
