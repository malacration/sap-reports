package br.andrew.sap_reports.web

import br.andrew.sap_reports.definition.ValidacaoException
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

    @ExceptionHandler(
        ParametrosInvalidosException::class,
        FormatoInvalidoException::class,
        MethodArgumentNotValidException::class,
        MissingServletRequestParameterException::class,
    )
    fun requisicaoInvalida(ex: Exception) = ResponseEntity.badRequest().body(
        ErroDto("parametros_invalidos", ex.message ?: "Parametros invalidos."),
    )

    @ExceptionHandler(OdbcException::class)
    fun odbc(ex: OdbcException): ResponseEntity<ErroDto> {
        val timeout = ex.status == 504 || ex.codigo == "timeout"
        val status = if (timeout) HttpStatus.GATEWAY_TIMEOUT else HttpStatus.BAD_GATEWAY
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
