package br.andrew.sap_reports.execucao

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contra um Redis de verdade. So roda com REDIS_TESTE_HOST definido:
 *
 *     docker run -d --rm -p 6379:6379 redis:7-alpine
 *     REDIS_TESTE_HOST=localhost ./gradlew test --tests '*RedisExecucaoStoreTest*'
 */
class RedisExecucaoStoreTest {
    private lateinit var fabrica: LettuceConnectionFactory
    private lateinit var store: RedisExecucaoStore
    private val prefixo = "teste-${UUID.randomUUID()}"

    @BeforeEach
    fun conectar() {
        val host = System.getenv("REDIS_TESTE_HOST")
        assumeTrue(!host.isNullOrBlank(), "REDIS_TESTE_HOST nao definido")
        fabrica = LettuceConnectionFactory(RedisStandaloneConfiguration(host, 6379)).also {
            it.afterPropertiesSet()
            it.start()
        }
        val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        store = RedisExecucaoStore(StringRedisTemplate(fabrica), json, ExecucaoProperties(prefixoRedis = prefixo))
    }

    @AfterEach
    fun desconectar() {
        if (!::fabrica.isInitialized) return
        val redis = StringRedisTemplate(fabrica)
        redis.keys("$prefixo:*").let { if (it.isNotEmpty()) redis.delete(it) }
        fabrica.destroy()
    }

    private fun execucao(dono: String, criadoEm: Instant = Instant.now()) = Execucao(
        id = UUID.randomUUID().toString(), dono = dono, tipo = TipoExecucao.RENDER,
        relatorioId = 7, relatorioNome = "Vendas", versao = 1, formato = "pdf",
        params = mapOf("dataInicio" to "2026-01-01", "filiais" to listOf(1, 2)),
        criadoEm = criadoEm,
    )

    @Test
    fun `grava, le de volta e lista por dono do mais recente ao mais antigo`() {
        val antiga = execucao("ana", Instant.now().minusSeconds(60))
        val nova = execucao("ana")
        store.salvar(antiga)
        store.salvar(nova)
        store.salvar(execucao("bia"))

        assertEquals(listOf(nova.id, antiga.id), store.listar("ana", 10).map { it.id })
        assertEquals(nova, store.buscar(nova.id), "ida e volta pelo JSON sem perder nada")
    }

    @Test
    fun `fila entrega na ordem de chegada e marca ativas`() {
        val primeira = execucao("ana").also(store::salvar)
        val segunda = execucao("ana").also(store::salvar)
        store.enfileirar(primeira.id)
        store.enfileirar(segunda.id)

        assertEquals(primeira.id, store.proxima(Duration.ofSeconds(1)))
        assertEquals(segunda.id, store.proxima(Duration.ofSeconds(1)))
        assertNull(store.proxima(Duration.ofMillis(100)))
        assertEquals(setOf(primeira.id, segunda.id), store.ativas())

        store.salvar(primeira.copy(status = StatusExecucao.CONCLUIDO))
        assertEquals(setOf(segunda.id), store.ativas())
    }

    @Test
    fun `remover tira do historico e execucao expirada some da lista`() {
        val e = execucao("ana").also(store::salvar)
        store.remover(e)
        assertNull(store.buscar(e.id))
        assertTrue(store.listar("ana", 10).isEmpty())

        val outra = execucao("ana").also(store::salvar)
        StringRedisTemplate(fabrica).delete("$prefixo:execucao:${outra.id}")   // como se o TTL vencesse
        assertTrue(store.listar("ana", 10).isEmpty())
    }
}
