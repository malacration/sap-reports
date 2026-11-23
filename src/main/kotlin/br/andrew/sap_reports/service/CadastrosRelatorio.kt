package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.odbc.paraDecimal
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Busca dos filtros de parâmetro dos relatórios — uso exclusivo do sap-reports.
 * Sem o corte de carteira e sem o PAC% das buscas de venda, de propósito:
 * relatório olha histórico, não o que o vendedor pode vender.
 * Tela de venda/cadastro NÃO usa esta busca; use as do sap-service.
 *
 * O `tipo` só escolhe uma entrada do registro fechado abaixo e nunca entra no
 * SQL; o termo vai sempre como parâmetro nomeado. CPF/CNPJ serve para buscar,
 * nunca volta na resposta.
 */
@Component
class CadastrosRelatorio(
    private val odbc: OdbcClient,
    private val properties: ReportProperties,
) {
    val tipos: Set<String> = REGISTRO.keys

    /** `null` = tipo desconhecido, sem consulta. [pagina] tem que estar em [PAGINAS]. */
    fun buscar(tipo: String, q: String?, pagina: Int = 0): PaginaBusca? {
        val consulta = REGISTRO[tipo] ?: return null
        val params = mutableMapOf<String, Any?>("termo" to termo(q), "prefixo" to prefixo(q))
        val doc = documento(q).takeIf { consulta.documento.isNotEmpty() }
        if (doc != null) {
            params["doc"] = doc
            params["docMascara"] = mascara(doc, if (doc.length == 11) "###.###.###-##" else "##.###.###/####-##")
        }
        if (consulta.exato) params["exato"] = q?.trim().orEmpty()
        // O SQL pede LIMITE + 1; com maxRows = LIMITE o sap-odbc marca `truncated` quando há próxima página.
        val resposta = odbc.consultar(sql(consulta, pagina, doc != null), params, LIMITE)
        val itens = resposta.rows.mapNotNull { linha ->
            val codigo = if (consulta.numerico) linha["CODIGO"].paraDecimal()?.toInt() else linha["CODIGO"]?.toString()
            codigo?.let { ItemCadastro(it, linha["NOME"]?.toString()) }
        }
        return PaginaBusca(itens, resposta.truncated)
    }

    internal fun sql(tipo: String, pagina: Int = 0, porDocumento: Boolean = false): String? =
        REGISTRO[tipo]?.let { sql(it, pagina, porDocumento) }

    /** Única checagem de [pagina]: `buscar` monta o SQL antes de consultar, então página inválida nunca chega ao sap-odbc. */
    private fun sql(c: Consulta, pagina: Int, porDocumento: Boolean = false): String {
        require(pagina in PAGINAS) { "Pagina fora de $PAGINAS." }
        val p = properties.schemaCadastro?.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
        // ESCAPE '\' (valido no HANA): só `*` é coringa; `_` e `%` digitados são literais.
        // CPF/CNPJ só por igualdade (nunca LIKE), senão `1__.___` revela o documento dígito a dígito.
        val buscas = c.busca.map { "UPPER($it) LIKE :termo ESCAPE '\\'" } +
            if (porDocumento) c.documento.flatMap { listOf("$it = :doc", "$it = :docMascara") } else emptyList()
        val busca = buscas.joinToString(" OR ")
        val onde = when {
            c.exato -> "(${c.filtro} AND ($busca)) OR ${c.codigo} = :exato"
            c.filtro != null -> "${c.filtro} AND ($busca)"
            else -> busca
        }
        // Relevância: código começando pelo termo, depois nome começando, depois o resto.
        // Código numérico (condição de pagamento, grupo) não entra na busca, então não pesa na ordem.
        val porCodigo = if (c.numerico) "" else "WHEN UPPER(${c.codigo}) LIKE :prefixo ESCAPE '\\' THEN 0 "
        // DISTINCT: o LEFT JOIN do CRD7 pode repetir o parceiro. "ORDEM" fica no SELECT porque
        // o HANA só ordena DISTINCT por coluna da lista; o código ignora a coluna.
        // LIMIT/OFFSET literais: vêm do Int já validado em PAGINAS, nunca de texto do usuário.
        return """
            SELECT DISTINCT ${c.codigo} AS "CODIGO", ${c.nome} AS "NOME",
                CASE ${porCodigo}WHEN UPPER(${c.nome}) LIKE :prefixo ESCAPE '\' THEN 1 ELSE 2 END AS "ORDEM"
            FROM ${c.tabela(p)}
            WHERE $onde
            ORDER BY "ORDEM", "NOME", "CODIGO"
            LIMIT ${LIMITE + 1} OFFSET ${pagina * LIMITE}
        """.trimIndent()
    }

    internal fun termo(q: String?): String = preparar(q).let { if (it.isEmpty()) "%" else "%$it%" }

    /** `q` vazio vira "%". */
    internal fun prefixo(q: String?): String = "${preparar(q)}%"

    /** `*` é o único coringa: `\`, `%` e `_` digitados são escapados. */
    private fun preparar(q: String?): String =
        q?.trim().orEmpty().uppercase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace('*', '%')

    /** Só dígitos do termo (sem `.`, `-`, `/`, espaço) quando formam CPF (11) ou CNPJ (14); senão `null`. */
    private fun documento(q: String?): String? =
        q.orEmpty().trim().filterNot { it in ".-/ " }.takeIf { d -> d.all(Char::isDigit) && d.length in setOf(11, 14) }

    private fun mascara(digitos: String, molde: String): String {
        val resto = digitos.iterator()
        return molde.map { if (it == '#') resto.next() else it }.joinToString("")
    }

    private class Consulta(
        val tabela: (prefixo: String) -> String,
        val codigo: String,
        val nome: String,
        val busca: List<String>,
        val filtro: String? = null,
        val numerico: Boolean = false,
        val exato: Boolean = false,
        val documento: List<String> = emptyList(),
    )

    companion object {
        const val LIMITE = 50
        val PAGINAS = 0..200

        private fun parceiro(cardType: Char) = Consulta(
            tabela = { p -> """${p}OCRD T LEFT JOIN ${p}CRD7 D ON D."CardCode" = T."CardCode" AND D."Address" = ''""" },
            codigo = """T."CardCode"""",
            nome = """T."CardName"""",
            busca = listOf("""T."CardCode"""", """T."CardName""""),
            documento = listOf("""D."TaxId4"""", """D."TaxId0""""),
            filtro = """T."CardType" = '$cardType'""",
        )

        private val REGISTRO: Map<String, Consulta> = mapOf(
            "parceiro_negocio" to parceiro('C'),
            "fornecedor" to parceiro('S'),
            // Inativo no SAP B1 é frozenFor = 'Y' (validFor não serve); código exato traz até inativo.
            "item" to Consulta(
                tabela = { p -> "${p}OITM T" },
                codigo = """T."ItemCode"""",
                nome = """T."ItemName"""",
                busca = listOf("""T."ItemCode"""", """T."ItemName""""),
                filtro = """T."frozenFor" = 'N'""",
                exato = true,
            ),
            "conta_contabil" to Consulta(
                tabela = { p -> "${p}OACT T" },
                codigo = """T."AcctCode"""",
                nome = """T."AcctName"""",
                busca = listOf("""T."AcctCode"""", """T."FormatCode"""", """T."AcctName""""),
                filtro = """T."Postable" = 'Y'""",
            ),
            "centro_custo" to Consulta(
                tabela = { p -> "${p}OPRC T" },
                codigo = """T."PrcCode"""",
                nome = """T."PrcName"""",
                busca = listOf("""T."PrcCode"""", """T."PrcName""""),
            ),
            "condicao_pagamento" to Consulta(
                tabela = { p -> "${p}OCTG T" },
                codigo = """T."GroupNum"""",
                nome = """T."PymntGroup"""",
                busca = listOf("""T."PymntGroup""""),
                numerico = true,
            ),
            "grupo_item" to Consulta(
                tabela = { p -> "${p}OITB T" },
                codigo = """T."ItmsGrpCod"""",
                nome = """T."ItmsGrpNam"""",
                busca = listOf("""T."ItmsGrpNam""""),
                numerico = true,
            ),
            "deposito" to Consulta(
                tabela = { p -> "${p}OWHS T" },
                codigo = """T."WhsCode"""",
                nome = """T."WhsName"""",
                busca = listOf("""T."WhsCode"""", """T."WhsName""""),
            ),
        )
    }
}

/** [temProxima]: existe ao menos mais uma linha depois desta página. */
data class PaginaBusca(val itens: List<ItemCadastro>, val temProxima: Boolean)

/** `codigo` é Int em `condicao_pagamento` e `grupo_item`, String nos demais. */
data class ItemCadastro(val codigo: Any, val nome: String?)
