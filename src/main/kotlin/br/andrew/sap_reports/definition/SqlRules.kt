package br.andrew.sap_reports.definition

/**
 * Regras do SQL espelhadas do `ReadOnlySqlValidator` do sap-odbc.
 *
 * Duplicacao deliberada: o sap-odbc continua sendo a autoridade em runtime, mas
 * barrar aqui, no upload, transforma um 400 em tempo de execucao - que o usuario
 * final veria - em erro de validacao para quem escreveu o relatorio, com o
 * caminho exato no YAML.
 *
 * Se as regras do sap-odbc mudarem, este arquivo precisa acompanhar.
 */
object SqlRules {

    private val COMECA_COM_SELECT = Regex("^(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)

    /** `:nome`, ignorando o segundo `:` de um cast `a::int`. */
    private val PARAMETRO_NOMEADO = Regex("(?<!:):([A-Za-z][A-Za-z0-9_]*)")

    val NOME_PARAMETRO_VALIDO = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    /**
     * Nomes de parametro (`:nome`) do SQL, ignorando o conteudo de literais de
     * texto: `WHERE OBS = 'chave:valor'` nao declara um parametro `valor`.
     */
    fun parametros(sql: String): Set<String> =
        PARAMETRO_NOMEADO.findAll(mascararLiterais(sql)).map { it.groupValues[1] }.toSet()

    fun comecaComSelect(sql: String) = COMECA_COM_SELECT.containsMatchIn(sql.trim())

    /** Posicao do primeiro comentario fora de literal, ou -1. */
    fun posicaoComentario(sql: String): Int {
        var i = 0
        var aspas: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                aspas != null -> if (c == aspas) {
                    if (i + 1 < sql.length && sql[i + 1] == aspas) i++ else aspas = null
                }
                c == '\'' || c == '"' -> aspas = c
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> return i
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> return i
            }
            i++
        }
        return -1
    }

    /** true se houver conteudo depois do primeiro `;` fora de literal. */
    fun temMultiplasInstrucoes(sql: String): Boolean {
        var i = 0
        var aspas: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                aspas != null -> if (c == aspas) {
                    if (i + 1 < sql.length && sql[i + 1] == aspas) i++ else aspas = null
                }
                c == '\'' || c == '"' -> aspas = c
                c == ';' -> return sql.substring(i + 1).isNotBlank()
            }
            i++
        }
        return false
    }

    /** Substitui o conteudo de literais por espacos, preservando as posicoes. */
    private fun mascararLiterais(sql: String): String {
        val saida = StringBuilder(sql.length)
        var aspas: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                aspas != null -> if (c == aspas) {
                    if (i + 1 < sql.length && sql[i + 1] == aspas) { saida.append("  "); i += 2; continue }
                    aspas = null; saida.append(c)
                } else saida.append(' ')
                c == '\'' || c == '"' -> { aspas = c; saida.append(c) }
                else -> saida.append(c)
            }
            i++
        }
        return saida.toString()
    }
}
