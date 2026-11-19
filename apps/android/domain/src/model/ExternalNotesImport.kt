package com.lomo.domain.model

enum class ExternalNotesSource { FLOMO, WE_READ }

data class ExternalNotesImportSummary(val noteCount: Int, val omittedAttachmentCount: Int)
