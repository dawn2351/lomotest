package com.lomo.domain.repository

import com.lomo.domain.model.BackupReminderCadence
import com.lomo.domain.model.BackupReminderSchedule
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface BackupReminderRepository {
    val schedule: Flow<BackupReminderSchedule>

    suspend fun configure(cadence: BackupReminderCadence?, today: LocalDate)

    suspend fun reschedule(expected: BackupReminderSchedule.Scheduled, nextDue: LocalDate)
}
