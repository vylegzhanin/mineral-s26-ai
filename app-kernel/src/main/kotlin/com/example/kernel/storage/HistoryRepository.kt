package com.example.kernel.storage

interface HistoryRepository {
    suspend fun appendAction(record: ActionLogRecord): ActionLogRecord
    suspend fun listActions(scope: HistoryScope, limit: Int = 200): List<ActionLogRecord>
    suspend fun getCursor(scope: HistoryScope): HistoryCursorRecord?
    suspend fun setCursor(scope: HistoryScope, cursorActionId: Long?): HistoryCursorRecord
}
