package br.andrew.sap_reports.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwk
import io.jsonwebtoken.security.Jwks
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.security.PublicKey
import java.time.Duration
import java.util.Base64

/** Valida exclusivamente RS256 e mantem as chaves publicas do realm em memoria. */
@Service
class KeycloakJwtService(private val properties: KeycloakProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = RestClient.builder().requestFactory(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(10))
        },
    ).build()
    private val json = JsonMapper.builder().build()

    @Volatile
    private var chaves: Map<String, PublicKey> = emptyMap()

    fun validar(token: String): Claims {
        val cabecalho = lerCabecalho(token)
        require(cabecalho.alg == "RS256") { "Algoritmo JWT '${cabecalho.alg}' nao permitido." }
        val chave = localizarChave(cabecalho.kid)
        return Jwts.parser()
            .verifyWith(chave)
            .requireIssuer(properties.issuer())
            .build()
            .parseSignedClaims(token)
            .payload
    }

    private fun lerCabecalho(token: String): CabecalhoJwt {
        val partes = token.split('.')
        require(partes.size == 3) { "JWT malformado." }
        val no = json.readTree(String(Base64.getUrlDecoder().decode(partes[0]), Charsets.UTF_8))
        return CabecalhoJwt(
            alg = no["alg"]?.stringValue() ?: throw IllegalArgumentException("JWT sem alg."),
            kid = no["kid"]?.stringValue() ?: throw IllegalArgumentException("JWT sem kid."),
        )
    }

    private fun localizarChave(kid: String): PublicKey {
        if (chaves.isEmpty()) recarregarChaves()
        chaves[kid]?.let { return it }
        // kid desconhecido e o sinal esperado de rotacao de chave.
        recarregarChaves()
        return chaves[kid] ?: throw IllegalArgumentException("Chave '$kid' nao encontrada no JWKS.")
    }

    @Synchronized
    internal fun recarregarChaves() {
        val corpo = http.get().uri(properties.jwksUri()).retrieve().body(String::class.java)
            ?: throw IllegalStateException("JWKS vazio em ${properties.jwksUri()}.")
        val conjunto = Jwks.setParser().build().parse(corpo)
        chaves = conjunto.getKeys().mapNotNull { jwk: Jwk<*> ->
            (jwk.toKey() as? PublicKey)?.let { jwk.id to it }
        }.toMap()
        require(chaves.isNotEmpty()) { "JWKS nao contem chaves publicas." }
        log.info("JWKS do Keycloak carregado: {} chave(s)", chaves.size)
    }

    private data class CabecalhoJwt(val alg: String, val kid: String)
}
