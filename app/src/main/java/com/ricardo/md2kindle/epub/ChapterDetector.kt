package com.ricardo.md2kindle.epub

import java.text.Normalizer
import java.util.Locale

data class DetectedChapter(
    val title: String,
    val tocLevel: Int,
    val anchorId: String,
    val markdown: String,
    val anchorAliases: Set<String>,
)

data class DetectedBookStructure(
    val frontMatterMarkdown: String?,
    val chapters: List<DetectedChapter>,
)

data class TocPreviewEntry(
    val title: String,
    val level: Int,
)

class ChapterDetector {
    fun detectBook(markdown: String, fallbackTitle: String): DetectedBookStructure {
        val normalized = normalize(markdown)
        val lines = promotePlainSectionLines(normalized.lines())
        val headings = scanHeadings(lines)
        if (headings.isEmpty()) {
            return singleChapter(lines.joinToString("\n").trim(), fallbackTitle)
        }

        val firstH1 = headings.firstOrNull { it.level == 1 }
        val manualMap = headings.firstOrNull {
            it.level == 2 && it.title.isManualMapHeading()
        }
        val contentStart = manualMap?.let { map ->
            headings.firstOrNull {
                it.level == 2 && it.lineIndex > map.lineIndex
            }?.lineIndex ?: (map.lineIndex + 1)
        } ?: 0
        val eligibleHeadings = headings.filter { it.lineIndex >= contentStart }
        val h2Headings = eligibleHeadings.filter { it.level == 2 }
        val semanticH3 = eligibleHeadings.filter {
            it.level == 3 && it.title.isSemanticSubchapter()
        }
        val allH3 = eligibleHeadings.filter { it.level == 3 }
        val h3Candidates = when {
            semanticH3.isNotEmpty() -> semanticH3
            h2Headings.isEmpty() && allH3.size >= 2 -> allH3
            else -> emptyList()
        }

        val candidates = eligibleHeadings.filter { heading ->
            when (heading.level) {
                1 -> h2Headings.isEmpty() || heading != firstH1
                2 -> !heading.title.isManualMapHeading()
                3 -> heading in h3Candidates
                else -> false
            }
        }
        if (candidates.isEmpty()) {
            val title = firstH1?.title ?: fallbackTitle.ifBlank { "Libro" }
            return singleChapter(lines.joinToString("\n").trim(), title, headings)
        }

        val frontMatterEnd = manualMap?.lineIndex ?: candidates.first().lineIndex
        val frontMatter = lines.subList(0, frontMatterEnd)
            .joinToString("\n")
            .trim()
        val result = mutableListOf<DetectedChapter>()
        val usedIds = mutableSetOf<String>()

        candidates.forEachIndexed { index, heading ->
            val end = candidates.getOrNull(index + 1)?.lineIndex ?: lines.size
            val chunkHeadings = headings.filter {
                it.lineIndex in heading.lineIndex until end
            }
            val baseId = slug(heading.title).ifBlank { "capitulo-${index + 1}" }
            val anchorId = uniqueSlug(baseId, usedIds)
            usedIds += anchorId
            result += DetectedChapter(
                title = heading.title.ifBlank { "Capítulo ${index + 1}" },
                tocLevel = if (heading.level >= 3) 2 else 1,
                anchorId = anchorId,
                markdown = lines.subList(heading.lineIndex, end).joinToString("\n").trim(),
                anchorAliases = (chunkHeadings.map { slug(it.title) } + anchorId)
                    .filter { it.isNotBlank() }
                    .toSet(),
            )
        }

        return DetectedBookStructure(
            frontMatterMarkdown = frontMatter.takeIf { it.isNotBlank() },
            chapters = result,
        )
    }

    fun detect(markdown: String, fallbackTitle: String): List<DetectedChapter> =
        detectBook(markdown, fallbackTitle).chapters

    fun previews(markdown: String, fallbackTitle: String): List<TocPreviewEntry> =
        detect(markdown, fallbackTitle).map {
            TocPreviewEntry(title = it.title, level = it.tocLevel)
        }

    fun slug(value: String): String = Normalizer.normalize(
        plainHeading(value),
        Normalizer.Form.NFD,
    )
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}\s-]"""), "")
        .trim()
        .replace(Regex("""[\s-]+"""), "-")

    private fun singleChapter(
        markdown: String,
        fallbackTitle: String,
        headings: List<HeadingLine> = emptyList(),
    ): DetectedBookStructure {
        val title = fallbackTitle.ifBlank { "Libro" }
        val id = slug(title).ifBlank { "libro" }
        return DetectedBookStructure(
            frontMatterMarkdown = null,
            chapters = listOf(
                DetectedChapter(
                    title = title,
                    tocLevel = 1,
                    anchorId = id,
                    markdown = markdown,
                    anchorAliases = (headings.map { slug(it.title) } + id)
                        .filter { it.isNotBlank() }
                        .toSet(),
                ),
            ),
        )
    }

    private fun uniqueSlug(base: String, used: Set<String>): String {
        if (base !in used) return base
        var suffix = 2
        while ("$base-$suffix" in used) suffix += 1
        return "$base-$suffix"
    }

    private fun String.isManualMapHeading(): Boolean =
        slug(this) in MANUAL_MAP_HEADINGS

    private fun normalize(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removePrefix("\uFEFF")

    private fun promotePlainSectionLines(lines: List<String>): List<String> {
        var fence: String? = null
        val candidates = lines.mapIndexed { index, line ->
            val trimmed = line.trim()
            val wasInsideFence = fence != null
            fence = updateFence(fence, trimmed)
            index.takeIf {
                !wasInsideFence &&
                fence == null &&
                !trimmed.startsWith("#") &&
                trimmed.length in 3..120 &&
                PLAIN_SECTION_PATTERN.matches(trimmed) &&
                lines.getOrNull(index - 1)?.isBlank() != false &&
                lines.getOrNull(index + 1)?.isBlank() != false
            }
        }.filterNotNull().toSet()
        if (candidates.size < 2) return lines
        return lines.mapIndexed { index, line ->
            if (index in candidates) "## ${line.trim()}" else line
        }
    }

    private fun scanHeadings(lines: List<String>): List<HeadingLine> {
        val result = mutableListOf<HeadingLine>()
        var fence: String? = null
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            val previousFence = fence
            fence = updateFence(fence, trimmed)
            if (previousFence != null || trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                return@forEachIndexed
            }
            HEADING_PATTERN.matchEntire(trimmed)?.let { match ->
                result += HeadingLine(
                    lineIndex = index,
                    level = match.groupValues[1].length,
                    title = plainHeading(match.groupValues[2]).trimEnd('#').trim(),
                )
            }
        }
        return result
    }

    private fun updateFence(current: String?, trimmed: String): String? {
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        } ?: return current
        return if (current == null) marker else if (current == marker) null else current
    }

    private fun plainHeading(value: String): String = value
        .replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""[*_`~]"""), "")
        .trim()

    private fun String.isSemanticSubchapter(): Boolean =
        SEMANTIC_SUBCHAPTER_PATTERN.containsMatchIn(trim())

    private data class HeadingLine(
        val lineIndex: Int,
        val level: Int,
        val title: String,
    )

    companion object {
        private val HEADING_PATTERN = Regex("""^(#{1,3})\s+(.+?)\s*$""")
        private val SEMANTIC_SUBCHAPTER_PATTERN = Regex(
            """^(?:\d{1,3}[.)]\s+|cap[ií]tulo\s+|lecci[oó]n\s+|secci[oó]n\s+)""",
            RegexOption.IGNORE_CASE,
        )
        private val PLAIN_SECTION_PATTERN = Regex(
            """^(?:(?:cap[ií]tulo|lecci[oó]n|secci[oó]n|parte|pr[oó]logo|ep[ií]logo|introducci[oó]n|conclusi[oó]n|anexo)\b|\d{1,3}[.)]\s+).+""",
            RegexOption.IGNORE_CASE,
        )
        private val MANUAL_MAP_HEADINGS = setOf(
            "mapa-de-escucha",
            "indice",
            "tabla-de-contenidos",
            "contenido",
            "contenidos",
        )
    }
}
