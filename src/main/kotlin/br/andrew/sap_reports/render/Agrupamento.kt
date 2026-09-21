package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.Resumo
import br.andrew.sap_reports.definition.TipoTotal
import br.andrew.sap_reports.odbc.paraDecimal
import java.math.BigDecimal
import java.math.MathContext
import java.text.Collator
import java.util.Locale

/**
 * Grupos e totais calculados no servidor, a partir dos valores BRUTOS da consulta.
 *
 * O template Handlebars nao faz conta (logic-less, de proposito). Somar o texto
 * ja formatado ("R$ 1.234,56") seria impossivel; somar em Double erraria centavos.
 * Aqui tudo e BigDecimal e so vira texto no fim, pelo mesmo [ValueFormatter] das linhas.
 *
 * As linhas sao reunidas POR VALOR, nao por blocos contiguos: sem ORDER BY o
 * grupo nao se parte, so muda a ordem em que os grupos aparecem.
 */
class Agrupamento private constructor(
    private val def: ReportDefinition,
    private val linhas: List<Map<String, Any?>>,
    private val niveis: List<String>,
    private val ordenarPorValor: Boolean,
) {
    /** Um grupo de um nivel. `indices` aponta para as linhas do resultado. */
    class Grupo(
        val coluna: Coluna,
        val valorBruto: Any?,
        val indices: List<Int>,
        val totais: Map<String, String>,
        val filhos: List<Grupo>,
    )

    private val colunasComTotal = def.colunas.filter { TipoTotal.de(it.total) != null }

    val totais: Map<String, String> = totalizar(linhas.indices.toList())
    val grupos: List<Grupo> = agrupar(linhas.indices.toList(), nivel = 0)

    private fun agrupar(indices: List<Int>, nivel: Int): List<Grupo> {
        val campo = niveis.getOrNull(nivel) ?: return emptyList()
        val coluna = def.colunas.first { it.campo == campo }
        // LinkedHashMap: sem ordenacao explicita, preserva a ordem do ORDER BY da consulta.
        val porChave = LinkedHashMap<String, MutableList<Int>>()
        indices.forEach { i -> porChave.getOrPut(chave(linhas[i][campo])) { mutableListOf() } += i }
        val grupos = porChave.values.map { membros ->
            Grupo(
                coluna = coluna,
                valorBruto = linhas[membros.first()][campo],
                indices = membros,
                totais = totalizar(membros),
                filhos = agrupar(membros, nivel + 1),
            )
        }
        return if (ordenarPorValor) grupos.sortedWith(POR_VALOR) else grupos
    }

    private fun totalizar(indices: List<Int>): Map<String, String> =
        colunasComTotal.associate { coluna ->
            val tipo = TipoTotal.de(coluna.total)!!
            val valores = indices.map { linhas[it][coluna.campo] }
            coluna.campo to formatar(tipo, coluna, valores)
        }

    private fun formatar(tipo: TipoTotal, coluna: Coluna, valores: List<Any?>): String {
        if (tipo == TipoTotal.COUNT) return ValueFormatter.numero(valores.count { it != null })
        val numeros = valores.mapNotNull { it.paraDecimal() }
        val resultado: BigDecimal = when (tipo) {
            TipoTotal.SUM -> numeros.fold(BigDecimal.ZERO, BigDecimal::add)
            TipoTotal.AVG -> if (numeros.isEmpty()) return "" else
                numeros.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(numeros.size), MathContext.DECIMAL64)
            TipoTotal.MIN -> numeros.minOrNull() ?: return ""
            TipoTotal.MAX -> numeros.maxOrNull() ?: return ""
            TipoTotal.COUNT -> error("tratado acima")
        }
        return ValueFormatter.porColuna(resultado, coluna)
    }

    private fun chave(valor: Any?): String = valor?.toString() ?: ""

    companion object {
        /** Agrupamento principal (`agrupar`), na ordem da consulta. */
        fun calcular(def: ReportDefinition, linhas: List<Map<String, Any?>>) =
            Agrupamento(def, linhas, def.agrupar, ordenarPorValor = false)

        /**
         * Resumo independente (`resumos[].por`). Ordenado pelo valor do grupo: as
         * linhas vem na ordem do agrupamento principal, que nao serve a este corte.
         */
        fun resumo(def: ReportDefinition, linhas: List<Map<String, Any?>>, por: List<String>) =
            Agrupamento(def, linhas, por, ordenarPorValor = true)

        /** Titulo declarado, ou "Resumo por Vendedor / Filial" a partir dos titulos das colunas. */
        fun tituloResumo(def: ReportDefinition, resumo: Resumo): String =
            resumo.titulo?.takeIf { it.isNotBlank() }
                ?: "Resumo por " + resumo.por.joinToString(" / ") { campo ->
                    def.colunas.firstOrNull { it.campo == campo }?.titulo ?: campo
                }

        private val texto = Collator.getInstance(Locale.forLanguageTag("pt-BR")).apply { strength = Collator.PRIMARY }

        /** Numeros em ordem numerica, texto em ordem alfabetica pt-BR; vazio por ultimo. */
        private val POR_VALOR = Comparator<Grupo> { a, b ->
            val (x, y) = a.valorBruto to b.valorBruto
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> {
                    val (nx, ny) = x.paraDecimal() to y.paraDecimal()
                    if (nx != null && ny != null) nx.compareTo(ny) else texto.compare(x.toString(), y.toString())
                }
            }
        }
    }
}
