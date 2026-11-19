package com.lomo.app.feature.tag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.usecase.TagMemoChange
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AdaptiveContentWidths

private enum class TagScope(val label: Int) {
    ALL(R.string.tag_management_all),
    TOP_LEVEL(R.string.tag_management_top_level),
    NESTED(R.string.tag_management_nested),
    ;

    fun matches(name: String): Boolean = when (this) {
        ALL -> true
        TOP_LEVEL -> '/' !in name
        NESTED -> '/' in name
    }
}

private data class TagChangeConfirmation(val name: String, val change: TagMemoChange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagManagementScreen(
    onBack: () -> Unit,
    onOpenTag: (String) -> Unit,
    viewModel: TagManagementViewModel = injectedKoinViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val mount by viewModel.mount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var search by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(TagScope.ALL) }
    var confirmation by remember { mutableStateOf<TagChangeConfirmation?>(null) }
    val visible = remember(tags, search, scope) {
        val query = search.trim().removePrefix("#")
        tags.filter { scope.matches(it.name) && it.name.contains(query, ignoreCase = true) }.sortedBy { it.name }
    }
    val message = when (val state = operation) {
        is TagManagementOperation.Finished -> stringResource(R.string.tag_management_updated, state.count)
        TagManagementOperation.Failed -> stringResource(R.string.tag_management_failed)
        TagManagementOperation.Idle, TagManagementOperation.Running -> null
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.acknowledge(operation)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tag_management_title)) },
                navigationIcon = {
                    LomoIconButton(onClick = onBack) {
                        LomoIcon(LomoIcons.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AdaptiveContentWidths.Reading).fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tag_management_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagScope.entries.forEach { option ->
                        FilterChip(
                            selected = scope == option,
                            onClick = { scope = option },
                            label = { Text(stringResource(option.label)) },
                        )
                    }
                }
                if (operation == TagManagementOperation.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn {
                    if (visible.isEmpty()) {
                        item { Text(stringResource(R.string.tag_management_empty), Modifier.padding(vertical = 24.dp)) }
                    }
                    items(visible, key = MemoTagCount::name) { tag ->
                        ManageTagRow(
                            tag = tag,
                            editable = operation == TagManagementOperation.Idle && mount.admitsProjectionWrites,
                            onOpen = { onOpenTag(tag.name) },
                            onChange = { confirmation = TagChangeConfirmation(tag.name, it) },
                        )
                    }
                }
            }
        }
    }

    confirmation?.let { request ->
        TagChangeDialog(
            request = request,
            enabled = mount.admitsProjectionWrites && operation == TagManagementOperation.Idle,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                viewModel.applyChange(request.name, request.change)
            },
        )
    }
}

@Composable
private fun TagChangeDialog(
    request: TagChangeConfirmation,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val removing = request.change == TagMemoChange.REMOVE_TAG
    val title = if (removing) R.string.tag_management_remove else R.string.tag_management_trash
    val message = if (removing) R.string.tag_management_remove_message else R.string.tag_management_trash_message
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message, request.name)) },
        dismissButton = {
            LomoTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            LomoTextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun ManageTagRow(tag: MemoTagCount, editable: Boolean, onOpen: () -> Unit, onChange: (TagMemoChange) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen) {
        ListItem(
            content = { Text("#${tag.name}") },
            supportingContent = { Text(tag.count.toString()) },
            trailingContent = {
                Box {
                    LomoIconButton(onClick = { showMenu = true }, enabled = editable) {
                        LomoIcon(
                            LomoIcons.MoreVert,
                            contentDescription = stringResource(R.string.tag_management_action, tag.name),
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tag_management_remove)) },
                            onClick = {
                                showMenu = false
                                onChange(TagMemoChange.REMOVE_TAG)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tag_management_trash)) },
                            onClick = {
                                showMenu = false
                                onChange(TagMemoChange.MOVE_TO_TRASH)
                            },
                        )
                    }
                }
            },
        )
    }
}
