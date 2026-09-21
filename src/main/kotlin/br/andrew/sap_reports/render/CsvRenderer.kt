package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.QueryResponse
import org.springframework.stereotype.Component

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

    private fun StringBuilder.linha(definicao: ReportDefinition, linha: Map<String, Any?>) {
        append(definicao.colunas.joinToString(";") { coluna -> escapar(formatar(linha[coluna.campo], coluna)) })
        append("\r\n")
    }

    /** O rotulo vai na primeira coluna sem total; os totais, nas proprias colunas. */
    private fun StringBuilder.totalizadora(definicao: ReportDefinition, rotulo: String, totais: Map<String, String>) {
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
