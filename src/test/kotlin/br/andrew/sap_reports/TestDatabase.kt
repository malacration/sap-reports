package br.andrew.sap_reports

import br.andrew.sap_reports.storage.ReportRepository
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Banco H2 com o changelog real aplicado - o schema dos testes e o mesmo da producao. */
fun repositorioDeTeste(): Pair<ReportRepository, JdbcTemplate> {
    val dataSource = DriverManagerDataSource(
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1",
        "sa",
        "",
    )
    dataSource.connection.use { conexao ->
        val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conexao))
        Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database)
            .use { it.update("") }
    }
    val clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)
    return ReportRepository(NamedParameterJdbcTemplate(dataSource), clock) to JdbcTemplate(dataSource)
}
