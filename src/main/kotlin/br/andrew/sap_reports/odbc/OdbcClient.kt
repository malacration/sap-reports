package br.andrew.sap_reports.odbc

import br.andrew.sap_reports.config.OdbcProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Cliente do sap-odbc - o unico caminho deste servico ate os dados de negocio.
 *
 * O sap-odbc valida o SQL (somente leitura, instrucao unica, sem comentario) e
 * executa com bind parameters. Este servico nunca fala JDBC com o schema de
 * negocio; a conexao JDBC daqui alcanca apenas SAP_REPORTS, onde ficam os
 * templates.
 */
@Component
class OdbcClient(private val props: OdbcProperties) {

    private val log = LoggerFactory.getLogger(OdbcClient::class.java)

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.baseUrl)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds.toLong()))
                // Maior que o queryTimeout: se fosse menor, o cliente desistiria antes
                // de o sap-odbc responder o proprio timeout, e um 504 legitimo chegaria
                // aqui como falha de rede, sem a mensagem util.
                setReadTimeout(Duration.ofSeconds(props.readTimeoutSeconds.toLong()))
            },
        )
        .build()

    fun consultar(sql: String, params: Map<String, Any?>, maxRows: Int): QueryResponse {
        val corpo = QueryRequest(
            sql = sql,
            params = params,
            maxRows = maxRows,
            timeoutSeconds = props.queryTimeoutSeconds,
        )

        val resposta = try {
            client.post()
                .uri("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .apply { if (!props.apiKey.isNullOrBlank()) header("X-API-Key", props.apiKey) }
                .body(corpo)
                .exchange { _, res ->
                    val status = res.statusCode
                    val texto = res.body.readAllBytes().decodeToString()
                    status to texto
                }
        } catch (ex: Exception) {
            log.error("Falha de comunicacao com o sap-odbc", ex)
            throw OdbcException("erro_odbc", "Nao foi possivel consultar o sap-odbc.", null, ex)
        }

        val (status, texto) = resposta!!
        if (status.is2xxSuccessful) return Json.ler(texto)

        throw traduzir(status, texto)
    }

    /**
     * Mesma consulta pelo endpoint de FLUXO do sap-odbc (`/api/v1/query/stream`, NDJSON):
     * as linhas chegam uma a uma e vao direto para [aoLer], sem montar a lista inteira.
     * E o caminho dos arquivos grandes (CSV/PDF), com teto bem acima do [consultar].
     *
     * So retorna se o sap-odbc mandar a mensagem `fim`. Conexao cortada, erro no meio da
     * leitura ou fim ausente viram [OdbcException]: um relatorio montado com parte das
     * linhas e pior que falhar, entao quem chama deve descartar o que ja recebeu.
     */
    fun consultarFluxo(
        sql: String,
        params: Map<String, Any?>,
        maxRows: Int,
        aoIniciar: (List<ColumnMeta>) -> Unit,
        aoLer: (Map<String, Any?>) -> Unit,
    ): FimFluxo {
        val corpo = QueryRequest(sql = sql, params = params, maxRows = maxRows, timeoutSeconds = props.queryTimeoutSeconds)
        val resultado = try {
            client.post()
                .uri("/api/v1/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                // JSON tambem: erro antes do fluxo comecar volta com o envelope comum.
                .accept(NDJSON, MediaType.APPLICATION_JSON)
                .apply { if (!props.apiKey.isNullOrBlank()) header("X-API-Key", props.apiKey) }
                .body(corpo)
                .exchange { _, res ->
                    if (!res.statusCode.is2xxSuccessful) {
                        return@exchange Result.failure<FimFluxo>(traduzir(res.statusCode, res.body.readAllBytes().decodeToString()))
                    }
                    Result.success(lerFluxo(res.body, aoIniciar, aoLer))
                }!!
        } catch (ex: OdbcException) {
            throw ex
        } catch (ex: FalhaDeQuemConsome) {
            // Erro de quem recebe as linhas (disco, relatorio invalido): nao e falha do sap-odbc.
            throw ex.cause!!
        } catch (ex: Exception) {
            log.error("Falha de comunicacao com o sap-odbc durante o fluxo", ex)
            throw OdbcException("erro_odbc", "Nao foi possivel consultar o sap-odbc.", null, ex)
        }
        return resultado.getOrThrow()
    }

    private fun lerFluxo(
        corpo: java.io.InputStream,
        aoIniciar: (List<ColumnMeta>) -> Unit,
        aoLer: (Map<String, Any?>) -> Unit,
    ): FimFluxo {
        corpo.bufferedReader(Charsets.UTF_8).useLines { linhas ->
            for (texto in linhas) {
                if (texto.isBlank()) continue
                val msg = Json.lerMensagem(texto)
                when (msg.tipo) {
                    "colunas" -> consumir { aoIniciar(msg.columns ?: emptyList()) }
                    "linha" -> consumir { aoLer(msg.row ?: emptyMap()) }
                    "fim" -> return FimFluxo(msg.rowCount ?: 0, msg.truncated ?: false, msg.elapsedMs ?: 0)
                    "erro" -> {
                        val codigo = msg.erro ?: "erro_odbc"
                        log.warn("sap-odbc interrompeu o fluxo ({}): {}", codigo, msg.mensagem?.take(300))
                        throw OdbcException(codigo, mensagemPara(null, codigo), null)
                    }
                }
            }
        }
        throw OdbcException("resultado_incompleto", "A consulta foi interrompida antes do fim. Tente novamente.", null)
    }

    /**
     * Traduz o erro do sap-odbc preservando o codigo dele. O SQL nunca vai na
     * mensagem devolvida ao usuario final - quem escreveu o relatorio ve o
     * detalhe no log, o consumidor ve texto limpo.
     */
    private fun traduzir(status: HttpStatusCode, corpo: String): OdbcException {
        val erro = runCatching { Json.lerErro(corpo) }.getOrNull()
        val codigo = erro?.erro ?: "erro_odbc"
        log.warn("sap-odbc respondeu {} ({}): {}", status.value(), codigo, erro?.mensagem?.take(300))
        return OdbcException(codigo, mensagemPara(status.value(), codigo), status.value())
    }

    private fun mensagemPara(status: Int?, codigo: String): String = when {
        status == 401 -> "O sap-reports nao esta autorizado no sap-odbc (X-API-Key)."
        status == 504 || codigo == "timeout" -> "A consulta excedeu o tempo limite."
        codigo == "sql_invalido" -> "A consulta do relatorio foi recusada pelo sap-odbc."
        codigo == "sql_rejeitado_pelo_banco" -> "A consulta foi recusada pelo banco."
        codigo == "ocupado" -> "Muitos relatorios grandes sendo gerados agora. Tente novamente em instantes."
        else -> "Falha ao consultar os dados."
    }

    private inline fun consumir(bloco: () -> Unit) =
        try { bloco() } catch (ex: Exception) { throw FalhaDeQuemConsome(ex) }

    private class FalhaDeQuemConsome(causa: Exception) : RuntimeException(causa)

    private companion object {
        val NDJSON = MediaType.parseMediaType("application/x-ndjson")
    }
}

/** Fim de um fluxo completo. `truncated` tem o mesmo significado de [QueryResponse.truncated]. */
data class FimFluxo(val rowCount: Int, val truncated: Boolean, val elapsedMs: Long)

/** Uma mensagem do NDJSON do sap-odbc: `colunas`, `linha`, `fim` ou `erro`. */
internal data class MensagemFluxo(
    val tipo: String? = null,
    val columns: List<ColumnMeta>? = null,
    val row: Map<String, Any?>? = null,
    val rowCount: Int? = null,
    val truncated: Boolean? = null,
    val elapsedMs: Long? = null,
    val erro: String? = null,
    val mensagem: String? = null,
    val sqlState: String? = null,
)

class OdbcException(
    val codigo: String,
    override val message: String,
    val status: Int?,
    causa: Throwable? = null,
) : RuntimeException(message, causa)

/** Espelho do contrato do sap-odbc. */
data class QueryRequest(
    val sql: String,
    val params: Map<String, Any?>,
    val maxRows: Int,
    val timeoutSeconds: Int,
)

data class ColumnMeta(val name: String, val type: String, val nullable: Boolean)

data class QueryResponse(
    val columns: List<ColumnMeta>,
    val rows: List<Map<String, Any?>>,
    val rowCount: Int,
    /**
     * true quando existiam MAIS linhas do que o limite pedido.
     *
     * Para relatorio isso e dado faltando, nao um detalhe: entregar um PDF
     * silenciosamente incompleto e pior que falhar. Quem chama deve recusar.
     */
    val truncated: Boolean,
    val elapsedMs: Long,
)

/**
 * Envelope de erro padrao das APIs do workspace: {erro, mensagem}.
 *
 * Ja foi {error, message} no sap-odbc. A divergencia causou um bug real: o
 * cliente lia o campo errado, todo erro virava generico e se perdia `timeout` e
 * `sql_invalido`. Se mudar de novo, mude nos tres servicos juntos.
 */
data class ErroOdbc(val erro: String, val mensagem: String, val sqlState: String? = null)

private object Json {
    /**
     * Checagem estrita de nulo DESLIGADA de proposito.
     *
     * Com ela, o modulo Kotlin do Jackson 3 recusa `null` nos VALORES do mapa de
     * cada linha, ignorando que o tipo declarado e `Map<String, Any?>`. Mas NULL
     * e dado legitimo no ERP - colaborador sem cargo, cliente sem e-mail - e a
     * pre-visualizacao inteira caia com 500 por causa de uma celula vazia.
     */
    private val mapper = tools.jackson.databind.json.JsonMapper.builder()
        .addModule(
            tools.jackson.module.kotlin.KotlinModule.Builder()
                .disable(tools.jackson.module.kotlin.KotlinFeature.NewStrictNullChecks)
                .disable(tools.jackson.module.kotlin.KotlinFeature.StrictNullChecks)
                .build(),
        )
        .build()

    fun ler(texto: String): QueryResponse = mapper.readValue(texto, QueryResponse::class.java)
    fun lerErro(texto: String): ErroOdbc = mapper.readValue(texto, ErroOdbc::class.java)
    fun lerMensagem(texto: String): MensagemFluxo = mapper.readValue(texto, MensagemFluxo::class.java)
}

/**
 * Converte um valor vindo do sap-odbc para BigDecimal.
 *
 * O sap-odbc serializa BigDecimal como STRING JSON
 * (`stripTrailingZeros().toPlainString()`), nao como numero. Ler isso como Double
 * introduz erro de arredondamento em valor financeiro - por isso a conversao
 * passa sempre por String.
 */
fun Any?.paraDecimal(): BigDecimal? = when (this) {
    null -> null
    is BigDecimal -> this
    is Number -> BigDecimal(this.toString())
    is String -> this.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    else -> null
}
