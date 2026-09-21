package br.andrew.sap_reports.render

import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Logo enviada pelo cliente (o front manda o `logo.png` que o deploy injetou nele)
 * para aparecer no cabecalho do relatorio como `{{meta.logo}}`.
 *
 * Vem de fora, entao so passa imagem raster em `data:` base64 cujos bytes batem com
 * o tipo declarado. SVG fica de fora de proposito: pode carregar script e referencia
 * externa. Logo invalida e descartada - o relatorio sai sem ela, nao falha.
 */
object LogoRelatorio {
    private val log = LoggerFactory.getLogger(LogoRelatorio::class.java)

    /** ~512 KB de imagem. Logo maior que isso e erro de configuracao, nao logo. */
    const val MAX_BYTES = 512 * 1024

    private val FORMATO = Regex("^data:image/(png|jpeg|gif|webp);base64,([A-Za-z0-9+/]+={0,2})$")

    fun validar(logo: String?): String? {
        if (logo.isNullOrBlank()) return null
        val valor = logo.trim()
        val partes = FORMATO.matchEntire(valor)
        if (partes == null) {
            log.warn("Logo descartada: esperado data:image/(png|jpeg|gif|webp);base64,...")
            return null
        }
        val (tipo, base64) = partes.destructured
        if (base64.length > MAX_BYTES / 3 * 4 + 4) {
            log.warn("Logo descartada: maior que {} bytes.", MAX_BYTES)
            return null
        }
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
        if (bytes == null || !assinaturaConfere(tipo, bytes)) {
            log.warn("Logo descartada: conteudo nao e {}.", tipo)
            return null
        }
        return valor
    }

    private fun assinaturaConfere(tipo: String, b: ByteArray): Boolean = when (tipo) {
        "png" -> b.comeca(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        "jpeg" -> b.comeca(0xFF, 0xD8, 0xFF)
        "gif" -> b.comeca(0x47, 0x49, 0x46, 0x38)
        "webp" -> b.comeca(0x52, 0x49, 0x46, 0x46) && b.size > 12 &&
            String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }

    private fun ByteArray.comeca(vararg esperado: Int) =
        size >= esperado.size && esperado.indices.all { (this[it].toInt() and 0xFF) == esperado[it] }
}
