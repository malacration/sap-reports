package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Busca dos filtros de relatorio pelo sap-odbc, substituido por WireMock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CadastrosRelatorioTest {

    private lateinit var servidor: WireMockServer
    private lateinit var cadastros: CadastrosRelatorio

    @BeforeAll
    fun subir() {
        servidor = WireMockServer(options().dynamicPort())
        servidor.start()
        cadastros = CadastrosRelatorio(
            OdbcClient(OdbcProperties(baseUrl = "http://localhost:${servidor.port()}")),
            ReportProperties(),
        )
    }

    @AfterAll
    fun derrubar() = servidor.stop()

    @BeforeEach
    fun limpar() = servidor.resetAll()

    private fun responder(vararg linhas: String, truncado: Boolean = false) = servidor.stubFor(
        post(urlEqualTo("/api/v1/query")).willReturn(okJson(
            """{"columns":[],"rows":[${linhas.joinToString(",")}],"rowCount":${linhas.size},"truncated":$truncado,"elapsedMs":1}""",
        )),
    )

    @Test
    fun `cliente manda termo como parametro e filtra CardType C`() {
        responder("""{"CODIGO":"C001","NOME":"Mauro"}""")

        assertEquals(listOf(ItemCadastro("C001", "Mauro")), cadastros.buscar("parceiro_negocio", "mauro")?.itens)

        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%MAURO%")))
                .withRequestBody(matchingJsonPath("$.sql", containing("'C'")))
                .withRequestBody(matchingJsonPath("$.sql", notContaining("MAURO"))),
        )
    }

    @Test
    fun `fornecedor filtra CardType S`() {
        responder()
        cadastros.buscar("fornecedor", "x")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.sql", containing("'S'"))),
        )
    }

    @Test
    fun `item traz so ativos ou o codigo exato`() {
        responder()
        cadastros.buscar("item", " PAC001 ")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.sql", containing("frozenFor")))
                .withRequestBody(matchingJsonPath("$.params.exato", equalTo("PAC001"))),
        )
    }

    @Test
    fun `termo com aspas e curingas vai so no parametro`() {
        responder()
        cadastros.buscar("parceiro_negocio", "O'BRIEN_%")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.sql", equalTo(cadastros.sql("parceiro_negocio"))))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%O'BRIEN_%%"))),
        )
    }

    @Test
    fun `grupo_item devolve codigo numerico`() {
        responder("""{"CODIGO":"7","NOME":"RACAO"}""")
        assertEquals(listOf(ItemCadastro(7, "RACAO")), cadastros.buscar("grupo_item", null)?.itens)
    }

    @Test
    fun `so item manda exato`() {
        responder()
        cadastros.buscar("conta_contabil", "PAC001")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(not(matchingJsonPath("$.params.exato"))),
        )
    }

    @Test
    fun `item nao usa validFor`() =
        assertTrue("validFor" !in assertNotNull(cadastros.sql("item")))

    @Test
    fun `cnpj so com digitos vira mascara`() =
        assertEquals("%12.345.678/0001-90%", cadastros.termo("12345678000190"))

    @Test
    fun `cpf so com digitos vira mascara`() =
        assertEquals("%123.456.789-01%", cadastros.termo("12345678901"))

    @Test
    fun `asterisco vira coringa`() =
        assertEquals("%MAURO%CARRETA%", cadastros.termo("mauro*carreta"))

    @Test
    fun `termo vazio busca tudo`() {
        assertEquals("%", cadastros.termo(null))
        assertEquals("%", cadastros.termo("  "))
    }

    @Test
    fun `condicao de pagamento devolve codigo numerico`() {
        // BigDecimal chega como string do sap-odbc; inteiro, como numero.
        responder("""{"CODIGO":"-1","NOME":"À vista"}""", """{"CODIGO":3,"NOME":"30 dias"}""")
        assertEquals(
            listOf(ItemCadastro(-1, "À vista"), ItemCadastro(3, "30 dias")),
            cadastros.buscar("condicao_pagamento", null)?.itens,
        )
    }

    @Test
    fun `resposta nao traz documento`() {
        responder("""{"CODIGO":"C001","NOME":"Mauro","TaxId0":"12.345.678/0001-90"}""")
        val itens = assertNotNull(cadastros.buscar("parceiro_negocio", "12345678000190")).itens
        assertEquals(listOf(ItemCadastro("C001", "Mauro")), itens)
        assertTrue("12.345.678" !in itens.toString())
    }

    @Test
    fun `tipo desconhecido nao consulta`() {
        assertNull(cadastros.buscar("OCRD; DROP", "x"))
        assertNull(cadastros.buscar("filial", "x"))
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `prefixo vai como parametro e ordena codigo, depois nome, depois o resto`() {
        responder()
        cadastros.buscar("item", "pac")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.params.prefixo", equalTo("PAC%")))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%PAC%")))
                .withRequestBody(matchingJsonPath("$.sql", notContaining("PAC"))),
        )
        val sql = assertNotNull(cadastros.sql("item"))
        assertTrue(
            """CASE WHEN UPPER(T."ItemCode") LIKE :prefixo THEN 0 WHEN UPPER(T."ItemName") LIKE :prefixo THEN 1 ELSE 2 END AS "ORDEM"""" in sql,
            sql,
        )
        assertTrue("""ORDER BY "ORDEM", "NOME", "CODIGO"""" in sql, sql)
    }

    @Test
    fun `prefixo prepara como o termo`() {
        assertEquals("%", cadastros.prefixo(null))
        assertEquals("12.345.678/0001-90%", cadastros.prefixo("12345678000190"))
        assertEquals("MAURO%CARRETA%", cadastros.prefixo(" mauro*carreta "))
    }

    @Test
    fun `parceiro ordena pelo CardCode e continua sem duplicata`() {
        val sql = assertNotNull(cadastros.sql("parceiro_negocio"))
        assertTrue("SELECT DISTINCT" in sql, sql)
        assertTrue("""WHEN UPPER(T."CardCode") LIKE :prefixo THEN 0""" in sql, sql)
        assertTrue("TaxId0\") LIKE :prefixo" !in sql, sql)
    }

    @Test
    fun `codigo numerico nao pesa na ordem`() {
        val sql = assertNotNull(cadastros.sql("condicao_pagamento"))
        assertTrue("""CASE WHEN UPPER(T."PymntGroup") LIKE :prefixo THEN 1 ELSE 2 END""" in sql, sql)
    }

    @Test
    fun `pagina 0 nao desloca e pagina 1 desloca 50`() {
        assertTrue("LIMIT 51 OFFSET 0" in assertNotNull(cadastros.sql("item")))
        assertTrue("LIMIT 51 OFFSET 50" in assertNotNull(cadastros.sql("item", 1)))
        responder()
        cadastros.buscar("deposito", "x", 2)
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.sql", containing("LIMIT 51 OFFSET 100"))),
        )
    }

    @Test
    fun `truncated do sap-odbc vira proxima pagina`() {
        responder("""{"CODIGO":"01","NOME":"A"}""", truncado = true)
        assertTrue(assertNotNull(cadastros.buscar("deposito", null)).temProxima)
        servidor.resetAll()
        responder("""{"CODIGO":"01","NOME":"A"}""")
        assertEquals(false, assertNotNull(cadastros.buscar("deposito", null)).temProxima)
    }

    @Test
    fun `pagina fora da faixa nao consulta`() {
        assertFailsWith<IllegalArgumentException> { cadastros.buscar("item", "x", -1) }
        assertFailsWith<IllegalArgumentException> { cadastros.buscar("item", "x", 201) }
        assertFailsWith<IllegalArgumentException> { cadastros.sql("item", -1) }
        assertFailsWith<IllegalArgumentException> { cadastros.sql("item", 201) }
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `limite de 50 linhas`() {
        responder()
        cadastros.buscar("deposito", "")
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.maxRows", equalTo("50")))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%"))),
        )
    }

    @Test
    fun `schema do cadastro qualifica as tabelas`() {
        val comSchema = CadastrosRelatorio(OdbcClient(OdbcProperties()), ReportProperties(schemaCadastro = "HOMOLOG2"))
        val tabelas = mapOf(
            "parceiro_negocio" to listOf("OCRD", "CRD7"),
            "fornecedor" to listOf("OCRD", "CRD7"),
            "item" to listOf("OITM"),
            "conta_contabil" to listOf("OACT"),
            "centro_custo" to listOf("OPRC"),
            "condicao_pagamento" to listOf("OCTG"),
            "grupo_item" to listOf("OITB"),
            "deposito" to listOf("OWHS"),
        )
        assertEquals(tabelas.keys, comSchema.tipos)
        tabelas.forEach { (tipo, nomes) ->
            val sql = assertNotNull(comSchema.sql(tipo))
            nomes.forEach { assertTrue("HOMOLOG2.$it" in sql, "$tipo: $it sem schema") }
        }
    }
}
