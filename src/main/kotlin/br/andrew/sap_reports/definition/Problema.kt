package br.andrew.sap_reports.definition

/**
 * Um problema encontrado na validacao de um relatorio enviado.
 *
 * [caminho] aponta o local exato no YAML (`consulta.sql`, `parametros[1].nome`)
 * ou a string `template`. [regra] e um identificador estavel que o front usa para
 * destacar o campo correspondente - por isso nao muda junto com o texto.
 */
data class Problema(
    val caminho: String,
    val regra: Regra,
    val mensagem: String,
    val linha: Int? = null,
)

/** Identificadores estaveis, espelhados no enum `Problema.regra` do openapi.yaml. */
enum class Regra(val codigo: String) {
    SCHEMA_INVALIDO("schema_invalido"),
    PARAMETRO_AUSENTE("parametro_ausente"),
    PARAMETRO_NAO_USADO("parametro_nao_usado"),
    NOME_PARAMETRO_INVALIDO("nome_parametro_invalido"),
    SQL_COM_COMENTARIO("sql_com_comentario"),
    SQL_MULTIPLAS_INSTRUCOES("sql_multiplas_instrucoes"),
    SQL_NAO_E_SELECT("sql_nao_e_select"),
    TABELA_NAO_PERMITIDA("tabela_nao_permitida"),
    TEMPLATE_NAO_COMPILA("template_nao_compila"),
    ESCAPE_DESABILITADO("escape_desabilitado"),
    TAG_PROIBIDA("tag_proibida"),
    RECURSO_EXTERNO_PROIBIDO("recurso_externo_proibido"),
    LIMITE_EXCEDIDO("limite_excedido"),
}

/**
 * Resultado da validacao.
 *
 * Carrega TODOS os problemas, nao apenas o primeiro: quem escreveu o arquivo
 * (pessoa ou IA) corrige tudo numa passada em vez de descobrir um erro por envio.
 */
data class ResultadoValidacao(
    val problemas: List<Problema> = emptyList(),
    val tabelas: List<String> = emptyList(),
    val parametros: List<String> = emptyList(),
) {
    val valido: Boolean get() = problemas.isEmpty()
}

/** Lancada quando a definicao e recusada; vira 422 com a lista de problemas. */
class ValidacaoException(val resultado: ResultadoValidacao) :
    RuntimeException("Definicao de relatorio recusada: ${resultado.problemas.size} problema(s).")
