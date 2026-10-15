package br.andrew.sap_reports.web

import br.andrew.sap_reports.definition.ValidacaoException
import br.andrew.sap_reports.execucao.ExecucaoEmAndamentoException
import br.andrew.sap_reports.execucao.ExecucaoExcluidaException
import br.andrew.sap_reports.execucao.ExecucaoNaoEncontradaException
import br.andrew.sap_reports.execucao.MuitasExecucoesException
import br.andrew.sap_reports.odbc.OdbcException
import br.andrew.sap_reports.storage.RelatorioNaoEncontradoException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ValidacaoException::class)
    fun validacao(ex: ValidacaoException) = ResponseEntity.unprocessableContent().body(
        ValidacaoFalhaDto(
            problemas = ex.resultado.problemas.map {
                ProblemaDto(it.caminho, it.regra.codigo, it.mensagem, it.linha)
            },
        ),
    )

    @ExceptionHandler(RelatorioNaoEncontradoException::class)
    fun naoEncontrado(ex: RelatorioNaoEncontradoException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroDto("nao_encontrado", ex.message!!))

    @ExceptionHandler(AcessoRelatorioNegadoException::class)
    fun proibido(ex: AcessoRelatorioNegadoException) =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErroDto("proibido", ex.message!!))

    @ExceptionHandler(ResultadoTruncadoException::class)
    fun truncado(ex: ResultadoTruncadoException) =
        ResponseEntity.unprocessableContent().body(ErroDto("resultado_truncado", ex.message!!))

    @ExceptionHandler(RelatorioDesordenadoException::class)
    fun desordenado(ex: RelatorioDesordenadoException) =
        ResponseEntity.unprocessableContent().body(ErroDto("agrupamento_desordenado", ex.message!!))

    @ExceptionHandler(
        ParametrosInvalidosException::class,
        FormatoInvalidoException::class,
        MethodArgumentNotValidException::class,
        MissingServletRequestParameterException::class,
    )
    fun requisicaoInvalida(ex: Exception) = ResponseEntity.badRequest().body(
        ErroDto("parametros_invalidos", ex.message ?: "Parametros invalidos."),
    )

    @ExceptionHandler(ExecucaoNaoEncontradaException::class)
    fun execucaoNaoEncontrada(ex: ExecucaoNaoEncontradaException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroDto("nao_encontrado", ex.message!!))

    /** 410: existiu, mas o arquivo foi apagado pela retencao. A tela oferece gerar de novo. */
    @ExceptionHandler(ExecucaoExcluidaException::class)
    fun execucaoExcluida(ex: ExecucaoExcluidaException) =
        ResponseEntity.status(HttpStatus.GONE).body(ErroDto("execucao_excluida", ex.message!!))

    @ExceptionHandler(ExecucaoEmAndamentoException::class)
    fun execucaoEmAndamento(ex: ExecucaoEmAndamentoException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErroDto("execucao_indisponivel", ex.message!!))

    @ExceptionHandler(MuitasExecucoesException::class)
    fun muitasExecucoes(ex: MuitasExecucoesException) =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErroDto("muitas_execucoes", ex.message!!))

    @ExceptionHandler(OdbcException::class)
    fun odbc(ex: OdbcException): ResponseEntity<ErroDto> {
        val timeout = ex.status == 504 || ex.codigo == "timeout"
        val status = when {
            timeout -> HttpStatus.GATEWAY_TIMEOUT
            // Fluxos do sap-odbc esgotados: temporario, o usuario pode tentar de novo.
            ex.codigo == "ocupado" -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_GATEWAY
        }
        return ResponseEntity.status(status).body(ErroDto(ex.codigo, ex.message))
    }

    /**
     * Ultima rede: qualquer excecao nao prevista vira o envelope padrao, sem
     * detalhe interno.
     *
     * Sem isto o erro caia no handler padrao do Spring, que no modo dev (DevTools
     * liga `include-stacktrace`) devolvia ao navegador o stacktrace inteiro, com
     * nomes de classe, caminho de pacote e parte da resposta do banco. O detalhe
     * continua no log, com o horario para correlacionar.
     */
    @ExceptionHandler(Exception::class)
    fun onInesperado(ex: Exception): ResponseEntity<Map<String, String>> {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java)
            .error("Erro inesperado ao processar a requisicao", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf(
                "erro" to "erro_interno",
                "mensagem" to "Erro inesperado ao gerar o relatorio. O detalhe foi registrado no log do servidor.",
            ),
        )
    }
}

/** Erro conhecido ja traduzido para o envelope da API. */
data class ErroMapeado(
    val status: Int,
    val erro: String,
    val mensagem: String,
    val problemas: List<ProblemaDto>? = null,
)

/**
 * O mesmo mapeamento dos handlers acima, para quem trata o erro FORA de uma
 * requisicao HTTP: a execucao assincrona (worker) e as ferramentas MCP.
 * Null = erro inesperado; quem chama registra no log e responde generico.
 */
internal fun erroConhecido(ex: Throwable): ErroMapeado? = when (ex) {
    is ValidacaoException -> ErroMapeado(
        422, "validacao_falhou", "A definicao do relatorio foi recusada.",
        ex.resultado.problemas.map { ProblemaDto(it.caminho, it.regra.codigo, it.mensagem, it.linha) },
    )
    is RelatorioNaoEncontradoException -> ErroMapeado(404, "nao_encontrado", ex.message!!)
    is ExecucaoNaoEncontradaException -> ErroMapeado(404, "nao_encontrado", ex.message!!)
    is ExecucaoExcluidaException -> ErroMapeado(410, "execucao_excluida", ex.message!!)
    is ExecucaoEmAndamentoException -> ErroMapeado(409, "execucao_indisponivel", ex.message!!)
    is MuitasExecucoesException -> ErroMapeado(429, "muitas_execucoes", ex.message!!)
    is AcessoRelatorioNegadoException -> ErroMapeado(403, "proibido", ex.message!!)
    is ResultadoTruncadoException -> ErroMapeado(422, "resultado_truncado", ex.message!!)
    is RelatorioDesordenadoException -> ErroMapeado(422, "agrupamento_desordenado", ex.message!!)
    is ParametrosInvalidosException, is FormatoInvalidoException ->
        ErroMapeado(400, "parametros_invalidos", ex.message ?: "Parametros invalidos.")
    is OdbcException -> ErroMapeado(
        when {
            ex.status == 504 || ex.codigo == "timeout" -> 504
            ex.codigo == "ocupado" -> 503
            else -> 502
        },
        ex.codigo, ex.message,
    )
    else -> null
}
