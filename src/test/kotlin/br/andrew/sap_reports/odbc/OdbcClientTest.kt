package br.andrew.sap_reports.odbc

import br.andrew.sap_reports.config.OdbcProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integracao com o sap-odbc, substituido por WireMock.
 *
 * O foco esta nas armadilhas do contrato real, confirmadas lendo o codigo do
 * sap-odbc: BigDecimal volta como string, e `truncated` significa dado faltando.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdbcClientTest {

    private lateinit var servidor: WireMockServer
    private lateinit var client: OdbcClient

    @BeforeAll
    fun subir() {
        servidor = WireMockServer(options().dynamicPort())
        servidor.start()
        client = OdbcClient(
            OdbcProperties(
                baseUrl = "http://localhost:${servidor.port()}",
                apiKey = "chave-de-teste",
                queryTimeoutSeconds = 30,
            ),
        )
    }

    @AfterAll
    fun derrubar() = servidor.stop()

    private fun respondeCom(status: Int, corpo: String) {
        servidor.resetAll()
        servidor.stubFor(
            post(urlEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpo)),
        )
    }

    @Test
    fun `envia api key e o corpo esperado pelo sap-odbc`() {
        respondeCom(200, """{"columns":[],"rows":[],"rowCount":0,"truncated":false,"elapsedMs":5}""")
        client.consultar("SELECT 1 FROM DUMMY", mapOf("a" to 1), 100)

        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withHeader("X-API-Key", equalTo("chave-de-teste"))
                .withRequestBody(matchingJsonPath("$.sql"))
                .withRequestBody(matchingJsonPath("$.params.a"))
                .withRequestBody(matchingJsonPath("$.maxRows", equalTo("100")))
                .withRequestBody(matchingJsonPath("$.timeoutSeconds", equalTo("30"))),
        )
    }

    @Test
    fun `BigDecimal chega como string e converte sem perder precisao`() {
        // O sap-odbc serializa BigDecimal com toPlainString(). Ler como Double
        // corromperia centavos - este teste trava esse comportamento.
        respondeCom(200, """
            {"columns":[{"name":"TOTAL","type":"DECIMAL","nullable":true}],
             "rows":[{"TOTAL":"12345678901234.56"}],
             "rowCount":1,"truncated":false,"elapsedMs":9}
        """.trimIndent())

        val r = client.consultar("SELECT TOTAL FROM T", emptyMap(), 10)
        val valor = r.rows.first()["TOTAL"]

        assertTrue(valor is String, "o contrato do sap-odbc entrega decimal como string")
        assertEquals(BigDecimal("12345678901234.56"), valor.paraDecimal())
        assertEquals("12345678901234.56", valor.paraDecimal()!!.toPlainString())
    }

    @Test
    fun `truncated verdadeiro e preservado para quem chama poder recusar`() {
        respondeCom(200, """
            {"columns":[],"rows":[{"A":1}],"rowCount":1,"truncated":true,"elapsedMs":3}
        """.trimIndent())
        assertTrue(client.consultar("SELECT A FROM T", emptyMap(), 1).truncated)
    }

    @Test
    fun `traduz sql invalido preservando o codigo do sap-odbc`() {
        respondeCom(400, """{"erro":"sql_invalido","mensagem":"Comentarios nao sao permitidos."}""")
        val ex = assertFailsWith<OdbcException> { client.consultar("SELECT 1 -- x", emptyMap(), 10) }
        assertEquals("sql_invalido", ex.codigo)
        assertTrue("recusada" in ex.message)
    }

    @Test
    fun `traduz timeout`() {
        respondeCom(504, """{"erro":"timeout","mensagem":"A consulta excedeu o tempo limite."}""")
        val ex = assertFailsWith<OdbcException> { client.consultar("SELECT 1 FROM T", emptyMap(), 10) }
        assertEquals("timeout", ex.codigo)
    }

    @Test
    fun `api key errada vira mensagem acionavel`() {
        respondeCom(401, """{"erro":"nao_autorizado","mensagem":"Header X-API-Key ausente ou invalido."}""")
        val ex = assertFailsWith<OdbcException> { client.consultar("SELECT 1 FROM T", emptyMap(), 10) }
        assertTrue("X-API-Key" in ex.message, "a mensagem deve dizer o que corrigir: ${ex.message}")
    }

    @Test
    fun `mensagem devolvida nunca contem o sql`() {
        respondeCom(400, """{"erro":"sql_invalido","mensagem":"erro perto de SELECT SENHA FROM SEGREDO"}""")
        val ex = assertFailsWith<OdbcException> { client.consultar("SELECT SENHA FROM SEGREDO", emptyMap(), 10) }
        assertTrue("SEGREDO" !in ex.message, "o SQL nao pode vazar ao usuario final: ${ex.message}")
    }

    @Test
    fun `coluna nula numa linha nao derruba a consulta`() {
        // Caso real: colaborador sem cargo cadastrado. NULL e dado legitimo no
        // ERP - o parser recusava e a pre-visualizacao inteira caia com 500.
        respondeCom(200, """
            {"columns":[{"name":"NOME","type":"NVARCHAR","nullable":true},
                        {"name":"CARGO","type":"NVARCHAR","nullable":true}],
             "rows":[{"NOME":"Ana","CARGO":"Analista"},{"NOME":"Bruno","CARGO":null}],
             "rowCount":2,"truncated":false,"elapsedMs":4}
        """.trimIndent())

        val r = client.consultar("SELECT NOME, CARGO FROM T", emptyMap(), 10)
        assertEquals(2, r.rows.size)
        assertTrue(r.rows[1].containsKey("CARGO"), "a coluna precisa continuar presente")
        assertEquals(null, r.rows[1]["CARGO"])
    }
}
