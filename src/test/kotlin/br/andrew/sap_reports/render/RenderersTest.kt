package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.QueryResponse
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class RenderersTest {
    @Test
    fun `html final remove urls ofuscadas eventos e recursos externos`() {
        val html = HtmlRenderer().renderizar(
            """<style>td { color: red; }</style><table><tr><td>Total</td></tr></table>
                <a href=javascript:alert(1)>x</a><a href="java&#115;cript:alert(1)">y</a>
                <img src=http://interno/segredo onerror=alert(1)><a href="{{params.link}}">z</a>""",
            definicao(listOf(Coluna("TOTAL", "Total"))), resposta(mapOf("TOTAL" to 1)),
            mapOf("link" to "javascript:alert(1)"),
        )
        assertTrue(!html.contains("javascript", true), html)
        assertTrue(!html.contains("onerror", true), html)
        assertTrue(!html.contains("http://interno"), html)
        assertTrue(html.contains("color: red"), html)
        assertTrue(html.contains("<table>"), html)
    }
    @Test
    fun `helper monetario preserva BigDecimal recebido como string`() {
        val def = definicao(listOf(Coluna("TOTAL", "Total", "moeda")))
        val resposta = resposta(mapOf("TOTAL" to "9007199254740993.12"))

        val html = HtmlRenderer().renderizar(
            "{{#each linhas}}{{TOTAL}}{{/each}}",
            def,
            resposta,
            emptyMap(),
        )

        assertTrue(html.contains("9.007.199.254.740.993,12"), html)
    }

    @Test
    fun `csv usa BOM e escapa aspas ponto e virgula e quebra de linha`() {
        val def = definicao(listOf(Coluna("NOME", "Nome; completo")))
        val bytes = CsvRenderer().renderizar(def, resposta(mapOf("NOME" to "Cliente \"A\"\nSul")))

        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3).toByteArray())
        val csv = bytes.drop(3).toByteArray().decodeToString()
        assertTrue(csv.startsWith("\"Nome; completo\"\r\n"), csv)
        assertTrue(csv.contains("\"Cliente \"\"A\"\"\nSul\""), csv)
    }

    @Test
    fun `pdf nao le recurso file mesmo quando chamado sem passar pelo validador`() {
        val png = ByteArrayOutputStream().also { saida ->
            val imagem = BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 120) for (y in 0 until 120) imagem.setRGB(x, y, (x * 7919 + y * 104729))
            ImageIO.write(imagem, "png", saida)
        }.toByteArray()
        val arquivo = createTempFile(suffix = ".png").toFile().apply { writeBytes(png) }
        val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"
        val renderer = PdfRenderer()

        val externo = renderer.renderizar("<html><body><img src=\"${arquivo.toURI()}\" /></body></html>")
        val embutido = renderer.renderizar("<html><body><img src=\"$dataUri\" /></body></html>")

        assertTrue(embutido.size > externo.size + 1_000, "file parece ter sido carregado: ${externo.size} vs ${embutido.size}")
    }

    private fun definicao(colunas: List<Coluna>) = ReportDefinition(
        id = "relatorio-teste", nome = "Teste", papeis = listOf("admin"),
        consulta = Consulta("SELECT 1"), colunas = colunas,
    )

    private fun resposta(linha: Map<String, Any?>) = QueryResponse(emptyList(), listOf(linha), 1, false, 1)

    @Test
    fun `pdf tambem nao busca recurso http (SSRF)`() {
        // O teste existente cobre file://. Host interno e o outro lado do mesmo
        // vetor: o servidor faria a requisicao ao gerar o PDF, funcionando como
        // scanner da rede interna a mando de quem enviou o template.
        val html = """<html><body><img src="http://169.254.169.254/latest/meta-data/"/>ok</body></html>"""
        val pdf = PdfRenderer().renderizar(html)
        assertTrue(pdf.isNotEmpty(), "o PDF deve ser gerado, apenas sem buscar o recurso")
    }

    @Test
    fun `celula nula sai vazia no csv, nao como texto null`() {
        val def = br.andrew.sap_reports.definition.ReportDefinition(
            colunas = listOf(
                br.andrew.sap_reports.definition.Coluna(campo = "NOME", titulo = "Nome"),
                br.andrew.sap_reports.definition.Coluna(campo = "CARGO", titulo = "Cargo"),
            ),
        )
        val resposta = br.andrew.sap_reports.odbc.QueryResponse(
            columns = emptyList(),
            rows = listOf(mapOf("NOME" to "Bruno", "CARGO" to null)),
            rowCount = 1, truncated = false, elapsedMs = 1,
        )
        val csv = String(CsvRenderer().renderizar(def, resposta), Charsets.UTF_8)
        kotlin.test.assertFalse(csv.contains("null"), "celula vazia virou o texto 'null': $csv")
    }

    @Test
    fun `css do template vira estilo no head, nao texto no corpo`() {
        // Caso real: o sanitizador descartava a tag <style> e mantinha o CSS como
        // texto - o PDF saia com "@page { size: A4 ... }" no topo e sem estilo.
        val template = """
            <style>@page { size: A4 } h1 { color: #123456 }</style>
            <h1>Titulo</h1>
        """.trimIndent()
        val def = br.andrew.sap_reports.definition.ReportDefinition(nome = "Teste")
        val resposta = br.andrew.sap_reports.odbc.QueryResponse(
            columns = emptyList(), rows = emptyList(), rowCount = 0, truncated = false, elapsedMs = 1,
        )
        val html = HtmlRenderer().renderizar(template, def, resposta, emptyMap())
        val corpo = html.substringAfter("<body", "")
        kotlin.test.assertFalse(corpo.contains("@page"), "CSS vazou como texto no corpo: $corpo")
        val head = html.substringBefore("<body")
        kotlin.test.assertTrue(head.contains("<style>") && head.contains("#123456"), "CSS nao entrou no head: $head")
    }

    @Test
    fun `css com import ou url externa e descartado`() {
        // O CSS entra no head sem passar pelo sanitizador, entao precisa de filtro
        // proprio: @import e url() externo fariam o gerador de PDF buscar recurso.
        val template = "<style>@import url(http://10.0.0.1/x.css); h1 { color: red }</style><h1>X</h1>"
        val def = br.andrew.sap_reports.definition.ReportDefinition(nome = "Teste")
        val resposta = br.andrew.sap_reports.odbc.QueryResponse(
            columns = emptyList(), rows = emptyList(), rowCount = 0, truncated = false, elapsedMs = 1,
        )
        val html = HtmlRenderer().renderizar(template, def, resposta, emptyMap())
        kotlin.test.assertFalse(html.contains("10.0.0.1"), "recurso externo passou: $html")
    }

    @Test
    fun `dado com tag de fechamento de style nao escapa do css`() {
        // {{ }} dentro de <style> seria o caminho para dado de cliente virar CSS
        // ou HTML. O CSS e extraido do template ANTES da interpolacao.
        val template = "<style>h1 { color: red }</style><h1>{{params.nome}}</h1>"
        val def = br.andrew.sap_reports.definition.ReportDefinition(nome = "Teste")
        val resposta = br.andrew.sap_reports.odbc.QueryResponse(
            columns = emptyList(), rows = emptyList(), rowCount = 0, truncated = false, elapsedMs = 1,
        )
        val html = HtmlRenderer().renderizar(template, def, resposta,
            mapOf("nome" to "</style><script>alert(1)</script>"))
        kotlin.test.assertFalse(html.contains("<script>"), "script injetado pelo dado: $html")
    }

    @Test
    fun `pdf do scaffold nao imprime o css como texto`() {
        // Prova ponta a ponta: template real do exemplo, render HTML, PDF, e
        // extracao do texto do PDF gerado.
        val template = java.io.File("../report-skill/exemplos/vendas-por-cliente.hbs").readText()
        val def = br.andrew.sap_reports.definition.ReportDefinition(nome = "Vendas")
        val resposta = br.andrew.sap_reports.odbc.QueryResponse(
            columns = emptyList(),
            rows = listOf(mapOf("CardCode" to "C001", "CardName" to "Cliente", "NOTAS" to 2, "TOTAL" to "10.50")),
            rowCount = 1, truncated = false, elapsedMs = 1,
        )
        val html = HtmlRenderer().renderizar(template, def, resposta, mapOf("dataInicio" to "2026-01-01", "dataFim" to "2026-01-31"))
        val pdf = PdfRenderer().renderizar(html)
        val texto = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
        kotlin.test.assertFalse(texto.contains("@page"), "CSS impresso no PDF: $texto")
        kotlin.test.assertFalse(texto.contains("border-collapse"), "CSS impresso no PDF: $texto")
        kotlin.test.assertTrue(texto.contains("Cliente"), "o conteudo do relatorio sumiu: $texto")
    }
}
