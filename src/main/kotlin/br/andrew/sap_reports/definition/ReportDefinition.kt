package br.andrew.sap_reports.definition

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Definicao de um relatorio, desserializada do YAML enviado.
 *
 * Os defaults existem para que uma definicao minima seja valida; a exigencia dos
 * campos realmente obrigatorios fica no [ReportUploadValidator], que consegue
 * apontar o caminho exato do problema. Falhar na desserializacao daria uma
 * mensagem do Jackson, inutil para quem escreveu o arquivo.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class ReportDefinition(
    /** Legado: o ID agora e gerado pelo banco. Aceito e ignorado para ler versoes antigas. */
    val id: String? = null,
    val nome: String = "",
    val descricao: String? = null,
    val papeis: List<String> = emptyList(),
    val parametros: List<Parametro> = emptyList(),
    val consulta: Consulta = Consulta(),
    val colunas: List<Coluna> = emptyList(),
    val formatos: List<String> = listOf("pdf", "html", "csv"),
    /** Campos de `colunas` que quebram o resultado em grupos, do nivel mais externo ao mais interno. */
    val agrupar: List<String> = emptyList(),
    /** Cortes independentes do agrupamento principal (ex.: total por vendedor somando todas as filiais). */
    val resumos: List<Resumo> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class Resumo(
    val titulo: String? = null,
    /** Campos de `colunas`, do nivel externo ao interno. */
    val por: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class Consulta(
    val sql: String = "",
    val maxRows: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class Parametro(
    val nome: String = "",
    val tipo: String = "texto",
    val rotulo: String? = null,
    val obrigatorio: Boolean = true,
    val padrao: Any? = null,
    val opcoes: List<Opcao>? = null,
    val multiplo: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class Opcao(
    val valor: String = "",
    val rotulo: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = false)
data class Coluna(
    val campo: String = "",
    val titulo: String = "",
    val tipo: String = "texto",
    val alinhamento: String? = null,
    /** Agregacao calculada no servidor, por grupo e no total geral. */
    val total: String? = null,
)

/** Tipos aceitos em `parametros[].tipo`. */
enum class TipoParametro(val yaml: String) {
    TEXTO("texto"), NUMERO("numero"), DATE("date"),
    DATETIME("datetime"), BOOLEANO("booleano"), LISTA("lista"),
    FILIAL("filial"), VENDEDOR("vendedor"), PARCEIRO_NEGOCIO("parceiro_negocio"),
    ITEM("item"), LOCALIDADE("localidade");

    companion object {
        fun de(valor: String) = entries.firstOrNull { it.yaml == valor }
    }
}

/** Tipos aceitos em `colunas[].tipo`, usados na formatacao da saida. */
enum class TipoColuna(val yaml: String) {
    TEXTO("texto"), NUMERO("numero"), MOEDA("moeda"),
    DATA("data"), DATAHORA("datahora"), PERCENTUAL("percentual");

    companion object {
        fun de(valor: String) = entries.firstOrNull { it.yaml == valor }
    }
}

/** Agregacoes aceitas em `colunas[].total`. */
enum class TipoTotal(val yaml: String, val exigeNumero: Boolean) {
    SUM("sum", true), AVG("avg", true), MIN("min", true),
    MAX("max", true), COUNT("count", false);

    companion object {
        fun de(valor: String?) = entries.firstOrNull { it.yaml == valor }
    }
}

enum class Formato(val yaml: String) {
    PDF("pdf"), HTML("html"), CSV("csv");

    companion object {
        fun de(valor: String) = entries.firstOrNull { it.yaml == valor }
    }
}
