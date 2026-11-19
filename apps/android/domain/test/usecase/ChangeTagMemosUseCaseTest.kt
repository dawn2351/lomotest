/*
 * Behavior Contract:
 * - Owner: domain tag mutation.
 * - Priority tier: P1.
 * - Given a complete render document, removing a tag uses its UTF-8 source spans alone.
 * - Given a note consisting of one tag, removing the tag retains the label as text.
 * - Evidence: assert the actual updated content and preservation of code and sibling tags.
 */
package com.lomo.domain.usecase

import com.lomo.domain.model.TagPath
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class ChangeTagMemosUseCaseTest : DomainFunSpec() {
    init {
        test("given Unicode and nested tags when removing one tag then sibling tags and code survive") {
            val source = "你好 #读书 `#读书` #读书/小说"
            val target = MarkdownRenderInline.Tag(span(source, "#读书"), "读书")
            val code = MarkdownRenderInline.Code(span(source, "`#读书`"), "#读书")
            val nested = MarkdownRenderInline.Tag(span(source, "#读书/小说"), "读书/小说")
            removeMemoTag(source, renderDocument(source, listOf(target, code, nested)), TagPath("读书")) shouldBe
                "你好  `#读书` #读书/小说"
        }

        test("given a tag-only memo when the tag is removed then its label remains plain text") {
            val source = "#读书"
            val target = MarkdownRenderInline.Tag(span(source, source), "读书")
            removeMemoTag(source, renderDocument(source, listOf(target)), TagPath("读书")) shouldBe "读书"
        }
    }

    private fun renderDocument(source: String, inlines: List<MarkdownRenderInline>): MarkdownRenderDocument {
        val length = source.encodeToByteArray().size.toULong()
        return MarkdownRenderDocument(
            sourceByteLength = length,
            plainText = source,
            tagNames = listOf("读书"),
            attachments = emptyList(),
            blocks = listOf(MarkdownRenderBlock.Paragraph(MarkdownSourceSpan(0uL, length), inlines)),
        )
    }

    private fun span(source: String, token: String): MarkdownSourceSpan {
        val index = source.indexOf(token)
        require(index >= 0)
        val start = source.substring(0, index).encodeToByteArray().size.toULong()
        return MarkdownSourceSpan(start, start + token.encodeToByteArray().size.toULong())
    }
}
