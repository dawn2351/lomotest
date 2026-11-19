package com.lomo.app.feature.memo

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.util.ShareUtils
import com.lomo.app.util.activityKoinViewModel
import com.lomo.app.util.rememberShareUtils
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber
import java.text.DateFormat
import java.util.Date

data class MemoMenuPresentationState(
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
)

data class MemoMenuShareCardRequest(
    val content: String,
    val showTime: Boolean,
    val showSignature: Boolean,
    val signatureText: String,
    val timestamp: Long?,
    val tags: List<String>,
    val resolvedImagePaths: List<String>,
)

internal fun formatQuotedMemo(label: String, timestamp: String, content: String): String = buildString {
    appendLine("> $label $timestamp")
    appendLine(">")
    content.trimEnd().lineSequence().forEach { line -> appendLine("> $line".trimEnd()) }
    appendLine()
}

data class MemoMenuShareTextRequest(val content: String)

data class MemoMenuLanShareRequest(val content: String, val timestamp: Long)

/**
 * The screen-specific action occupying the third menu tile. Exactly one exists per screen:
 * the main list pins in place, every other memo surface jumps back to the main list.
 */
sealed interface MemoMenuContextAction {
    data class TogglePin(val setPinned: (Memo, Boolean) -> Unit) : MemoMenuContextAction

    data class Jump(val jump: (MemoMenuSelection) -> Unit) : MemoMenuContextAction
}

class MemoMenuCommandHandler(
    val presentationState: MemoMenuPresentationState,
    private val onEditMemo: (String) -> Unit,
    private val onDeleteMemo: (Memo, String?) -> Unit,
    private val onShareCard: (MemoMenuShareCardRequest) -> Unit,
    private val onShareText: (MemoMenuShareTextRequest) -> Unit,
    private val onCopyMemo: (MemoMenuSelection, String) -> Unit,
    val contextAction: MemoMenuContextAction,
    private val onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    private val onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
    private val onQuoteMemo: ((MemoMenuSelection) -> Unit)? = null,
) {
    val hasQuoteMemo: Boolean
        get() = onQuoteMemo != null
    val hasLanShare: Boolean
        get() = onLanShare != null

    val hasVersionHistory: Boolean
        get() = onVersionHistory != null

    fun edit(selection: MemoMenuSelection) {
        onEditMemo(selection.memo.id)
    }

    fun delete(selection: MemoMenuSelection) {
        onDeleteMemo(selection.memo, selection.anchoredAfterKey)
    }

    fun copy(selection: MemoMenuSelection, label: String) {
        onCopyMemo(selection, label)
    }

    fun shareCard(selection: MemoMenuSelection) {
        val memo = selection.memo
        onShareCard(
            MemoMenuShareCardRequest(
                content = memo.content,
                showTime = presentationState.shareCardShowTime,
                showSignature = presentationState.shareCardShowSignature,
                signatureText = presentationState.shareCardSignatureText,
                timestamp = memo.timestamp,
                tags = memo.tags,
                resolvedImagePaths = selection.state.imageUrls,
            ),
        )
    }

    fun shareText(selection: MemoMenuSelection) {
        onShareText(MemoMenuShareTextRequest(content = selection.memo.content))
    }

    fun lanShare(selection: MemoMenuSelection) {
        val handler = onLanShare ?: return
        val memo = selection.memo
        handler(
            MemoMenuLanShareRequest(
                content = memo.content,
                timestamp = memo.timestamp,
            ),
        )
    }

    fun runContextAction(selection: MemoMenuSelection) {
        when (contextAction) {
            is MemoMenuContextAction.TogglePin -> contextAction.setPinned(selection.memo, !selection.memo.isPinned)
            is MemoMenuContextAction.Jump -> contextAction.jump(selection)
        }
    }

    fun quote(selection: MemoMenuSelection) {
        onQuoteMemo?.invoke(selection)
    }

    fun versionHistory(selection: MemoMenuSelection) {
        onVersionHistory?.invoke(selection)
    }
}

@Composable
fun rememberMemoMenuCommandHandler(
    presentationState: MemoMenuPresentationState,
    onEditMemo: (String) -> Unit,
    onDeleteMemo: (Memo, String?) -> Unit,
    contextAction: MemoMenuContextAction,
    onLanShare: ((MemoMenuLanShareRequest) -> Unit)? = null,
    onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
    onNavigateToMain: (() -> Unit)? = null,
): MemoMenuCommandHandler {
    val context = LocalContext.current
    val shareUtils = rememberShareUtils()
    val memoQueryRepository = koinInject<MemoQueryRepository>()
    val dispatcherProvider = koinInject<DispatcherProvider>()
    val copyFailedMessage = stringResource(R.string.memo_copy_failed)
    val quoteFailedMessage = stringResource(R.string.memo_quote_failed)
    val quoteHeader = stringResource(R.string.memo_quote_header)
    val mainViewModel: MainViewModel = activityKoinViewModel()
    val navigateToMain = rememberUpdatedState(onNavigateToMain)
    val scope = rememberCoroutineScope()
    val editMemoState = rememberUpdatedState(onEditMemo)
    val deleteMemoState = rememberUpdatedState(onDeleteMemo)
    val lanShareState = rememberUpdatedState(onLanShare)
    val versionHistoryState = rememberUpdatedState(onVersionHistory)
    val hasLanShare = onLanShare != null
    val hasVersionHistory = onVersionHistory != null

    return remember(
        presentationState,
        context,
        shareUtils,
        memoQueryRepository,
        dispatcherProvider,
        copyFailedMessage,
        quoteFailedMessage,
        quoteHeader,
        mainViewModel,
        scope,
        contextAction,
        hasLanShare,
        hasVersionHistory,
    ) {
        MemoMenuCommandHandler(
            presentationState = presentationState,
            onEditMemo = { memoId -> editMemoState.value(memoId) },
            onQuoteMemo = { selection ->
                scope.launch {
                    runSuspendCatching {
                        val memo = checkNotNull(memoQueryRepository.getMemoById(selection.memo.id))
                        val timestamp = DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                            context.resources.configuration.locales[0],
                        ).format(Date(memo.timestamp))
                        formatQuotedMemo(quoteHeader, timestamp, memo.content)
                    }.onSuccess { draft ->
                        navigateToMain.value?.invoke()
                        mainViewModel.handleSharedText(draft)
                    }.onFailure { failure ->
                        Timber.w(failure, "Unable to load memo for quoting")
                        Toast.makeText(context, quoteFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDeleteMemo = { memo, anchoredAfterKey ->
                deleteMemoState.value(memo, anchoredAfterKey)
            },
            onShareCard = { request ->
                scope.launch {
                    shareMemoAsImage(
                        request = request,
                        context = context,
                        shareUtils = shareUtils,
                    )
                }
            },
            onShareText = { request ->
                shareUtils.shareMemoText(
                    context = context,
                    content = request.content,
                )
            },
            contextAction = contextAction,
            onCopyMemo = { selection, label ->
                scope.launch {
                    copyMemoToClipboard(
                        context,
                        memoQueryRepository,
                        selection,
                        label,
                        copyFailedMessage,
                        dispatcherProvider,
                    )
                }
            },
            onLanShare =
            if (hasLanShare) {
                { request -> lanShareState.value?.invoke(request) }
            } else {
                null
            },
            onVersionHistory =
            if (hasVersionHistory) {
                { selection -> versionHistoryState.value?.invoke(selection) }
            } else {
                null
            },
        )
    }
}

private suspend fun shareMemoAsImage(request: MemoMenuShareCardRequest, context: Context, shareUtils: ShareUtils) {
    shareUtils.shareMemoAsImage(
        context = context,
        content = request.content,
        showTime = request.showTime,
        showSignature = request.showSignature,
        signatureText = request.signatureText,
        timestamp = request.timestamp,
        tags = request.tags,
        resolvedImagePaths = request.resolvedImagePaths,
    )
}
