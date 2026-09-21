package br.andrew.sap_reports.security

import br.andrew.sap_reports.config.ReportProperties
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/**
 * Tokens de servico para agentes de IA criarem relatorios pela API.
 *
 * Desenho:
 *  - o valor e mostrado UMA VEZ, na criacao; o banco guarda so o SHA-256.
 *    Token recuperavel e um segredo que vaza junto com o backup;
 *  - expiracao OBRIGATORIA, com teto configuravel. Credencial de maquina
 *    esquecida e o risco mais comum desse tipo de token;
 *  - revogavel a qualquer momento;
 *  - registra o ultimo uso, para descobrir token ativo e esquecido.
 */
@Service
open class ServiceTokenService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: ReportProperties,
) {
    private val random = SecureRandom()

    data class TokenCriado(val id: String, val nome: String, val valor: String, val expiraEm: LocalDateTime)
    data class TokenResumo(
        val id: String, val nome: String, val prefixo: String, val criadoPor: String,
        val criadoEm: LocalDateTime, val expiraEm: LocalDateTime,
        val revogadoEm: LocalDateTime?, val ultimoUso: LocalDateTime?,
    ) {
        val ativo: Boolean get() = revogadoEm == null && expiraEm.isAfter(LocalDateTime.now())
    }

    fun criar(nome: String, diasValidade: Int, criadoPor: String): TokenCriado {
        require(nome.isNotBlank()) { "Informe um nome para identificar o token." }
        require(diasValidade in 1..props.tokenMaxDias) {
            "A validade deve estar entre 1 e ${props.tokenMaxDias} dias."
        }

        // 32 bytes aleatorios: espaco grande o bastante para forca bruta ser
        // inviavel mesmo se a API estiver exposta na internet.
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val valor = "rpt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = UUID.randomUUID().toString()
        val expira = LocalDateTime.now().plusDays(diasValidade.toLong())

        jdbc.update(
            """INSERT INTO RELATORIO_TOKEN (ID, NOME, HASH, PREFIXO, CRIADO_POR, CRIADO_EM, EXPIRA_EM)
               VALUES (:id, :nome, :hash, :prefixo, :criadoPor, :agora, :expira)""",
            mapOf(
                "id" to id, "nome" to nome, "hash" to hash(valor),
                "prefixo" to valor.take(12), "criadoPor" to criadoPor,
                "agora" to LocalDateTime.now(), "expira" to expira,
            ),
        )
        return TokenCriado(id, nome, valor, expira)
    }

    /**
     * Valida o token e devolve a identidade, ou null.
     *
     * A busca e pelo HASH: o valor apresentado nunca e comparado em texto, e o
     * banco nao tem como devolver o token de ninguem.
     */
    open fun autenticar(valor: String): UsuarioAutenticado? {
        val linha = jdbc.queryForList(
            """SELECT ID, NOME, CRIADO_POR, EXPIRA_EM, REVOGADO_EM
               FROM RELATORIO_TOKEN WHERE HASH = :hash""",
            mapOf("hash" to hash(valor)),
        ).firstOrNull() ?: return null

        if (linha["REVOGADO_EM"] != null) return null
        val expira = (linha["EXPIRA_EM"] as? java.sql.Timestamp)?.toLocalDateTime() ?: return null
        if (expira.isBefore(LocalDateTime.now())) return null

        jdbc.update(
            "UPDATE RELATORIO_TOKEN SET ULTIMO_USO = :agora WHERE ID = :id",
            mapOf("agora" to LocalDateTime.now(), "id" to linha["ID"]),
        )

        // Herda o papel de autoria, mas marcado como token de servico - e essa
        // marca que impede publicar.
        return UsuarioAutenticado(
            usuario = "token:${linha["NOME"]}",
            sapId = linha["CRIADO_POR"]?.toString() ?: "",
            papeis = setOf(props.authorRole),
            filiais = emptySet(),
            tokenDeServico = true,
        )
    }

    fun listar(): List<TokenResumo> = jdbc.queryForList(
        """SELECT ID, NOME, PREFIXO, CRIADO_POR, CRIADO_EM, EXPIRA_EM, REVOGADO_EM, ULTIMO_USO
           FROM RELATORIO_TOKEN ORDER BY CRIADO_EM DESC""",
        emptyMap<String, Any>(),
    ).map { l ->
        TokenResumo(
            id = l["ID"].toString(), nome = l["NOME"].toString(), prefixo = l["PREFIXO"].toString(),
            criadoPor = l["CRIADO_POR"].toString(),
            criadoEm = (l["CRIADO_EM"] as java.sql.Timestamp).toLocalDateTime(),
            expiraEm = (l["EXPIRA_EM"] as java.sql.Timestamp).toLocalDateTime(),
            revogadoEm = (l["REVOGADO_EM"] as? java.sql.Timestamp)?.toLocalDateTime(),
            ultimoUso = (l["ULTIMO_USO"] as? java.sql.Timestamp)?.toLocalDateTime(),
        )
    }

    fun revogar(id: String): Boolean = jdbc.update(
        "UPDATE RELATORIO_TOKEN SET REVOGADO_EM = :agora WHERE ID = :id AND REVOGADO_EM IS NULL",
        mapOf("agora" to LocalDateTime.now(), "id" to id),
    ) > 0

    private fun hash(valor: String): String =
        MessageDigest.getInstance("SHA-256").digest(valor.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
