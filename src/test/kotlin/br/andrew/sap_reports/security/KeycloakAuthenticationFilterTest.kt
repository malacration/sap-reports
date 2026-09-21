package br.andrew.sap_reports.security

import br.andrew.sap_reports.config.ReportProperties
import io.jsonwebtoken.Jwts
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeycloakAuthenticationFilterTest {
    @Test
    fun `token de servico nao pode excluir relatorio`() {
        val filter = filtro(UsuarioAutenticado("agente", "10", setOf("admin"), emptySet(), true))
        val request = MockHttpServletRequest("DELETE", "/api/v1/admin/relatorios/vendas").apply {
            addHeader("Authorization", "token-valido")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        assertEquals(403, response.status)
    }

    @Test
    fun `context path e segmentos codificados nao contornam autenticacao`() {
        listOf("/reports/api/v1/relatorios", "/api;v=1/v1/relatorios", "/%61pi/v1/relatorios").forEach { uri ->
            val request = MockHttpServletRequest("GET", uri).apply {
                if (uri.startsWith("/reports/")) contextPath = "/reports"
            }
            val response = MockHttpServletResponse()
            filtro(UsuarioAutenticado("ana", "10", emptySet(), emptySet()))
                .doFilter(request, response, MockFilterChain())
            assertEquals(401, response.status, uri)
        }
    }

    @Test
    fun `parametros de matriz nao contornam papel de administrador`() {
        val request = MockHttpServletRequest("GET", "/api/v1/admin;x=1/relatorios").apply {
            addHeader("Authorization", "token-valido")
        }
        val response = MockHttpServletResponse()
        filtro(UsuarioAutenticado("ana", "10", setOf("vendedor"), emptySet()))
            .doFilter(request, response, MockFilterChain())
        assertEquals(403, response.status)
    }

    @Test
    fun `rota da api sem token devolve 401`() {
        val filter = filtro(UsuarioAutenticado("ana", "10", setOf("admin"), emptySet()))
        val request = MockHttpServletRequest("GET", "/api/v1/relatorios")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("nao_autorizado"))
    }

    @Test
    fun `usuario autenticado com papel errado recebe 403 em admin`() {
        val filter = filtro(UsuarioAutenticado("ana", "10", setOf("vendedor"), emptySet()))
        val request = MockHttpServletRequest("GET", "/api/v1/admin/relatorios").apply {
            addHeader("Authorization", "token-valido")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("proibido"))
    }

    @Test
    fun `mapper combina papeis do realm e client e extrai filial e id SAP`() {
        val properties = KeycloakProperties(clientId = "front-sap", sapIdClaim = "sap_code")
        val claims = Jwts.claims().add(
            mapOf(
                "preferred_username" to "ana",
                "sap_code" to 10,
                "realm_access" to mapOf("roles" to listOf("admin", "filial-3-matriz")),
                "resource_access" to mapOf("front-sap" to mapOf("roles" to listOf("financeiro"))),
            ),
        ).build()

        val usuario = KeycloakUserMapper(properties).mapear(claims)

        assertEquals("10", usuario.sapId)
        assertEquals(setOf("admin", "financeiro"), usuario.papeis)
        assertEquals(setOf(3), usuario.filiais)
    }

    /** ServiceTokenService que nao reconhece nenhum token. */
    private fun semTokensDeServico(): br.andrew.sap_reports.security.ServiceTokenService =
        object : br.andrew.sap_reports.security.ServiceTokenService(
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:vazio", "sa", ""),
            ),
            br.andrew.sap_reports.config.ReportProperties(),
        ) {
            override fun autenticar(valor: String) = null
        }

    private fun filtro(usuario: UsuarioAutenticado) = KeycloakAuthenticationFilter(
        KeycloakProperties(enabled = true),
        TokenAuthenticator { usuario },
        ReportProperties(authorRole = "admin"),
        // Estes testes exercitam o caminho do Keycloak. O de token de servico tem
        // suite propria (ServiceTokenServiceTest); aqui basta um dublê que nunca
        // reconhece token, garantindo que o caminho nao e acionado por engano.
        semTokensDeServico(),
    )
}
