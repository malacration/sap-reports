package br.andrew.sap_reports.web

import br.andrew.sap_reports.security.KeycloakAuthenticationFilter
import br.andrew.sap_reports.security.ServiceTokenService
import br.andrew.sap_reports.security.UsuarioAutenticado
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * Tokens de servico para agentes de IA.
 *
 * So papel de autoria acessa, e um token de servico NAO consegue chegar aqui
 * (ver ROTAS_PROIBIDAS_AO_TOKEN no filtro) - senao um token emitiria outro e a
 * expiracao deixaria de significar alguma coisa.
 */
@RestController
@RequestMapping("/api/v1/admin/tokens")
class TokenController(private val service: ServiceTokenService) {

    data class NovoToken(val nome: String = "", val diasValidade: Int = 30)

    /**
     * O valor do token aparece **uma unica vez**, nesta resposta. Nao ha
     * endpoint para recupera-lo: o banco guarda apenas o hash.
     */
    @PostMapping
    fun criar(request: HttpServletRequest, @RequestBody corpo: NovoToken): ResponseEntity<Any> {
        val usuario = request.getAttribute(KeycloakAuthenticationFilter.USUARIO_ATTRIBUTE) as? UsuarioAutenticado
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try {
            val criado = service.criar(corpo.nome, corpo.diasValidade, usuario.usuario)
            ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf(
                    "id" to criado.id,
                    "nome" to criado.nome,
                    "token" to criado.valor,
                    "expiraEm" to criado.expiraEm,
                    "aviso" to "Guarde agora: este valor nao sera exibido de novo.",
                ),
            )
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("erro" to "parametros_invalidos", "mensagem" to ex.message))
        }
    }

    @GetMapping
    fun listar() = service.listar().map {
        mapOf(
            "id" to it.id, "nome" to it.nome, "prefixo" to it.prefixo,
            "criadoPor" to it.criadoPor, "criadoEm" to it.criadoEm,
            "expiraEm" to it.expiraEm, "revogadoEm" to it.revogadoEm,
            "ultimoUso" to it.ultimoUso, "ativo" to it.ativo,
            // Ajuda a tela a destacar o que precisa de atencao.
            "expirado" to it.expiraEm.isBefore(LocalDateTime.now()),
        )
    }

    @DeleteMapping("/{id}")
    fun revogar(@PathVariable id: String): ResponseEntity<Any> =
        if (service.revogar(id)) ResponseEntity.noContent().build()
        else ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("erro" to "nao_encontrado", "mensagem" to "Token inexistente ou ja revogado."))
}
