package br.andrew.sap_reports.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "keycloak")
data class KeycloakProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val realm: String = "",
    val clientId: String = "",
    val sapIdClaim: String = "sap_code",
) {
    fun issuer(): String = "${url.trimEnd('/')}/realms/$realm"
    fun jwksUri(): String = "${issuer()}/protocol/openid-connect/certs"
}
