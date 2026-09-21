package br.andrew.sap_reports.security

import br.andrew.sap_reports.config.ReportProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.security.MessageDigest
import java.util.UUID

/**
 * Token de servico e credencial de maquina: se vazar, alguem cria relatorio no
 * lugar da IA. Os testes cobrem as garantias que sustentam o desenho -
 * irrecuperabilidade, expiracao e revogacao.
 */
class ServiceTokenServiceTest {

    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: ServiceTokenService

    @BeforeEach
    fun preparar() {
        val ds = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "")
        jdbc = JdbcTemplate(ds)
        jdbc.execute(
            """CREATE TABLE RELATORIO_TOKEN (
                 ID VARCHAR(36) PRIMARY KEY, NOME VARCHAR(100) NOT NULL,
                 HASH VARCHAR(64) NOT NULL UNIQUE, PREFIXO VARCHAR(12) NOT NULL,
                 CRIADO_POR VARCHAR(100) NOT NULL, CRIADO_EM TIMESTAMP NOT NULL,
                 EXPIRA_EM TIMESTAMP NOT NULL, REVOGADO_EM TIMESTAMP, ULTIMO_USO TIMESTAMP)""",
        )
        service = ServiceTokenService(NamedParameterJdbcTemplate(jdbc), ReportProperties())
    }

    @Test
    fun `o valor do token NAO fica no banco - so o hash`() {
        val criado = service.criar("GPT de relatorios", 30, "andrew")

        val guardado = jdbc.queryForMap("SELECT HASH, PREFIXO FROM RELATORIO_TOKEN")
        assertNotEquals(criado.valor, guardado["HASH"], "o valor nao pode ser armazenado")

        val esperado = MessageDigest.getInstance("SHA-256").digest(criado.valor.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(esperado, guardado["HASH"])
        // Prefixo serve so para a tela identificar qual token e qual.
        assertTrue(criado.valor.startsWith(guardado["PREFIXO"].toString()))
    }

    @Test
    fun `o token gerado e autenticavel e marcado como de servico`() {
        val criado = service.criar("gpt", 30, "andrew")
        val usuario = service.autenticar(criado.valor)
        assertNotNull(usuario)
        assertTrue(usuario!!.tokenDeServico, "e a marca que impede publicar")
        assertTrue("admin" in usuario.papeis, "herda o papel de autoria")
    }

    @Test
    fun `token inexistente nao autentica`() {
        assertNull(service.autenticar("rpt_valorQualquer"))
    }

    @Test
    fun `token revogado deixa de autenticar na hora`() {
        val criado = service.criar("gpt", 30, "andrew")
        assertTrue(service.revogar(criado.id))
        assertNull(service.autenticar(criado.valor), "revogacao precisa valer imediatamente")
    }

    @Test
    fun `token expirado nao autentica`() {
        val criado = service.criar("gpt", 1, "andrew")
        jdbc.update("UPDATE RELATORIO_TOKEN SET EXPIRA_EM = DATEADD('DAY', -1, CURRENT_TIMESTAMP)")
        assertNull(service.autenticar(criado.valor), "prazo vencido e a principal protecao de credencial de maquina")
    }

    @Test
    fun `validade acima do teto e recusada`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.criar("gpt", 365, "andrew")
        }
        assertTrue(ex.message!!.contains("90"), ex.message!!)
    }

    @Test
    fun `validade zero ou negativa e recusada`() {
        assertThrows(IllegalArgumentException::class.java) { service.criar("gpt", 0, "andrew") }
        assertThrows(IllegalArgumentException::class.java) { service.criar("gpt", -5, "andrew") }
    }

    @Test
    fun `nome em branco e recusado`() {
        // Sem nome ninguem sabe o que esta revogando depois.
        assertThrows(IllegalArgumentException::class.java) { service.criar("  ", 30, "andrew") }
    }

    @Test
    fun `dois tokens nunca sao iguais`() {
        val valores = (1..50).map { service.criar("t$it", 30, "andrew").valor }
        assertEquals(50, valores.toSet().size, "colisao indicaria gerador fraco")
        assertTrue(valores.all { it.startsWith("rpt_") && it.length > 40 })
    }

    @Test
    fun `o uso e registrado para descobrir token esquecido`() {
        val criado = service.criar("gpt", 30, "andrew")
        assertNull(service.listar().first().ultimoUso)
        service.autenticar(criado.valor)
        assertNotNull(service.listar().first().ultimoUso)
    }

    @Test
    fun `revogar duas vezes devolve false na segunda`() {
        val criado = service.criar("gpt", 30, "andrew")
        assertTrue(service.revogar(criado.id))
        assertFalse(service.revogar(criado.id))
    }
}
