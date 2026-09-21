package br.andrew.sap_reports.storage

import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * Executa o changelog de verdade.
 *
 * Os outros testes de repositorio criam as tabelas a mao (TestDatabase.kt), o
 * que nao exercita o changelog - ele podia estar quebrado e nenhum teste
 * acusaria. O erro so apareceria na subida, em ambiente real.
 *
 * Roda em H2: nao valida particularidade do HANA, mas pega o que quebra em
 * qualquer banco - YAML malformado, changeset duplicado, coluna referenciada
 * que nao existe, chave estrangeira apontando para tabela ausente.
 */
class ChangelogAplicaTest {

    private fun aplicar(): JdbcTemplate {
        val ds = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "",
        )
        ds.connection.use { conexao ->
            val database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(conexao))
            Liquibase(
                "db/changelog/db.changelog-master.yaml",
                ClassLoaderResourceAccessor(),
                database,
            ).use { it.update("") }
        }
        return JdbcTemplate(ds)
    }

    @Test
    fun `o changelog aplica e cria todas as tabelas`() {
        val jdbc = aplicar()
        val tabelas = jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
            String::class.java,
        ).filterNotNull().map { it.uppercase() }.toSet()

        listOf("RELATORIO", "RELATORIO_VERSAO", "RELATORIO_AUDITORIA", "RELATORIO_TOKEN")
            .forEach { assertTrue(it in tabelas, "faltou a tabela $it. Criadas: $tabelas") }

        // As de controle do proprio Liquibase - a prova de que ele registrou.
        assertTrue("DATABASECHANGELOG" in tabelas)
        assertTrue("DATABASECHANGELOGLOCK" in tabelas)
    }

    @Test
    fun `todos os changesets ficam registrados como executados`() {
        val jdbc = aplicar()
        val registros = jdbc.queryForList("SELECT ID, EXECTYPE FROM DATABASECHANGELOG")
        assertTrue(registros.isNotEmpty(), "nenhum changeset registrado")
        registros.forEach {
            assertEquals("EXECUTED", it["EXECTYPE"], "changeset ${it["ID"]} nao aplicou")
        }
    }

    @Test
    fun `aplicar duas vezes e idempotente`() {
        // Toda subida do servico roda o changelog. Se nao fosse idempotente, o
        // segundo start quebraria.
        val ds = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "",
        )
        repeat(2) {
            ds.connection.use { conexao ->
                val database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(JdbcConnection(conexao))
                Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database)
                    .use { lb -> lb.update("") }
            }
        }
        val total = JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Int::class.java)
        assertTrue(total!! > 0)
    }

    @Test
    fun `tabela pre-existente NAO derruba a migracao`() {
        // Caso real: a tabela foi criada fora do Liquibase (bootstrap manual, ou
        // execucao parcial anterior). Sem preCondition, a subida morre com
        // "cannot use duplicate table name" e o servico nao sobe.
        val ds = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "",
        )
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            """CREATE TABLE RELATORIO_TOKEN (
                 ID VARCHAR(36) PRIMARY KEY, NOME VARCHAR(100) NOT NULL,
                 HASH VARCHAR(64) NOT NULL, PREFIXO VARCHAR(12) NOT NULL,
                 CRIADO_POR VARCHAR(100) NOT NULL, CRIADO_EM TIMESTAMP NOT NULL,
                 EXPIRA_EM TIMESTAMP NOT NULL, REVOGADO_EM TIMESTAMP, ULTIMO_USO TIMESTAMP)""",
        )

        ds.connection.use { conexao ->
            val database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(conexao))
            Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database)
                .use { it.update("") }
        }

        // O changeset da tabela existente fica MARK_RAN; os demais aplicam.
        val tipos = jdbc.queryForList("SELECT ID, EXECTYPE FROM DATABASECHANGELOG")
            .associate { it["ID"].toString() to it["EXECTYPE"].toString() }
        assertEquals("MARK_RAN", tipos["004-relatorio-token"], "deveria marcar, nao recriar")
        assertTrue(tipos.values.none { it == "FAILED" }, "nenhum changeset pode falhar: $tipos")
    }

    @Test
    fun `banco existente com ID texto migra para ID numerico sem perder dados`() {
        val ds = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "",
        )
        val jdbc = JdbcTemplate(ds)
        // Schema como estava em producao antes da migracao 005-007.
        jdbc.execute(
            """CREATE TABLE RELATORIO (
                 ID VARCHAR(64) PRIMARY KEY, NOME VARCHAR(200) NOT NULL, DESCRICAO VARCHAR(500),
                 PAPEIS VARCHAR(500) NOT NULL, FORMATOS VARCHAR(100) NOT NULL,
                 VERSAO_PUBLICADA INTEGER, ULTIMA_VERSAO INTEGER NOT NULL,
                 CRIADO_POR VARCHAR(100) NOT NULL, CRIADO_EM TIMESTAMP NOT NULL, ATUALIZADO_EM TIMESTAMP NOT NULL)""",
        )
        jdbc.execute(
            """CREATE TABLE RELATORIO_VERSAO (
                 RELATORIO_ID VARCHAR(64) NOT NULL, VERSAO INTEGER NOT NULL,
                 DEFINICAO NCLOB NOT NULL, TEMPLATE NCLOB NOT NULL,
                 CRIADO_POR VARCHAR(100) NOT NULL, CRIADO_EM TIMESTAMP NOT NULL, PUBLICADO_EM TIMESTAMP,
                 PRIMARY KEY (RELATORIO_ID, VERSAO),
                 FOREIGN KEY (RELATORIO_ID) REFERENCES RELATORIO(ID) ON DELETE CASCADE)""",
        )
        jdbc.execute(
            """CREATE TABLE RELATORIO_AUDITORIA (
                 ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                 RELATORIO_ID VARCHAR(64) NOT NULL, VERSAO INTEGER,
                 ACAO VARCHAR(20) NOT NULL, QUEM VARCHAR(100) NOT NULL, QUANDO TIMESTAMP NOT NULL)""",
        )
        jdbc.update(
            """INSERT INTO RELATORIO VALUES
                 ('vendas-b', 'Vendas B', NULL, 'admin', 'pdf', 2, 2, 'ana', TIMESTAMP '2026-02-01 00:00:00', TIMESTAMP '2026-02-02 00:00:00'),
                 ('vendas-a', 'Vendas A', 'desc', 'admin,vendedor', 'pdf,csv', NULL, 1, 'bia', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')""",
        )
        jdbc.update(
            """INSERT INTO RELATORIO_VERSAO VALUES
                 ('vendas-b', 1, 'def-b1', 'tpl-b1', 'ana', TIMESTAMP '2026-02-01 00:00:00', NULL),
                 ('vendas-b', 2, 'def-b2', 'tpl-b2', 'ana', TIMESTAMP '2026-02-02 00:00:00', TIMESTAMP '2026-02-02 00:00:00'),
                 ('vendas-a', 1, 'def-a1', 'tpl-a1', 'bia', TIMESTAMP '2026-01-01 00:00:00', NULL)""",
        )
        jdbc.update(
            """INSERT INTO RELATORIO_AUDITORIA (RELATORIO_ID, VERSAO, ACAO, QUEM, QUANDO) VALUES
                 ('vendas-a', 1, 'CRIOU', 'bia', TIMESTAMP '2026-01-01 00:00:00'),
                 ('removido', 1, 'REMOVEU', 'ana', TIMESTAMP '2026-01-15 00:00:00'),
                 ('vendas-b', 2, 'PUBLICOU', 'ana', TIMESTAMP '2026-02-02 00:00:00')""",
        )

        ds.connection.use { conexao ->
            val database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(conexao))
            Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database)
                .use { it.update("") }
        }

        // Numeracao segue a ordem de criacao: vendas-a (jan) = 1, vendas-b (fev) = 2.
        val ids = jdbc.queryForList("SELECT CHAVE_ANTIGA, ID FROM RELATORIO")
            .associate { it["CHAVE_ANTIGA"].toString() to (it["ID"] as Number).toLong() }
        assertEquals(mapOf("vendas-a" to 1L, "vendas-b" to 2L), ids)
        assertEquals(
            2,
            jdbc.queryForObject("SELECT VERSAO_PUBLICADA FROM RELATORIO WHERE ID = 2", Int::class.java),
        )

        val versoes = jdbc.queryForList("SELECT RELATORIO_ID, VERSAO, DEFINICAO FROM RELATORIO_VERSAO ORDER BY RELATORIO_ID, VERSAO")
            .map { Triple((it["RELATORIO_ID"] as Number).toLong(), it["VERSAO"], it["DEFINICAO"].toString()) }
        assertEquals(listOf(Triple(1L, 1, "def-a1"), Triple(2L, 1, "def-b1"), Triple(2L, 2, "def-b2")), versoes)

        // Evento de relatorio ja removido fica sem ID, mas mantem a chave antiga.
        val auditoria = jdbc.queryForList("SELECT RELATORIO_ID, CHAVE_ANTIGA, ACAO FROM RELATORIO_AUDITORIA ORDER BY ID")
            .map { Triple((it["RELATORIO_ID"] as Number?)?.toLong(), it["CHAVE_ANTIGA"], it["ACAO"]) }
        assertEquals(
            listOf(Triple(1L, "vendas-a", "CRIOU"), Triple(null, "removido", "REMOVEU"), Triple(2L, "vendas-b", "PUBLICOU")),
            auditoria,
        )

        // Tabelas temporarias foram removidas e o IDENTITY continua depois dos migrados.
        val tabelas = jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String::class.java,
        )
        assertTrue(tabelas.none { it!!.startsWith("MIGRACAO_") }, "sobrou tabela temporaria: $tabelas")
        jdbc.update(
            """INSERT INTO RELATORIO (NOME, PAPEIS, FORMATOS, ULTIMA_VERSAO, CRIADO_POR, CRIADO_EM, ATUALIZADO_EM)
               VALUES ('Novo', 'admin', 'pdf', 1, 'ana', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
        )
        assertEquals(3L, jdbc.queryForObject("SELECT MAX(ID) FROM RELATORIO", Long::class.java))
    }
}
