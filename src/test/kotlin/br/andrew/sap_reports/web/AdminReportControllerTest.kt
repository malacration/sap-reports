package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.service.DefinitionParser
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.Test

class AdminReportControllerTest {
    @Test
    fun `422 devolve todos os problemas do validador`() {
        val properties = ReportProperties()
        val validator = ReportUploadValidator(properties)
        val (repository, _) = repositorioDeTeste()
        val service = ReportService(repository, validator, DefinitionParser())
        val renderService = RenderService(
            repository, DefinitionParser(), OdbcClient(OdbcProperties(baseUrl = "http://localhost:1")),
            HtmlRenderer(), PdfRenderer(), CsvRenderer(), properties,
        )
        val controller = AdminReportController(service, renderService)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(ApiExceptionHandler())
            .build()
        val upload = UploadRequest(
            definicao = """
                nome: ''
                papeis: []
                consulta:
                  sql: DELETE FROM SEGREDO
                colunas: []
                formatos: []
            """.trimIndent(),
            template = "<script>{{{conteudo}}}</script>",
        )
        val esperado = validator.validar(upload.definicao, upload.template).problemas.size
        val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
            .writeValueAsString(upload)

        mvc.post("/api/v1/admin/relatorios/validar") {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.erro") { value("validacao_falhou") }
            jsonPath("$.problemas.length()") { value(esperado) }
        }
    }
}
