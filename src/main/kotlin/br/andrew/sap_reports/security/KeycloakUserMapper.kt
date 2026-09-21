package br.andrew.sap_reports.security

import io.jsonwebtoken.Claims
import org.springframework.stereotype.Component

@Component
class KeycloakUserMapper(private val properties: KeycloakProperties) {
    private val filialPrefix = "filial-"

    fun mapear(claims: Claims): UsuarioAutenticado {
        val papeisToken = extrairPapeis(claims)
        val filiais = papeisToken.asSequence()
            .filter { it.startsWith(filialPrefix, ignoreCase = true) }
            .mapNotNull { papel ->
                papel.substring(filialPrefix.length).substringBefore('-').toIntOrNull()
            }
            .toSet()
        val papeis = papeisToken
            .filterNot { it.startsWith(filialPrefix, ignoreCase = true) }
            .toSet()
        val sapId = claims[properties.sapIdClaim]?.toString()?.toIntOrNull()?.toString()
            ?: throw IllegalArgumentException("Claim '${properties.sapIdClaim}' ausente ou invalido no token.")
        val usuario = claims["preferred_username"]?.toString()
            ?: claims["email"]?.toString()
            ?: claims.subject
            ?: throw IllegalArgumentException("Token sem identificacao do usuario.")
        return UsuarioAutenticado(usuario, sapId, papeis, filiais)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun extrairPapeis(claims: Claims): List<String> {
        val papeis = mutableListOf<String>()
        (claims["realm_access"] as? Map<String, Any?>)?.let { acesso ->
            (acesso["roles"] as? List<*>)?.forEach { it?.let { papel -> papeis += papel.toString() } }
        }
        (claims["resource_access"] as? Map<String, Any?>)?.get(properties.clientId)?.let { cliente ->
            ((cliente as? Map<String, Any?>)?.get("roles") as? List<*>)
                ?.forEach { it?.let { papel -> papeis += papel.toString() } }
        }
        return papeis.distinct()
    }
}
