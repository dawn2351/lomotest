package com.lomo.domain.model

import java.time.LocalDate

enum class BackupReminderCadence(val months: Long) {
    THREE_MONTHS(3),
    SIX_MONTHS(6),
    TWELVE_MONTHS(12),
}

sealed interface BackupReminderSchedule {
    data object Off : BackupReminderSchedule

    data class Scheduled(val cadence: BackupReminderCadence, val nextDue: LocalDate) : BackupReminderSchedule
}
