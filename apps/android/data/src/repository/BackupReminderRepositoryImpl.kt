package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lomo.domain.model.BackupReminderCadence
import com.lomo.domain.model.BackupReminderSchedule
import com.lomo.domain.repository.BackupReminderRepository
import kotlinx.coroutines.flow.map
import java.time.LocalDate

internal class BackupReminderRepositoryImpl(private val preferences: DataStore<Preferences>) :
    BackupReminderRepository {
    override val schedule = preferences.data.map(::readSchedule)

    override suspend fun configure(cadence: BackupReminderCadence?, today: LocalDate) {
        preferences.edit { stored ->
            if (cadence == null) {
                stored.remove(cadenceKey)
                stored.remove(dueDayKey)
            } else {
                stored[cadenceKey] = cadence.name
                stored[dueDayKey] = today.plusMonths(cadence.months).toEpochDay()
            }
        }
    }

    override suspend fun reschedule(expected: BackupReminderSchedule.Scheduled, nextDue: LocalDate) {
        preferences.edit { stored ->
            if (readSchedule(stored) == expected) stored[dueDayKey] = nextDue.toEpochDay()
        }
    }

    private fun readSchedule(stored: Preferences): BackupReminderSchedule {
        val name = stored[cadenceKey] ?: return BackupReminderSchedule.Off
        val cadence = BackupReminderCadence.valueOf(name)
        val epochDay = checkNotNull(stored[dueDayKey]) { "Backup reminder has no due date" }
        return BackupReminderSchedule.Scheduled(cadence, LocalDate.ofEpochDay(epochDay))
    }

    private companion object {
        val cadenceKey = stringPreferencesKey("backup_reminder_cadence")
        val dueDayKey = longPreferencesKey("backup_reminder_due_day")
    }
}
