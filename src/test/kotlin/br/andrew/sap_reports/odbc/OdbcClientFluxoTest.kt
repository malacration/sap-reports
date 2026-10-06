package br.andrew.sap_reports.odbc

import br.andrew.sap_reports.config.OdbcProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdbcClientFluxoTest {

    private lateinit var servidor: WireMockServer
    private lateinit var client: OdbcClient

    @BeforeAll
    fun subir() {
        servidor = WireMockServer(options().dynamicPort()).also { it.start() }
        client = OdbcClient(OdbcProperties(baseUrl = "http://localhost:${servidor.port()}", apiKey = "chave"))
    }

    @AfterAll
    fun derrubar() = servidor.stop()

    private fun responde(status: Int, corpo: String, tipo: String = "application/x-ndjson") {
        servidor.resetAll()
        servidor.stubFor(post(urlEqualTo("/api/v1/query/stream"))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", tipo).withBody(corpo)))
    }

    private fun consumir(): Pair<List<Map<String, Any?>>, FimFluxo> {
        val linhas = mutableListOf<Map<String, Any?>>()
        val fim = client.consultarFluxo("SELECT 1", emptyMap(), 10, {}, { linhas += it })
        return linhas to fim
    }

    @Test
    fun `le colunas, linhas e fim e envia a api key`() {
        responde(200, """
            {"tipo":"colunas","columns":[{"name":"V","type":"DECIMAL","nullable":true}]}
            {"tipo":"linha","row":{"V":"1.10"}}
            {"tipo":"linha","row":{"V":null}}
            {"tipo":"fim","rowCount":2,"truncated":false,"elapsedMs":5}
        """.trimIndent())
        val (linhas, fim) = consumir()
        assertEquals(listOf(mapOf("V" to "1.10"), mapOf("V" to null)), linhas)
        assertEquals(FimFluxo(2, false, 5), fim)
        servidor.verify(postRequestedFor(urlEqualTo("/api/v1/query/stream")).withHeader("X-API-Key", equalTo("chave")))
    }

    @Test
    fun `sem mensagem de fim o resultado e incompleto`() {
        responde(200, """{"tipo":"colunas","columns":[]}""" + "\n" + """{"tipo":"linha","row":{"V":1}}""")
        assertEquals("resultado_incompleto", assertFailsWith<OdbcException> { consumir() }.codigo)
    }

    @Test
    fun `erro no meio do fluxo preserva o codigo e nao repassa texto do banco`() {
        responde(200, """{"tipo":"colunas","columns":[]}""" + "\n" +
            """{"tipo":"erro","erro":"timeout","mensagem":"SELECT segredo FROM tabela"}""")
        val erro = assertFailsWith<OdbcException> { consumir() }
        assertEquals("timeout", erro.codigo)
        assertEquals("A consulta excedeu o tempo limite.", erro.message)
    }

    @Test
    fun `erro antes do fluxo vem como http comum`() {
        responde(503, """{"erro":"ocupado","mensagem":"Limite de fluxos"}""", "application/json")
        val erro = assertFailsWith<OdbcException> { consumir() }
        assertEquals("ocupado", erro.codigo)
        assertEquals(503, erro.status)
    }

    @Test
    fun `excecao de quem consome as linhas passa sem virar falha do sap-odbc`() {
        responde(200, """{"tipo":"colunas","columns":[]}""" + "\n" + """{"tipo":"linha","row":{"V":1}}""" + "\n" +
            """{"tipo":"fim","rowCount":1,"truncated":false,"elapsedMs":1}""")
        val disco = IllegalStateException("disco cheio")
        val erro = assertFailsWith<IllegalStateException> {
            client.consultarFluxo("SELECT 1", emptyMap(), 10, {}, { throw disco })
        }
        assertSame(disco, erro)
    }
}
