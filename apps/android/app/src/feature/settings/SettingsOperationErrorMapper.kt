package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.EngineCommandFailureException

class SettingsOperationErrorMapper {
    fun map(throwable: Throwable, fallbackMessage: String): SettingsOperationError =
        SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))

    fun mapStorageFailure(throwable: Throwable, fallbackMessage: String): SettingsOperationError.Message {
        val causes = generateSequence(throwable, { it.cause }).take(8).toList()
        val typed = causes.filterIsInstance<EngineCommandFailureException>().firstOrNull()
        val code = typed?.failure?.code ?: causes.last().javaClass.simpleName
        val chain = causes.joinToString(" -> ") { it.javaClass.simpleName }
        return SettingsOperationError.Message(
            text = "$fallbackMessage ($code)",
            diagnostic = "storage-settings / $code / $chain",
        )
    }
}
