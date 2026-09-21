package br.andrew.sap_reports.web

import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
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
    ): ResponseEntity<ByteArray> = resposta(
        id,
        renderer.renderizarPublicado(id, formato, request.params, principal.usuario().papeis, request.logo),
    )

    private fun Principal.usuario() = this as UsuarioAutenticado
}

internal fun resposta(id: Long, saida: SaidaRenderizada): ResponseEntity<ByteArray> {
    val data = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val disposicao = if (saida.extensao == "html") "inline" else "attachment"
    return ResponseEntity.ok()
        .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.CONTENT_TYPE, saida.contentType)
        .header(HttpHeaders.CONTENT_DISPOSITION, "$disposicao; filename=\"relatorio-$id-$data.${saida.extensao}\"")
        .body(saida.bytes)
}
