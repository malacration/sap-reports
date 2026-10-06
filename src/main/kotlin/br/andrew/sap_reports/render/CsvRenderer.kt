package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.QueryResponse
import org.springframework.stereotype.Component
import java.io.OutputStream

/**
 * CSV com as linhas na ordem da consulta. Com `agrupar`, cada grupo termina numa
 * linha "Subtotal"; com `colunas[].total`, o arquivo termina em "Total geral".
 * Cada item de `resumos` vira um bloco depois do total geral, uma linha por grupo.
 * Os valores sao os mesmos calculados para o HTML/PDF ([Agrupamento]).
 */
@Component
class CsvRenderer {
    fun renderizar(definicao: ReportDefinition, resposta: QueryResponse): ByteArray {
        val agrupamento = Agrupamento.calcular(definicao, resposta.rows)
        val temTotal = definicao.colunas.any { it.total != null }
        val texto = buildString {
            append(definicao.colunas.joinToString(";") { escapar(it.titulo) })
            append("\r\n")
            if (agrupamento.grupos.isEmpty()) {
                resposta.rows.forEach { linha(definicao, it) }
            } else {
                agrupamento.grupos.forEach { grupo(definicao, resposta, it, temTotal) }
            }
            if (temTotal) totalizadora(definicao, "Total geral", agrupamento.totais)
            definicao.resumos.forEach { resumo ->
                // Linha em branco separa o bloco; o titulo vai na coluna de rotulo.
                append("\r\n")
                totalizadora(definicao, Agrupamento.tituloResumo(definicao, resumo), emptyMap())
                Agrupamento.resumo(definicao, resposta.rows, resumo.por).grupos
                    .forEach { linhaDeResumo(definicao, it, emptyList()) }
            }
        }
        return BOM + texto.toByteArray(Charsets.UTF_8)
    }

    /**
     * Mesmo CSV, escrito direto em [saida] a partir do resultado em disco: nada alem de
     * uma linha e da arvore de grupos fica em memoria.
     *
     * Exige grupos contiguos (ver [AgregadoEmFluxo]): o subtotal de um grupo e escrito
     * quando a linha seguinte ja e de outro grupo. Com essa condicao, a saida e identica
     * a de [renderizar] - os dois usam as mesmas funcoes de linha e o mesmo [Acumulador].
     */
    fun renderizarEmFluxo(definicao: ReportDefinition, disco: ResultadoEmDisco, agregado: AgregadoEmFluxo, saida: OutputStream) {
        val temTotal = definicao.colunas.any { it.total != null }
        saida.write(BOM)
        val escritor = saida.bufferedWriter(Charsets.UTF_8)
        escritor.append(definicao.colunas.joinToString(";") { escapar(it.titulo) }).append("\r\n")
        val abertos = ArrayList<AgregadoEmFluxo.No>()
        fun fecharAte(nivel: Int) {
            while (abertos.size > nivel) {
                val no = abertos.removeAt(abertos.size - 1)
                if (temTotal) escritor.subtotal(definicao, no)
            }
        }
        disco.ler { linhas ->
            linhas.forEach { linha ->
                val caminho = agregado.caminho(linha)
                val divergeEm = caminho.indices.firstOrNull { it >= abertos.size || abertos[it] !== caminho[it] }
                    ?: caminho.size
                fecharAte(divergeEm)
                for (i in abertos.size until caminho.size) abertos += caminho[i]
                escritor.linha(definicao, linha)
            }
        }
        fecharAte(0)
        if (temTotal) escritor.totalizadora(definicao, "Total geral", agregado.totais)
        definicao.resumos.forEachIndexed { i, resumo ->
            escritor.append("\r\n")
            escritor.totalizadora(definicao, Agrupamento.tituloResumo(definicao, resumo), emptyMap())
            agregado.gruposDoResumo(i).forEach { escritor.linhaDeResumo(definicao, agregado, it, emptyList()) }
        }
        escritor.flush()
    }

    private fun Appendable.subtotal(definicao: ReportDefinition, no: AgregadoEmFluxo.No) {
        val coluna = no.coluna!!
        totalizadora(definicao, "Subtotal ${coluna.titulo}: ${ValueFormatter.porColuna(no.valorBruto, coluna)}", no.totais)
    }

    private fun Appendable.linhaDeResumo(
        definicao: ReportDefinition,
        agregado: AgregadoEmFluxo,
        no: AgregadoEmFluxo.No,
        caminho: List<String>,
    ) {
        val atual = caminho + "${no.coluna!!.titulo}: ${ValueFormatter.porColuna(no.valorBruto, no.coluna)}"
        totalizadora(definicao, atual.joinToString(" / "), no.totais)
        agregado.filhosOrdenados(no).forEach { linhaDeResumo(definicao, agregado, it, atual) }
    }

    private fun StringBuilder.grupo(
        definicao: ReportDefinition,
        resposta: QueryResponse,
        grupo: Agrupamento.Grupo,
        temTotal: Boolean,
    ) {
        if (grupo.filhos.isEmpty()) {
            grupo.indices.forEach { linha(definicao, resposta.rows[it]) }
        } else {
            grupo.filhos.forEach { grupo(definicao, resposta, it, temTotal) }
        }
        if (temTotal) {
            val valor = ValueFormatter.porColuna(grupo.valorBruto, grupo.coluna)
            totalizadora(definicao, "Subtotal ${grupo.coluna.titulo}: $valor", grupo.totais)
        }
    }

    /** Cada grupo do resumo vira uma linha: "Filial X / Vendedor Y" e os totais. */
    private fun StringBuilder.linhaDeResumo(definicao: ReportDefinition, grupo: Agrupamento.Grupo, caminho: List<String>) {
        val atual = caminho + "${grupo.coluna.titulo}: ${ValueFormatter.porColuna(grupo.valorBruto, grupo.coluna)}"
        totalizadora(definicao, atual.joinToString(" / "), grupo.totais)
        grupo.filhos.forEach { linhaDeResumo(definicao, it, atual) }
    }

    private fun Appendable.linha(definicao: ReportDefinition, linha: Map<String, Any?>) {
        append(definicao.colunas.joinToString(";") { coluna -> escapar(formatar(linha[coluna.campo], coluna)) })
        append("\r\n")
    }

    /** O rotulo vai na primeira coluna sem total; os totais, nas proprias colunas. */
    private fun Appendable.totalizadora(definicao: ReportDefinition, rotulo: String, totais: Map<String, String>) {
        val colunaRotulo = definicao.colunas.firstOrNull { it.total == null }?.campo
        append(definicao.colunas.joinToString(";") { coluna ->
            escapar(totais[coluna.campo] ?: if (coluna.campo == colunaRotulo) rotulo else "")
        })
        append("\r\n")
    }

    private fun formatar(valor: Any?, coluna: Coluna): String = ValueFormatter.porColuna(valor, coluna)

    private fun escapar(valor: String): String =
        if (valor.any { it == '"' || it == ';' || it == '\r' || it == '\n' }) {
            "\"${valor.replace("\"", "\"\"")}\""
        } else valor

    companion object {
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
