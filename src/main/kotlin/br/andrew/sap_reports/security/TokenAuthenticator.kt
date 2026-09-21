package br.andrew.sap_reports.security

import org.springframework.stereotype.Service

fun interface TokenAuthenticator {
    fun autenticar(token: String): UsuarioAutenticado
}

@Service
class KeycloakTokenAuthenticator(
    private val jwtService: KeycloakJwtService,
    private val userMapper: KeycloakUserMapper,
) : TokenAuthenticator {
    override fun autenticar(token: String): UsuarioAutenticado = userMapper.mapear(jwtService.validar(token))
}
