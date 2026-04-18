package com.example.edge.ktor.storage.postgres

import com.example.kernel.storage.DatasetObjectRecord
import com.example.kernel.storage.ObjectRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class JooqObjectRepository(
    private val dsl: DSLContext
) : ObjectRepository {

    private val table = DSL.table("dataset_object")
    private val colId = DSL.field("id", UUID::class.java)
    private val colDatasetId = DSL.field("dataset_id", String::class.java)
    private val colName = DSL.field("name", String::class.java)
    private val colCategory = DSL.field("category", String::class.java)
    private val colPreviewRef = DSL.field("preview_ref", String::class.java)
    private val colProperties = DSL.field("properties", JSONB::class.java)
    private val colUpdatedAt = DSL.field("updated_at", Instant::class.java)

    override suspend fun upsert(record: DatasetObjectRecord): DatasetObjectRecord = PostgresStorageBootstrap.io {
        dsl.insertInto(table)
            .set(colId, record.id)
            .set(colDatasetId, record.datasetId)
            .set(colName, record.name)
            .set(colCategory, record.category)
            .set(colPreviewRef, record.previewRef)
            .set(colProperties, JSONB.valueOf(JsonbCodec.encode(record.properties)))
            .set(colUpdatedAt, record.updatedAt)
            .onConflict(colId)
            .doUpdate()
            .set(colDatasetId, record.datasetId)
            .set(colName, record.name)
            .set(colCategory, record.category)
            .set(colPreviewRef, record.previewRef)
            .set(colProperties, JSONB.valueOf(JsonbCodec.encode(record.properties)))
            .set(colUpdatedAt, record.updatedAt)
            .execute()
        record
    }

    override suspend fun findById(id: UUID): DatasetObjectRecord? = PostgresStorageBootstrap.io {
        dsl.select(colId, colDatasetId, colName, colCategory, colPreviewRef, colProperties, colUpdatedAt)
            .from(table)
            .where(colId.eq(id))
            .fetchOne(::toRecord)
    }

    override suspend fun findByDataset(datasetId: String): List<DatasetObjectRecord> = PostgresStorageBootstrap.io {
        dsl.select(colId, colDatasetId, colName, colCategory, colPreviewRef, colProperties, colUpdatedAt)
            .from(table)
            .where(colDatasetId.eq(datasetId))
            .orderBy(colUpdatedAt.desc())
            .fetch(::toRecord)
    }

    private fun toRecord(r: org.jooq.Record): DatasetObjectRecord {
        return DatasetObjectRecord(
            id = r.get(colId) ?: error("dataset_object.id is null"),
            datasetId = r.get(colDatasetId) ?: "",
            name = r.get(colName) ?: "",
            category = r.get(colCategory),
            previewRef = r.get(colPreviewRef) ?: "",
            properties = JsonbCodec.decode(r.get(colProperties)?.data()),
            updatedAt = r.get(colUpdatedAt) ?: Instant.now()
        )
    }
}
