package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.web.UploadRequest
import kotlin.test.Test
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
}
