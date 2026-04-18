package com.example.kernel.storage

import java.util.UUID

interface ObjectRepository {
    suspend fun upsert(record: DatasetObjectRecord): DatasetObjectRecord
    suspend fun findById(id: UUID): DatasetObjectRecord?
    suspend fun findByDataset(datasetId: String): List<DatasetObjectRecord>
}
