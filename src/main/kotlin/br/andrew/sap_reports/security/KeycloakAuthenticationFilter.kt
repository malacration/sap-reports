package br.andrew.sap_reports.security

import br.andrew.sap_reports.config.ReportProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper

@Component
class KeycloakAuthenticationFilter(
    private val properties: KeycloakProperties,
    private val authenticator: TokenAuthenticator,
    private val reportProperties: ReportProperties,
    private val serviceTokens: ServiceTokenService,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)


    /**
     * O preflight do CORS (`OPTIONS`) NAO pode exigir autenticacao: o navegador
     * nao envia credencial nessa requisicao. Exigir token aqui devolve 401 ao
     * preflight, o navegador aborta, e o erro que chega na tela parece falha de
     * rede - sem nenhuma pista de que a causa e autenticacao. Classico "funciona
     * no Postman e falha no navegador", que so aparece com front em outro host.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        org.springframework.web.cors.CorsUtils.isPreFlightRequest(request) ||
            !caminho(request).startsWith("/api/v1/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (!properties.enabled || token == null) {
            responder(response, 401, "nao_autorizado", "Token ausente ou invalido.")
            return
        }

        // Token de servico (agente de IA) tem prefixo proprio e NAO passa pelo
        // Keycloak - e emitido e validado aqui. Verificado antes para nao gastar
        // uma validacao de assinatura JWT com um valor que nunca sera JWT.
        val usuario = if (token.startsWith(PREFIXO_SERVICO)) {
            serviceTokens.autenticar(token) ?: run {
                log.warn("Token de servico recusado (inexistente, expirado ou revogado)")
                responder(response, 401, "nao_autorizado", "Token de servico invalido, expirado ou revogado.")
                return
            }
        } else {
            try {
                authenticator.autenticar(token)
            } catch (ex: Exception) {
                log.warn("Token Keycloak recusado: {}", ex.message)
                responder(response, 401, "nao_autorizado", "Token ausente ou invalido.")
                return
            }
        }

        // Token de servico faz AUTORIA, nao administracao: nao publica, nao
        // remove e nao gerencia outros tokens. Publicar decide quem enxerga
        // faturamento - fica com uma pessoa. A checagem e por rota, e nao por
        // papel, porque o token herda o papel de autor de proposito.
        val caminho = caminho(request)
        if (usuario.tokenDeServico && (request.method == "DELETE" ||
                ROTAS_PROIBIDAS_AO_TOKEN.any { caminho.contains(it) })) {
            responder(
                response,
                403,
                "proibido",
                "Token de servico nao pode publicar, remover nem gerenciar tokens. " +
                    "Crie o rascunho e peca a uma pessoa para publicar.",
            )
            return
        }

        if (caminho.startsWith("/api/v1/admin/") && reportProperties.authorRole !in usuario.papeis) {
            responder(
                response,
                403,
                "proibido",
                "Requer o papel ${reportProperties.authorRole}.",
            )
            return
        }

        request.setAttribute(USUARIO_ATTRIBUTE, usuario)
        filterChain.doFilter(RequisicaoAutenticada(request, usuario), response)
    }

    private fun responder(response: HttpServletResponse, status: Int, erro: String, mensagem: String) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = "application/json"
        response.writer.write("{\"erro\":\"$erro\",\"mensagem\":\"${escapar(mensagem)}\"}")
    }

    private fun escapar(valor: String) = valor.replace("\\", "\\\\").replace("\"", "\\\"")

    // Usa o caminho da aplicacao, decodificado e sem parametros de matriz,
    // como o roteamento MVC. requestURI inclui context-path e texto codificado.
    private fun caminho(request: HttpServletRequest) =
        UrlPathHelper.defaultInstance.getPathWithinApplication(request)

    private class RequisicaoAutenticada(
        request: HttpServletRequest,
        private val usuario: UsuarioAutenticado,
    ) : HttpServletRequestWrapper(request) {
        override fun getUserPrincipal() = usuario
        override fun getRemoteUser() = usuario.usuario
        override fun isUserInRole(role: String?) = role != null && role in usuario.papeis
    }

    companion object {
        const val USUARIO_ATTRIBUTE = "usuarioAutenticado"

        /** Prefixo que distingue token de servico de um JWT do Keycloak. */
        const val PREFIXO_SERVICO = "rpt_"

        /**
         * Rotas que token de servico NAO acessa, mesmo herdando o papel de autor.
         * Publicar decide quem enxerga faturamento; gerenciar tokens permitiria a
         * um token emitir outro, sem prazo, driblando a expiracao.
         */
        val ROTAS_PROIBIDAS_AO_TOKEN = listOf("/publicar", "/rollback/", "/tokens")
    }
}
