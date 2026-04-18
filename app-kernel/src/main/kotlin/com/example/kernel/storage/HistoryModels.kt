package com.example.kernel.storage

import java.time.Instant

enum class HistoryScopeType {
    USER,
    SESSION
}

data class HistoryScope(
    val type: HistoryScopeType,
    val id: String
)

data class ActionLogRecord(
    val id: Long? = null,
    val scope: HistoryScope,
    val actionType: String,
    val payloadJson: String,
    val inversePayloadJson: String,
    val createdAt: Instant = Instant.now()
)

data class HistoryCursorRecord(
    val scope: HistoryScope,
    val cursorActionId: Long?,
    val updatedAt: Instant = Instant.now()
)
