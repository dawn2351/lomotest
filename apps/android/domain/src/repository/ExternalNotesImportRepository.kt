package com.lomo.domain.repository

import com.lomo.domain.model.ExternalNotesImportSummary
import com.lomo.domain.model.ExternalNotesSource
import java.io.InputStream

interface ExternalNotesImportRepository {
    suspend fun importNotes(source: ExternalNotesSource, openInput: () -> InputStream?): ExternalNotesImportSummary
}
