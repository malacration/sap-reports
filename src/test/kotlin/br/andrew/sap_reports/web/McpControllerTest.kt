package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.execucao.ExecucaoProperties
import br.andrew.sap_reports.execucao.ExecucaoService
import br.andrew.sap_reports.execucao.MemoriaExecucaoStore
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O servidor MCP nao tem regra propria: estes testes conferem o protocolo
 * (JSON-RPC sobre HTTP) e que as ferramentas chegam aos mesmos servicos da API.
 */
class McpControllerTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val agente = UsuarioAutenticado("token:gpt", "andrew", setOf("admin"), emptySet(), true)
    private val store = MemoriaExecucaoStore()
    private lateinit var execucoes: ExecucaoService
    private val mvc: MockMvc = run {
        val properties = ReportProperties()
        val (repository, _) = repositorioDeTeste()
        val reports = ReportService(repository, ReportUploadValidator(properties), DefinitionParser())
        val odbc = OdbcClient(OdbcProperties(baseUrl = "http://localhost:1"))
        val renderer = RenderService(
            repository, DefinitionParser(), odbc, HtmlRenderer(), properties,
            RenderEmDisco(odbc, HtmlRenderer(), PdfRenderer(), CsvRenderer(), properties),
            br.andrew.sap_reports.service.FiliaisDoUsuario(odbc, properties),
        )
        execucoes = ExecucaoService(store, reports, renderer, ExecucaoProperties(), Clock.systemUTC())
        MockMvcBuilders.standaloneSetup(
            McpController(reports, execucoes, ExemploController(DefinitionParser()), json),
        ).build()
    }

    private fun enviar(mensagem: Map<String, Any?>): JsonNode {
        val resposta = mvc.post("/api/v1/admin/mcp") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mensagem)
            principal = agente
        }.andExpect { status { isOk() } }.andReturn().response
        return json.readTree(resposta.getContentAsString(Charsets.UTF_8))
    }

    private fun chamar(ferramenta: String, argumentos: Map<String, Any?> = emptyMap()): JsonNode = enviar(
        mapOf(
            "jsonrpc" to "2.0", "id" to 7, "method" to "tools/call",
            "params" to mapOf("name" to ferramenta, "arguments" to argumentos),
        ),
    )["result"]

    private fun JsonNode.textoDoResultado(): String = this["content"][0]["text"].asString()

    private fun exemplo(): Map<String, String> {
        val exemplo = ExemploController(DefinitionParser()).listar().first()
        return mapOf("definicao" to exemplo.definicao, "template" to exemplo.template)
    }

    @Test
    fun `initialize devolve a versao pedida e anuncia ferramentas`() {
        val resposta = enviar(
            mapOf(
                "jsonrpc" to "2.0", "id" to "a1", "method" to "initialize",
                "params" to mapOf("protocolVersion" to "2025-03-26", "capabilities" to emptyMap<String, Any>()),
            ),
        )
        assertEquals("a1", resposta["id"].stringValue(), "o id volta como veio, texto ou numero")
        assertEquals("2025-03-26", resposta["result"]["protocolVersion"].stringValue())
        assertTrue(resposta["result"]["capabilities"].has("tools"))
        assertEquals("sap-reports", resposta["result"]["serverInfo"]["name"].stringValue())
    }

    @Test
    fun `versao desconhecida recebe a mais recente do servidor`() {
        val resposta = enviar(
            mapOf(
                "jsonrpc" to "2.0", "id" to 1, "method" to "initialize",
                "params" to mapOf("protocolVersion" to "1999-01-01"),
            ),
        )
        assertEquals(McpController.VERSOES.first(), resposta["result"]["protocolVersion"].stringValue())
    }

    @Test
    fun `notificacao recebe 202 sem corpo`() {
        mvc.post("/api/v1/admin/mcp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            principal = agente
        }.andExpect {
            status { isAccepted() }
            content { string("") }
        }
    }

    @Test
    fun `GET devolve 405 - nao ha fluxo SSE`() {
        mvc.get("/api/v1/admin/mcp") { principal = agente }.andExpect { status { isMethodNotAllowed() } }
    }

    @Test
    fun `corpo que nao e JSON devolve erro de parse`() {
        mvc.post("/api/v1/admin/mcp") {
            contentType = MediaType.APPLICATION_JSON
            content = "isto nao e json"
            principal = agente
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value(-32700) }
        }
    }

    @Test
    fun `metodo desconhecido devolve erro JSON-RPC`() {
        val resposta = enviar(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "resources/list"))
        assertEquals(-32601, resposta["error"]["code"].intValue())
    }

    @Test
    fun `tools list nao oferece publicar, rollback nem remover`() {
        val ferramentas = enviar(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))["result"]["tools"]
        val nomes: List<String> = ferramentas.asIterable().map { it["name"].asString() }

        assertTrue(nomes.containsAll(listOf("guia", "validar_relatorio", "criar_relatorio", "preview_relatorio")))
        assertTrue(
            nomes.none { n -> listOf("public", "rollback", "remov", "exclu", "token").any { it in n } },
            "publicar e decisao humana: $nomes",
        )
        ferramentas.forEach { assertEquals("object", it["inputSchema"]["type"].stringValue()) }
    }

    @Test
    fun `validacao recusada volta como isError com todos os problemas`() {
        val resultado = chamar(
            "validar_relatorio",
            mapOf("definicao" to "nome: ''\npapeis: []\nconsulta:\n  sql: DELETE FROM X\ncolunas: []\nformatos: []",
                "template" to "<script>{{{x}}}</script>"),
        )
        assertTrue(resultado["isError"].booleanValue())
        val corpo = json.readTree(resultado.textoDoResultado())
        assertEquals("validacao_falhou", corpo["erro"].stringValue())
        assertTrue(corpo["problemas"].size() > 1, "todos de uma vez, como na API")
    }

    @Test
    fun `cria rascunho em nome do token, le de volta e atualiza`() {
        val criado = chamar("criar_relatorio", exemplo())
        assertFalse(criado["isError"].booleanValue(), criado.toString())
        val relatorio = json.readTree(criado.textoDoResultado())
        assertEquals("RASCUNHO", relatorio["status"].stringValue())
        assertEquals("token:gpt", relatorio["criadoPor"].stringValue())
        val id = relatorio["id"].longValue()

        val lido = json.readTree(chamar("obter_relatorio", mapOf("id" to id)).textoDoResultado())
        assertEquals(exemplo()["definicao"], lido["definicao"].stringValue())

        // Id como texto tambem vale: modelos trocam numero por string.
        val nova = json.readTree(chamar("atualizar_relatorio", exemplo() + ("id" to id.toString())).textoDoResultado())
        assertEquals(2, nova["ultimaVersao"].intValue())
        assertTrue(nova["versaoPublicada"].isNull, "nova versao continua rascunho")

        val versoes = json.readTree(chamar("listar_versoes", mapOf("id" to id)).textoDoResultado())
        assertEquals(2, versoes.size())
    }

    @Test
    fun `relatorio inexistente e argumento ausente voltam como isError`() {
        val inexistente = chamar("obter_relatorio", mapOf("id" to 99999))
        assertTrue(inexistente["isError"].booleanValue())
        assertTrue("nao_encontrado" in inexistente.textoDoResultado())

        val semId = chamar("obter_relatorio")
        assertTrue(semId["isError"].booleanValue())
        assertTrue("parametros_invalidos" in semId.textoDoResultado())
    }

    @Test
    fun `ferramenta desconhecida devolve erro JSON-RPC`() {
        val resposta = enviar(
            mapOf(
                "jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to mapOf("name" to "publicar_relatorio", "arguments" to emptyMap<String, Any>()),
            ),
        )
        assertEquals(-32602, resposta["error"]["code"].intValue())
    }

    @Test
    fun `guia traz as regras da skill sem o frontmatter`() {
        val guia = chamar("guia").textoDoResultado()
        assertTrue("validar_relatorio" in guia)
        assertTrue("Regras que o validador aplica" in guia)
        assertFalse("name: report-skill" in guia)
    }

    @Test
    fun `previa e assincrona - pedido responde na hora e o resultado vem depois`() {
        val id = json.readTree(chamar("criar_relatorio", exemplo()).textoDoResultado())["id"].longValue()

        val pedido = chamar(
            "preview_relatorio",
            mapOf("id" to id, "params" to mapOf("dataInicio" to "2026-01-01", "dataFim" to "2026-01-31")),
        )
        assertFalse(pedido["isError"].booleanValue(), pedido.toString())
        val execucao = json.readTree(pedido.textoDoResultado())["execucao"].asString()

        val pendente = json.readTree(chamar("resultado_preview", mapOf("execucao" to execucao)).textoDoResultado())
        assertEquals("PENDENTE", pendente["status"].asString())

        // O sap-odbc dos testes nao existe: a execucao falha, e a falha chega ao modelo.
        execucoes.executar(store.fila.pollLast()!!)
        val falha = chamar("resultado_preview", mapOf("execucao" to execucao))
        assertTrue(falha["isError"].booleanValue())
        assertTrue("erro" in falha.textoDoResultado())
    }

    @Test
    fun `execucao de outro dono nao e encontrada`() {
        val resultado = chamar("resultado_preview", mapOf("execucao" to "nao-existe"))
        assertTrue(resultado["isError"].booleanValue())
        assertTrue("nao_encontrado" in resultado.textoDoResultado())
    }
}
