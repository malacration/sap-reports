package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.web.UploadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportServiceTest {
    @Test
    fun `relatorio sem intersecao de papeis nao aparece na listagem`() {
        val (repository, _) = repositorioDeTeste()
        val properties = ReportProperties()
        val service = ReportService(repository, ReportUploadValidator(properties), DefinitionParser())
        val upload = UploadRequest(
            definicao = """
                nome: Vendas restritas
                papeis: [financeiro]
                consulta:
                  sql: SELECT 1 AS TOTAL
                colunas:
                  - campo: TOTAL
                    titulo: Total
                    tipo: moeda
            """.trimIndent(),
            template = "{{#each linhas}}{{TOTAL}}{{/each}}",
        )
        val id = service.criar(upload, "autor").id
        service.publicar(id, 1, "autor")

        assertTrue(service.listarPublicados(setOf("vendedor")).isEmpty())
        assertTrue(service.listarPublicados(setOf("financeiro")).any { it.id == id })
    }

    private fun upload(pastaYaml: String) = UploadRequest(
        definicao = """
            nome: Vendas
            $pastaYaml
            papeis: [vendedor]
            consulta:
              sql: SELECT 1 AS TOTAL
            colunas:
              - campo: TOTAL
                titulo: Total
                tipo: moeda
        """.trimIndent(),
        template = "{{#each linhas}}{{TOTAL}}{{/each}}",
    )

    @Test
    fun `pasta aparece nas listagens e o consumo mostra a da versao publicada`() {
        val (repository, _) = repositorioDeTeste()
        val service = ReportService(repository, ReportUploadValidator(ReportProperties()), DefinitionParser())
        val id = service.criar(upload("pasta: '  Vendas '"), "autor").id
        service.publicar(id, 1, "autor")
        service.novaVersao(id, upload("pasta: Financeiro"), "autor")

        // A lista admin acompanha a ultima versao (rascunho); o consumo, a publicada.
        assertEquals("Financeiro", service.listarAdmin().single { it.id == id }.pasta)
        assertEquals("Vendas", service.listarPublicados(setOf("vendedor")).single { it.id == id }.pasta)
        assertEquals("Vendas", service.detalhePublicado(id, setOf("vendedor")).pasta)
        assertEquals("Financeiro", service.detalheAdmin(id, null).pasta)
    }

    @Test
    fun `pasta de dois niveis chega inteira nas listagens`() {
        val (repository, _) = repositorioDeTeste()
        val service = ReportService(repository, ReportUploadValidator(ReportProperties()), DefinitionParser())
        val id = service.criar(upload("pasta: 'Financeiro / Contas a pagar'"), "autor").id
        service.publicar(id, 1, "autor")

        assertEquals("Financeiro/Contas a pagar", service.listarAdmin().single { it.id == id }.pasta)
        assertEquals("Financeiro/Contas a pagar", service.listarPublicados(setOf("vendedor")).single { it.id == id }.pasta)
    }

    @Test
    fun `relatorio sem pasta volta com pasta nula`() {
        val (repository, _) = repositorioDeTeste()
        val service = ReportService(repository, ReportUploadValidator(ReportProperties()), DefinitionParser())
        val id = service.criar(upload("descricao: sem pasta"), "autor").id
        service.publicar(id, 1, "autor")

        assertNull(service.listarPublicados(setOf("vendedor")).single { it.id == id }.pasta)
        assertNull(service.listarAdmin().single { it.id == id }.pasta)
    }
}
