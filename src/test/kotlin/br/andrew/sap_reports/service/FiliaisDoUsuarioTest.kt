package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.security.UsuarioAutenticado
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cadastro de filiais lido pelo sap-odbc, substituido por WireMock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FiliaisDoUsuarioTest {

    private lateinit var servidor: WireMockServer
    private lateinit var filiais: FiliaisDoUsuario

    @BeforeAll
    fun subir() {
        servidor = WireMockServer(options().dynamicPort())
        servidor.start()
        filiais = FiliaisDoUsuario(
            OdbcClient(OdbcProperties(baseUrl = "http://localhost:${servidor.port()}")),
            ReportProperties(),
        )
    }

    @AfterAll
    fun derrubar() = servidor.stop()

    @BeforeEach
    fun limpar() = servidor.resetAll()

    private fun usuario(sapId: String = "10", vararg papeis: String) =
        UsuarioAutenticado("fulano", sapId, papeis.toSet().ifEmpty { setOf("vendedor") }, emptySet())

    @Test
    fun `le as filiais do cadastro pelo codigo do colaborador`() {
        // BigDecimal chega como string do sap-odbc; inteiro, como numero.
        servidor.stubFor(
            post(urlEqualTo("/api/v1/query")).willReturn(okJson(
                """{"columns":[],"rows":[{"BPLID":3},{"BPLID":"5"}],"rowCount":2,"truncated":false,"elapsedMs":1}""",
            )),
        )

        assertEquals(setOf(3, 5), filiais.permitidas(usuario("10")))

        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.params.colaborador", equalTo("10")))
                .withRequestBody(matchingJsonPath("$.sql", containing("HEM10")))
                .withRequestBody(matchingJsonPath("$.sql", containing("@RO_FILIAL_LINHA"))),
        )
    }

    @Test
    fun `admin e vendedor_admin veem todas sem consultar o cadastro`() {
        assertNull(filiais.permitidas(usuario("10", "admin")))
        assertNull(filiais.permitidas(usuario("10", "vendedor", "vendedor_admin")))
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `sem codigo de colaborador numerico nao tem filial`() {
        assertEquals(emptySet(), filiais.permitidas(usuario("joao")))
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `schema do cadastro qualifica todas as tabelas`() {
        val sql = FiliaisDoUsuario(OdbcClient(OdbcProperties()), ReportProperties(schemaCadastro = "HOMOLOG2")).sql()
        listOf("OHEM", "OSLP", "\"@RO_FILIAL_LINHA\"", "OBPL", "HEM10").forEach { tabela ->
            assertTrue("HOMOLOG2.$tabela" in sql, "$tabela sem schema")
        }
        assertFailsWith<IllegalArgumentException> { ReportProperties(schemaCadastro = "X; DROP") }
    }
}
