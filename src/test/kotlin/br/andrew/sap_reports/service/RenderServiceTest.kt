package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Parametro
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.web.AcessoRelatorioNegadoException
import br.andrew.sap_reports.web.ParametrosInvalidosException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

    private val service = OdbcClient(OdbcProperties()).let { odbc ->
        RenderService(
            repositorioDeTeste().first, DefinitionParser(), odbc, HtmlRenderer(), ReportProperties(),
            RenderEmDisco(odbc, HtmlRenderer(), PdfRenderer(), CsvRenderer(), ReportProperties()),
            FiliaisDoUsuario(odbc, ReportProperties()),
        )
    }

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

    private val filiais = Parametro(nome = "filiais", tipo = "filial", multiplo = true)
    private fun codigos(vararg valores: Int) = valores.map { it.toBigDecimal() }

    @Test
    fun `filiais fora do cadastro sao cortadas e a ordem pedida e mantida`() {
        val params = mapOf("filiais" to codigos(2, 3, 4, 5), "outro" to "x")
        val saida = service.restringirFiliais(listOf(filiais), params) { setOf(5, 3, 9) }
        assertEquals(mapOf("filiais" to codigos(3, 5), "outro" to "x"), saida)
    }

    @Test
    fun `nenhuma filial pedida no cadastro e recusada`() {
        val erro = assertFailsWith<AcessoRelatorioNegadoException> {
            service.restringirFiliais(listOf(filiais), mapOf("filiais" to codigos(2, 3))) { setOf(9) }
        }
        assertEquals("Nenhuma das filiais selecionadas esta liberada no seu cadastro.", erro.message)
    }

    @Test
    fun `filial escalar fora do cadastro e recusada e dentro passa`() {
        val filial = Parametro(nome = "filial", tipo = "filial")
        assertEquals(
            mapOf("filial" to 3.toBigDecimal()),
            service.restringirFiliais(listOf(filial), mapOf("filial" to 3.toBigDecimal())) { setOf(3) },
        )
        assertFailsWith<AcessoRelatorioNegadoException> {
            service.restringirFiliais(listOf(filial), mapOf("filial" to 2.toBigDecimal())) { setOf(3) }
        }
    }

    @Test
    fun `usuario sem filial no cadastro e recusado`() {
        val erro = assertFailsWith<AcessoRelatorioNegadoException> {
            service.restringirFiliais(listOf(filiais), mapOf("filiais" to codigos(2))) { emptySet() }
        }
        assertEquals("Seu usuario nao tem filial liberada no cadastro. Peca a liberacao ao administrador.", erro.message)
    }

    @Test
    fun `quem ve todas as filiais recebe o pedido como esta`() {
        val params = mapOf("filiais" to codigos(2, 3))
        assertSame(params, service.restringirFiliais(listOf(filiais), params) { null })
    }

    @Test
    fun `sem filial para conferir o cadastro nao e consultado`() {
        val naoConsultar: () -> Set<Int>? = { error("nao deveria consultar o cadastro") }
        val vendedor = Parametro(nome = "vendedor", tipo = "vendedor")
        val params = mapOf("vendedor" to 7.toBigDecimal())
        assertSame(params, service.restringirFiliais(listOf(vendedor), params, naoConsultar))

        val opcional = mapOf("filiais" to null)
        assertSame(opcional, service.restringirFiliais(listOf(filiais.copy(obrigatorio = false)), opcional, naoConsultar))
    }
}
