package br.andrew.sap_reports.storage

import br.andrew.sap_reports.definition.ReportDefinition
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.jdbc.support.JdbcUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDateTime

/** Persistencia JDBC do catalogo. As fontes em RELATORIO_VERSAO nunca sao atualizadas. */
@Repository
class ReportRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val relatorioMapper = RowMapper { rs: ResultSet, _: Int ->
        RelatorioRegistro(
            id = rs.getLong("ID"),
            nome = rs.getString("NOME"),
            descricao = rs.getString("DESCRICAO"),
            papeis = lista(rs.getString("PAPEIS")),
            formatos = lista(rs.getString("FORMATOS")),
            versaoPublicada = rs.getInt("VERSAO_PUBLICADA").let { if (rs.wasNull()) null else it },
            ultimaVersao = rs.getInt("ULTIMA_VERSAO"),
            criadoPor = rs.getString("CRIADO_POR"),
            criadoEm = rs.getTimestamp("CRIADO_EM").toLocalDateTime(),
            atualizadoEm = rs.getTimestamp("ATUALIZADO_EM").toLocalDateTime(),
        )
    }

    private val versaoMapper = RowMapper { rs: ResultSet, _: Int ->
        RelatorioVersaoRegistro(
            relatorioId = rs.getLong("RELATORIO_ID"),
            versao = rs.getInt("VERSAO"),
            definicao = rs.getString("DEFINICAO"),
            template = rs.getString("TEMPLATE"),
            criadoPor = rs.getString("CRIADO_POR"),
            criadoEm = rs.getTimestamp("CRIADO_EM").toLocalDateTime(),
            publicadoEm = rs.getTimestamp("PUBLICADO_EM")?.toLocalDateTime(),
        )
    }

    @Transactional
    fun criar(def: ReportDefinition, definicao: String, template: String, quem: String): RelatorioRegistro {
        val agora = agora()
        // ID e IDENTITY no banco: o relatorio nao tem mais chave escolhida pelo autor.
        val id = inserirComIdentity(
            """
            INSERT INTO RELATORIO
                (NOME, DESCRICAO, PAPEIS, FORMATOS, VERSAO_PUBLICADA,
                 ULTIMA_VERSAO, CRIADO_POR, CRIADO_EM, ATUALIZADO_EM)
            VALUES
                (:nome, :descricao, :papeis, :formatos, NULL,
                 1, :quem, :agora, :agora)
            """.trimIndent(),
            parametrosBase(def, quem, agora),
        )
        inserirVersao(id, 1, definicao, template, quem, agora)
        auditar(id, 1, AcaoAuditoria.CRIOU, quem, agora)
        return buscar(id)!!
    }

    @Transactional
    fun novaVersao(
        id: Long,
        def: ReportDefinition,
        definicao: String,
        template: String,
        quem: String,
    ): RelatorioRegistro {
        val atual = bloquear(id)
        val numero = atual.ultimaVersao + 1
        val agora = agora()
        val params = parametrosBase(def, quem, agora)
            .addValue("id", id)
            .addValue("versao", numero)
        jdbc.update(
            """
            UPDATE RELATORIO SET NOME=:nome, DESCRICAO=:descricao, PAPEIS=:papeis,
                FORMATOS=:formatos, ULTIMA_VERSAO=:versao, ATUALIZADO_EM=:agora
            WHERE ID=:id
            """.trimIndent(),
            params,
        )
        inserirVersao(id, numero, definicao, template, quem, agora)
        auditar(id, numero, AcaoAuditoria.NOVA_VERSAO, quem, agora)
        return buscar(id)!!
    }

    @Transactional
    fun publicar(id: Long, versao: Int, quem: String): RelatorioRegistro {
        bloquear(id)
        exigirVersao(id, versao)
        val agora = agora()
        jdbc.update(
            "UPDATE RELATORIO SET VERSAO_PUBLICADA=:versao, ATUALIZADO_EM=:agora WHERE ID=:id",
            mapOf("id" to id, "versao" to versao, "agora" to agora),
        )
        // Registra somente a primeira publicacao; a fonte da versao permanece imutavel.
        jdbc.update(
            """
            UPDATE RELATORIO_VERSAO SET PUBLICADO_EM=COALESCE(PUBLICADO_EM, :agora)
            WHERE RELATORIO_ID=:id AND VERSAO=:versao
            """.trimIndent(),
            mapOf("id" to id, "versao" to versao, "agora" to agora),
        )
        auditar(id, versao, AcaoAuditoria.PUBLICOU, quem, agora)
        return buscar(id)!!
    }

    @Transactional
    fun rollback(id: Long, versao: Int, quem: String): RelatorioRegistro {
        bloquear(id)
        exigirVersao(id, versao)
        val agora = agora()
        jdbc.update(
            "UPDATE RELATORIO SET VERSAO_PUBLICADA=:versao, ATUALIZADO_EM=:agora WHERE ID=:id",
            mapOf("id" to id, "versao" to versao, "agora" to agora),
        )
        auditar(id, versao, AcaoAuditoria.ROLLBACK, quem, agora)
        return buscar(id)!!
    }

    @Transactional
    fun remover(id: Long, quem: String) {
        val atual = bloquear(id)
        val agora = agora()
        auditar(id, atual.ultimaVersao, AcaoAuditoria.REMOVEU, quem, agora)
        jdbc.update("DELETE FROM RELATORIO WHERE ID=:id", mapOf("id" to id))
    }

    fun listarTodos(): List<RelatorioRegistro> = jdbc.query(
        "SELECT * FROM RELATORIO ORDER BY NOME, ID",
        emptyMap<String, Any>(),
        relatorioMapper,
    )

    fun listarPublicados(): List<Pair<RelatorioRegistro, RelatorioVersaoRegistro>> =
        listarTodos().asSequence()
            .filter { it.versaoPublicada != null }
            .mapNotNull { relatorio ->
                buscarVersao(relatorio.id, relatorio.versaoPublicada!!)?.let { relatorio to it }
            }
            .toList()

    fun buscar(id: Long): RelatorioRegistro? = jdbc.query(
        "SELECT * FROM RELATORIO WHERE ID=:id",
        mapOf("id" to id),
        relatorioMapper,
    ).firstOrNull()

    fun buscarVersao(id: Long, versao: Int? = null): RelatorioVersaoRegistro? {
        val numero = versao ?: buscar(id)?.ultimaVersao ?: return null
        return jdbc.query(
            "SELECT * FROM RELATORIO_VERSAO WHERE RELATORIO_ID=:id AND VERSAO=:versao",
            mapOf("id" to id, "versao" to numero),
            versaoMapper,
        ).firstOrNull()
    }

    fun listarVersoes(id: Long): List<RelatorioVersaoRegistro> {
        if (buscar(id) == null) throw RelatorioNaoEncontradoException(id)
        return jdbc.query(
            "SELECT * FROM RELATORIO_VERSAO WHERE RELATORIO_ID=:id ORDER BY VERSAO DESC",
            mapOf("id" to id),
            versaoMapper,
        )
    }

    fun listarAuditoria(id: Long): List<EventoAuditoriaRegistro> = jdbc.query(
        """
        SELECT QUEM, QUANDO, ACAO, VERSAO FROM RELATORIO_AUDITORIA
        WHERE RELATORIO_ID=:id ORDER BY QUANDO DESC, ID DESC
        """.trimIndent(),
        mapOf("id" to id),
    ) { rs, _ ->
        EventoAuditoriaRegistro(
            quem = rs.getString("QUEM"),
            quando = rs.getTimestamp("QUANDO").toLocalDateTime(),
            acao = AcaoAuditoria.valueOf(rs.getString("ACAO")),
            versao = rs.getInt("VERSAO").let { if (rs.wasNull()) null else it },
        )
    }

    private fun bloquear(id: Long): RelatorioRegistro = jdbc.query(
        "SELECT * FROM RELATORIO WHERE ID=:id FOR UPDATE",
        mapOf("id" to id),
        relatorioMapper,
    ).firstOrNull() ?: throw RelatorioNaoEncontradoException(id)

    private fun exigirVersao(id: Long, versao: Int) {
        if (buscarVersao(id, versao) == null) throw RelatorioNaoEncontradoException(id, versao)
    }

    private fun inserirVersao(
        id: Long,
        versao: Int,
        definicao: String,
        template: String,
        quem: String,
        quando: LocalDateTime,
    ) {
        jdbc.update(
            """
            INSERT INTO RELATORIO_VERSAO
                (RELATORIO_ID, VERSAO, DEFINICAO, TEMPLATE, CRIADO_POR, CRIADO_EM, PUBLICADO_EM)
            VALUES (:id, :versao, :definicao, :template, :quem, :quando, NULL)
            """.trimIndent(),
            mapOf(
                "id" to id, "versao" to versao, "definicao" to definicao,
                "template" to template, "quem" to quem, "quando" to quando,
            ),
        )
    }

    private fun auditar(id: Long, versao: Int?, acao: AcaoAuditoria, quem: String, quando: LocalDateTime) {
        jdbc.update(
            """
            INSERT INTO RELATORIO_AUDITORIA (RELATORIO_ID, VERSAO, ACAO, QUEM, QUANDO)
            VALUES (:id, :versao, :acao, :quem, :quando)
            """.trimIndent(),
            mapOf("id" to id, "versao" to versao, "acao" to acao.name, "quem" to quem, "quando" to quando),
        )
    }

    /**
     * INSERT que devolve o IDENTITY gerado.
     *
     * O driver do HANA NAO implementa `prepareStatement(sql, String[])`, que e o
     * caminho do GeneratedKeyHolder com nome de coluna - quebrava so em producao,
     * porque o H2 dos testes suporta. No HANA le `CURRENT_IDENTITY_VALUE()`, que e
     * por SESSAO: dentro da transacao a conexao e a mesma do INSERT, entao nao ha
     * corrida com outra requisicao.
     */
    private fun inserirComIdentity(sql: String, params: MapSqlParameterSource): Long {
        if (ehHana) {
            jdbc.update(sql, params)
            return jdbc.jdbcTemplate.queryForObject("SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY", Long::class.java)
                ?: error("HANA nao devolveu o IDENTITY gerado para RELATORIO.")
        }
        val chave = GeneratedKeyHolder()
        jdbc.update(sql, params, chave, arrayOf("ID"))
        return chave.key?.toLong() ?: error("Banco nao devolveu o ID gerado para RELATORIO.")
    }

    /** O driver do HANA se identifica como "HDB". */
    private val ehHana: Boolean by lazy {
        val produto = JdbcUtils.extractDatabaseMetaData(jdbc.jdbcTemplate.dataSource!!) { it.databaseProductName }
        produto.equals("HDB", ignoreCase = true) || produto.contains("HANA", ignoreCase = true)
    }

    private fun parametrosBase(def: ReportDefinition, quem: String, agora: LocalDateTime) =
        MapSqlParameterSource()
            .addValue("nome", def.nome)
            .addValue("descricao", def.descricao)
            .addValue("papeis", def.papeis.joinToString(","))
            .addValue("formatos", def.formatos.joinToString(","))
            .addValue("quem", quem)
            .addValue("agora", agora)

    private fun agora(): LocalDateTime = LocalDateTime.now(clock)

    private fun lista(valor: String): List<String> =
        valor.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
