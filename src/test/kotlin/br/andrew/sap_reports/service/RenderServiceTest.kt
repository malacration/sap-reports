package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Parametro
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.web.ParametrosInvalidosException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderServiceTest {
    @Test
    fun `cadastros numericos aceitam codigos inteiros e arrays sem interpolacao`() {
        for (tipo in listOf("filial", "vendedor")) {
            val param = Parametro(nome = "codigo", tipo = tipo)
            assertEquals(2.toBigDecimal(), service.validarParametros(listOf(param), mapOf("codigo" to 2))["codigo"])
            assertEquals(listOf(2.toBigDecimal(), 3.toBigDecimal()), service.validarParametros(
                listOf(param.copy(multiplo = true)), mapOf("codigo" to listOf(2, 3, 2)),
            )["codigo"])
            for (valor in listOf("2", 1.5, listOf(2), mapOf("codigo" to 2))) {
                assertFailsWith<ParametrosInvalidosException> { service.validarParametros(listOf(param), mapOf("codigo" to valor)) }
            }
        }
    }

    @Test
    fun `cadastros textuais preservam codigos e rejeitam objetos`() {
        for (tipo in listOf("parceiro_negocio", "item", "localidade")) {
            val param = Parametro(nome = "codigo", tipo = tipo, multiplo = true)
            assertEquals(listOf("001", "A'B"), service.validarParametros(listOf(param), mapOf("codigo" to listOf("001", "A'B")))["codigo"])
            for (valor in listOf(emptyList<Any>(), listOf(null), listOf("001", 2), listOf(""), "001", listOf(listOf("001")))) {
                assertFailsWith<ParametrosInvalidosException> { service.validarParametros(listOf(param), mapOf("codigo" to valor)) }
            }
        }
    }

    @Test
    fun `multiplo opcional sem selecao usa null e padrao usa array`() {
        val param = Parametro(nome = "filiais", tipo = "filial", multiplo = true, obrigatorio = false)
        assertEquals(mapOf("filiais" to null), service.validarParametros(listOf(param), emptyMap()))
        assertEquals(mapOf("filiais" to null), service.validarParametros(listOf(param), mapOf("filiais" to null)))
        assertEquals(listOf(1.toBigDecimal()), service.validarParametros(listOf(param.copy(padrao = listOf(1))), emptyMap())["filiais"])
        assertFailsWith<ParametrosInvalidosException> { service.validarParametros(listOf(param), mapOf("filiais" to emptyList<Any>())) }
    }

    @Test
    fun `lista multipla valida cada opcao e lista antiga continua escalar`() {
        val param = Parametro(nome = "status", tipo = "lista", opcoes = listOf(br.andrew.sap_reports.definition.Opcao("A", "Ativo")))
        assertEquals("A", service.validarParametros(listOf(param), mapOf("status" to "A"))["status"])
        assertEquals(listOf("A"), service.validarParametros(listOf(param.copy(multiplo = true)), mapOf("status" to listOf("A")))["status"])
        assertFailsWith<ParametrosInvalidosException> {
            service.validarParametros(listOf(param.copy(multiplo = true)), mapOf("status" to listOf("A", "X")))
        }
    }

    private val service = RenderService(
        repositorioDeTeste().first, DefinitionParser(), OdbcClient(OdbcProperties()),
        HtmlRenderer(), PdfRenderer(), CsvRenderer(), ReportProperties(),
    )

    @Test
    fun `parametro obrigatorio nulo e recusado mesmo com padrao`() {
        listOf(null, "padrao").forEach { padrao ->
            assertFailsWith<ParametrosInvalidosException> {
                service.validarParametros(listOf(Parametro(nome = "cliente", padrao = padrao)), mapOf("cliente" to null))
            }
        }
    }

    @Test
    fun `parametro opcional aceita nulo e obrigatorio ausente usa padrao`() {
        assertEquals(mapOf("cliente" to null), service.validarParametros(
            listOf(Parametro(nome = "cliente", obrigatorio = false)), emptyMap(),
        ))
        assertEquals(mapOf("cliente" to "todos"), service.validarParametros(
            listOf(Parametro(nome = "cliente", padrao = "todos")), emptyMap(),
        ))
    }
}
