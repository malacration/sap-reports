package br.andrew.sap_reports.execucao

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.repositorioDeTeste
import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.DefinitionParser
import br.andrew.sap_reports.service.RenderEmDisco
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import br.andrew.sap_reports.storage.RelatorioNaoEncontradoException
import br.andrew.sap_reports.web.ExemploController
import br.andrew.sap_reports.web.ParametrosInvalidosException
import br.andrew.sap_reports.web.RenderRequest
import br.andrew.sap_reports.web.UploadRequest
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Execucao assincrona de ponta a ponta: pedido -> fila -> worker -> arquivo,
 * com o sap-odbc simulado e o Redis trocado por [MemoriaExecucaoStore].
 */
class ExecucaoServiceTest {
    @TempDir
    lateinit var pasta: Path

    private val servidor = WireMockServer(options().dynamicPort()).also { it.start() }
    private val relogio = RelogioAjustavel(Instant.parse("2026-10-14T12:00:00Z"))
    private val store = MemoriaExecucaoStore()
    private lateinit var service: ExecucaoService
    private lateinit var reports: ReportService
    private var relatorioId = 0L

    private val ana = UsuarioAutenticado("ana", "10", setOf("vendedor"), emptySet())
    private val bia = UsuarioAutenticado("bia", "11", setOf("vendedor"), emptySet())
    private val params = RenderRequest(mapOf("dataInicio" to "2026-01-01", "dataFim" to "2026-01-31"))

    @BeforeTest
    fun preparar() {
        val properties = ReportProperties()
        val (repository, _) = repositorioDeTeste()
        reports = ReportService(repository, ReportUploadValidator(properties), DefinitionParser())
        val odbc = OdbcClient(OdbcProperties(baseUrl = servidor.baseUrl()))
        val renderer = RenderService(
            repository, DefinitionParser(), odbc, HtmlRenderer(), properties,
            RenderEmDisco(odbc, HtmlRenderer(), PdfRenderer(), CsvRenderer(), properties),
        )
        service = ExecucaoService(
            store, reports, renderer,
            ExecucaoProperties(diretorio = pasta.toString(), maxAtivasPorUsuario = 2), relogio,
        )
        // "Vendas por cliente": papeis [vendedor, admin], dataInicio/dataFim obrigatorios.
        val exemplo = ExemploController(DefinitionParser()).listar().first { it.id == "vendas-por-cliente" }
        relatorioId = reports.criar(UploadRequest(exemplo.definicao, exemplo.template), "autor").id
        reports.publicar(relatorioId, 1, "autor")
        consultaDevolve("""
            {"columns":[{"name":"CardCode","type":"NVARCHAR","nullable":true},{"name":"CardName","type":"NVARCHAR","nullable":true},
                        {"name":"NOTAS","type":"INTEGER","nullable":true},{"name":"TOTAL","type":"DECIMAL","nullable":true}],
             "rows":[{"CardCode":"C001","CardName":"Fazenda Boa Vista","NOTAS":3,"TOTAL":"1500.50"}],
             "rowCount":1,"truncated":false,"elapsedMs":4}
        """)
    }

    @AfterEach
    fun parar() = servidor.stop()

    private fun consultaDevolve(corpo: String, status: Int = 200) {
        servidor.stubFor(
            post(urlEqualTo("/api/v1/query")).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo.trimIndent()),
            ),
        )
    }

    /** O que o worker faria: consome a fila ate esvaziar. */
    private fun rodarFila() {
        while (true) service.executar(store.proxima(Duration.ZERO) ?: return)
    }

    @Test
    fun `pedido responde na hora e o worker gera o arquivo`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        assertEquals(StatusExecucao.PENDENTE, pedido.status)
        assertEquals("Vendas por cliente", pedido.relatorioNome)
        assertEquals(1, store.fila.size, "so enfileirou - nada executou ainda")

        rodarFila()

        val pronta = service.buscar(pedido.id, ana)
        assertEquals(StatusExecucao.CONCLUIDO, pronta.status)
        assertNotNull(pronta.expiraEm)
        val (_, arquivo) = service.arquivo(pedido.id, ana)
        assertTrue("Fazenda Boa Vista" in Files.readString(arquivo))
        assertEquals(params.params, pronta.params, "parametros guardados para gerar de novo")
    }

    @Test
    fun `cada usuario so enxerga as proprias execucoes`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        rodarFila()

        assertEquals(listOf(pedido.id), service.listar(ana).map { it.id })
        assertTrue(service.listar(bia).isEmpty())
        assertFailsWith<ExecucaoNaoEncontradaException> { service.buscar(pedido.id, bia) }
        assertFailsWith<ExecucaoNaoEncontradaException> { service.arquivo(pedido.id, bia) }
        assertFailsWith<ExecucaoNaoEncontradaException> { service.remover(pedido.id, bia) }
    }

    @Test
    fun `tokens de mesmo nome criados por pessoas diferentes nao se enxergam`() {
        val daAna = UsuarioAutenticado("token:gpt", "ana", setOf("admin"), emptySet(), true)
        val daBia = UsuarioAutenticado("token:gpt", "bia", setOf("admin"), emptySet(), true)
        val pedido = service.iniciarPreview(relatorioId, null, "html", params, daAna)
        assertFailsWith<ExecucaoNaoEncontradaException> { service.buscar(pedido.id, daBia) }
    }

    @Test
    fun `erro de preenchimento e de acesso aparece no pedido, sem enfileirar`() {
        assertFailsWith<ParametrosInvalidosException> { service.iniciarRender(relatorioId, "html", RenderRequest(), ana) }
        val semPapel = UsuarioAutenticado("caio", "12", setOf("financeiro"), emptySet())
        assertFailsWith<RelatorioNaoEncontradoException> { service.iniciarRender(relatorioId, "html", params, semPapel) }
        assertTrue(store.fila.isEmpty())
    }

    @Test
    fun `falha da consulta vira execucao FALHOU com o mesmo erro da API`() {
        consultaDevolve("""{"erro":"sql_invalido","mensagem":"Coluna inexistente."}""", status = 400)
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        rodarFila()

        val falha = service.buscar(pedido.id, ana)
        assertEquals(StatusExecucao.FALHOU, falha.status)
        assertEquals("sql_invalido", falha.erro!!.erro)
        assertEquals(502, falha.erro!!.status)
        assertFailsWith<ExecucaoEmAndamentoException> { service.arquivo(pedido.id, ana) }
    }

    @Test
    fun `depois da retencao o arquivo aparece como excluido`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        rodarFila()

        relogio.avancar(Duration.ofHours(25))
        val vencida = service.buscar(pedido.id, ana)
        assertEquals(StatusExecucao.EXCLUIDO, vencida.status)
        assertNull(vencida.arquivo)
        assertFailsWith<ExecucaoExcluidaException> { service.arquivo(pedido.id, ana) }
        assertEquals(StatusExecucao.EXCLUIDO, service.listar(ana).single().status, "continua na lista para gerar de novo")
    }

    @Test
    fun `arquivo apagado do disco tambem aparece como excluido`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        rodarFila()
        Files.list(pasta).use { it.forEach(Files::delete) }

        assertEquals(StatusExecucao.EXCLUIDO, service.buscar(pedido.id, ana).status)
    }

    @Test
    fun `limpeza apaga so arquivos com a retencao vencida`() {
        val antiga = service.iniciarRender(relatorioId, "html", params, ana)
        val recente = service.iniciarRender(relatorioId, "html", params, ana)
        rodarFila()
        val (_, arquivoAntigo) = service.arquivo(antiga.id, ana)
        Files.setLastModifiedTime(arquivoAntigo, FileTime.from(relogio.instant().minus(Duration.ofHours(30))))

        service.limparArquivosVencidos()

        assertFalse(Files.exists(arquivoAntigo))
        assertEquals(StatusExecucao.CONCLUIDO, service.buscar(recente.id, ana).status)
    }

    @Test
    fun `limita execucoes ativas por usuario`() {
        service.iniciarRender(relatorioId, "html", params, ana)
        service.iniciarRender(relatorioId, "html", params, ana)
        assertFailsWith<MuitasExecucoesException> { service.iniciarRender(relatorioId, "html", params, ana) }
        service.iniciarRender(relatorioId, "html", params, bia)

        rodarFila()
        service.iniciarRender(relatorioId, "html", params, ana)
    }

    @Test
    fun `ao subir, execucao interrompida no meio vira falha`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        store.salvar(store.buscar(pedido.id)!!.copy(status = StatusExecucao.EXECUTANDO))

        service.recuperarInterrompidas()

        val recuperada = service.buscar(pedido.id, ana)
        assertEquals(StatusExecucao.FALHOU, recuperada.status)
        assertEquals("execucao_interrompida", recuperada.erro!!.erro)
        assertTrue(store.ativas().isEmpty())
    }

    @Test
    fun `remover apaga arquivo e execucao, mas nao a que esta em andamento`() {
        val pedido = service.iniciarRender(relatorioId, "html", params, ana)
        assertFailsWith<ExecucaoEmAndamentoException> { service.remover(pedido.id, ana) }
        rodarFila()
        val (_, arquivo) = service.arquivo(pedido.id, ana)

        service.remover(pedido.id, ana)

        assertFalse(Files.exists(arquivo))
        assertTrue(service.listar(ana).isEmpty())
    }

    @Test
    fun `logo e papeis nao ficam guardados depois de executar`() {
        val pedido = service.iniciarRender(relatorioId, "html", params.copy(logo = "data:image/png;base64,AAAA"), ana)
        assertNotNull(store.buscar(pedido.id)!!.logo)
        rodarFila()
        val guardada = store.buscar(pedido.id)!!
        assertNull(guardada.logo)
        assertTrue(guardada.papeis.isEmpty())
    }

    private class RelogioAjustavel(private var agora: Instant) : Clock() {
        fun avancar(d: Duration) { agora = agora.plus(d) }
        override fun instant() = agora
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
}
