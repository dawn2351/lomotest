package com.lomo.app.feature.main

import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

class MainStartupCoordinator(
    private val getCurrentAppBuildVersionUseCase: GetCurrentAppBuildVersionUseCase,
    startupMaintenanceUseCase: () -> StartupMaintenanceUseCase,
    private val appConfigStateProvider: AppConfigStateProvider,
    private val audioPlayerManager: AudioPlayerManager,
    private val observeWorkspaceSessionUseCase: ObserveWorkspaceSessionUseCase,
) {
    // Homepage observation must not construct the sync/archive maintenance graph.
    private val startupMaintenanceUseCase by lazy(startupMaintenanceUseCase)

    suspend fun initializeRootDirectory(): String? {
        val mount = observeWorkspaceSessionUseCase.mount.first { current ->
            when (current.readiness) {
                EngineReadiness.Opening -> false

                EngineReadiness.Ready -> current.admitsProjectionWrites

                EngineReadiness.AwaitingWorkspaceSelection,
                EngineReadiness.ShuttingDown,
                is EngineReadiness.ReadOnlyRecovery,
                -> true
            }
        }
        val root = mount.location?.raw.takeIf { mount.admitsProjectionWrites }
        audioPlayerManager.setRootLocation(root)
        return root
    }

    suspend fun runDeferredStartupTasks(rootDir: String?) {
        startupMaintenanceUseCase.runDeferredStartupTasks(
            rootDir = rootDir,
            currentVersion = getCurrentAppBuildVersionUseCase(),
        )
    }

    fun observeRootDirectoryChanges(): Flow<String?> = observeWorkspaceSessionUseCase.observeActiveRootPath()
        .onEach(audioPlayerManager.setRootLocation)

    fun observeVoiceDirectoryChanges(): Flow<String?> = appConfigStateProvider.voiceDirectory
        .onEach(audioPlayerManager.setVoiceLocation)
}
