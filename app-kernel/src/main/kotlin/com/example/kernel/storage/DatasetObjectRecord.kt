package com.example.kernel.storage

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class DatasetObjectRecord(
    val id: UUID,
    val datasetId: String,
    val name: String,
    val category: String?,
    val previewRef: String,
    val properties: JsonObject,
    val updatedAt: Instant = Instant.now()
)
