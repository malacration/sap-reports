package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.service.DefinitionParser
import kotlin.test.Test
import kotlin.test.assertTrue

class ExemploControllerTest {
    private val validator = ReportUploadValidator(ReportProperties(allowedTables = listOf("SBOGRUPOROVEMA.*")))

    @Test
    fun `lista todos os exemplos empacotados e todos passam no validador`() {
        val exemplos = ExemploController(DefinitionParser()).listar()

        val ids = exemplos.map { it.id }
        assertTrue(
            ids.containsAll(listOf("vendas-por-cliente", "vendas-por-cadastro", "vendas-agrupadas-por-vendedor")),
            ids.toString(),
        )
        exemplos.forEach { exemplo ->
            assertTrue(exemplo.nome.isNotBlank() && exemplo.template.isNotBlank(), exemplo.id)
            val resultado = validator.validar(exemplo.definicao, exemplo.template)
            assertTrue(resultado.valido, "${exemplo.id}: ${resultado.problemas}")
        }
    }
}
