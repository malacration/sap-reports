package br.andrew.sap_reports.definition

import br.andrew.sap_reports.config.ReportProperties
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.io.TemplateLoader
import com.github.jknack.handlebars.io.TemplateSource
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.util.TablesNamesFinder
import org.springframework.stereotype.Component

/**
 * Valida um relatorio enviado por formulario.
 *
 * Este e o ponto central de seguranca do servico. Como os relatorios chegam em
 * runtime, sem passar por revisao no Git, esta classe e a UNICA barreira entre um
 * arquivo enviado e a execucao no servidor. Toda checagem aqui fecha um vetor
 * concreto; nenhuma existe por preciosismo.
 *
 * Acumula TODOS os problemas em vez de parar no primeiro: quem escreveu o arquivo
 * corrige tudo numa passada.
 */
@Component
class ReportUploadValidator(private val props: ReportProperties) {

    // Boot 4 usa Jackson 3 (tools.jackson); o modulo Kotlin so existe nessa versao.
    //
    // FAIL_ON_UNKNOWN_PROPERTIES precisa ser habilitado explicitamente: ao contrario
    // do Jackson 2, o 3 vem com ele DESLIGADO. Sem isso, um erro de digitacao como
    // `formatoss:` seria ignorado em silencio e o relatorio sairia diferente do que
    // o autor escreveu - justamente o tipo de falha que a revisao no Git pegaria.
    private val yaml = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Partials/includes desabilitados: senao o template le arquivo do disco. */
    private val handlebars = Handlebars(SemPartials()).apply {
        setPrettyPrint(false)
        setInfiniteLoops(false)
        // Os mesmos helpers do HtmlRenderer; so o nome importa para compilar. Sem
        // isto, `{{moeda x}}`/`{{data x}}` eram recusados aqui mas aceitos no render.
        HELPERS_FORMATACAO.forEach { nome -> registerHelper(nome, Helper<Any?> { valor, _ -> valor }) }
    }


    fun validar(definicaoYaml: String, template: String): ResultadoValidacao {
        val problemas = mutableListOf<Problema>()

        // 1. Limites de tamanho, antes de qualquer parse.
        if (definicaoYaml.length > props.maxDefinitionLength) {
            problemas += Problema("definicao", Regra.LIMITE_EXCEDIDO,
                "Definicao com ${definicaoYaml.length} caracteres excede o limite de ${props.maxDefinitionLength}.")
        }
        if (template.length > props.maxTemplateLength) {
            problemas += Problema("template", Regra.LIMITE_EXCEDIDO,
                "Template com ${template.length} caracteres excede o limite de ${props.maxTemplateLength}.")
        }
        if (problemas.isNotEmpty()) return ResultadoValidacao(problemas)

        // 2. Estrutura do YAML. Campo desconhecido tambem falha: erro de digitacao
        //    passaria despercebido e o relatorio sairia diferente do esperado.
        val def = try {
            yaml.readValue(definicaoYaml, ReportDefinition::class.java)
        } catch (ex: Exception) {
            return ResultadoValidacao(listOf(Problema("definicao", Regra.SCHEMA_INVALIDO,
                "YAML invalido: ${ex.message?.lineSequence()?.firstOrNull()?.take(200)}")))
        }

        problemas += validarCampos(def)
        problemas += TemplateRules.validar(template)
        problemas += compilaTemplate(template)

        val (probsSql, tabelas) = validarSql(def)
        problemas += probsSql
        problemas += validarParametros(def)

        return ResultadoValidacao(
            problemas = problemas,
            tabelas = tabelas,
            parametros = def.parametros.map { it.nome },
        )
    }

    fun validarOuFalhar(definicaoYaml: String, template: String): ReportDefinition {
        val resultado = validar(definicaoYaml, template)
        if (!resultado.valido) throw ValidacaoException(resultado)
        return yaml.readValue(definicaoYaml, ReportDefinition::class.java)
    }

    // ------------------------------------------------------------------

    private fun validarCampos(def: ReportDefinition): List<Problema> {
        val p = mutableListOf<Problema>()

        if (def.nome.isBlank()) {
            p += Problema("nome", Regra.SCHEMA_INVALIDO, "O campo 'nome' e obrigatorio.")
        }
        if (def.papeis.isEmpty()) {
            p += Problema("papeis", Regra.SCHEMA_INVALIDO,
                "Informe ao menos um papel - sem isso ninguem enxerga o relatorio.")
        }
        if (def.colunas.isEmpty()) {
            p += Problema("colunas", Regra.SCHEMA_INVALIDO, "Informe ao menos uma coluna.")
        }

        def.formatos.forEachIndexed { i, f ->
            if (Formato.de(f) == null) {
                p += Problema("formatos[$i]", Regra.SCHEMA_INVALIDO,
                    "Formato '$f' invalido. Use: ${Formato.entries.joinToString(", ") { it.yaml }}.")
            }
        }
        if (def.formatos.isEmpty()) {
            p += Problema("formatos", Regra.SCHEMA_INVALIDO, "Informe ao menos um formato de saida.")
        }

        def.colunas.forEachIndexed { i, c ->
            if (c.campo.isBlank()) {
                p += Problema("colunas[$i].campo", Regra.SCHEMA_INVALIDO, "O campo 'campo' e obrigatorio.")
            }
            if (c.titulo.isBlank()) {
                p += Problema("colunas[$i].titulo", Regra.SCHEMA_INVALIDO, "O campo 'titulo' e obrigatorio.")
            }
            if (TipoColuna.de(c.tipo) == null) {
                p += Problema("colunas[$i].tipo", Regra.SCHEMA_INVALIDO,
                    "Tipo '${c.tipo}' invalido. Use: ${TipoColuna.entries.joinToString(", ") { it.yaml }}.")
            }
            if (c.total != null) {
                val total = TipoTotal.de(c.total)
                if (total == null) {
                    p += Problema("colunas[$i].total", Regra.SCHEMA_INVALIDO,
                        "Total '${c.total}' invalido. Use: ${TipoTotal.entries.joinToString(", ") { it.yaml }}.")
                } else if (total.exigeNumero && c.tipo !in TIPOS_NUMERICOS) {
                    p += Problema("colunas[$i].total", Regra.SCHEMA_INVALIDO,
                        "'${total.yaml}' so vale para colunas ${TIPOS_NUMERICOS.joinToString("/")}; " +
                            "'${c.campo}' e '${c.tipo}'. Para contar linhas use 'count'.")
                }
            }
        }

        val campos = def.colunas.map { it.campo }.toSet()
        def.agrupar.forEachIndexed { i, campo ->
            if (campo !in campos) {
                p += Problema("agrupar[$i]", Regra.SCHEMA_INVALIDO,
                    "Campo '$campo' nao esta em 'colunas'. Declare-o la (ele da o titulo e o formato do grupo).")
            }
        }
        if (def.agrupar.size != def.agrupar.toSet().size) {
            p += Problema("agrupar", Regra.SCHEMA_INVALIDO, "Campo repetido em 'agrupar'.")
        }
        if (def.agrupar.size > MAX_NIVEIS_GRUPO) {
            p += Problema("agrupar", Regra.LIMITE_EXCEDIDO,
                "No maximo $MAX_NIVEIS_GRUPO niveis de agrupamento; recebido ${def.agrupar.size}.")
        }

        if (def.resumos.size > MAX_RESUMOS) {
            p += Problema("resumos", Regra.LIMITE_EXCEDIDO,
                "No maximo $MAX_RESUMOS resumos; recebido ${def.resumos.size}.")
        }
        def.resumos.forEachIndexed { r, resumo ->
            if (resumo.por.isEmpty()) {
                p += Problema("resumos[$r].por", Regra.SCHEMA_INVALIDO, "Informe ao menos um campo em 'por'.")
            }
            if (resumo.por.size > MAX_NIVEIS_GRUPO) {
                p += Problema("resumos[$r].por", Regra.LIMITE_EXCEDIDO,
                    "No maximo $MAX_NIVEIS_GRUPO niveis; recebido ${resumo.por.size}.")
            }
            if (resumo.por.size != resumo.por.toSet().size) {
                p += Problema("resumos[$r].por", Regra.SCHEMA_INVALIDO, "Campo repetido em 'por'.")
            }
            resumo.por.forEachIndexed { i, campo ->
                if (campo !in campos) {
                    p += Problema("resumos[$r].por[$i]", Regra.SCHEMA_INVALIDO,
                        "Campo '$campo' nao esta em 'colunas'. Declare-o la (ele da o titulo e o formato do grupo).")
                }
            }
            if ((resumo.titulo?.length ?: 0) > 200) {
                p += Problema("resumos[$r].titulo", Regra.LIMITE_EXCEDIDO, "Titulo com mais de 200 caracteres.")
            }
        }
        if (def.resumos.isNotEmpty() && def.colunas.none { it.total != null }) {
            p += Problema("resumos", Regra.SCHEMA_INVALIDO,
                "Resumo sem nenhuma coluna com 'total' nao tem o que mostrar. Declare 'total: sum' (ou outra operacao) nas colunas.")
        }

        val maxRows = def.consulta.maxRows
        if (maxRows != null && maxRows > props.maxRows) {
            p += Problema("consulta.maxRows", Regra.LIMITE_EXCEDIDO,
                "maxRows $maxRows excede o teto de ${props.maxRows}.")
        }
        if (maxRows != null && maxRows <= 0) {
            p += Problema("consulta.maxRows", Regra.SCHEMA_INVALIDO, "maxRows deve ser maior que zero.")
        }
        return p
    }

    private fun validarParametros(def: ReportDefinition): List<Problema> {
        val p = mutableListOf<Problema>()

        def.parametros.forEachIndexed { i, param ->
            if (param.multiplo && param.tipo !in ValoresParametro.tiposMultiplos) {
                p += Problema("parametros[$i].multiplo", Regra.SCHEMA_INVALIDO,
                    "Selecao multipla disponivel apenas para lista e cadastros existentes.")
            }
            if (param.padrao != null && ValoresParametro.converter(param, param.padrao) === ValoresParametro.INVALIDO) {
                p += Problema("parametros[$i].padrao", Regra.SCHEMA_INVALIDO,
                    "Valor padrao incompativel com o tipo e a cardinalidade do parametro.")
            }
            if (def.parametros.count { it.nome == param.nome } > 1) {
                p += Problema("parametros[$i].nome", Regra.SCHEMA_INVALIDO, "Nome de parametro duplicado.")
            }
            if (!SqlRules.NOME_PARAMETRO_VALIDO.matches(param.nome)) {
                p += Problema("parametros[$i].nome", Regra.NOME_PARAMETRO_INVALIDO,
                    "Nome '${param.nome}' invalido. Comece com letra e use apenas letras, digitos e _.")
            }
            if (TipoParametro.de(param.tipo) == null) {
                p += Problema("parametros[$i].tipo", Regra.SCHEMA_INVALIDO,
                    "Tipo '${param.tipo}' invalido. Use: ${TipoParametro.entries.joinToString(", ") { it.yaml }}.")
            }
            if (param.tipo == TipoParametro.LISTA.yaml && param.opcoes.isNullOrEmpty()) {
                p += Problema("parametros[$i].opcoes", Regra.SCHEMA_INVALIDO,
                    "Parametro do tipo 'lista' exige 'opcoes'.")
            }
        }

        // O sap-odbc recusa parametro faltando E sobrando. Barrar aqui evita que o
        // erro apareca so na execucao, para o usuario final.
        val noSql = SqlRules.parametros(def.consulta.sql)
        val declarados = def.parametros.map { it.nome }.toSet()

        (noSql - declarados).sorted().forEach { nome ->
            p += Problema("parametros", Regra.PARAMETRO_AUSENTE,
                "O SQL usa ':$nome', mas ele nao esta declarado em 'parametros'.")
        }
        (declarados - noSql).sorted().forEach { nome ->
            val i = def.parametros.indexOfFirst { it.nome == nome }
            p += Problema("parametros[$i].nome", Regra.PARAMETRO_NAO_USADO,
                "O parametro '$nome' esta declarado mas nao aparece no SQL. " +
                    "O sap-odbc recusa parametro sobrando.")
        }
        return p
    }

    private fun validarSql(def: ReportDefinition): Pair<List<Problema>, List<String>> {
        val p = mutableListOf<Problema>()
        val sql = def.consulta.sql.trim()

        if (sql.isBlank()) {
            return listOf(Problema("consulta.sql", Regra.SCHEMA_INVALIDO, "O campo 'consulta.sql' e obrigatorio.")) to emptyList()
        }

        val comentario = SqlRules.posicaoComentario(sql)
        if (comentario >= 0) {
            p += Problema("consulta.sql", Regra.SQL_COM_COMENTARIO,
                "Comentario na posicao $comentario. O sap-odbc recusa comentarios - eles escondem trecho da consulta.")
        }
        if (SqlRules.temMultiplasInstrucoes(sql)) {
            p += Problema("consulta.sql", Regra.SQL_MULTIPLAS_INSTRUCOES,
                "Mais de uma instrucao. Apenas uma consulta por relatorio.")
        }
        if (!SqlRules.comecaComSelect(sql)) {
            p += Problema("consulta.sql", Regra.SQL_NAO_E_SELECT,
                "A consulta deve comecar com SELECT ou WITH.")
        }
        if (p.isNotEmpty()) return p to emptyList()

        val semPontoEVirgula = sql.trimEnd().removeSuffix(";")
        val tabelas = try {
            TablesNamesFinder<Void>().getTables(CCJSqlParserUtil.parse(semPontoEVirgula)).toList()
        } catch (ex: Exception) {
            p += Problema("consulta.sql", Regra.SQL_NAO_E_SELECT,
                "Nao foi possivel interpretar o SQL: ${ex.message?.lineSequence()?.firstOrNull()?.take(200)}")
            return p to emptyList()
        }

        // Allowlist: o sap-odbc impede escrita, mas nao impede LER tudo. Sem esta
        // checagem, quem envia relatorio consegue despejar qualquer tabela liberada.
        if (props.allowedTables.isNotEmpty()) {
            tabelas.forEach { tabela ->
                if (!permitida(tabela)) {
                    p += Problema("consulta.sql", Regra.TABELA_NAO_PERMITIDA,
                        "A tabela '$tabela' nao esta na lista permitida.")
                }
            }
        }
        return p to tabelas
    }

    private fun permitida(tabela: String): Boolean {
        val alvo = tabela.replace("\"", "").uppercase()
        return props.allowedTables.any { padrao ->
            val p = padrao.replace("\"", "").uppercase()
            if (p.endsWith("*")) alvo.startsWith(p.dropLast(1)) else alvo == p
        }
    }

    private fun compilaTemplate(template: String): List<Problema> = try {
        handlebars.compileInline(template)
        emptyList()
    } catch (ex: Exception) {
        listOf(Problema("template", Regra.TEMPLATE_NAO_COMPILA,
            "O template nao compila: ${ex.message?.lineSequence()?.firstOrNull()?.take(200)}"))
    }

    /**
     * Recusa qualquer `{{> partial}}`. Um partial resolveria caminho no disco do
     * servidor, dando leitura de arquivo a quem envia o template.
     */
    private class SemPartials : TemplateLoader {
        override fun sourceAt(location: String): TemplateSource =
            throw IllegalArgumentException("Partials nao sao permitidos (tentou carregar '$location').")
        override fun resolve(location: String) = location
        override fun getPrefix() = ""
        override fun getSuffix() = ""
        override fun setPrefix(prefix: String) {}
        override fun setSuffix(suffix: String) {}
        override fun setCharset(charset: java.nio.charset.Charset) {}
        override fun getCharset(): java.nio.charset.Charset = Charsets.UTF_8
    }

    private companion object {
        val TIPOS_NUMERICOS = setOf("numero", "moeda", "percentual")
        const val MAX_NIVEIS_GRUPO = 3
        const val MAX_RESUMOS = 5
        val HELPERS_FORMATACAO = listOf("moeda", "data", "numero", "percentual")
    }
}
