package br.andrew.sap_reports

import br.andrew.sap_reports.execucao.Execucao
import br.andrew.sap_reports.execucao.StatusExecucao
import br.andrew.sap_reports.execucao.TipoExecucao
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
class SapReportsApplicationTests {
    @Autowired
    lateinit var json: JsonMapper

    @Test
    fun `contexto sobe`() {
    }

    /** O RedisExecucaoStore usa o JsonMapper da aplicacao: a execucao precisa ir e voltar inteira. */
    @Test
    fun `execucao sobrevive ao JSON do Redis`() {
        val execucao = Execucao(
            id = "x", dono = "ana", tipo = TipoExecucao.PREVIEW, relatorioId = 3, relatorioNome = "Vendas",
            versao = 2, formato = "csv", params = mapOf("filiais" to listOf(1, 2), "inicio" to "2026-01-01"),
            status = StatusExecucao.CONCLUIDO, criadoEm = Instant.parse("2026-10-14T12:00:00.123Z"),
            concluidoEm = Instant.parse("2026-10-14T12:01:00Z"),
        )
        assertEquals(execucao, json.readValue(json.writeValueAsString(execucao), Execucao::class.java))
    }
}
