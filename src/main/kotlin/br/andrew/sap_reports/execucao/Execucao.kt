package br.andrew.sap_reports.execucao

import java.time.Instant

enum class TipoExecucao { RENDER, PREVIEW }

enum class StatusExecucao {
    PENDENTE, EXECUTANDO, CONCLUIDO, FALHOU,

    /** So na resposta: concluida, mas o arquivo ja foi apagado (retencao vencida). */
    EXCLUIDO,
}

/**
 * Uma execucao de relatorio, como fica guardada no Redis.
 *
 * Guarda os parametros para permitir "gerar novamente" depois que o arquivo
 * expira. `logo` e `papeis` so servem para executar: sao descartados ao terminar.
 */
data class Execucao(
    val id: String,
    /** Dono - so ele lista, consulta e baixa. Ver [br.andrew.sap_reports.security.UsuarioAutenticado.dono]. */
    val dono: String,
    val tipo: TipoExecucao,
    val relatorioId: Long,
    val relatorioNome: String,
    /** Versao executada; em RENDER, a publicada no momento do pedido. */
    val versao: Int?,
    val formato: String,
    val params: Map<String, Any?> = emptyMap(),
    val logo: String? = null,
    /** Papeis do usuario no pedido: o render confere o acesso de novo ao executar. */
    val papeis: Set<String> = emptySet(),
    val status: StatusExecucao = StatusExecucao.PENDENTE,
    val criadoEm: Instant,
    val iniciadoEm: Instant? = null,
    val concluidoEm: Instant? = null,
    val falha: FalhaExecucao? = null,
    val arquivo: ArquivoExecucao? = null,
) {
    val ativa: Boolean get() = status == StatusExecucao.PENDENTE || status == StatusExecucao.EXECUTANDO
}

/** O mesmo envelope `erro`/`mensagem` da API, com o status HTTP que a rota sincrona daria. */
data class FalhaExecucao(val status: Int, val erro: String, val mensagem: String)

data class ArquivoExecucao(val nome: String, val contentType: String, val extensao: String, val tamanho: Long)

/** O que a API devolve: sem logo, sem papeis e com o status ja resolvido (EXCLUIDO). */
data class ExecucaoDto(
    val id: String,
    val tipo: TipoExecucao,
    val relatorioId: Long,
    val relatorioNome: String,
    val versao: Int?,
    val formato: String,
    val params: Map<String, Any?>,
    val status: StatusExecucao,
    val criadoEm: Instant,
    val iniciadoEm: Instant?,
    val concluidoEm: Instant?,
    /** Quando o arquivo deixa de existir; null enquanto nao concluir. */
    val expiraEm: Instant?,
    val erro: FalhaExecucao?,
    val arquivo: ArquivoExecucao?,
)

class ExecucaoNaoEncontradaException(id: String) : RuntimeException("Execucao '$id' nao existe ou nao e sua.")
class ExecucaoExcluidaException : RuntimeException(
    "O arquivo desta execucao foi excluido (fica disponivel por tempo limitado). Gere o relatorio novamente.",
)
class ExecucaoEmAndamentoException(mensagem: String) : RuntimeException(mensagem)
class MuitasExecucoesException(limite: Int) : RuntimeException(
    "Voce ja tem $limite relatorios na fila ou em execucao. Aguarde algum terminar.",
)
