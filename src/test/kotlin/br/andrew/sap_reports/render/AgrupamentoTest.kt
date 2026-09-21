package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.QueryResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgrupamentoTest {
    private val colunas = listOf(
        Coluna("VENDEDOR", "Vendedor"),
        Coluna("CLIENTE", "Cliente"),
        Coluna("NOTAS", "Notas", "numero", total = "sum"),
        Coluna("TOTAL", "Total", "moeda", total = "sum"),
    )

    // BigDecimal chega como string do sap-odbc; 0.1 + 0.2 em Double daria 0,30000000000000004.
    private val linhas = listOf(
        mapOf("VENDEDOR" to "Ana", "CLIENTE" to "C1", "NOTAS" to 2, "TOTAL" to "0.1"),
        mapOf("VENDEDOR" to "Ana", "CLIENTE" to "C2", "NOTAS" to 1, "TOTAL" to "0.2"),
        mapOf("VENDEDOR" to "Bia", "CLIENTE" to "C3", "NOTAS" to 3, "TOTAL" to "1000.50"),
    )

    private fun def(agrupar: List<String> = listOf("VENDEDOR"), cols: List<Coluna> = colunas) = ReportDefinition(
        nome = "Teste", papeis = listOf("admin"), consulta = Consulta("SELECT 1"),
        colunas = cols, agrupar = agrupar,
    )

    @Test
    fun `soma por grupo e no total geral em BigDecimal`() {
        val a = Agrupamento.calcular(def(), linhas)

        assertEquals(listOf("Ana", "Bia"), a.grupos.map { it.valorBruto })
        assertEquals(listOf(listOf(0, 1), listOf(2)), a.grupos.map { it.indices })
        assertEquals(mapOf("NOTAS" to "3", "TOTAL" to "R$ 0,30"), a.grupos[0].totais.mapValues { it.value.replace(' ', ' ') })
        assertEquals("R$ 1.000,80", a.totais["TOTAL"]!!.replace(' ', ' '))
        assertEquals("6", a.totais["NOTAS"])
    }

    @Test
    fun `media minimo maximo e contagem`() {
        val cols = listOf(
            Coluna("VENDEDOR", "Vendedor", total = "count"),
            Coluna("NOTAS", "Notas", "numero", total = "avg"),
            Coluna("TOTAL", "Total", "numero", total = "max"),
        )
        val a = Agrupamento.calcular(def(emptyList(), cols), linhas)
        assertEquals("3", a.totais["VENDEDOR"])
        assertEquals("2", a.totais["NOTAS"])
        assertEquals("1.000,5", a.totais["TOTAL"])
        assertTrue(a.grupos.isEmpty())
    }

    @Test
    fun `dois niveis de agrupamento`() {
        val a = Agrupamento.calcular(def(listOf("VENDEDOR", "CLIENTE")), linhas)
        assertEquals(listOf("C1", "C2"), a.grupos[0].filhos.map { it.valorBruto })
        assertEquals("2", a.grupos[0].filhos[0].totais["NOTAS"])
        assertTrue(a.grupos[0].filhos.all { it.filhos.isEmpty() })
    }

    @Test
    fun `exemplo filial e vendedor - vendedor em duas filiais tem subtotal em cada uma`() {
        val base = "src/main/resources/skill/claude/exemplos/vendas-por-filial-e-vendedor"
        val definicao = br.andrew.sap_reports.service.DefinitionParser().ler(java.io.File("$base.yaml").readText())
        val dados = listOf(
            mapOf("FILIAL" to "Matriz", "VENDEDOR" to "Ana", "CLIENTE" to "C1", "NOME" to "X", "NOTAS" to 1, "TOTAL" to "100.00"),
            mapOf("FILIAL" to "Matriz", "VENDEDOR" to "Bia", "CLIENTE" to "C2", "NOME" to "Y", "NOTAS" to 2, "TOTAL" to "50.25"),
            mapOf("FILIAL" to "Filial AC", "VENDEDOR" to "Ana", "CLIENTE" to "C3", "NOME" to "Z", "NOTAS" to 1, "TOTAL" to "10.00"),
        )
        val html = HtmlRenderer().renderizar(
            java.io.File("$base.hbs").readText(), definicao,
            QueryResponse(emptyList(), dados, dados.size, false, 1), emptyMap(),
        ).replace('\u00a0', ' ')
        val texto = Regex("<[^>]+>").replace(html, " ").replace(Regex("\\s+"), " ")

        // Ana aparece nas duas filiais, com subtotal proprio em cada uma, na ordem do SQL.
        val esperado = listOf(
            "Filial: Matriz", "Vendedor: Ana", "Subtotal Ana 1 R$ 100,00",
            "Vendedor: Bia", "Subtotal Bia 2 R$ 50,25", "Total Matriz 3 R$ 150,25",
            "Filial: Filial AC", "Vendedor: Ana", "Subtotal Ana 1 R$ 10,00", "Total Filial AC 1 R$ 10,00",
            "Total geral 4 R$ 160,25",
            // Resumo: Ana somando as duas filiais.
            "Total por vendedor (todas as filiais)", "Ana 2 R$ 110,00", "Bia 2 R$ 50,25",
        )
        var desde = 0
        esperado.forEach { trecho ->
            val pos = texto.indexOf(trecho, desde)
            assertTrue(pos >= 0, "faltou '$trecho' depois da posicao $desde: $texto")
            desde = pos + trecho.length
        }
    }

    @Test
    fun `template recebe grupos com linhas formatadas e totais`() {
        val html = HtmlRenderer().renderizar(
            """{{#each grupos}}[{{titulo}}|{{valor}}/{{quantidade}}:{{#each linhas}}{{CLIENTE}},{{/each}}|{{totais.NOTAS}}]{{/each}}
               TOTAL|{{totais.NOTAS}} N|{{meta.quantidade}}""",
            def(), QueryResponse(emptyList(), linhas, linhas.size, false, 1), emptyMap(),
        )
        assertTrue(html.contains("[Vendedor|Ana/2:C1,C2,|3][Vendedor|Bia/1:C3,|3]"), html)
        assertTrue(html.contains("TOTAL|6 N|3"), html)
    }

    @Test
    fun `csv fecha cada grupo com subtotal e termina com total geral`() {
        val csv = CsvRenderer().renderizar(def(), QueryResponse(emptyList(), linhas, linhas.size, false, 1))
            .drop(3).toByteArray().decodeToString().replace(' ', ' ')
        assertEquals(
            listOf(
                "Vendedor;Cliente;Notas;Total",
                "Ana;C1;2;R$ 0,10",
                "Ana;C2;1;R$ 0,20",
                "Subtotal Vendedor: Ana;;3;R$ 0,30",
                "Bia;C3;3;R$ 1.000,50",
                "Subtotal Vendedor: Bia;;3;R$ 1.000,50",
                "Total geral;;6;R$ 1.000,80",
            ),
            csv.trimEnd().split("\r\n"),
        )
    }

    @Test
    fun `csv sem totais nao ganha linhas extras`() {
        val cols = colunas.map { it.copy(total = null) }
        val csv = CsvRenderer().renderizar(def(cols = cols), QueryResponse(emptyList(), linhas, linhas.size, false, 1))
            .drop(3).toByteArray().decodeToString()
        assertEquals(4, csv.trimEnd().split("\r\n").size)
    }

    // ---- resumos ------------------------------------------------------------

    private val vendas = listOf(
        mapOf("FILIAL" to "Matriz", "VENDEDOR" to "Bia", "NOTAS" to 2, "TOTAL" to "50.25"),
        mapOf("FILIAL" to "Matriz", "VENDEDOR" to "Ana", "NOTAS" to 1, "TOTAL" to "100.00"),
        mapOf("FILIAL" to "Filial AC", "VENDEDOR" to "Ana", "NOTAS" to 1, "TOTAL" to "10.00"),
    )
    private val colunasVendas = listOf(
        Coluna("FILIAL", "Filial"), Coluna("VENDEDOR", "Vendedor"),
        Coluna("NOTAS", "Notas", "numero", total = "sum"), Coluna("TOTAL", "Total", "moeda", total = "sum"),
    )
    private fun defVendas(resumos: List<br.andrew.sap_reports.definition.Resumo>) = ReportDefinition(
        nome = "Teste", papeis = listOf("admin"), consulta = Consulta("SELECT 1"),
        colunas = colunasVendas, agrupar = listOf("FILIAL", "VENDEDOR"), resumos = resumos,
    )

    @Test
    fun `resumo soma o vendedor atravessando as filiais e ordena por nome`() {
        val def = defVendas(listOf(br.andrew.sap_reports.definition.Resumo(por = listOf("VENDEDOR"))))
        val r = Agrupamento.resumo(def, vendas, listOf("VENDEDOR"))

        assertEquals(listOf("Ana", "Bia"), r.grupos.map { it.valorBruto })
        assertEquals("R$ 110,00", r.grupos[0].totais["TOTAL"]!!.replace('\u00a0', ' '))
        assertEquals("2", r.grupos[0].totais["NOTAS"])
        assertEquals("Resumo por Vendedor", Agrupamento.tituloResumo(def, def.resumos[0]))
    }

    @Test
    fun `resumo nao depende da ordem do SQL - grupos nao se partem`() {
        val embaralhado = listOf(vendas[1], vendas[0], vendas[2])
        val r = Agrupamento.resumo(defVendas(emptyList()), embaralhado, listOf("VENDEDOR"))
        assertEquals(listOf(listOf(0, 2), listOf(1)), r.grupos.map { it.indices })
    }

    @Test
    fun `template recebe resumos com titulo e grupos`() {
        val def = defVendas(listOf(br.andrew.sap_reports.definition.Resumo("Por vendedor", listOf("VENDEDOR"))))
        val html = HtmlRenderer().renderizar(
            "{{#each resumos}}<h2>{{titulo}}</h2>{{#each grupos}}[{{valor}}|{{totais.TOTAL}}]{{/each}}{{/each}}",
            def, QueryResponse(emptyList(), vendas, vendas.size, false, 1), emptyMap(),
        ).replace('\u00a0', ' ')
        assertTrue(html.contains("<h2>Por vendedor</h2>[Ana|R$ 110,00][Bia|R$ 50,25]"), html)
    }

    @Test
    fun `csv traz cada resumo como bloco depois do total geral`() {
        val def = defVendas(listOf(
            br.andrew.sap_reports.definition.Resumo("Por vendedor", listOf("VENDEDOR")),
            br.andrew.sap_reports.definition.Resumo(por = listOf("VENDEDOR", "FILIAL")),
        ))
        val csv = CsvRenderer().renderizar(def, QueryResponse(emptyList(), vendas, vendas.size, false, 1))
            .drop(3).toByteArray().decodeToString().replace('\u00a0', ' ')
        val linhas = csv.trimEnd().split("\r\n")
        val depoisDoTotal = linhas.drop(linhas.indexOf("Total geral;;4;R$ 160,25") + 1)
        assertEquals(
            listOf(
                "",
                "Por vendedor;;;",
                "Vendedor: Ana;;2;R$ 110,00",
                "Vendedor: Bia;;2;R$ 50,25",
                "",
                "Resumo por Vendedor / Filial;;;",
                "Vendedor: Ana;;2;R$ 110,00",
                "Vendedor: Ana / Filial: Filial AC;;1;R$ 10,00",
                "Vendedor: Ana / Filial: Matriz;;1;R$ 100,00",
                "Vendedor: Bia;;2;R$ 50,25",
                "Vendedor: Bia / Filial: Matriz;;2;R$ 50,25",
            ),
            depoisDoTotal,
        )
    }
}
