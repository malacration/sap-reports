package br.andrew.sap_reports.execucao

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Consome a fila do Redis com [ExecucaoProperties.workers] threads.
 *
 * BRPOP com espera curta, e nao infinita: o laco volta a cada poucos segundos
 * para perceber o desligamento. Redis fora do ar nao derruba o servico - o laco
 * registra e tenta de novo.
 */
@Component
class ExecucaoWorker(
    private val service: ExecucaoService,
    private val store: ExecucaoStore,
    private val props: ExecucaoProperties,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var rodando = false
    private val threads = mutableListOf<Thread>()

    override fun start() {
        if (props.workers <= 0) return
        rodando = true
        runCatching { service.recuperarInterrompidas() }
            .onFailure { log.warn("Nao foi possivel recuperar execucoes interrompidas: {}", it.message) }
        repeat(props.workers) { i ->
            threads += Thread.ofPlatform().name("execucao-relatorio-$i").daemon(true).start(::laco)
        }
        log.info("{} worker(s) de relatorio consumindo a fila", props.workers)
    }

    private fun laco() {
        while (rodando) {
            try {
                val id = store.proxima(ESPERA) ?: continue
                service.executar(id)
            } catch (ex: Exception) {
                if (!rodando) return
                log.warn("Falha ao consumir a fila de relatorios: {}", ex.message)
                try {
                    Thread.sleep(PAUSA_APOS_ERRO.toMillis())
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    override fun stop() {
        rodando = false
        threads.forEach(Thread::interrupt)
        threads.clear()
    }

    override fun isRunning() = rodando

    private companion object {
        val ESPERA: Duration = Duration.ofSeconds(5)
        val PAUSA_APOS_ERRO: Duration = Duration.ofSeconds(2)
    }
}
