package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.MemoUpdateAttempt
import com.lomo.domain.model.TagPath
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class TagMemoChange { REMOVE_TAG, MOVE_TO_TRASH }

class ChangeTagMemosUseCase(
    private val search: MemoSearchRepository,
    private val query: MemoQueryRepository,
    private val markdown: MarkdownWorkspaceRepository,
    private val update: UpdateMemoContentUseCase,
    private val delete: DeleteMemoUseCase,
    private val lease: WorkspaceMutationLease,
    private val dispatcher: DispatcherProvider,
) {
    suspend operator fun invoke(tag: TagPath, change: TagMemoChange): Int = withContext(dispatcher.io) {
        lease.withWrite {
            val ids = collectTaggedMemoIds(search.getMemosByTagPagingSource(TagSelection(tag, TagSelectionMode.Exact)))
            var changed = 0
            for (id in ids) {
                val current = query.getMemoById(id) ?: continue
                if (tag.value !in current.tags) continue
                when (change) {
                    TagMemoChange.REMOVE_TAG -> update(
                        MemoUpdateAttempt(
                            operationId = MemoOperationId.mint(),
                            draftId = DraftId.mint(),
                            snapshot = EditableMemoSnapshot.fromFullSnapshot(current),
                            content = removeMemoTag(
                                current.rawContent,
                                markdown.renderMarkdown(current.rawContent),
                                tag,
                            ),
                        ),
                    )

                    TagMemoChange.MOVE_TO_TRASH -> delete(current, MemoOperationId.mint())
                }
                changed += 1
            }
            changed
        }
    }
}

private suspend fun collectTaggedMemoIds(source: PagingSource<String, Memo>): Set<String> {
    val ids = linkedSetOf<String>()
    val seenCursors = mutableSetOf<String>()
    var params: PagingSource.LoadParams<String> = PagingSource.LoadParams.Refresh(null, TAG_BATCH_PAGE_SIZE, false)
    try {
        while (true) {
            when (val result = source.load(params)) {
                is PagingSource.LoadResult.Error -> throw result.throwable

                is PagingSource.LoadResult.Invalid -> error("Tag listing changed before the operation could start")

                is PagingSource.LoadResult.Page -> {
                    result.data.mapTo(ids, Memo::id)
                    val cursor = result.nextKey ?: return ids
                    check(seenCursors.add(cursor)) { "Tag listing did not advance" }
                    params = PagingSource.LoadParams.Append(cursor, TAG_BATCH_PAGE_SIZE, false)
                }
            }
        }
    } finally {
        source.invalidate()
    }
}

internal fun removeMemoTag(content: String, document: MarkdownRenderDocument, tag: TagPath): String {
    val spans = document.blocks.asSequence()
        .flatMap(MarkdownRenderBlock::semanticTags)
        .filter { it.name == tag.value }
        .map(MarkdownRenderInline.Tag::sourceSpan)
        .sortedBy { it.startByte }
        .toList()
    require(spans.isNotEmpty()) { "The rendered memo has no matching tag spans" }

    val bytes = content.encodeToByteArray()
    require(document.sourceByteLength == bytes.size.toULong()) { "Tag spans refer to a different memo body" }
    val output = ByteArrayOutputStream(bytes.size)
    var last = 0
    for (span in spans) {
        val start = span.startByte.toInt()
        val end = span.endByte.toInt()
        require(start >= last && end > start && bytes[start] == '#'.code.toByte()) {
            "Invalid source span for tag removal"
        }
        output.write(bytes, last, start - last)
        last = end
    }
    output.write(bytes, last, bytes.size - last)
    val updated = output.toByteArray().decodeToString(throwOnInvalidSequence = true)
    // The editor cannot persist a blank note. A tag-only note retains its visible label as text.
    return if (updated.isBlank()) tag.value else updated
}

private fun MarkdownRenderBlock.semanticTags(): Sequence<MarkdownRenderInline.Tag> {
    val inlines = when (this) {
        is MarkdownRenderBlock.Paragraph -> inlines.asSequence()

        is MarkdownRenderBlock.Heading -> inlines.asSequence()

        is MarkdownRenderBlock.BlockQuote -> return blocks.asSequence().flatMap(MarkdownRenderBlock::semanticTags)

        is MarkdownRenderBlock.ListBlock -> return items.asSequence().flatMap { item ->
            item.blocks.asSequence().flatMap(MarkdownRenderBlock::semanticTags)
        }

        is MarkdownRenderBlock.Table -> sequenceOf(header).plus(rows).flatten().flatMap { it.inlines }

        else -> emptySequence()
    }
    return inlines.flatMap(MarkdownRenderInline::semanticTags)
}

private fun MarkdownRenderInline.semanticTags(): Sequence<MarkdownRenderInline.Tag> {
    val children = when (this) {
        is MarkdownRenderInline.Tag -> return sequenceOf(this)
        is MarkdownRenderInline.Strong -> inlines
        is MarkdownRenderInline.Emphasis -> inlines
        is MarkdownRenderInline.Strikethrough -> inlines
        is MarkdownRenderInline.Underline -> inlines
        is MarkdownRenderInline.Highlight -> inlines
        is MarkdownRenderInline.Link -> inlines
        is MarkdownRenderInline.WikiReference -> inlines
        else -> return emptySequence()
    }
    return children.asSequence().flatMap(MarkdownRenderInline::semanticTags)
}

private const val TAG_BATCH_PAGE_SIZE = 100
