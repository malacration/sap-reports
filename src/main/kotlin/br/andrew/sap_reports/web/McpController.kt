package br.andrew.sap_reports.web

import br.andrew.sap_reports.execucao.ExecucaoService
import br.andrew.sap_reports.execucao.StatusExecucao
import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.ReportService
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.security.Principal
import java.util.Base64

/**
 * Servidor MCP (Model Context Protocol) da autoria de relatorios.
 *
 * Expoe como *ferramentas* o mesmo ciclo que a skill faz com curl - ler o
 * schema, validar, criar rascunho, nova versao, previa - para que um agente de
 * IA trabalhe sem montar requisicao HTTP na mao. Nenhuma regra mora aqui: cada
 * ferramenta chama o mesmo [ReportService]/[RenderService] das rotas REST.
 *
 * A previa e assincrona, como no resto da API: `preview_relatorio` so enfileira
 * e `resultado_preview` devolve o estado ou o conteudo. Nenhuma chamada fica
 * presa a consulta - o proxy na frente da API corta requisicao demorada.
 *
 * Transporte: Streamable HTTP **sem estado**. Cada POST traz uma mensagem
 * JSON-RPC e recebe a resposta em `application/json`; nao ha sessao nem fluxo
 * SSE (o GET devolve 405, como a especificacao preve para servidor sem fluxo).
 * Sem estado o servico continua escalando atras do gateway sem afinidade.
 *
 * Seguranca: a rota fica sob `/api/v1/admin/`, entao o filtro de autenticacao ja
 * exige token valido e papel de autoria - o agente usa o mesmo token de servico
 * (`rpt_...`) da skill. **Publicar, rollback e remover nao sao ferramentas**, de
 * proposito: publicar decide quem enxerga faturamento e continua com uma pessoa.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp")
class McpController(
    private val reports: ReportService,
    private val execucoes: ExecucaoService,
    private val exemplos: ExemploController,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class ArgumentoInvalido(mensagem: String) : RuntimeException(mensagem)
    private class FalhaDaExecucao(val erro: String, mensagem: String) : RuntimeException(mensagem)

    private class Ferramenta(
        val nome: String,
        val descricao: String,
        val propriedades: Map<String, Map<String, Any>> = emptyMap(),
        val obrigatorios: List<String> = emptyList(),
        /** Devolve os itens de `content` do resultado. */
        val executar: (argumentos: JsonNode?, usuario: UsuarioAutenticado) -> List<Map<String, Any?>>,
    )

    private val fonte = mapOf(
        "definicao" to mapOf("type" to "string", "description" to "Conteudo do .yaml (consulta e metadados)."),
        "template" to mapOf("type" to "string", "description" to "Conteudo do .hbs (Handlebars)."),
    )
    private val idRelatorio = mapOf("type" to "integer", "description" to "Id numerico devolvido na criacao.")
    private val numeroVersao = mapOf("type" to "integer", "description" to "Versao; omitida usa a mais recente.")

    private val ferramentas: List<Ferramenta> = listOf(
        Ferramenta(
            "guia",
            "Guia de autoria: formato do YAML e do template, regras do validador, agrupamento, " +
                "limites. Chame UMA vez antes de escrever o primeiro relatorio.",
        ) { _, _ -> texto(guia) },
        Ferramenta(
            "schema",
            "JSON Schema da definicao (.yaml). Campos desconhecidos sao recusados.",
        ) { _, _ -> texto(recurso("report-definition.schema.json")) },
        Ferramenta(
            "listar_exemplos",
            "Exemplos prontos (definicao + template) que passam no validador. Bom ponto de partida.",
        ) { _, _ -> comoJson(exemplos.listar()) },
        Ferramenta(
            "validar_relatorio",
            "Valida definicao e template SEM gravar e devolve TODOS os problemas de uma vez. " +
                "Chame sempre antes de criar ou atualizar, e repita ate passar.",
            fonte, listOf("definicao", "template"),
        ) { a, _ -> comoJson(reports.validar(upload(a))) },
        Ferramenta(
            "criar_relatorio",
            "Cria um relatorio novo como RASCUNHO (versao 1) e devolve o id. Nao publica.",
            fonte, listOf("definicao", "template"),
        ) { a, u -> comoJson(reports.criar(upload(a), u.name)) },
        Ferramenta(
            "atualizar_relatorio",
            "Cria uma versao nova (rascunho) de um relatorio existente. A versao publicada " +
                "continua servindo os usuarios ate uma pessoa publicar a nova.",
            mapOf("id" to idRelatorio) + fonte, listOf("id", "definicao", "template"),
        ) { a, u -> comoJson(reports.novaVersao(inteiro(a, "id")!!.toLong(), upload(a), u.name)) },
        Ferramenta(
            "listar_relatorios",
            "Lista todos os relatorios, inclusive rascunhos, com status e versoes.",
        ) { _, _ -> comoJson(reports.listarAdmin()) },
        Ferramenta(
            "obter_relatorio",
            "Devolve a definicao e o template de um relatorio, em texto, na versao pedida.",
            mapOf("id" to idRelatorio, "versao" to numeroVersao), listOf("id"),
        ) { a, _ -> comoJson(reports.detalheAdmin(inteiro(a, "id")!!.toLong(), inteiro(a, "versao", false))) },
        Ferramenta(
            "listar_versoes",
            "Historico de versoes de um relatorio e qual delas esta publicada.",
            mapOf("id" to idRelatorio), listOf("id"),
        ) { a, _ -> comoJson(reports.listarVersoes(inteiro(a, "id")!!.toLong())) },
        Ferramenta(
            "preview_relatorio",
            "Pede a execucao de qualquer versao (publicada ou nao), sem publicar, e devolve o id " +
                "da execucao NA HORA. A consulta roda em segundo plano: busque o conteudo com resultado_preview.",
            mapOf(
                "id" to idRelatorio,
                "versao" to numeroVersao,
                "formato" to mapOf("type" to "string", "enum" to listOf("html", "csv", "pdf"), "default" to "html"),
                "params" to mapOf(
                    "type" to "object",
                    "description" to "Valores dos parametros; as chaves sao os `parametros[].nome` da definicao.",
                ),
            ),
            listOf("id"),
        ) { a, u -> iniciarPreview(a, u) },
        Ferramenta(
            "resultado_preview",
            "Estado e conteudo de uma execucao pedida por preview_relatorio. Enquanto status for " +
                "PENDENTE ou EXECUTANDO, aguarde alguns segundos e chame de novo. Concluida, html e csv " +
                "voltam como texto e pdf como arquivo embutido.",
            mapOf(
                "execucao" to mapOf("type" to "string", "description" to "Id devolvido por preview_relatorio."),
                "maxCaracteres" to mapOf(
                    "type" to "integer",
                    "description" to "Corte do texto devolvido em html/csv (padrao $MAX_CARACTERES).",
                ),
            ),
            listOf("execucao"),
        ) { a, u -> resultadoPreview(a, u) },
    )

    @PostMapping
    fun receber(@RequestBody(required = false) corpo: String?, principal: Principal): ResponseEntity<Any> {
        val mensagem = try {
            json.readTree(corpo.orEmpty())
        } catch (ex: JacksonException) {
            null
        }
        if (mensagem == null || !mensagem.isObject) {
            // Lote (array) saiu da especificacao em 2025-06-18; nao e aceito aqui.
            return ResponseEntity.badRequest().body(erro(null, -32700, "Envie uma unica mensagem JSON-RPC."))
        }
        val id = mensagem["id"]?.takeUnless { it.isNull }
        val metodo = mensagem["method"]?.takeIf { it.isString }?.stringValue()

        // Notificacao (ou resposta do cliente): nada a devolver.
        if (id == null || metodo == null) return ResponseEntity.accepted().build()

        val params = mensagem["params"]
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
            when (metodo) {
                "initialize" -> resultado(id, inicializar(params))
                "ping" -> resultado(id, emptyMap<String, Any>())
                "tools/list" -> resultado(id, mapOf("tools" to ferramentas.map(::descrever)))
                "tools/call" -> chamar(id, params, principal as UsuarioAutenticado)
                else -> erro(id, -32601, "Metodo '$metodo' nao suportado.")
            },
        )
    }

    /** Servidor sem fluxo SSE e sem sessao: a especificacao pede 405 nos dois. */
    @RequestMapping(method = [RequestMethod.GET, RequestMethod.DELETE])
    fun semFluxo(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build()

    private fun inicializar(params: JsonNode?): Map<String, Any> {
        val pedida = params?.get("protocolVersion")?.takeIf { it.isString }?.stringValue()
        return mapOf(
            // Versao pedida, se conhecida; senao a mais recente daqui e o cliente decide.
            "protocolVersion" to (pedida?.takeIf { it in VERSOES } ?: VERSOES.first()),
            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
            "serverInfo" to mapOf(
                "name" to "sap-reports",
                "version" to (javaClass.`package`?.implementationVersion ?: "dev"),
            ),
            "instructions" to INSTRUCOES,
        )
    }

    private fun descrever(f: Ferramenta) = mapOf(
        "name" to f.nome,
        "description" to f.descricao,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to f.propriedades,
            "required" to f.obrigatorios,
        ),
    )

    /**
     * Falha da ferramenta volta como resultado com `isError`, e nao como erro
     * JSON-RPC: e o que faz o texto chegar ao modelo, que entao corrige e tenta
     * de novo. O corpo e o mesmo envelope `erro`/`mensagem`/`problemas` da API REST.
     */
    private fun chamar(id: JsonNode, params: JsonNode?, usuario: UsuarioAutenticado): Map<String, Any?> {
        val nome = params?.get("name")?.takeIf { it.isString }?.stringValue()
        val ferramenta = ferramentas.firstOrNull { it.nome == nome }
            ?: return erro(id, -32602, "Ferramenta '$nome' desconhecida.")
        val argumentos = params?.get("arguments")?.takeIf { it.isObject }
        return try {
            resultado(id, mapOf("content" to ferramenta.executar(argumentos, usuario), "isError" to false))
        } catch (ex: Exception) {
            resultado(id, mapOf("content" to comoJson(falha(ex)), "isError" to true))
        }
    }

    private fun falha(ex: Exception): Any {
        if (ex is ArgumentoInvalido) return ErroDto("parametros_invalidos", ex.message!!)
        if (ex is FalhaDaExecucao) return ErroDto(ex.erro, ex.message!!)
        val conhecido = erroConhecido(ex)
        if (conhecido == null) {
            log.error("Erro inesperado numa ferramenta MCP", ex)
            return ErroDto("erro_interno", "Erro inesperado. O detalhe foi registrado no log do servidor.")
        }
        return conhecido.problemas?.let { ValidacaoFalhaDto(conhecido.erro, conhecido.mensagem, it) }
            ?: ErroDto(conhecido.erro, conhecido.mensagem)
    }

    private fun iniciarPreview(a: JsonNode?, usuario: UsuarioAutenticado): List<Map<String, Any?>> {
        val formato = a?.get("formato")?.takeIf { it.isString }?.stringValue() ?: "html"
        val params = a?.get("params")?.takeIf { it.isObject }
            ?.let { json.convertValue(it, object : TypeReference<Map<String, Any?>>() {}) }
            ?: emptyMap()
        val execucao = execucoes.iniciarPreview(
            inteiro(a, "id")!!.toLong(), inteiro(a, "versao", false), formato, RenderRequest(params), usuario,
        )
        return comoJson(
            mapOf(
                "execucao" to execucao.id,
                "status" to execucao.status,
                "proximoPasso" to "Chame resultado_preview com execucao=${execucao.id} em alguns segundos.",
            ),
        )
    }

    private fun resultadoPreview(a: JsonNode?, usuario: UsuarioAutenticado): List<Map<String, Any?>> {
        val id = a?.get("execucao")?.takeIf { it.isString }?.stringValue()
            ?: throw ArgumentoInvalido("O argumento 'execucao' e obrigatorio e deve ser texto.")
        val limite = inteiro(a, "maxCaracteres", false)?.coerceAtLeast(1) ?: MAX_CARACTERES
        val execucao = execucoes.buscar(id, usuario)
        when (execucao.status) {
            StatusExecucao.PENDENTE, StatusExecucao.EXECUTANDO -> return comoJson(
                mapOf(
                    "execucao" to id, "status" to execucao.status,
                    "proximoPasso" to "Ainda executando. Aguarde alguns segundos e chame resultado_preview de novo.",
                ),
            )
            // Falha volta como erro da ferramenta, com o mesmo envelope da API.
            StatusExecucao.FALHOU -> throw FalhaDaExecucao(execucao.erro!!.erro, execucao.erro.mensagem)
            else -> Unit
        }

        val (pronta, caminho) = execucoes.arquivo(id, usuario)
        val arquivo = pronta.arquivo!!
        if (arquivo.extensao != "pdf") {
            val conteudo = Files.readString(caminho, Charsets.UTF_8)
            return if (conteudo.length <= limite) texto(conteudo) else texto(
                conteudo.take(limite) + "\n\n[cortado: ${conteudo.length} caracteres no total, " +
                    "$limite exibidos. Restrinja os parametros ou aumente maxCaracteres.]",
            )
        }
        if (arquivo.tamanho > MAX_BYTES_PDF) {
            throw ArgumentoInvalido(
                "O PDF tem ${arquivo.tamanho} bytes, acima do que cabe numa resposta MCP ($MAX_BYTES_PDF). " +
                    "Confira em html e baixe o PDF pela tela (Minhas execucoes).",
            )
        }
        return listOf(
            mapOf(
                "type" to "resource",
                "resource" to mapOf(
                    "uri" to "sap-reports://execucoes/$id/${arquivo.nome}",
                    "mimeType" to arquivo.contentType,
                    "blob" to Base64.getEncoder().encodeToString(Files.readAllBytes(caminho)),
                ),
            ),
        )
    }

    private fun upload(a: JsonNode?) = UploadRequest(textoDe(a, "definicao"), textoDe(a, "template"))

    private fun textoDe(a: JsonNode?, campo: String): String =
        a?.get(campo)?.takeIf { it.isString }?.stringValue()
            ?: throw ArgumentoInvalido("O argumento '$campo' e obrigatorio e deve ser texto.")

    private fun inteiro(a: JsonNode?, campo: String, obrigatorio: Boolean = true): Int? {
        val no = a?.get(campo)?.takeUnless { it.isNull }
        if (no == null) {
            if (obrigatorio) throw ArgumentoInvalido("O argumento '$campo' e obrigatorio.")
            return null
        }
        // Aceita "12" tambem: modelos trocam numero por texto com frequencia.
        return (if (no.isIntegralNumber && no.canConvertToInt()) no.intValue() else no.asString().toIntOrNull())
            ?: throw ArgumentoInvalido("O argumento '$campo' deve ser um numero inteiro.")
    }

    private fun texto(conteudo: String) = listOf(mapOf<String, Any?>("type" to "text", "text" to conteudo))
    private fun comoJson(valor: Any) = texto(json.writerWithDefaultPrettyPrinter().writeValueAsString(valor))

    private fun resultado(id: JsonNode, valor: Any) = mapOf("jsonrpc" to "2.0", "id" to id, "result" to valor)
    private fun erro(id: JsonNode?, codigo: Int, mensagem: String) =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to codigo, "message" to mensagem))

    private fun recurso(caminho: String) =
        ClassPathResource(caminho).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * O mesmo texto da skill, para as regras terem uma fonte so. A skill fala em
     * rotas HTTP; o cabecalho diz qual ferramenta corresponde a cada uma.
     */
    private val guia: String by lazy {
        val skill = recurso("skill/claude/SKILL.md").replace(Regex("\\A---\\n.*?\\n---\\n", RegexOption.DOT_MATCHES_ALL), "")
        "$ROTAS_PARA_FERRAMENTAS\n$skill\n\n${recurso("skill/claude/referencia/regras.md")}"
    }

    companion object {
        /** Versoes do protocolo aceitas, da mais recente para a mais antiga. */
        val VERSOES = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** Uma previa HTML de milhares de linhas esgotaria o contexto do modelo. */
        const val MAX_CARACTERES = 60_000
        const val MAX_BYTES_PDF = 3_000_000L

        const val INSTRUCOES =
            "Autoria de relatorios do sap-reports (YAML + Handlebars). Ciclo, sempre nesta ordem: " +
                "guia (uma vez) -> validar_relatorio ate passar -> criar_relatorio ou atualizar_relatorio -> " +
                "preview_relatorio em html -> resultado_preview ate concluir. Publicar NAO e uma ferramenta: entregue o id do rascunho " +
                "para uma pessoa conferir e publicar pela tela."

        private val ROTAS_PARA_FERRAMENTAS = """
            # Guia de autoria (servidor MCP)

            O texto abaixo e o da skill, escrito em termos de rotas HTTP. Aqui cada rota
            e uma ferramenta - voce nao monta requisicao nem lida com token:

            | Rota no texto | Ferramenta |
            | --- | --- |
            | `GET /admin/schema` | `schema` |
            | `GET /admin/exemplos` | `listar_exemplos` |
            | `POST /admin/relatorios/validar` | `validar_relatorio` |
            | `POST /admin/relatorios` | `criar_relatorio` |
            | `PUT /admin/relatorios/{id}` | `atualizar_relatorio` |
            | `GET /admin/relatorios` | `listar_relatorios` |
            | `GET /admin/relatorios/{id}` | `obter_relatorio` |
            | `GET /admin/relatorios/{id}/versoes` | `listar_versoes` |
            | `POST /admin/relatorios/{id}/preview` | `preview_relatorio` + `resultado_preview` |

            Onde o texto fala em "422", leia: a ferramenta voltou com erro e a lista `problemas`.
            A previa e assincrona: `preview_relatorio` devolve o id da execucao na hora, e
            `resultado_preview` responde PENDENTE/EXECUTANDO ate o conteudo ficar pronto.
            Publicar, rollback e remover nao existem como ferramenta.
        """.trimIndent()
    }
}
