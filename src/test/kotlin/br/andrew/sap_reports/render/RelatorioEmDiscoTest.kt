package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.Resumo
import br.andrew.sap_reports.odbc.QueryResponse
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * O caminho em disco (linha a linha) tem que produzir EXATAMENTE o mesmo CSV que o caminho
 * em memoria para os mesmos dados - senao o mesmo relatorio mudaria de conteudo so por ter
 * passado do limite de linhas.
 */
class RelatorioEmDiscoTest {

    @TempDir
    lateinit var dir: Path

    private val colunas = listOf(
        Coluna("VENDEDOR", "Vendedor"),
        Coluna("CLIENTE", "Cliente"),
        Coluna("NOTAS", "Notas", "numero", total = "count"),
        Coluna("TOTAL", "Total", "moeda", total = "sum"),
        Coluna("MEDIA", "Media", "moeda", total = "avg"),
        Coluna("MENOR", "Menor", "numero", total = "min"),
        Coluna("MAIOR", "Maior", "numero", total = "max"),
    )

    // Contiguo por VENDEDOR e CLIENTE (como viria com ORDER BY VENDEDOR, CLIENTE); decimal
    // como string, igual ao sap-odbc; um nulo para AVG/COUNT ignorarem.
    private val linhas = listOf(
        linha("Ana", "C1", 1, "0.1"), linha("Ana", "C1", 2, "0.2"), linha("Ana", "C2", 3, null),
        linha("Bia", "C1", 4, "1000.50"), linha("Bia", "C3", 5, "3"), linha(null, "C9", 6, "7.25"),
    )

    private fun linha(vendedor: String?, cliente: String, n: Int, total: String?) = linkedMapOf<String, Any?>(
        "VENDEDOR" to vendedor, "CLIENTE" to cliente, "NOTAS" to n, "TOTAL" to total,
        "MEDIA" to total, "MENOR" to n, "MAIOR" to n,
    )

    private fun def(agrupar: List<String>, resumos: List<Resumo> = emptyList()) = ReportDefinition(
        nome = "Teste", papeis = listOf("admin"), consulta = Consulta("SELECT 1"),
        colunas = colunas, agrupar = agrupar, resumos = resumos,
    )

    private fun emDisco(def: ReportDefinition, dados: List<Map<String, Any?>> = linhas): Pair<ResultadoEmDisco, AgregadoEmFluxo> {
        val disco = ResultadoEmDisco(dir)
        val agregado = AgregadoEmFluxo(def)
        dados.forEach { disco.gravar(it); agregado.somar(it) }
        disco.close()
        return disco to agregado
    }

    private fun csvEmFluxo(def: ReportDefinition): ByteArray {
        val (disco, agregado) = emDisco(def)
        return ByteArrayOutputStream().also { CsvRenderer().renderizarEmFluxo(def, disco, agregado, it) }.toByteArray()
    }

    private fun csvEmMemoria(def: ReportDefinition) =
        CsvRenderer().renderizar(def, QueryResponse(emptyList(), linhas, linhas.size, false, 0))

    @Test
    fun `csv em fluxo e identico ao em memoria - sem agrupamento`() {
        val d = def(emptyList())
        assertContentEquals(csvEmMemoria(d), csvEmFluxo(d))
    }

    @Test
    fun `csv em fluxo e identico ao em memoria - dois niveis, todos os totais e resumo`() {
        // O resumo por CLIENTE nao e contiguo (C1 aparece em Ana e Bia): resumo nao exige ordem.
        val d = def(listOf("VENDEDOR", "CLIENTE"), listOf(Resumo(por = listOf("CLIENTE")), Resumo("Por vendedor", listOf("VENDEDOR", "CLIENTE"))))
        val esperado = csvEmMemoria(d)
        assertContentEquals(esperado, csvEmFluxo(d), String(csvEmFluxo(d)) + "\n---\n" + String(esperado))
    }

    @Test
    fun `agregado aponta o primeiro grupo que reaparece depois de fechado`() {
        val d = def(listOf("VENDEDOR", "CLIENTE"))
        assertNull(emDisco(d).second.desordenado)

        val foraDeOrdem = listOf(linha("Ana", "C1", 1, "1"), linha("Bia", "C1", 2, "1"), linha("Ana", "C2", 3, "1"))
        assertEquals("VENDEDOR", emDisco(d, foraDeOrdem).second.desordenado)

        val clienteVolta = listOf(linha("Ana", "C1", 1, "1"), linha("Ana", "C2", 2, "1"), linha("Ana", "C1", 3, "1"))
        assertEquals("CLIENTE", emDisco(d, clienteVolta).second.desordenado)
    }

    @Test
    fun `no do grupo sabe onde comeca e termina e soma o grupo inteiro`() {
        val (_, agregado) = emDisco(def(listOf("VENDEDOR")))
        val ana = agregado.caminho(linhas[0]).single()
        assertEquals(0, ana.primeira)
        assertEquals(2, ana.ultima)
        assertEquals(3, ana.quantidade)
        assertEquals("R$ 0,30", ana.totais["TOTAL"]!!.replace('\u00A0', ' '))
        assertEquals(agregado.totais, Agrupamento.calcular(def(listOf("VENDEDOR")), linhas).totais)
    }

    @Test
    fun `linhas relidas do disco tem os mesmos valores das gravadas`() {
        val (disco, _) = emDisco(def(emptyList()))
        assertEquals(linhas, disco.emMemoria().rows)
    }
}
