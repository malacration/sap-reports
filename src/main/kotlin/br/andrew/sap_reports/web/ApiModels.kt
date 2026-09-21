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

data class SaidaRenderizada(
    val bytes: ByteArray,
    val contentType: String,
    val extensao: String,
)

class ParametrosInvalidosException(mensagem: String) : RuntimeException(mensagem)
class ResultadoTruncadoException(val limite: Int) : RuntimeException(
    "A consulta retornou mais de $limite linhas. Restrinja o periodo.",
)
class FormatoInvalidoException(formato: String) : RuntimeException("Formato '$formato' invalido ou nao habilitado.")
class AcessoRelatorioNegadoException : RuntimeException("Usuario sem papel para acessar este relatorio.")
