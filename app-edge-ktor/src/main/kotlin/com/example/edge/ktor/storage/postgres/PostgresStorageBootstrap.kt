package com.example.edge.ktor.storage.postgres

import com.example.kernel.storage.HistoryRepository
import com.example.kernel.storage.ObjectRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

data class StorageBindings(
    val dataSource: DataSource,
    val dsl: DSLContext,
    val objectRepository: ObjectRepository,
    val historyRepository: HistoryRepository
)

object PostgresStorageBootstrap {
    fun initializeFromEnvOrNull(): StorageBindings? {
        val config = PostgresConfig.fromEnv() ?: return null
        val dataSource = createDataSource(config)

        if (config.migrateOnStart) {
            runMigrations(config, dataSource)
        }

        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        return StorageBindings(
            dataSource = dataSource,
            dsl = dsl,
            objectRepository = JooqObjectRepository(dsl),
            historyRepository = JooqHistoryRepository(dsl)
        )
    }

    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun createDataSource(config: PostgresConfig): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maxPoolSize
            driverClassName = "org.postgresql.Driver"
            isAutoCommit = true
        }
        return HikariDataSource(hikari)
    }

    private fun runMigrations(config: PostgresConfig, dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
        println("[storage] Flyway migrations applied for ${config.jdbcUrl}")
    }
}
