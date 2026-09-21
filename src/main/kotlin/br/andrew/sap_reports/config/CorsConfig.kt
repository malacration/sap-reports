package br.andrew.sap_reports.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS do sap-reports.
 *
 * Necessario porque o front pode apontar para um HOST diferente deste servico -
 * acesso cruzado. Quando os dois ficam atras do mesmo gateway (com o prefixo
 * /api/sap-reports), o navegador nem consulta CORS; quando os hosts divergem,
 * sem isto a requisicao morre no preflight e o erro que aparece na tela nao diz
 * nada util sobre a causa.
 *
 * Segue o padrao do sap-rovema (`infrastructure/security/CorsConfig`):
 *  - `allowedOriginPatterns`, nao `allowedOrigins`: o segundo faz comparacao
 *    exata, entao padrao com `[*]` entraria como texto literal e nunca casaria;
 *  - `Authorization` precisa estar em allowedHeaders, senao o token do Keycloak
 *    nao chega e tudo vira 401 sem explicacao;
 *  - `Content-Disposition` precisa ser EXPOSTO, senao o front nao consegue ler o
 *    nome do arquivo de PDF/CSV baixado.
 */
@Configuration
class CorsConfig(
    @Value("\${cors.origins:http://localhost:4200}") private val origens: List<String>,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        val padroes = mutableListOf(
            "http://localhost:[*]",
            "http://*localhost:[*]",
        ).also { lista ->
            lista.addAll(origens.map { it.trim().removeSuffix("/") }.filter { it.isNotEmpty() })
        }

        registry.addMapping("/api/**")
            .allowedOriginPatterns(*padroes.toTypedArray())
            .allowedMethods("HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Cache-Control", "pragma")
            .exposedHeaders("Content-Disposition", "Content-Type")
            .allowCredentials(true)
    }
}
