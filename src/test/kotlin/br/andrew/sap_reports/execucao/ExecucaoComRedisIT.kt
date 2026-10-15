package br.andrew.sap_reports.execucao

import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.ReportService
import br.andrew.sap_reports.web.ExemploController
import br.andrew.sap_reports.web.RenderRequest
import br.andrew.sap_reports.web.UploadRequest
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A aplicacao inteira com Redis de verdade: pedido -> fila no Redis -> worker
 * do Spring -> arquivo. Cobre o que o dublê nao cobre (JSON do Boot, BRPOP em
 * conexao dedicada, ciclo de vida do worker).
 *
 *     docker run -d --rm -p 6379:6379 redis:7-alpine
 *     REDIS_TESTE_HOST=localhost ./gradlew test --tests '*ExecucaoComRedisIT*'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REDIS_TESTE_HOST", matches = ".+")
class ExecucaoComRedisIT {
    @Autowired lateinit var execucoes: ExecucaoService
    @Autowired lateinit var reports: ReportService

    companion object {
        private val odbc = WireMockServer(options().dynamicPort()).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun propriedades(registro: DynamicPropertyRegistry) {
            registro.add("spring.data.redis.host") { System.getenv("REDIS_TESTE_HOST") }
            registro.add("reports.execucao.workers") { 1 }
            registro.add("reports.execucao.prefixo-redis") { "it-${System.nanoTime()}" }
            registro.add("reports.execucao.diretorio") { Files.createTempDirectory("execucoes-it").toString() }
            registro.add("odbc.base-url") { odbc.baseUrl() }
            // Schema real no H2, como o TestDatabase.
            registro.add("spring.datasource.url") { "jdbc:h2:mem:it-${System.nanoTime()};MODE=LEGACY;DB_CLOSE_DELAY=-1" }
            registro.add("spring.liquibase.enabled") { true }
            registro.add("spring.liquibase.default-schema") { "PUBLIC" }
            registro.add("spring.datasource.hikari.schema") { "PUBLIC" }
        }

        @JvmStatic
        @AfterAll
        fun parar() = odbc.stop()
    }

    @Test
    fun `o worker consome a fila do Redis e entrega o arquivo`() {
        odbc.stubFor(
            post(urlEqualTo("/api/v1/query")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(
                    """{"columns":[{"name":"CardCode","type":"NVARCHAR","nullable":true},
                        {"name":"CardName","type":"NVARCHAR","nullable":true},
                        {"name":"NOTAS","type":"INTEGER","nullable":true},
                        {"name":"TOTAL","type":"DECIMAL","nullable":true}],
                       "rows":[{"CardCode":"C9","CardName":"Sitio Esperanca","NOTAS":1,"TOTAL":"10.00"}],
                       "rowCount":1,"truncated":false,"elapsedMs":2}""",
                ),
            ),
        )
        val exemplo = ExemploController(br.andrew.sap_reports.service.DefinitionParser()).listar()
            .first { it.id == "vendas-por-cliente" }
        val id = reports.criar(UploadRequest(exemplo.definicao, exemplo.template), "autor").id
        reports.publicar(id, 1, "autor")
        val ana = UsuarioAutenticado("ana", "10", setOf("vendedor"), emptySet())

        val pedido = execucoes.iniciarRender(
            id, "html", RenderRequest(mapOf("dataInicio" to "2026-01-01", "dataFim" to "2026-01-31")), ana,
        )

        val limite = System.currentTimeMillis() + 20_000
        var atual = execucoes.buscar(pedido.id, ana)
        while (atual.status in listOf(StatusExecucao.PENDENTE, StatusExecucao.EXECUTANDO) && System.currentTimeMillis() < limite) {
            Thread.sleep(200)
            atual = execucoes.buscar(pedido.id, ana)
        }
        assertEquals(StatusExecucao.CONCLUIDO, atual.status, atual.erro?.mensagem)
        assertTrue("Sitio Esperanca" in Files.readString(execucoes.arquivo(pedido.id, ana).second))
        assertEquals(listOf(pedido.id), execucoes.listar(ana).map { it.id })
    }
}
