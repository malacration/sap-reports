package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.Formato
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.odbc.OdbcException
import br.andrew.sap_reports.odbc.QueryResponse
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.web.RelatorioDesordenadoException
import br.andrew.sap_reports.web.ResultadoTruncadoException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Do fluxo do sap-odbc (WireMock servindo NDJSON) ate o arquivo pronto, com limites pequenos
 * para forcar o modo em disco e o PDF em varios blocos com poucas linhas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenderEmDiscoTest {

    private lateinit var servidor: WireMockServer
    private val json = JsonMapper.builder().build()

    @TempDir
    lateinit var temp: Path

    @BeforeAll
    fun subir() {
        servidor = WireMockServer(options().dynamicPort()).also { it.start() }
    }

    @AfterAll
    fun derrubar() = servidor.stop()

    private val colunas = listOf(
        Coluna("VENDEDOR", "Vendedor"),
        Coluna("CLIENTE", "Cliente"),
        Coluna("TOTAL", "Total", "moeda", total = "sum"),
    )

    private fun def(agrupar: List<String> = listOf("VENDEDOR"), maxRows: Int? = null) = ReportDefinition(
        nome = "Vendas", papeis = listOf("admin"), consulta = Consulta("SELECT 1", maxRows),
        colunas = colunas, agrupar = agrupar, formatos = listOf("pdf", "csv", "html"),
    )

    /** Ana tem 5 linhas: com bloco de 2, o grupo dela e partido entre blocos. */
    private val linhas = (1..5).map { mapOf("VENDEDOR" to "Ana", "CLIENTE" to "C$it", "TOTAL" to "10") } +
        (1..2).map { mapOf("VENDEDOR" to "Bia", "CLIENTE" to "D$it", "TOTAL" to "1.5") }

    private fun fluxo(linhas: List<Map<String, Any?>>, truncado: Boolean = false, fim: Boolean = true) {
        val corpo = buildString {
            appendLine(json.writeValueAsString(mapOf("tipo" to "colunas", "columns" to emptyList<Any>())))
            linhas.forEach { appendLine(json.writeValueAsString(mapOf("tipo" to "linha", "row" to it))) }
            if (fim) appendLine(json.writeValueAsString(mapOf("tipo" to "fim", "rowCount" to linhas.size, "truncated" to truncado, "elapsedMs" to 1)))
        }
        servidor.resetAll()
        servidor.stubFor(post(urlEqualTo("/api/v1/query/stream")).willReturn(
            aResponse().withStatus(200).withHeader("Content-Type", "application/x-ndjson").withBody(corpo)))
    }

    private fun gerador(linhasEmMemoria: Int = 3, linhasPorBloco: Int = 2): RenderEmDisco {
        val props = ReportProperties(linhasEmMemoria = linhasEmMemoria, linhasPorBloco = linhasPorBloco,
            diretorioTemporario = temp.toString())
        val odbc = OdbcClient(OdbcProperties(baseUrl = "http://localhost:${servidor.port()}"))
        return RenderEmDisco(odbc, HtmlRenderer(), PdfRenderer(), CsvRenderer(), props)
    }

    private fun temporariosRestantes() = Files.list(temp).use { it.toList() }

    private fun csvEmMemoria(d: ReportDefinition) =
        CsvRenderer().renderizar(d, QueryResponse(emptyList(), linhas, linhas.size, false, 0))

    @Test
    fun `resultado pequeno segue o caminho em memoria e nao deixa temporario`() {
        fluxo(linhas)
        val saida = gerador(linhasEmMemoria = 100).gerar(def(), "", Formato.CSV, emptyMap(), null)
        assertContentEquals(csvEmMemoria(def()), saida.bytes)
        saida.close()
        assertEquals(emptyList(), temporariosRestantes())
    }

    @Test
    fun `csv grande sai em arquivo identico ao em memoria e o temporario some no close`() {
        fluxo(linhas)
        val saida = gerador().gerar(def(), "", Formato.CSV, emptyMap(), null)
        assertTrue(temporariosRestantes().isNotEmpty(), "o arquivo fica em disco ate o envio")
        val enviado = ByteArrayOutputStream().also { saida.escreverEm(it) }.toByteArray()
        assertContentEquals(csvEmMemoria(def()), enviado)
        assertEquals(enviado.size.toLong(), saida.tamanho)
        saida.close()
        assertEquals(emptyList(), temporariosRestantes())
    }

    @Test
    fun `pdf grande em blocos poe subtotal so onde o grupo termina e total geral uma vez`() {
        fluxo(linhas)
        val template = """
            <h1>{{meta.nome}} - bloco {{meta.bloco}}</h1>
            {{#each grupos}}
              <h2>{{valor}}{{#if continuacao}} (continuacao){{/if}} - {{quantidade}} linhas</h2>
              <table>{{#each linhas}}<tr><td>{{CLIENTE}}</td><td>{{TOTAL}}</td></tr>{{/each}}</table>
              {{#unless continua}}<p>Subtotal {{valor}}: {{totais.TOTAL}}</p>{{/unless}}
            {{/each}}
            {{#if meta.ultimoBloco}}<p>Total geral: {{totais.TOTAL}}</p>{{/if}}
        """.trimIndent()
        val saida = gerador().gerar(def(), template, Formato.PDF, emptyMap(), null)
        val texto = Loader.loadPDF(saida.bytes).use { PDFTextStripper().getText(it) }.replace(' ', ' ')

        // 7 linhas, bloco de 2 partido so no dobro dentro do grupo: mais de um bloco
        assertTrue("bloco 2" in texto, texto)
        assertTrue("Ana (continuacao)" in texto, texto)
        assertEquals(1, Regex("Subtotal Ana").findAll(texto).count(), texto)
        assertTrue("Subtotal Ana: R$ 50,00" in texto, texto)
        assertTrue("Ana - 5 linhas" in texto, texto)
        assertEquals(1, Regex("Total geral").findAll(texto).count(), texto)
        assertTrue("Total geral: R$ 53,00" in texto, texto)
        (1..5).forEach { assertTrue("C$it" in texto, "linha C$it faltando") }
        saida.close()
        assertEquals(emptyList(), temporariosRestantes())
    }

    @Test
    fun `grande com grupo fora de ordem e recusado e limpa o temporario`() {
        // Ana, Bia, Ana...: consulta sem ORDER BY VENDEDOR
        fluxo(listOf(linhas[0], linhas[5], linhas[1], linhas[2], linhas[6], linhas[3], linhas[4]))
        val erro = assertFailsWith<RelatorioDesordenadoException> {
            gerador().gerar(def(), "", Formato.CSV, emptyMap(), null)
        }
        assertEquals("VENDEDOR", erro.campo)
        assertEquals(emptyList(), temporariosRestantes())
    }

    @Test
    fun `fora de ordem abaixo do limite em memoria continua funcionando como antes`() {
        fluxo(listOf(linhas[0], linhas[5], linhas[1], linhas[2], linhas[6], linhas[3], linhas[4]))
        gerador(linhasEmMemoria = 100).gerar(def(), "", Formato.CSV, emptyMap(), null).close()
    }

    @Test
    fun `truncado e recusado e limpa o temporario`() {
        fluxo(linhas, truncado = true)
        assertFailsWith<ResultadoTruncadoException> { gerador().gerar(def(), "", Formato.CSV, emptyMap(), null) }
        assertEquals(emptyList(), temporariosRestantes())
    }

    @Test
    fun `fluxo sem fim e descartado, nunca vira relatorio parcial`() {
        fluxo(linhas, fim = false)
        val erro = assertFailsWith<OdbcException> { gerador().gerar(def(), "", Formato.CSV, emptyMap(), null) }
        assertEquals("resultado_incompleto", erro.codigo)
        assertEquals(emptyList(), temporariosRestantes())
    }
}
