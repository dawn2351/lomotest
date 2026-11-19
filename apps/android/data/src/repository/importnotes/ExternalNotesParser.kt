package com.lomo.data.repository.importnotes

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ExternalNote(
    val body: String,
    val timestampMillis: Long?,
    val imageReferences: List<String> = emptyList(),
    val omittedAttachmentCount: Int = 0,
)

internal fun parseFlomoExport(html: String, zone: ZoneId): List<ExternalNote> {
    val elements = Jsoup.parse(html).select("div.memo")
    require(elements.isNotEmpty()) { "The file contains no flomo memos" }
    return elements.map { memo ->
        val date = requireNotNull(memo.selectFirst(".time")) { "A flomo memo is missing its creation time" }
        val content = requireNotNull(memo.selectFirst(".content")) { "A flomo memo is missing its content" }
        val timestamp = LocalDateTime.parse(date.text(), flomoDateTime).atZone(zone).toInstant().toEpochMilli()
        val images = memo.select(".files img, .content img")
        val references = images.map { it.attr("src").trim() }.filter(String::isNotEmpty).distinct()
        val unsupported = memo.select(".files audio, .files video, .files a:not(:has(img, audio, video))").size +
            images.count { it.attr("src").isBlank() }
        ExternalNote(flomoPlainText(content), timestamp, references, unsupported)
    }
}

private fun flomoPlainText(content: Element): String {
    content.select("script, style, template").remove()
    return buildString {
        val destination = this
        content.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> destination.append(node.wholeText.replace('\u00A0', ' '))

                    is Element -> when (node.normalName()) {
                        "br" -> destination.appendLine()
                        "li" -> destination.append("- ")
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock && destination.isNotEmpty() && destination.last() != '\n') {
                    destination.appendLine()
                }
            }
        })
    }.lineSequence().joinToString("\n", transform = String::trimEnd).trim()
}

internal fun parseWeReadExport(text: String): List<ExternalNote> {
    val lines = text.lineSequence().map(String::trim).dropWhile(String::isEmpty).toList()
    val firstLine = requireNotNull(lines.firstOrNull()) { "The WeRead export is empty" }
    val title = bookTitle.matchEntire(firstLine)?.groupValues?.get(1) ?: firstLine
    require(title.isNotBlank() && !title.startsWith('◆')) { "The WeRead export is missing a book title" }
    val notes = mutableListOf<ExternalNote>()
    var chapter: String? = null
    var pendingLine: String? = null
    var highlight: MutableList<String>? = null
    fun finishHighlight() {
        val linesToSave = highlight ?: return
        while (linesToSave.lastOrNull()?.isEmpty() == true) linesToSave.removeAt(linesToSave.lastIndex)
        if (linesToSave.any(String::isNotBlank)) {
            val body = buildString {
                appendLine("《$title》")
                chapter?.let { appendLine(it) }
                appendLine()
                linesToSave.forEach { appendLine(if (it.isEmpty()) ">" else "> $it") }
                appendLine()
                append("#微信读书")
            }
            notes += ExternalNote(body, timestampMillis = null)
        }
        highlight = null
    }

    for ((index, line) in lines.withIndex().drop(1)) {
        when {
            line == "来自微信读书" || line.startsWith("来自微信读书：") -> {
                finishHighlight()
                break
            }

            noteCount.matches(line) || line == "点评" || line == "发表想法" -> {
                finishHighlight()
                pendingLine = null
            }

            line.startsWith('◆') -> {
                finishHighlight()
                pendingLine?.let { chapter = it }
                pendingLine = null
                highlight = mutableListOf(line.drop(1).trimStart())
            }

            chapterHeading.matches(line) -> {
                finishHighlight()
                pendingLine = line
            }

            highlight == null && line.isNotEmpty() -> pendingLine = line

            highlight != null -> {
                val current = checkNotNull(highlight)
                if (line.isNotEmpty() && current.lastOrNull()?.isEmpty() == true &&
                    lines.getOrNull(index + 1)?.startsWith('◆') == true
                ) {
                    // An unlabeled heading directly before the next ◆ belongs to that excerpt.
                    finishHighlight()
                    pendingLine = line
                } else {
                    current += line
                }
            }
        }
    }
    finishHighlight()
    require(notes.isNotEmpty()) { "The WeRead export contains no highlights marked with ◆" }
    return notes
}

private val flomoDateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val bookTitle = Regex("""《(.+?)》.*""")
private val noteCount = Regex("""\d+\s*个笔记""")
private val chapterHeading = Regex("""(?:第[零一二三四五六七八九十百千\d]+[章节部卷篇回].*|\d{1,3})""")
