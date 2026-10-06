package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.TipoTotal
import br.andrew.sap_reports.odbc.paraDecimal
import java.math.BigDecimal
import java.math.MathContext

/**
 * Total de UMA coluna, alimentado valor a valor.
 *
 * Existe para o [Agrupamento] (tudo em memoria) e o [AgregadoEmFluxo] (arquivo grande,
 * linha a linha) fazerem exatamente a mesma conta: se cada um somasse do seu jeito, o
 * mesmo relatorio sairia com centavos diferentes no PDF pequeno e no grande. Tudo em
 * BigDecimal, que soma exato em qualquer ordem - por isso acumular linha a linha da o
 * mesmo resultado que somar a lista inteira.
 */
class Acumulador(private val tipo: TipoTotal, private val coluna: Coluna) {
    private var naoNulos = 0L
    private var numeros = 0L
    private var soma = BigDecimal.ZERO
    private var minimo: BigDecimal? = null
    private var maximo: BigDecimal? = null

    fun somar(valor: Any?) {
        if (valor != null) naoNulos++
        val n = valor.paraDecimal() ?: return
        numeros++
        soma = soma.add(n)
        if (minimo == null || n < minimo) minimo = n
        if (maximo == null || n > maximo) maximo = n
    }

    /** Texto ja formatado como a coluna; vazio quando nao ha numero para AVG/MIN/MAX. */
    fun resultado(): String {
        val valor: BigDecimal = when (tipo) {
            TipoTotal.COUNT -> return ValueFormatter.numero(naoNulos)
            TipoTotal.SUM -> soma
            TipoTotal.AVG -> if (numeros == 0L) return "" else soma.divide(BigDecimal(numeros), MathContext.DECIMAL64)
            TipoTotal.MIN -> minimo ?: return ""
            TipoTotal.MAX -> maximo ?: return ""
        }
        return ValueFormatter.porColuna(valor, coluna)
    }

    companion object {
        /** Um acumulador por coluna com `total`, na ordem das colunas. */
        fun paraColunas(def: ReportDefinition): Map<String, Acumulador> =
            def.colunas.mapNotNull { coluna ->
                TipoTotal.de(coluna.total)?.let { coluna.campo to Acumulador(it, coluna) }
            }.toMap(LinkedHashMap())

        fun resultados(acumuladores: Map<String, Acumulador>): Map<String, String> =
            acumuladores.mapValuesTo(LinkedHashMap()) { it.value.resultado() }
    }
}
