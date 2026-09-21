package br.andrew.sap_reports.storage

import java.time.LocalDateTime

data class RelatorioRegistro(
    val id: Long,
    val nome: String,
    val descricao: String?,
    val papeis: List<String>,
    val formatos: List<String>,
    val versaoPublicada: Int?,
    val ultimaVersao: Int,
    val criadoPor: String,
    val criadoEm: LocalDateTime,
    val atualizadoEm: LocalDateTime,
)

data class RelatorioVersaoRegistro(
    val relatorioId: Long,
    val versao: Int,
    val definicao: String,
    val template: String,
    val criadoPor: String,
    val criadoEm: LocalDateTime,
    val publicadoEm: LocalDateTime?,
)

data class EventoAuditoriaRegistro(
    val quem: String,
    val quando: LocalDateTime,
    val acao: AcaoAuditoria,
    val versao: Int?,
)

enum class AcaoAuditoria {
    CRIOU, NOVA_VERSAO, PUBLICOU, ROLLBACK, REMOVEU,
}

class RelatorioNaoEncontradoException(val relatorioId: Long, val versao: Int? = null) :
    RuntimeException(
        versao?.let { "Relatorio $relatorioId nao possui a versao $it." }
            ?: "Relatorio $relatorioId nao existe.",
    )
