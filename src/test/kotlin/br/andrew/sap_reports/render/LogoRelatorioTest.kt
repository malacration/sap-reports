package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.Regra
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.TemplateRules
import br.andrew.sap_reports.odbc.QueryResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogoRelatorioTest {
    private val pngMinimo = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01,
    )
    private val pngDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngMinimo)

    @Test
    fun `aceita png jpeg gif e webp com assinatura correta`() {
        assertEquals(pngDataUri, LogoRelatorio.validar(pngDataUri))
        val jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
        assertEquals(jpeg, LogoRelatorio.validar(jpeg))
    }

    @Test
    fun `recusa o que nao e imagem raster embutida`() {
        assertNull(LogoRelatorio.validar(null))
        assertNull(LogoRelatorio.validar("   "))
        // SVG pode conter script e referencia externa.
        assertNull(LogoRelatorio.validar("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="))
        assertNull(LogoRelatorio.validar("https://interno/logo.png"))
        assertNull(LogoRelatorio.validar("data:text/html;base64,PGgxPng8L2gxPg=="))
        // Tipo declarado que nao bate com o conteudo.
        assertNull(LogoRelatorio.validar("data:image/png;base64," + Base64.getEncoder().encodeToString("nao e png".toByteArray())))
        assertNull(LogoRelatorio.validar("data:image/png;base64,###"))
    }

    @Test
    fun `recusa logo acima do teto`() {
        val grande = Base64.getEncoder().encodeToString(pngMinimo + ByteArray(LogoRelatorio.MAX_BYTES))
        assertNull(LogoRelatorio.validar("data:image/png;base64,$grande"))
    }

    @Test
    fun `template so pode usar a logo pela expressao meta ponto logo`() {
        val regras = { tpl: String -> TemplateRules.validar(tpl).map { it.regra }.toSet() }
        assertTrue(regras("""<img src="{{meta.logo}}" alt="Logo">""").isEmpty())
        assertTrue(regras("""<img src="{{ meta.logo }}">""").isEmpty())
        // Qualquer outra expressao continua barrada: so `meta.logo` e validado pelo servidor.
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in regras("""<img src="{{params.link}}">"""))
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in regras("""<img src="{{meta.logo}}x">"""))
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in regras("""<img src="http://interno/logo.png">"""))
    }

    @Test
    fun `logo valida chega ao html e a invalida apenas some`() {
        val def = ReportDefinition(nome = "T", papeis = listOf("admin"),
            consulta = Consulta("SELECT 1"), colunas = listOf(Coluna("A", "A")))
        val resposta = QueryResponse(emptyList(), listOf(mapOf("A" to 1)), 1, false, 1)
        val template = """{{#if meta.logo}}<img src="{{meta.logo}}" alt="Logo">{{else}}SEM LOGO{{/if}}"""

        // O sanitizador OWASP codifica '=' como &#61; no atributo; o navegador e o
        // gerador de PDF decodificam de volta.
        val comLogo = HtmlRenderer().renderizar(template, def, resposta, emptyMap(), pngDataUri)
            .replace("&#61;", "=")
        assertTrue(comLogo.contains("<img src=\"$pngDataUri\""), comLogo)

        val semLogo = HtmlRenderer().renderizar(template, def, resposta, emptyMap(), "https://interno/logo.png")
        assertTrue(semLogo.contains("SEM LOGO"), semLogo)
        assertTrue(!semLogo.contains("interno"), semLogo)
    }
}
