package com.example.edge.ktor.storage.postgres

data class PostgresConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val migrateOnStart: Boolean,
    val maxPoolSize: Int
) {
    companion object {
        fun fromEnv(): PostgresConfig? {
            val jdbcUrl = System.getenv("APP_DB_URL")?.trim().orEmpty()
            if (jdbcUrl.isBlank()) return null

            return PostgresConfig(
                jdbcUrl = jdbcUrl,
                username = System.getenv("APP_DB_USER")?.trim().orEmpty(),
                password = System.getenv("APP_DB_PASSWORD")?.trim().orEmpty(),
                migrateOnStart = (System.getenv("APP_DB_MIGRATE_ON_START") ?: "true").toBoolean(),
                maxPoolSize = System.getenv("APP_DB_POOL_SIZE")?.toIntOrNull()?.coerceAtLeast(1) ?: 8
            )
        }
    }
}
