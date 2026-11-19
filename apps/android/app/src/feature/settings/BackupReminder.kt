package com.lomo.app.feature.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.BackupReminderCadence
import com.lomo.domain.model.BackupReminderSchedule
import com.lomo.domain.repository.BackupReminderRepository
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.icon.LomoIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDate

@Composable
internal fun BackupReminderSettingsSection() {
    val repository = koinInject<BackupReminderRepository>()
    val schedule by repository.schedule.collectAsStateWithLifecycle(initialValue = BackupReminderSchedule.Off)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showChoices by remember { mutableStateOf(false) }
    val currentCadence = (schedule as? BackupReminderSchedule.Scheduled)?.cadence
    SettingsGroup(title = stringResource(R.string.backup_reminder_title)) {
        item {
            PreferenceItem(
                title = stringResource(R.string.backup_reminder_frequency),
                subtitle = backupReminderCadenceLabel(currentCadence),
                icon = LomoIcons.Schedule,
                onClick = { showChoices = true },
            )
        }
    }
    if (showChoices) {
        AlertDialog(
            onDismissRequest = { showChoices = false },
            title = { Text(stringResource(R.string.backup_reminder_frequency)) },
            text = {
                Column {
                    (listOf(null) + BackupReminderCadence.entries).forEach { cadence ->
                        PreferenceItem(
                            title = backupReminderCadenceLabel(cadence),
                            showChevron = false,
                            onClick = {
                                showChoices = false
                                scope.launch {
                                    runSuspendCatching { repository.configure(cadence, LocalDate.now()) }
                                        .onFailure { showBackupReminderFailure(context, it) }
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                LomoTextButton(onClick = { showChoices = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun BackupReminderDialogHost(canShow: Boolean) {
    val repository = koinInject<BackupReminderRepository>()
    val exporter = koinInject<ExportAllNotesArchiveUseCase>()
    val dispatcher = koinInject<DispatcherProvider>()
    val workspace = koinInject<ObserveWorkspaceSessionUseCase>()
    val mount by workspace.mount.collectAsStateWithLifecycle()
    val schedule by repository.schedule.collectAsStateWithLifecycle(initialValue = BackupReminderSchedule.Off)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var today by remember { mutableStateOf(LocalDate.now()) }
    var busy by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        scope.launch {
            try {
                runSuspendCatching {
                    val expected = repository.schedule.first() as? BackupReminderSchedule.Scheduled
                        ?: return@runSuspendCatching
                    val exportDate = LocalDate.now()
                    if (uri == null) {
                        repository.reschedule(expected, exportDate.plusDays(1))
                    } else {
                        withContext(dispatcher.io) {
                            val stream = checkNotNull(context.contentResolver.openOutputStream(uri))
                            stream.use { exporter(it) }
                        }
                        repository.reschedule(expected, exportDate.plusMonths(expected.cadence.months))
                        Toast.makeText(context, R.string.backup_reminder_exported, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { showBackupReminderFailure(context, it) }
            } finally {
                busy = false
            }
        }
    }

    val due = schedule as? BackupReminderSchedule.Scheduled ?: return
    if (!canShow || !mount.admitsProjectionWrites || busy || today.isBefore(due.nextDue)) return

    val remindTomorrow: () -> Unit = {
        busy = true
        scope.launch {
            try {
                runSuspendCatching { repository.reschedule(due, LocalDate.now().plusDays(1)) }
                    .onFailure { showBackupReminderFailure(context, it) }
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = remindTomorrow,
        title = { Text(stringResource(R.string.backup_reminder_title)) },
        text = { Text(stringResource(R.string.backup_reminder_due)) },
        dismissButton = {
            LomoTextButton(onClick = remindTomorrow) { Text(stringResource(R.string.backup_reminder_tomorrow)) }
        },
        confirmButton = {
            LomoTextButton(onClick = {
                runCatching {
                    busy = true
                    export.launch("lomo-notes.zip")
                }.onFailure {
                    busy = false
                    showBackupReminderFailure(context, it)
                }
            }) {
                Text(stringResource(R.string.settings_migration_export_notes))
            }
        },
    )
}

@Composable
private fun backupReminderCadenceLabel(cadence: BackupReminderCadence?): String = stringResource(
    when (cadence) {
        null -> R.string.backup_reminder_off
        BackupReminderCadence.THREE_MONTHS -> R.string.backup_reminder_3_months
        BackupReminderCadence.SIX_MONTHS -> R.string.backup_reminder_6_months
        BackupReminderCadence.TWELVE_MONTHS -> R.string.backup_reminder_12_months
    },
)

private fun showBackupReminderFailure(context: Context, failure: Throwable) {
    Timber.w(failure, "Backup reminder action failed")
    Toast.makeText(context, R.string.backup_reminder_failed, Toast.LENGTH_SHORT).show()
}
