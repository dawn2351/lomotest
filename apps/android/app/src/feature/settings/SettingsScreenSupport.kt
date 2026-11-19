package com.lomo.app.feature.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.ExternalNotesSource
import com.lomo.domain.repository.ExternalNotesImportRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

@Composable
internal fun rememberStoragePickerActions(
    storageFeature: SettingsStorageFeatureViewModel,
    s3Feature: SettingsS3FeatureViewModel,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): StoragePickerActions = StoragePickerActions(
    openRoot =
    rememberTreePickerAction(
        onUriSelected = storageFeature::updateRootUri,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    ),
    openImage =
    rememberTreePickerAction(
        onUriSelected = storageFeature::updateImageUri,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    ),
    openVoice =
    rememberTreePickerAction(
        onUriSelected = storageFeature::updateVoiceUri,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    ),
    openSyncInbox =
    rememberTreePickerAction(
        onUriSelected = storageFeature::updateSyncInboxUri,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    ),
    openS3LocalSyncDirectory =
    rememberTreePickerAction(
        onUriSelected = s3Feature.updateLocalSyncDirectory,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    ),
)

@Composable
private fun rememberTreePickerAction(
    onUriSelected: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            runCatching {
                val selection = checkNotNull(result.data) { "saf_picker_no_result" }
                val uri = checkNotNull(selection.data) { "saf_picker_no_tree" }
                persistTreePermission(context, uri, selection.flags)
                onUriSelected(uri.toString())
            }.onFailure { throwable ->
                Timber.w(throwable, "Unable to persist a writable document tree")
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf { it.isNotBlank() } ?: unknownErrorMessage,
                    )
                }
            }
        }
    return {
        launcher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                TREE_ACCESS_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            ),
        )
    }
}

@Composable
internal fun rememberMigrationPickerActions(
    migrationFeature: SettingsMigrationFeatureViewModel,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): MigrationPickerActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externalImporter = koinInject<ExternalNotesImportRepository>()
    val openFlomoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            migrationFeature.importExternalNotes(externalImporter, ExternalNotesSource.FLOMO) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }
    val openWeReadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            migrationFeature.importExternalNotes(externalImporter, ExternalNotesSource.WE_READ) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }
    val exportSettingsPassword = remember { mutableStateOf("") }
    val importSettingsPassword = remember { mutableStateOf("") }
    val createNotesArchiveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            migrationFeature.exportNotesArchive {
                context.contentResolver.openOutputStream(uri)
            }
        }
    val openNotesArchiveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            migrationFeature.importNotesArchive {
                context.contentResolver.openInputStream(uri)
            }
        }
    val createSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            migrationFeature.exportEncryptedSettings(
                password = exportSettingsPassword.value,
                openOutput = { context.contentResolver.openOutputStream(uri) },
            )
        }
    val openSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            migrationFeature.importEncryptedSettings(
                password = importSettingsPassword.value,
                openInput = { context.contentResolver.openInputStream(uri) },
            )
        }
    return MigrationPickerActions(
        exportNotesArchive = {
            runCatching {
                createNotesArchiveLauncher.launch("lomo-notes.zip")
            }.onFailure { throwable ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf(String::isNotBlank) ?: unknownErrorMessage,
                    )
                }
            }
        },
        importNotesArchive = {
            runCatching {
                openNotesArchiveLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }.onFailure { throwable ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf(String::isNotBlank) ?: unknownErrorMessage,
                    )
                }
            }
        },
        importFlomo = { openFlomoLauncher.launch(arrayOf("text/html", "application/zip", "application/octet-stream")) },
        importWeRead = { openWeReadLauncher.launch(arrayOf("text/plain", "application/octet-stream")) },
        importWeReadText = { text ->
            migrationFeature.importExternalNotes(externalImporter, ExternalNotesSource.WE_READ) {
                text.byteInputStream()
            }
        },
        exportEncryptedSettings = { password ->
            exportSettingsPassword.value = password
            runCatching {
                createSettingsLauncher.launch("lomo-settings.json")
            }.onFailure { throwable ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf(String::isNotBlank) ?: unknownErrorMessage,
                    )
                }
            }
        },
        importEncryptedSettings = { password ->
            importSettingsPassword.value = password
            runCatching {
                openSettingsLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
            }.onFailure { throwable ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        throwable.message?.takeIf(String::isNotBlank) ?: unknownErrorMessage,
                    )
                }
            }
        },
    )
}

private const val TREE_ACCESS_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun persistTreePermission(context: Context, uri: Uri, grantedFlags: Int) {
    val resolver = context.contentResolver
    fun hasWritableGrant(): Boolean = resolver.persistedUriPermissions.any { permission ->
        persistedTreeMatches(permission.uri, uri) &&
            permission.isReadPermission &&
            permission.isWritePermission
    }
    if (hasWritableGrant()) return

    check(grantedFlags and TREE_ACCESS_FLAGS == TREE_ACCESS_FLAGS) { "saf_grant_missing_read_or_write" }
    resolver.takePersistableUriPermission(uri, TREE_ACCESS_FLAGS)
    check(hasWritableGrant()) { "saf_grant_not_persisted" }
}

private fun persistedTreeMatches(persistedUri: Uri, requestedUri: Uri): Boolean {
    if (persistedUri == requestedUri || persistedUri.toString() == requestedUri.toString()) return true
    if (persistedUri.authority != requestedUri.authority ||
        !DocumentsContract.isTreeUri(persistedUri) ||
        !DocumentsContract.isTreeUri(requestedUri)
    ) {
        return false
    }
    return DocumentsContract.getTreeDocumentId(persistedUri) ==
        DocumentsContract.getTreeDocumentId(requestedUri)
}

@Composable
internal fun HandleSettingsOperationError(
    operationError: SettingsOperationError?,
    snackbarHostState: SnackbarHostState,
    messages: SettingsMessages,
    onClearOperationError: () -> Unit,
) {
    val localizedMessage =
        when (val error = operationError) {
            is SettingsOperationError.GitSync ->
                SettingsErrorPresenter.gitSyncErrorMessage(error.code, error.detail)

            is SettingsOperationError.Message -> error.text

            is SettingsOperationError.WebDavSync ->
                SettingsErrorPresenter.webDavSyncErrorMessage(error.code, error.detail)

            null -> null
        }
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.action_copy)
    val diagnostic = (operationError as? SettingsOperationError.Message)?.diagnostic
    LaunchedEffect(operationError) {
        operationError ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = localizedMessage ?: messages.unknownErrorMessage,
            actionLabel = copyLabel.takeIf { diagnostic != null },
            withDismissAction = diagnostic != null,
        )
        if (result == SnackbarResult.ActionPerformed && diagnostic != null) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText(copyLabel, "Android SDK ${Build.VERSION.SDK_INT} / $diagnostic"),
            )
        }
        onClearOperationError()
    }
}
