package br.andrew.sap_reports.storage

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifica que o Liquibase roda QUANDO O SPRING SOBE - nao apenas que o
 * changelog e valido.
 *
 * A distincao custou caro: `liquibase-core` sozinho NAO liga o Liquibase no
 * Boot 4, que modularizou as autoconfiguracoes. A LiquibaseAutoConfiguration
 * vive em `spring-boot-liquibase`, e sem esse modulo o servico sobe em silencio,
 * sem migrar nada - o sintoma e "tabela nao existe" em runtime, sem nenhuma
 * linha de liquibase no log.
 *
 * O teste que roda o changelog na mao (ChangelogAplicaTest) NAO pega isso: ele
 * prova que o changelog e valido, nao que alguem o executa.
 */
@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.liquibase.enabled=true",
        "spring.liquibase.default-schema=PUBLIC",
        "spring.datasource.url=jdbc:h2:mem:startup;MODE=LEGACY;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.schema=PUBLIC",
    ],
)
class LiquibaseNoStartupTest {

    @Autowired lateinit var contexto: ApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a autoconfiguracao do Liquibase esta ativa`() {
        // Sem o modulo spring-boot-liquibase este bean nao existe, e o servico
        // sobe sem migrar - falhando silenciosamente.
        assertTrue(
            contexto.getBeanNamesForType(liquibase.integration.spring.SpringLiquibase::class.java).isNotEmpty(),
            "SpringLiquibase ausente: o Liquibase NAO vai rodar no startup",
        )
    }

    @Test
    fun `as tabelas existem depois que o contexto sobe`() {
        val tabelas = jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
            String::class.java,
        ).filterNotNull().map { it.uppercase() }.toSet()

        listOf("RELATORIO", "RELATORIO_VERSAO", "RELATORIO_AUDITORIA", "RELATORIO_TOKEN")
            .forEach { assertTrue(it in tabelas, "faltou $it. Criadas: $tabelas") }
        assertTrue("DATABASECHANGELOG" in tabelas, "o Liquibase nao registrou execucao")
    }
}
