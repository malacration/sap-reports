package br.andrew.sap_reports.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * O front pode apontar para um host diferente deste servico. Sem CORS o
 * navegador barra no preflight, e o erro que chega na tela nao diz nada util -
 * parece falha de rede.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["cors.origins=https://front.exemplo.com.br"])
class CorsConfigTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `origem configurada passa no preflight`() {
        mvc.perform(
            options("/api/v1/relatorios")
                .header("Origin", "https://front.exemplo.com.br")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "https://front.exemplo.com.br"))
    }

    @Test
    fun `Authorization e aceito no preflight`() {
        // Sem este header liberado o token do Keycloak nao chega e tudo vira 401.
        mvc.perform(
            options("/api/v1/relatorios")
                .header("Origin", "https://front.exemplo.com.br")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
    }

    @Test
    fun `Content-Disposition e exposto para o download saber o nome do arquivo`() {
        mvc.perform(
            options("/api/v1/relatorios")
                .header("Origin", "https://front.exemplo.com.br")
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Content-Disposition")))
    }

    @Test
    fun `origem desconhecida e recusada`() {
        mvc.perform(
            options("/api/v1/relatorios")
                .header("Origin", "https://site-qualquer.com")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isForbidden)
    }
}
