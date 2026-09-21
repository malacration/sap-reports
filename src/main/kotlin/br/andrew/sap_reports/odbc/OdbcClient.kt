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
     * Traduz o erro do sap-odbc preservando o codigo dele. O SQL nunca vai na
     * mensagem devolvida ao usuario final - quem escreveu o relatorio ve o
     * detalhe no log, o consumidor ve texto limpo.
     */
    private fun traduzir(status: HttpStatusCode, corpo: String): OdbcException {
        val erro = runCatching { Json.lerErro(corpo) }.getOrNull()
        val codigo = erro?.erro ?: "erro_odbc"
        log.warn("sap-odbc respondeu {} ({}): {}", status.value(), codigo, erro?.mensagem?.take(300))
        val mensagem = when {
            status.value() == 401 -> "O sap-reports nao esta autorizado no sap-odbc (X-API-Key)."
            status.value() == 504 || codigo == "timeout" -> "A consulta excedeu o tempo limite."
            codigo == "sql_invalido" -> "A consulta do relatorio foi recusada pelo sap-odbc."
            codigo == "sql_rejeitado_pelo_banco" -> "A consulta foi recusada pelo banco."
            else -> "Falha ao consultar os dados."
        }
        return OdbcException(codigo, mensagem, status.value())
    }
}

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
