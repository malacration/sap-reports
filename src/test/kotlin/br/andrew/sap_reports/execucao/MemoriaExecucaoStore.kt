package br.andrew.sap_reports.execucao

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Dublê do Redis com o mesmo contrato de [RedisExecucaoStore]. */
class MemoriaExecucaoStore : ExecucaoStore {
    val execucoes = ConcurrentHashMap<String, Execucao>()
    val fila = LinkedBlockingDeque<String>()
    private val ativas = ConcurrentHashMap.newKeySet<String>()

    override fun salvar(execucao: Execucao) {
        execucoes[execucao.id] = execucao
        if (execucao.ativa) ativas += execucao.id else ativas -= execucao.id
    }

    override fun buscar(id: String) = execucoes[id]

    override fun listar(dono: String, limite: Int) =
        execucoes.values.filter { it.dono == dono }.sortedByDescending { it.criadoEm }.take(limite)

    override fun remover(execucao: Execucao) {
        execucoes -= execucao.id
        ativas -= execucao.id
    }

    override fun enfileirar(id: String) = fila.addFirst(id)

    override fun proxima(espera: Duration): String? = fila.pollLast(espera.toMillis(), TimeUnit.MILLISECONDS)

    override fun ativas(): Set<String> = ativas.toSet()
}
