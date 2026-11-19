package com.lomo.app.feature.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.TagPath
import com.lomo.domain.usecase.ChangeTagMemosUseCase
import com.lomo.domain.usecase.ObserveSidebarStatisticsUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.domain.usecase.TagMemoChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

internal sealed interface TagManagementOperation {
    data object Idle : TagManagementOperation
    data object Running : TagManagementOperation
    data class Finished(val count: Int) : TagManagementOperation
    data object Failed : TagManagementOperation
}

internal class TagManagementViewModel(
    observeStatistics: ObserveSidebarStatisticsUseCase,
    private val changeTag: ChangeTagMemosUseCase,
    observeWorkspace: ObserveWorkspaceSessionUseCase,
) : ViewModel() {
    val tags = observeStatistics().map { it.tagCounts }.stateIn(viewModelScope, appWhileSubscribed(), emptyList())
    val mount = observeWorkspace.mount
    private val currentOperation = MutableStateFlow<TagManagementOperation>(TagManagementOperation.Idle)
    val operation = currentOperation.asStateFlow()

    fun applyChange(tag: String, change: TagMemoChange) {
        if (!currentOperation.compareAndSet(TagManagementOperation.Idle, TagManagementOperation.Running)) return
        viewModelScope.launch {
            currentOperation.value = runSuspendCatching { changeTag(TagPath(tag), change) }.fold(
                onSuccess = { TagManagementOperation.Finished(it) },
                onFailure = {
                    Timber.w(it, "Tag management operation failed")
                    TagManagementOperation.Failed
                },
            )
        }
    }

    fun acknowledge(completed: TagManagementOperation) {
        currentOperation.compareAndSet(completed, TagManagementOperation.Idle)
    }
}
