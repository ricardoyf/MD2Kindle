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
    private val chapterDetector = ChapterDetector()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .build()

    fun detectTableOfContents(markdown: String, fallbackTitle: String): List<TocPreviewEntry> =
        chapterDetector.previews(markdown, fallbackTitle)

    fun write(book: EpubBook, output: OutputStream): EpubResult {
        require(book.markdown.isNotBlank()) { "El documento Markdown está vacío." }
        require(book.metadata.title.isNotBlank()) { "El título no puede estar vacío." }

        val normalizedMarkdown = book.markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removePrefix("\uFEFF")

        val warnings = mutableListOf<String>()
        val structure = chapterDetector.detectBook(normalizedMarkdown, book.metadata.title)
        val parsedFrontMatter = structure.frontMatterMarkdown?.let(parser::parse)
        val parsedChapters = structure.chapters.map { chapter ->
            ParsedChapter(chapter, parser.parse(chapter.markdown))
        }
        val imageBindings = resolveResources(
            frontMatter = parsedFrontMatter,
            chapters = parsedChapters,
            images = book.images,
            warnings = warnings,
        )

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

            parsedFrontMatter?.let { document ->
                writeText(
                    zip,
                    "EPUB/frontmatter.xhtml",
                    chapterXhtml(
                        title = book.metadata.title,
                        body = renderer.render(document),
                        language = safeLanguage(book.metadata.language),
                        anchorId = "portada-interior",
                    ),
                )
            }

            parsedChapters.forEachIndexed { index, chapter ->
                writeText(
                    zip,
                    "EPUB/chapter_${index + 1}.xhtml",
                    chapterXhtml(
                        title = chapter.detected.title,
                        body = renderer.render(chapter.document),
                        language = safeLanguage(book.metadata.language),
                        anchorId = chapter.detected.anchorId,
                    ),
                )
            }

            writeText(
                zip,
                "EPUB/contents.xhtml",
                visibleContentsXhtml(book.metadata, structure.chapters),
            )
            writeText(
                zip,
                "EPUB/nav.xhtml",
                navigationXhtml(book.metadata, structure.chapters, book.cover != null),
            )
            writeText(zip, "EPUB/toc.ncx", tocNcx(book.metadata, structure.chapters))
            writeText(
                zip,
                "EPUB/package.opf",
                packageOpf(
                    metadata = book.metadata,
                    chapters = structure.chapters,
                    imageBindings = imageBindings,
                    cover = book.cover,
                    hasFrontMatter = parsedFrontMatter != null,
                ),
            )
        }

        return EpubResult(
            chapterCount = structure.chapters.size,
            embeddedImageCount = imageBindings.size +
                if (book.cover != null) 1 else 0,
            warnings = warnings.distinct(),
        )
    }

    private fun resolveResources(
        frontMatter: Node?,
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
        val aliases = buildMap<String, String> {
            chapters.forEachIndexed { index, chapter ->
                val target = "chapter_${index + 1}.xhtml#${chapter.detected.anchorId}"
                chapter.detected.anchorAliases.forEach { alias -> putIfAbsent(alias, target) }
                putIfAbsent(chapter.detected.anchorId, target)
            }
        }
        val documents = buildList {
            frontMatter?.let(::add)
            addAll(chapters.map { it.document })
        }
        documents.forEach { document ->
            document.accept(object : AbstractVisitor() {
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
                    if (target.startsWith("#")) {
                        val alias = chapterDetector.slug(
                            decodePercentEncoding(target.removePrefix("#")),
                        )
                        val destination = aliases[alias]
                        if (destination == null) {
                            warnings += "Destino interno no encontrado: $target"
                            replaceWithText(link, nodeText(link))
                        } else {
                            link.destination = destination
                            visitChildren(link)
                        }
                    } else if (target.isUnpackagedLocalLink()) {
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

    private fun packageOpf(
        metadata: EpubMetadata,
        chapters: List<DetectedChapter>,
        imageBindings: List<ImageBinding>,
        cover: EpubAsset?,
        hasFrontMatter: Boolean,
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
        val frontMatterManifest = if (hasFrontMatter) {
            """<item id="frontmatter" href="frontmatter.xhtml" media-type="application/xhtml+xml"/>"""
        } else {
            ""
        }
        val coverSpine = if (cover == null) "" else """<itemref idref="cover-page" linear="no"/>"""
        val frontMatterSpine = if (hasFrontMatter) {
            """<itemref idref="frontmatter"/>"""
        } else {
            ""
        }
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
                <item id="contents" href="contents.xhtml" media-type="application/xhtml+xml"/>
                $coverManifest
                $frontMatterManifest
                $imageManifest
                $chapterManifest
              </manifest>
              <spine toc="ncx">
                $coverSpine
                $frontMatterSpine
                <itemref idref="contents"/>
                $chapterSpine
              </spine>
            </package>
            """.trimIndent(),
        )
    }

    private fun navigationXhtml(
        metadata: EpubMetadata,
        chapters: List<DetectedChapter>,
        hasCover: Boolean,
    ): String {
        val items = navigationList(chapters)
        val coverLandmark = if (hasCover) {
            """<li><a epub:type="cover" href="cover.xhtml">Portada</a></li>"""
        } else ""
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
                <nav epub:type="landmarks" hidden="hidden">
                  <h2>Guía</h2>
                  <ol>
                    $coverLandmark
                    <li><a epub:type="toc" href="contents.xhtml">Índice</a></li>
                    <li><a epub:type="bodymatter" href="chapter_1.xhtml#${xml(chapters.first().anchorId)}">Comienzo</a></li>
                  </ol>
                </nav>
            """.trimIndent(),
            extraNamespace = """ xmlns:epub="http://www.idpf.org/2007/ops"""",
        )
    }

    private fun visibleContentsXhtml(
        metadata: EpubMetadata,
        chapters: List<DetectedChapter>,
    ): String = xhtmlDocument(
        title = "Índice",
        language = safeLanguage(metadata.language),
        body = """
            <nav epub:type="toc" id="visible-toc">
              <h1>Índice</h1>
              <ol>
                ${navigationList(chapters)}
              </ol>
            </nav>
        """.trimIndent(),
        extraNamespace = """ xmlns:epub="http://www.idpf.org/2007/ops"""",
    )

    private fun navigationList(chapters: List<DetectedChapter>): String {
        val roots = tocRoots(chapters)
        return roots.joinToString("\n") { root ->
            val children = if (root.children.isEmpty()) {
                ""
            } else {
                root.children.joinToString(
                    prefix = "\n<ol>\n",
                    postfix = "\n</ol>",
                    separator = "\n",
                ) { child ->
                    """<li><a href="${xml(child.href)}">${xml(child.title)}</a></li>"""
                }
            }
            """<li><a href="${xml(root.href)}">${xml(root.title)}</a>$children</li>"""
        }
    }

    private fun tocNcx(metadata: EpubMetadata, chapters: List<DetectedChapter>): String {
        var playOrder = 0
        fun navPoint(item: TocItem): String {
            playOrder += 1
            val currentOrder = playOrder
            val children = item.children.joinToString("\n") { navPoint(it) }
            return """
                <navPoint id="navPoint-$currentOrder" playOrder="$currentOrder">
                  <navLabel><text>${xml(item.title)}</text></navLabel>
                  <content src="${xml(item.href)}"/>
                  $children
                </navPoint>
            """.trimIndent()
        }
        val navPoints = tocRoots(chapters).joinToString("\n") { navPoint(it) }
        return xmlDocument(
            """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head>
                <meta name="dtb:uid" content="${xml(metadata.identifier)}"/>
                <meta name="dtb:depth" content="2"/>
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

    private fun tocRoots(chapters: List<DetectedChapter>): List<TocItem> {
        val roots = mutableListOf<TocItem>()
        chapters.forEachIndexed { index, chapter ->
            val item = TocItem(
                title = chapter.title,
                href = "chapter_${index + 1}.xhtml#${chapter.anchorId}",
            )
            if (chapter.tocLevel == 1 || roots.isEmpty()) {
                roots += item
            } else {
                roots.last().children += item
            }
        }
        return roots
    }

    private fun chapterXhtml(
        title: String,
        body: String,
        language: String,
        anchorId: String,
    ): String =
        xhtmlDocument(title, language, """<main id="${xml(anchorId)}">$body</main>""")

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
        #visible-toc ol { padding-left: 1.35em; }
        #visible-toc li { margin: 0.45em 0; }
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

    private data class ParsedChapter(
        val detected: DetectedChapter,
        val document: Node,
    )

    private data class TocItem(
        val title: String,
        val href: String,
        val children: MutableList<TocItem> = mutableListOf(),
    )

    private data class ImageBinding(
        val asset: EpubAsset,
        val targetName: String,
    )

    companion object {
        const val EPUB_MIMETYPE = "application/epub+zip"
        private const val ZIP_TIME = 315_532_800_000L
        private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif")
    }
}
