package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.CadastrosRelatorio
import br.andrew.sap_reports.service.DefinitionParser
import br.andrew.sap_reports.service.FiliaisDoUsuario
import br.andrew.sap_reports.service.RenderEmDisco
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterEach
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Endpoint de busca dos filtros de relatorio, com o sap-odbc no WireMock.
 * O [ConsumerReportController] entra junto para provar que `cadastros/{tipo}`
 * nao cai no `GET /{id}`.
 */
class CadastroRelatorioControllerTest {
    private val servidor = WireMockServer(options().dynamicPort()).also { it.start() }
    private lateinit var mvc: MockMvc

    // "Vendas por cliente" e publicado para [vendedor, admin].
    private val vendedor = UsuarioAutenticado("ana", "10", setOf("vendedor"), emptySet())
    private val semRelatorio = UsuarioAutenticado("bia", "11", setOf("estoquista"), emptySet())
    private val autorSemRelatorio = UsuarioAutenticado("caio", "12", setOf("autor"), emptySet())

    @BeforeTest
    fun preparar() {
        val properties = ReportProperties(authorRole = "autor")
        val (repository, _) = repositorioDeTeste()
        val reports = ReportService(repository, ReportUploadValidator(properties), DefinitionParser())
        val odbc = OdbcClient(OdbcProperties(baseUrl = servidor.baseUrl()))
        val renderer = RenderService(
            repository, DefinitionParser(), odbc, HtmlRenderer(), properties,
            RenderEmDisco(odbc, HtmlRenderer(), PdfRenderer(), CsvRenderer(), properties),
            FiliaisDoUsuario(odbc, properties),
        )
        val execucoes = br.andrew.sap_reports.execucao.ExecucaoService(
            br.andrew.sap_reports.execucao.MemoriaExecucaoStore(), reports, renderer,
            br.andrew.sap_reports.execucao.ExecucaoProperties(), java.time.Clock.systemUTC(),
        )
        val exemplo = ExemploController(DefinitionParser()).listar().first { it.id == "vendas-por-cliente" }
        reports.publicar(reports.criar(UploadRequest(exemplo.definicao, exemplo.template), "autor").id, 1, "autor")

        mvc = MockMvcBuilders.standaloneSetup(
            CadastroRelatorioController(CadastrosRelatorio(odbc, properties), reports, properties),
            ConsumerReportController(reports, renderer, execucoes),
        ).setControllerAdvice(ApiExceptionHandler()).build()

        servidor.stubFor(post(urlEqualTo("/api/v1/query")).willReturn(okJson(
            """{"columns":[],"rows":[{"CODIGO":"C001","NOME":"Mauro"}],"rowCount":1,"truncated":false,"elapsedMs":1}""",
        )))
    }

    @AfterEach
    fun parar() = servidor.stop()

    @Test
    fun `busca devolve content e nextLink vazio`() {
        mvc.get("/api/v1/relatorios/cadastros/parceiro_negocio") {
            param("q", "mauro")
            principal = vendedor
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].codigo") { value("C001") }
            jsonPath("$.content[0].nome") { value("Mauro") }
            jsonPath("$.nextLink") { value("") }
        }
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%MAURO%"))),
        )
    }

    private fun responderComProxima() = servidor.stubFor(post(urlEqualTo("/api/v1/query")).willReturn(okJson(
        """{"columns":[],"rows":[{"CODIGO":"PAC1","NOME":"Pac"}],"rowCount":1,"truncated":true,"elapsedMs":1}""",
    )))

    @Test
    fun `com 51a linha o nextLink aponta a proxima pagina`() {
        responderComProxima()
        mvc.get("/api/v1/relatorios/cadastros/item") {
            param("q", "pac")
            param("pagina", "1")
            principal = vendedor
        }.andExpect {
            status { isOk() }
            jsonPath("$.nextLink") { value("cadastros/item?q=pac&pagina=2") }
        }
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.sql", containing("OFFSET 50"))),
        )
    }

    @Test
    fun `q com caracteres especiais vai url-encoded no nextLink`() {
        responderComProxima()
        mvc.get("/api/v1/relatorios/cadastros/parceiro_negocio") {
            param("q", "a&b=c d%/ç")
            principal = vendedor
        }.andExpect {
            jsonPath("$.nextLink") { value("cadastros/parceiro_negocio?q=a%26b%3Dc+d%25%2F%C3%A7&pagina=1") }
        }
    }

    @Test
    fun `ultima pagina aceita nao aponta alem`() {
        responderComProxima()
        mvc.get("/api/v1/relatorios/cadastros/item") {
            param("pagina", "200")
            principal = vendedor
        }.andExpect { jsonPath("$.nextLink") { value("") } }
    }

    @Test
    fun `pagina fora da faixa da 400 sem consultar`() {
        listOf("-1", "201", "abc").forEach { pagina ->
            mvc.get("/api/v1/relatorios/cadastros/item") {
                param("pagina", pagina)
                principal = vendedor
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.erro") { value("parametros_invalidos") }
            }
        }
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `tipo desconhecido da 404`() {
        mvc.get("/api/v1/relatorios/cadastros/filial") { principal = vendedor }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.erro") { value("nao_encontrado") }
            }
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `sem q busca com termo vazio`() {
        mvc.get("/api/v1/relatorios/cadastros/deposito") { principal = vendedor }
            .andExpect { status { isOk() } }
        servidor.verify(
            postRequestedFor(urlEqualTo("/api/v1/query"))
                .withRequestBody(matchingJsonPath("$.params.termo", equalTo("%"))),
        )
    }

    @Test
    fun `usuario sem relatorio recebe 403 e nao consulta`() {
        mvc.get("/api/v1/relatorios/cadastros/parceiro_negocio") {
            param("q", "mauro")
            principal = semRelatorio
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.erro") { value("proibido") }
        }
        servidor.verify(0, postRequestedFor(anyUrl()))
    }

    @Test
    fun `autor sem relatorio publicado busca para o preview do rascunho`() {
        mvc.get("/api/v1/relatorios/cadastros/item") { principal = autorSemRelatorio }
            .andExpect { status { isOk() } }
    }
}
