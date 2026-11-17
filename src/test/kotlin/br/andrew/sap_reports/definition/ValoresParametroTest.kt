package br.andrew.sap_reports.definition

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.service.CadastrosRelatorio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ValoresParametroTest {
    @Test
    fun `condicao_pagamento aceita -1`() {
        assertNotSame(ValoresParametro.INVALIDO, ValoresParametro.converter(Parametro(nome = "c", tipo = "condicao_pagamento"), -1))
    }

    @Test
    fun `tipos novos aceitam multiplo`() {
        assertEquals(listOf((-1).toBigDecimal(), 3.toBigDecimal()), ValoresParametro.converter(
            Parametro(nome = "c", tipo = "condicao_pagamento", multiplo = true), listOf(-1, 3)))
        assertEquals(listOf("1.1.01", "2.1.02"), ValoresParametro.converter(
            Parametro(nome = "c", tipo = "conta_contabil", multiplo = true), listOf("1.1.01", "2.1.02")))
    }

    @Test
    fun `todo tipo de cadastro tem busca`() {
        val semBusca = setOf("texto", "numero", "date", "datetime", "booleano", "lista", "filial", "vendedor", "localidade")
        val cadastros = TipoParametro.entries.map { it.yaml }.filter { it !in semBusca }
        assertEquals(cadastros.toSet(), CadastrosRelatorio(OdbcClient(OdbcProperties()), ReportProperties()).tipos)
    }
}
