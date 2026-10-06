package br.andrew.sap_reports.web

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Parametro
import java.time.LocalDateTime

data class UploadRequest(val definicao: String = "", val template: String = "")
data class RenderRequest(
    val params: Map<String, Any?> = emptyMap(),
    /** Logo do cliente (`data:image/...;base64`), exposta ao template como `{{meta.logo}}`. */
    val logo: String? = null,
)
data class PublicarRequest(val versao: Int)

data class RelatorioResumo(
    val id: Long,
    val nome: String,
    val descricao: String?,
    val formatos: List<String>,
    val versaoPublicada: Int,
    val atualizadoEm: LocalDateTime,
)

data class RelatorioDetalhe(
    val id: Long,
    val nome: String,
    val descricao: String?,
    val formatos: List<String>,
    val versaoPublicada: Int,
    val atualizadoEm: LocalDateTime,
    val parametros: List<Parametro>,
    val colunas: List<Coluna>,
)

data class RelatorioAdmin(
    val id: Long,
    val nome: String,
    val descricao: String?,
    val status: String,
    val ultimaVersao: Int,
    val versaoPublicada: Int?,
    val papeis: List<String>,
    val formatos: List<String>,
    val criadoPor: String,
    val criadoEm: LocalDateTime,
    val atualizadoEm: LocalDateTime,
)

data class RelatorioAdminDetalhe(
    val id: Long,
    val nome: String,
    val descricao: String?,
    val status: String,
    val ultimaVersao: Int,
    val versaoPublicada: Int?,
    val papeis: List<String>,
    val formatos: List<String>,
    val criadoPor: String,
    val criadoEm: LocalDateTime,
    val atualizadoEm: LocalDateTime,
    val versao: Int,
    val definicao: String,
    val template: String,
)

data class VersaoDto(
    val versao: Int,
    val criadoPor: String,
    val criadoEm: LocalDateTime,
    val publicadoEm: LocalDateTime?,
    val publicada: Boolean,
)

data class EventoAuditoriaDto(
    val quem: String,
    val quando: LocalDateTime,
    val acao: String,
    val versao: Int?,
)

data class ErroDto(val erro: String, val mensagem: String)
data class ProblemaDto(val caminho: String, val regra: String, val mensagem: String, val linha: Int?)
data class ValidacaoFalhaDto(
    val erro: String = "validacao_falhou",
    val mensagem: String = "A definicao do relatorio foi recusada.",
    val problemas: List<ProblemaDto>,
)
data class ValidacaoOkDto(val valido: Boolean = true, val tabelas: List<String>, val parametros: List<String>)

/**
 * Relatorio pronto para envio: em memoria (HTML e arquivos pequenos) ou num arquivo
 * temporario (PDF/CSV grandes). Feche depois de enviar - [close] apaga o temporario.
 */
class SaidaRenderizada private constructor(
    val contentType: String,
    val extensao: String,
    private val emMemoria: ByteArray?,
    private val arquivo: java.nio.file.Path?,
    private val temporario: java.nio.file.Path?,
) : AutoCloseable {
    constructor(bytes: ByteArray, contentType: String, extensao: String) : this(contentType, extensao, bytes, null, null)

    /** Conteudo inteiro em memoria. Para arquivo grande, prefira [escreverEm]. */
    val bytes: ByteArray get() = emMemoria ?: java.nio.file.Files.readAllBytes(arquivo!!)

    val tamanho: Long get() = emMemoria?.size?.toLong() ?: java.nio.file.Files.size(arquivo!!)

    fun escreverEm(saida: java.io.OutputStream) {
        if (emMemoria != null) saida.write(emMemoria) else java.nio.file.Files.copy(arquivo!!, saida)
    }

    override fun close() {
        temporario?.let(::apagarDiretorio)
    }

    companion object {
        /** [arquivo] fica dentro de [temporario], que e apagado inteiro no [close]. */
        fun deArquivo(arquivo: java.nio.file.Path, contentType: String, extensao: String, temporario: java.nio.file.Path) =
            SaidaRenderizada(contentType, extensao, null, arquivo, temporario)

        fun apagarDiretorio(diretorio: java.nio.file.Path) {
            if (!java.nio.file.Files.exists(diretorio)) return
            java.nio.file.Files.walk(diretorio).use { caminhos ->
                caminhos.sorted(Comparator.reverseOrder()).forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
            }
        }
    }
}

class ParametrosInvalidosException(mensagem: String) : RuntimeException(mensagem)
/**
 * Relatorio grande (montado em disco, linha a linha) com agrupamento cujos grupos nao vem
 * contiguos: o subtotal de um grupo ja teria sido escrito quando ele reaparece.
 */
class RelatorioDesordenadoException(val campo: String, val limite: Int) : RuntimeException(
    "A consulta passou de $limite linhas e, acima disso, o agrupamento exige as linhas " +
        "ordenadas pelas colunas agrupadas. Inclua ORDER BY comecando pelas colunas de 'agrupar' " +
        "(o grupo '$campo' reapareceu depois de fechado).",
)

class ResultadoTruncadoException(val limite: Int) : RuntimeException(
    "A consulta retornou mais de $limite linhas. Restrinja o periodo.",
)
class FormatoInvalidoException(formato: String) : RuntimeException("Formato '$formato' invalido ou nao habilitado.")
class AcessoRelatorioNegadoException : RuntimeException("Usuario sem papel para acessar este relatorio.")
