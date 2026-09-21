package br.andrew.sap_reports.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillControllerTest {
    private val controller = SkillController(SkillPackageService(), urlPublica = "")

    /** Passa a requisicao pelo mesmo filtro que `forward-headers-strategy: framework` registra. */
    private fun comoOServicoVe(requisicao: MockHttpServletRequest): HttpServletRequest {
        var vista: HttpServletRequest? = null
        ForwardedHeaderFilter().doFilter(requisicao, MockHttpServletResponse(), MockFilterChain().let { _ ->
            jakarta.servlet.FilterChain { req, _ -> vista = req as HttpServletRequest }
        })
        return vista!!
    }

    @Test
    fun `atras do Traefik deduz o endereco publico com o prefixo removido`() {
        // Como chega do Traefik: TLS terminado antes dele (porta 80 do entrypoint),
        // e o stripprefix informando o /reports que tirou do caminho.
        val requisicao = MockHttpServletRequest("GET", "/api/v1/admin/skill").apply {
            serverName = "sap-reports"; serverPort = 8080; scheme = "http"
            addHeader("X-Forwarded-Proto", "https")
            addHeader("X-Forwarded-Host", "cadastro-back.sustennutri.com.br")
            addHeader("X-Forwarded-Port", "80")
            addHeader("X-Forwarded-Prefix", "/reports")
        }
        assertEquals(
            "https://cadastro-back.sustennutri.com.br/reports",
            controller.urlDaRequisicao(comoOServicoVe(requisicao)),
        )
    }

    @Test
    fun `sem proxy, em desenvolvimento, usa host e porta locais`() {
        val requisicao = MockHttpServletRequest("GET", "/api/v1/admin/skill").apply {
            serverName = "localhost"; serverPort = 2030; scheme = "http"
        }
        assertEquals("http://localhost:2030", controller.urlDaRequisicao(comoOServicoVe(requisicao)))
    }
}
