package com.example.edge.ktor.storage.postgres

import com.example.kernel.storage.ActionLogRecord
import com.example.kernel.storage.HistoryCursorRecord
import com.example.kernel.storage.HistoryRepository
import com.example.kernel.storage.HistoryScope
import com.example.kernel.storage.HistoryScopeType
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Instant

class JooqHistoryRepository(
    private val dsl: DSLContext
) : HistoryRepository {

    private val actionLogTable = DSL.table("action_log")
    private val actionId = DSL.field("id", Long::class.java)
    private val actionScopeType = DSL.field("scope_type", String::class.java)
    private val actionScopeId = DSL.field("scope_id", String::class.java)
    private val actionType = DSL.field("action_type", String::class.java)
    private val actionPayload = DSL.field("payload", JSONB::class.java)
    private val actionInversePayload = DSL.field("inverse_payload", JSONB::class.java)
    private val actionCreatedAt = DSL.field("created_at", Instant::class.java)

    private val cursorTable = DSL.table("history_cursor")
    private val cursorScopeType = DSL.field("scope_type", String::class.java)
    private val cursorScopeId = DSL.field("scope_id", String::class.java)
    private val cursorActionId = DSL.field("cursor_action_id", Long::class.java)
    private val cursorUpdatedAt = DSL.field("updated_at", Instant::class.java)

    override suspend fun appendAction(record: ActionLogRecord): ActionLogRecord = PostgresStorageBootstrap.io {
        val inserted = dsl.insertInto(actionLogTable)
            .set(actionScopeType, record.scope.type.name)
            .set(actionScopeId, record.scope.id)
            .set(actionType, record.actionType)
            .set(actionPayload, JSONB.valueOf(JsonbCodec.encode(record.payload)))
            .set(actionInversePayload, JSONB.valueOf(JsonbCodec.encode(record.inversePayload)))
            .set(actionCreatedAt, record.createdAt)
            .returning(actionId)
            .fetchOne()

        record.copy(id = inserted?.get(actionId))
    }

    override suspend fun listActions(scope: HistoryScope, limit: Int): List<ActionLogRecord> = PostgresStorageBootstrap.io {
        dsl.select(
            actionId,
            actionScopeType,
            actionScopeId,
            actionType,
            actionPayload,
            actionInversePayload,
            actionCreatedAt
        )
            .from(actionLogTable)
            .where(actionScopeType.eq(scope.type.name).and(actionScopeId.eq(scope.id)))
            .orderBy(actionId.desc())
            .limit(limit.coerceIn(1, 1000))
            .fetch(::toAction)
    }

    override suspend fun getCursor(scope: HistoryScope): HistoryCursorRecord? = PostgresStorageBootstrap.io {
        dsl.select(cursorScopeType, cursorScopeId, cursorActionId, cursorUpdatedAt)
            .from(cursorTable)
            .where(cursorScopeType.eq(scope.type.name).and(cursorScopeId.eq(scope.id)))
            .fetchOne(::toCursor)
    }

    override suspend fun setCursor(scope: HistoryScope, cursorActionId: Long?): HistoryCursorRecord = PostgresStorageBootstrap.io {
        val now = Instant.now()
        dsl.insertInto(cursorTable)
            .set(cursorScopeType, scope.type.name)
            .set(cursorScopeId, scope.id)
            .set(this.cursorActionId, cursorActionId)
            .set(cursorUpdatedAt, now)
            .onConflict(cursorScopeType, cursorScopeId)
            .doUpdate()
            .set(this.cursorActionId, cursorActionId)
            .set(cursorUpdatedAt, now)
            .execute()

        HistoryCursorRecord(scope = scope, cursorActionId = cursorActionId, updatedAt = now)
    }

    private fun toAction(record: Record): ActionLogRecord {
        return ActionLogRecord(
            id = record.get(actionId),
            scope = HistoryScope(
                type = parseScopeType(record.get(actionScopeType)),
                id = record.get(actionScopeId) ?: ""
            ),
            actionType = record.get(actionType) ?: "",
            payload = JsonbCodec.decode(record.get(actionPayload)?.data()),
            inversePayload = JsonbCodec.decode(record.get(actionInversePayload)?.data()),
            createdAt = record.get(actionCreatedAt) ?: Instant.now()
        )
    }

    private fun toCursor(record: Record): HistoryCursorRecord {
        return HistoryCursorRecord(
            scope = HistoryScope(
                type = parseScopeType(record.get(cursorScopeType)),
                id = record.get(cursorScopeId) ?: ""
            ),
            cursorActionId = record.get(cursorActionId),
            updatedAt = record.get(cursorUpdatedAt) ?: Instant.now()
        )
    }

    private fun parseScopeType(value: String?): HistoryScopeType {
        return runCatching {
            HistoryScopeType.valueOf(value ?: HistoryScopeType.SESSION.name)
        }.getOrDefault(HistoryScopeType.SESSION)
    }
}
