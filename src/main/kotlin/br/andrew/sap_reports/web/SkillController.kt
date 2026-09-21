package br.andrew.sap_reports.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Download das instrucoes para IA.
 *
 * Fica sob /admin porque descreve a superficie da API: nao ha segredo no
 * conteudo, mas tambem nao ha motivo para publicar o mapa das rotas.
 */
@RestController
@RequestMapping("/api/v1/admin/skill")
class SkillController(
    private val pacote: SkillPackageService,
    /**
     * URL publica deste servico, usada no `servers` do OpenAPI do ChatGPT.
     * Sem ela o GPT tenta chamar localhost e falha sem mensagem util.
     */
    @Value("\${reports.public-url:}") private val urlPublica: String,
) {

    @GetMapping
    fun baixar(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val url = urlPublica.ifBlank { urlDaRequisicao(request) }
        val zip = pacote.montarZip(url)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sap-reports-skill.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(zip)
    }

    /** Somente o OpenAPI adaptado, para quem prefere importar direto no Actions. */
    @GetMapping("/openapi-actions.yaml", produces = ["application/yaml"])
    fun openApiActions(request: HttpServletRequest): ResponseEntity<String> {
        val url = urlPublica.ifBlank { urlDaRequisicao(request) }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi-actions.yaml\"")
            .body(pacote.openApiParaActions(url))
    }

    /**
     * Endereco publico deduzido da requisicao - dispensa configurar
     * `reports.public-url`, que fica so como sobrescrita.
     *
     * Atras do Traefik isso depende de `server.forward-headers-strategy: framework`:
     * o filtro do Spring aplica X-Forwarded-Proto/Host/Port e, principalmente, o
     * X-Forwarded-Prefix que o stripprefix envia. Sem o prefixo o endereco sairia
     * sem o /reports e o GPT chamaria o sap-service.
     *
     * Porta 80/443 e omitida mesmo quando nao bate com o esquema: atras de TLS
     * terminado antes do Traefik chega "https" com a porta 80 do entrypoint.
     */
    internal fun urlDaRequisicao(request: HttpServletRequest): String =
        "${request.scheme}://${request.serverName}" +
            (if (request.serverPort in listOf(80, 443)) "" else ":${request.serverPort}") +
            request.contextPath.trimEnd('/')
}
