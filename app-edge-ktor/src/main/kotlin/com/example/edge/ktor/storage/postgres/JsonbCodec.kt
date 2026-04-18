package com.example.edge.ktor.storage.postgres

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

object JsonbCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(value: JsonObject): String = json.encodeToString(JsonObject.serializer(), value)

    fun decode(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return buildJsonObject { }
        return runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            buildJsonObject { }
        }
    }
}
