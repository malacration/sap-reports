package br.andrew.sap_reports.web

import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/v1/admin")
class AdminReportController(
    private val reports: ReportService,
    private val renderer: RenderService,
) {
    @GetMapping("/relatorios")
    fun listar() = reports.listarAdmin()

    @PostMapping("/relatorios", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun criarJson(@RequestBody upload: UploadRequest, principal: Principal) =
        reports.criar(upload, principal.name)

    @PostMapping("/relatorios", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun criarMultipart(
        @RequestPart definicao: String,
        @RequestPart template: String,
        principal: Principal,
    ) = reports.criar(UploadRequest(definicao, template), principal.name)

    @PostMapping("/relatorios/validar")
    fun validar(@RequestBody upload: UploadRequest) = reports.validar(upload)

    @GetMapping("/relatorios/{id}")
    fun detalhe(@PathVariable id: Long, @RequestParam(required = false) versao: Int?) =
        reports.detalheAdmin(id, versao)

    @PutMapping("/relatorios/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun atualizarJson(
        @PathVariable id: Long,
        @RequestBody upload: UploadRequest,
        principal: Principal,
    ) = reports.novaVersao(id, upload, principal.name)

    @PutMapping("/relatorios/{id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun atualizarMultipart(
        @PathVariable id: Long,
        @RequestPart definicao: String,
        @RequestPart template: String,
        principal: Principal,
    ) = reports.novaVersao(id, UploadRequest(definicao, template), principal.name)

    @DeleteMapping("/relatorios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remover(@PathVariable id: Long, principal: Principal) {
        reports.remover(id, principal.name)
    }

    @PostMapping("/relatorios/{id}/preview")
    fun preview(
        @PathVariable id: Long,
        @RequestParam(required = false) versao: Int?,
        @RequestParam formato: String,
        @RequestBody request: RenderRequest,
    ) = resposta(id, renderer.preview(id, versao, formato, request.params, request.logo))

    @PostMapping("/relatorios/{id}/publicar")
    fun publicar(
        @PathVariable id: Long,
        @RequestBody request: PublicarRequest,
        principal: Principal,
    ) = reports.publicar(id, request.versao, principal.name)

    @GetMapping("/relatorios/{id}/versoes")
    fun versoes(@PathVariable id: Long) = reports.listarVersoes(id)

    @PostMapping("/relatorios/{id}/rollback/{versao}")
    fun rollback(
        @PathVariable id: Long,
        @PathVariable versao: Int,
        principal: Principal,
    ) = reports.rollback(id, versao, principal.name)

    @GetMapping("/relatorios/{id}/auditoria")
    fun auditoria(@PathVariable id: Long) = reports.listarAuditoria(id)

    @GetMapping("/schema", produces = ["application/schema+json"])
    fun schema(): ResponseEntity<String> {
        val conteudo = ClassPathResource("report-definition.schema.json")
            .inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/schema+json")).body(conteudo)
    }
}
