package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.ReportDefinition

/**
 * Totais, grupos e resumos calculados LINHA A LINHA, para relatorio grande demais para
 * o [Agrupamento] (que precisa de todas as linhas em memoria).
 *
 * Guarda um no por GRUPO, nunca as linhas: a memoria cresce com a quantidade de grupos,
 * nao com o tamanho do resultado. Cada no sabe onde comeca e termina ([No.primeira] e
 * [No.ultima], indices da linha no resultado) - e isso que deixa o PDF em blocos por o
 * subtotal so no bloco onde o grupo termina.
 *
 * Diferenca de contrato para o [Agrupamento]: aqui o grupo precisa ser CONTIGUO (a
 * consulta ordenada pelas colunas de `agrupar`). O [Agrupamento] reune por valor em
 * qualquer ordem porque tem todas as linhas; linha a linha, um grupo que reaparece
 * depois de fechado ja teve o subtotal escrito. [desordenado] aponta a primeira quebra.
 * Resumos nao tem essa exigencia: sao so totais, impressos no fim.
 */
class AgregadoEmFluxo(private val def: ReportDefinition) {

    class No(
        val coluna: Coluna?,
        val valorBruto: Any?,
        val primeira: Int,
        acumuladores: Map<String, Acumulador>,
    ) {
        private val acumuladores = acumuladores
        val filhos = LinkedHashMap<String, No>()
        var quantidade = 0
            private set
        var ultima = primeira
            private set
        private var ultimoFilho: No? = null

        internal fun somar(indice: Int, linha: Map<String, Any?>) {
            quantidade++
            ultima = indice
            acumuladores.forEach { (campo, acc) -> acc.somar(linha[campo]) }
        }

        internal fun filho(chave: String, criar: () -> No): Pair<No, Boolean> {
            val existente = filhos[chave]
            if (existente != null) return existente to (existente !== ultimoFilho)
            return criar().also { filhos[chave] = it; ultimoFilho = it } to false
        }

        val totais: Map<String, String> get() = Acumulador.resultados(acumuladores)
    }

    private val raiz = No(null, null, 0, Acumulador.paraColunas(def))
    private val resumos = def.resumos.map { No(null, null, 0, emptyMap()) }
    private var linhas = 0

    /** Campo de `agrupar` cujo grupo reapareceu depois de fechado, ou null se tudo contiguo. */
    var desordenado: String? = null
        private set

    val quantidade: Int get() = linhas
    val totais: Map<String, String> get() = raiz.totais

    fun somar(linha: Map<String, Any?>) {
        val indice = linhas++
        raiz.somar(indice, linha)
        var atual = raiz
        def.agrupar.forEach { campo ->
            val (no, reaberto) = atual.filho(Agrupamento.chaveDoGrupo(linha[campo])) {
                No(coluna(campo), linha[campo], indice, Acumulador.paraColunas(def))
            }
            if (reaberto && desordenado == null) desordenado = campo
            no.somar(indice, linha)
            atual = no
        }
        def.resumos.forEachIndexed { i, resumo ->
            var no = resumos[i]
            resumo.por.forEach { campo ->
                no = no.filho(Agrupamento.chaveDoGrupo(linha[campo])) {
                    No(coluna(campo), linha[campo], indice, Acumulador.paraColunas(def))
                }.first
                no.somar(indice, linha)
            }
        }
    }

    /** Nos de `agrupar` do nivel externo ao interno para esta linha. */
    fun caminho(linha: Map<String, Any?>): List<No> {
        val nos = ArrayList<No>(def.agrupar.size)
        var atual = raiz
        def.agrupar.forEach { campo ->
            atual = atual.filhos.getValue(Agrupamento.chaveDoGrupo(linha[campo]))
            nos += atual
        }
        return nos
    }

    /** Grupos de cada resumo, ordenados por valor em todos os niveis, como [Agrupamento.resumo]. */
    fun gruposDoResumo(indice: Int): List<No> = ordenados(resumos[indice])

    private fun ordenados(no: No): List<No> =
        no.filhos.values.sortedWith { a, b -> Agrupamento.compararValores(a.valorBruto, b.valorBruto) }

    fun filhosOrdenados(no: No): List<No> = ordenados(no)

    private fun coluna(campo: String): Coluna = def.colunas.first { it.campo == campo }
}
