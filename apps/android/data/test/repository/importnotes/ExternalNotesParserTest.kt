/*
 * Behavior Contract:
 * - Owner: data adapter for flomo HTML and WeRead copied highlights.
 * - Priority tier: P1.
 * - Preserve paragraphs, decoded entities, tags, chronological facts and multiline highlights.
 * - Reject a malformed export before the chooser starts a workspace mutation.
 * - Evidence: assert the normalized note bodies and reported unsupported attachments.
 */
package com.lomo.data.repository.importnotes

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId

class ExternalNotesParserTest : DataFunSpec() {
    init {
        test("given a flomo export when parsed then markup is cleaned and the date survives") {
            val html = """
                <div class="memo"><div class="time">2025-11-08 15:20:00</div>
                <div class="content"><p>#观察 A &amp; B<br>第二行</p><p>结尾</p></div>
                <div class="files"><img src="file/picture.png"></div></div>
            """.trimIndent()
            parseFlomoExport(html, ZoneId.of("Asia/Shanghai")) shouldBe listOf(
                ExternalNote(
                    body = "#观察 A & B\n第二行\n结尾",
                    timestampMillis = Instant.parse("2025-11-08T07:20:00Z").toEpochMilli(),
                    imageReferences = listOf("file/picture.png"),
                ),
            )
        }

        test("given WeRead highlights when parsed then matching chapters and continuation lines are retained") {
            val export = """
                《示例》
                2个笔记
                第一章
                ◆ 第一行
                这一行仍属同一条划线

                实践
                ◆ 下一章的划线
                来自微信读书
            """.trimIndent()
            val notes = parseWeReadExport(export)
            notes.map(ExternalNote::body) shouldBe listOf(
                "《示例》\n第一章\n\n> 第一行\n> 这一行仍属同一条划线\n\n#微信读书",
                "《示例》\n实践\n\n> 下一章的划线\n\n#微信读书",
            )
            notes.map(ExternalNote::timestampMillis) shouldBe listOf(null, null)
        }

        test("given ordinary text when parsed then it is not imported as WeRead notes") {
            shouldThrow<IllegalArgumentException> { parseWeReadExport("Some ordinary text") }
        }
    }
}
