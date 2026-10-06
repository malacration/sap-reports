package br.andrew.sap_reports.web

import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/v1/relatorios")
class ConsumerReportController(
    private val reports: ReportService,
    private val renderer: RenderService,
) {
    @GetMapping
    fun listar(principal: Principal) = reports.listarPublicados(principal.usuario().papeis)

    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: Long, principal: Principal) =
        reports.detalhePublicado(id, principal.usuario().papeis)

    @PostMapping("/{id}/render")
    fun renderizar(
        @PathVariable id: Long,
        @RequestParam formato: String,
        @RequestBody request: RenderRequest,
        principal: Principal,
        response: HttpServletResponse,
    ) = enviar(
        id,
        renderer.renderizarPublicado(id, formato, request.params, principal.usuario().papeis, request.logo),
        response,
    )

    private fun Principal.usuario() = this as UsuarioAutenticado
}

/**
 * Escreve o relatorio direto na resposta, com Content-Length, e apaga o temporario ao fim
 * (inclusive se o cliente desconectar no meio). Arquivo grande vai do disco para a rede
 * sem passar inteiro pela memoria.
 *
 * Sincrono de proposito, sem StreamingResponseBody: o caminho assincrono herdaria o timeout
 * de requisicao assincrona do container e cortaria o download de arquivo grande no meio.
 */
internal fun enviar(id: Long, saida: SaidaRenderizada, response: HttpServletResponse) = saida.use {
    val data = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val disposicao = if (saida.extensao == "html") "inline" else "attachment"
    response.status = HttpServletResponse.SC_OK
    response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'")
    response.setHeader("X-Content-Type-Options", "nosniff")
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    response.setHeader(HttpHeaders.CONTENT_TYPE, saida.contentType)
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "$disposicao; filename=\"relatorio-$id-$data.${saida.extensao}\"")
    response.setContentLengthLong(saida.tamanho)
    response.outputStream.use { saida.escreverEm(it) }
}
