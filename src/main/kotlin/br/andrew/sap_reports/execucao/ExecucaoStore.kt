package br.andrew.sap_reports.execucao

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/** Estado e fila das execucoes. Em producao, [RedisExecucaoStore]. */
interface ExecucaoStore {
    /** Grava (ou regrava) a execucao e a indexa no historico do dono. */
    fun salvar(execucao: Execucao)
    fun buscar(id: String): Execucao?
    /** Mais recentes primeiro. */
    fun listar(dono: String, limite: Int): List<Execucao>
    fun remover(execucao: Execucao)
    fun enfileirar(id: String)
    /** Proxima execucao da fila, esperando ate [espera]; null se nada chegou. */
    fun proxima(espera: Duration): String?
    /** Ids marcados como PENDENTE/EXECUTANDO - usado para recuperar apos reinicio. */
    fun ativas(): Set<String>
}

/**
 * Chaves (com o prefixo configurado):
 *  - `execucao:{id}`        JSON da execucao, com TTL do historico;
 *  - `execucoes:dono:{dono}` sorted set id -> criadoEm, o historico do usuario;
 *  - `execucoes:fila`       lista: LPUSH ao pedir, BRPOP nos workers;
 *  - `execucoes:ativas`     set das que estao na fila ou rodando.
 *
 * O estado mora no Redis, e nao na memoria do servico, para sobreviver a
 * reinicio: quem recarrega a pagina - ou volta depois de um deploy - encontra
 * as proprias execucoes.
 */
@Component
class RedisExecucaoStore(
    private val redis: StringRedisTemplate,
    private val json: JsonMapper,
    private val props: ExecucaoProperties,
) : ExecucaoStore {
    private val prefixo = props.prefixoRedis
    private val fila = "$prefixo:execucoes:fila"
    private val ativas = "$prefixo:execucoes:ativas"
    private val historico get() = Duration.ofDays(props.historicoDias)

    private fun chave(id: String) = "$prefixo:execucao:$id"
    private fun doDono(dono: String) = "$prefixo:execucoes:dono:$dono"

    override fun salvar(execucao: Execucao) {
        redis.opsForValue().set(chave(execucao.id), json.writeValueAsString(execucao), historico)
        redis.opsForZSet().add(doDono(execucao.dono), execucao.id, execucao.criadoEm.toEpochMilli().toDouble())
        redis.expire(doDono(execucao.dono), historico)
        if (execucao.ativa) redis.opsForSet().add(ativas, execucao.id)
        else redis.opsForSet().remove(ativas, execucao.id)
    }

    override fun buscar(id: String): Execucao? =
        redis.opsForValue().get(chave(id))?.let { json.readValue(it, Execucao::class.java) }

    override fun listar(dono: String, limite: Int): List<Execucao> {
        val indice = doDono(dono)
        val corte = Instant.now().minus(historico).toEpochMilli().toDouble()
        redis.opsForZSet().removeRangeByScore(indice, Double.NEGATIVE_INFINITY, corte)
        val ids = redis.opsForZSet().reverseRange(indice, 0, limite - 1L)?.toList().orEmpty()
        if (ids.isEmpty()) return emptyList()
        val valores = redis.opsForValue().multiGet(ids.map(::chave)).orEmpty()
        // Execucao cujo JSON expirou: tira do indice para nao reaparecer.
        val sumidas = ids.filterIndexed { i, _ -> valores.getOrNull(i) == null }
        if (sumidas.isNotEmpty()) redis.opsForZSet().remove(indice, *sumidas.toTypedArray())
        return valores.filterNotNull().map { json.readValue(it, Execucao::class.java) }
    }

    override fun remover(execucao: Execucao) {
        redis.delete(chave(execucao.id))
        redis.opsForZSet().remove(doDono(execucao.dono), execucao.id)
        redis.opsForSet().remove(ativas, execucao.id)
    }

    override fun enfileirar(id: String) {
        redis.opsForList().leftPush(fila, id)
    }

    override fun proxima(espera: Duration): String? = redis.opsForList().rightPop(fila, espera)

    override fun ativas(): Set<String> = redis.opsForSet().members(ativas).orEmpty()
}
