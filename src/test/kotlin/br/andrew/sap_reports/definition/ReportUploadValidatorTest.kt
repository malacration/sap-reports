package br.andrew.sap_reports.definition

import br.andrew.sap_reports.config.ReportProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testes de ataque do validador de upload.
 *
 * Como os relatorios chegam sem revisao humana, esta suite e adversarial: cada
 * caso e uma tentativa concreta de passar algo perigoso. Se um deles comecar a
 * ser ACEITO, a unica barreira do servico foi furada.
 *
 * Os casos legitimos no fim existem para que endurecer a validacao nao se
 * transforme em recusar tudo - um validador que rejeita relatorio valido tambem
 * esta quebrado.
 */
class ReportUploadValidatorTest {

    private val validator = ReportUploadValidator(
        ReportProperties(allowedTables = listOf("SBOGRUPOROVEMA.OINV", "SBOGRUPOROVEMA.OCRD")),
    )

    private fun definicao(
        sql: String = "SELECT CardCode, DocTotal FROM SBOGRUPOROVEMA.OINV WHERE DocDate >= :dataInicio",
        parametros: List<Pair<String, String>> = listOf("dataInicio" to "date"),
        maxRows: Int = 5000,
    ): String = buildString {
        appendLine("id: vendas")
        appendLine("nome: Vendas")
        appendLine("papeis: [admin]")
        appendLine("parametros:")
        parametros.forEach { (nome, tipo) ->
            appendLine("  - nome: $nome")
            appendLine("    tipo: $tipo")
            appendLine("    obrigatorio: true")
        }
        appendLine("consulta:")
        // Escalar entre aspas simples: o SQL pode conter ':' e '#', que em YAML
        // solto mudariam o significado da linha.
        appendLine("  sql: '" + sql.replace("'", "''") + "'")
        appendLine("  maxRows: $maxRows")
        appendLine("colunas:")
        appendLine("  - campo: CardCode")
        appendLine("    titulo: Codigo")
        appendLine("  - campo: DocTotal")
        appendLine("    titulo: Total")
        appendLine("    tipo: moeda")
        appendLine("formatos: [pdf, html, csv]")
    }

    private val templateOk = "<h1>Vendas</h1>{{#each linhas}}<p>{{CardCode}}</p>{{/each}}"

    private fun regras(def: String, tpl: String = templateOk): Set<Regra> =
        validator.validar(def, tpl).problemas.map { it.regra }.toSet()

    private fun aceito(def: String, tpl: String = templateOk) {
        val r = validator.validar(def, tpl)
        assertTrue(r.valido, "deveria ser aceito, mas: " + r.problemas.joinToString { "${it.caminho}=${it.mensagem}" })
    }

    // ---------- Template: XSS ----------

    @Test
    fun `recusa interpolacao sem escape`() {
        assertTrue(Regra.ESCAPE_DESABILITADO in regras(definicao(), "<p>{{{conteudo}}}</p>"))
        assertTrue(Regra.ESCAPE_DESABILITADO in regras(definicao(), "<p>{{& conteudo}}</p>"))
        assertTrue(Regra.ESCAPE_DESABILITADO in regras(definicao(), "<p>{{&conteudo}}</p>"))
    }

    @Test
    fun `recusa tags e eventos perigosos`() {
        assertTrue(Regra.TAG_PROIBIDA in regras(definicao(), "<script>alert(1)</script>"))
        assertTrue(Regra.TAG_PROIBIDA in regras(definicao(), "<iframe src='data:text/html,x'></iframe>"))
        assertTrue(Regra.TAG_PROIBIDA in regras(definicao(), "<object data='data:x'></object>"))
        assertTrue(Regra.TAG_PROIBIDA in regras(definicao(), "<img onerror='alert(1)'>"))
        assertTrue(Regra.TAG_PROIBIDA in regras(definicao(), "<a href='javascript:alert(1)'>x</a>"))
    }

    // ---------- Template: SSRF e leitura de arquivo local ----------

    @Test
    fun `recusa leitura de arquivo local via recurso`() {
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in regras(definicao(), "<img src=\"file:///etc/passwd\">"))
    }

    @Test
    fun `recusa ssrf para host interno`() {
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in
            regras(definicao(), "<img src=\"http://169.254.169.254/latest/meta-data/\">"))
        assertTrue(Regra.RECURSO_EXTERNO_PROIBIDO in
            regras(definicao(), "<div style=\"background:url('http://interno/x.png')\"></div>"))
    }

    @Test
    fun `recusa partial que leria arquivo do servidor`() {
        assertTrue(Regra.TEMPLATE_NAO_COMPILA in regras(definicao(), "{{> /etc/passwd}}"))
    }

    @Test
    fun `aceita imagem embutida em data uri`() {
        aceito(definicao(), "<img src=\"data:image/png;base64,iVBORw0KGgo=\"><p>{{x}}</p>")
    }

    // ---------- SQL ----------

    @Test
    fun `recusa tabela fora da allowlist`() {
        val r = regras(definicao(sql = "SELECT * FROM SYS.USERS WHERE A = :dataInicio"))
        assertTrue(Regra.TABELA_NAO_PERMITIDA in r)
    }

    @Test
    fun `recusa tabela nao listada mesmo via union e subquery`() {
        assertTrue(Regra.TABELA_NAO_PERMITIDA in regras(definicao(
            sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE X = :dataInicio UNION ALL SELECT A FROM OUTRO.SEGREDO")))
        assertTrue(Regra.TABELA_NAO_PERMITIDA in regras(definicao(
            sql = "SELECT (SELECT COUNT(*) FROM OUTRO.SEGREDO) FROM SBOGRUPOROVEMA.OINV WHERE X = :dataInicio")))
    }

    @Test
    fun `recusa comentario e instrucao empilhada`() {
        assertTrue(Regra.SQL_COM_COMENTARIO in regras(definicao(
            sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE X = :dataInicio -- oculto")))
        assertTrue(Regra.SQL_MULTIPLAS_INSTRUCOES in regras(definicao(
            sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE X = :dataInicio; DROP TABLE X")))
    }

    @Test
    fun `recusa instrucao que nao e select`() {
        assertTrue(Regra.SQL_NAO_E_SELECT in regras(definicao(
            sql = "DELETE FROM SBOGRUPOROVEMA.OINV WHERE X = :dataInicio")))
    }

    // ---------- Parametros ----------

    @Test
    fun `cadastros existentes aceitam selecao unica e multipla`() {
        for (tipo in listOf("filial", "vendedor", "parceiro_negocio", "item", "localidade")) {
            val def = definicao(sql = "SELECT CardCode FROM SBOGRUPOROVEMA.OINV WHERE CardCode IN (:codigo)", parametros = listOf("codigo" to tipo))
            aceito(def)
            aceito(def.replace("obrigatorio: true", "obrigatorio: true\n    multiplo: true"))
        }
    }

    @Test
    fun `recusa padroes invalidos cardinalidade e configuracao de busca arbitraria`() {
        val def = definicao(parametros = listOf("dataInicio" to "filial"))
        for (padrao in listOf("[]", "[1, null]", "[1, abc]", "1", "[1.5]")) {
            assertTrue(Regra.SCHEMA_INVALIDO in regras(def.replace("obrigatorio: true", "multiplo: true\n    padrao: $padrao")))
        }
        aceito(def.replace("obrigatorio: true", "multiplo: true\n    padrao: [1, 2]"))
        assertTrue(Regra.SCHEMA_INVALIDO in regras(definicao().replace("obrigatorio: true", "multiplo: true")))
        assertTrue(Regra.SCHEMA_INVALIDO in regras(def.replace("obrigatorio: true", "busca: https://externo")))
    }

    @Test
    fun `recusa parametro do sql nao declarado`() {
        val r = regras(definicao(
            sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE D = :dataInicio AND F = :filial"))
        assertTrue(Regra.PARAMETRO_AUSENTE in r)
    }

    @Test
    fun `recusa parametro declarado e nao usado`() {
        val r = regras(definicao(
            parametros = listOf("dataInicio" to "date", "sobrando" to "texto")))
        assertTrue(Regra.PARAMETRO_NAO_USADO in r)
    }

    @Test
    fun `dois pontos em literal nao vira parametro`() {
        aceito(definicao(
            sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE OBS = 'chave:valor' AND D = :dataInicio"))
    }

    // ---------- Limites ----------

    @Test
    fun `identificador com dois pontos nao declara parametro`() {
        aceito(definicao(sql = "SELECT A AS \"saldo:filial\" FROM SBOGRUPOROVEMA.OINV WHERE D = :dataInicio"))
    }

    @Test
    fun `recusa maxRows acima do teto`() {
        assertTrue(Regra.LIMITE_EXCEDIDO in regras(definicao(maxRows = 999_999)))
    }

    @Test
    fun `recusa template gigante`() {
        val v = ReportUploadValidator(ReportProperties(maxTemplateLength = 100))
        val r = v.validar(definicao(), "x".repeat(200))
        assertTrue(r.problemas.any { it.regra == Regra.LIMITE_EXCEDIDO })
    }

    // ---------- Estrutura ----------

    @Test
    fun `recusa campo desconhecido no yaml`() {
        val comTypo = definicao().replace("formatos:", "formatoss:")
        assertTrue(Regra.SCHEMA_INVALIDO in regras(comTypo))
    }

    @Test
    fun `aceita definicao sem id - o banco gera o numero`() {
        assertTrue(regras(definicao().replace("id: vendas\n", "")).isEmpty())
    }

    @Test
    fun `id legado no yaml e ignorado, nao recusado`() {
        // Versoes gravadas antes da migracao trazem 'id:'; precisam continuar legiveis.
        assertTrue(regras(definicao().replace("id: vendas", "id: Qualquer Coisa")).isEmpty())
    }

    @Test
    fun `aceita os helpers de formatacao que o renderizador oferece`() {
        val tpl = "{{#each linhas}}{{moeda DocTotal}} {{numero DocTotal}} {{percentual DocTotal}} {{data params.dataInicio}}{{/each}}"
        assertTrue(regras(definicao(), tpl).isEmpty(), regras(definicao(), tpl).toString())
        assertTrue(Regra.TEMPLATE_NAO_COMPILA in regras(definicao(), "{{inventado x}}"))
    }

    @Test
    fun `aceita agrupamento e totais validos`() {
        val def = definicao().replace("    tipo: moeda\n", "    tipo: moeda\n    total: sum\n") + "agrupar: [CardCode]\n"
        assertTrue(regras(def).isEmpty(), regras(def).toString())
    }

    @Test
    fun `aceita resumo valido e recusa resumo mal formado`() {
        val comTotal = definicao().replace("    tipo: moeda\n", "    tipo: moeda\n    total: sum\n")
        val ok = comTotal + "resumos:\n  - titulo: Por codigo\n    por: [CardCode]\n"
        assertTrue(regras(ok).isEmpty(), regras(ok).toString())

        assertTrue(Regra.SCHEMA_INVALIDO in regras(comTotal + "resumos:\n  - por: [SlpName]\n"))
        assertTrue(Regra.SCHEMA_INVALIDO in regras(comTotal + "resumos:\n  - por: []\n"))
        assertTrue(Regra.SCHEMA_INVALIDO in regras(comTotal + "resumos:\n  - por: [CardCode]\n    ordem: x\n"))
        // Sem nenhuma coluna com total, o resumo nao teria o que mostrar.
        assertTrue(Regra.SCHEMA_INVALIDO in regras(definicao() + "resumos:\n  - por: [CardCode]\n"))
    }

    @Test
    fun `recusa agrupar por campo fora de colunas`() {
        assertTrue(Regra.SCHEMA_INVALIDO in regras(definicao() + "agrupar: [SlpName]\n"))
    }

    @Test
    fun `recusa soma em coluna de texto e total desconhecido`() {
        val soma = definicao().replace("    titulo: Codigo\n", "    titulo: Codigo\n    total: sum\n")
        assertTrue(Regra.SCHEMA_INVALIDO in regras(soma))
        val contagem = definicao().replace("    titulo: Codigo\n", "    titulo: Codigo\n    total: count\n")
        assertTrue(regras(contagem).isEmpty(), regras(contagem).toString())
        val invalido = definicao().replace("    tipo: moeda\n", "    tipo: moeda\n    total: soma\n")
        assertTrue(Regra.SCHEMA_INVALIDO in regras(invalido))
    }

    @Test
    fun `recusa relatorio sem papel`() {
        assertTrue(Regra.SCHEMA_INVALIDO in regras(definicao().replace("papeis: [admin]", "papeis: []")))
    }

    // ---------- Acumula todos os problemas ----------

    @Test
    fun `reporta todos os problemas de uma vez`() {
        val r = validator.validar(
            definicao(sql = "SELECT A FROM SYS.USERS WHERE D = :dataInicio -- x"),
            "<script>x</script><p>{{{y}}}</p>",
        )
        assertTrue(r.problemas.size >= 3, "esperava varios problemas, veio ${r.problemas.size}")
        assertTrue(r.problemas.all { it.caminho.isNotBlank() }, "todo problema precisa apontar um caminho")
    }

    // ---------- Legitimos: nao podem ser recusados ----------

    @Test
    fun `aceita relatorio valido`() {
        aceito(definicao())
    }

    @Test
    fun `aceita join agrupamento e cte entre tabelas permitidas`() {
        aceito(definicao(sql = "SELECT i.CardCode, SUM(i.DocTotal) AS T FROM SBOGRUPOROVEMA.OINV i " +
            "JOIN SBOGRUPOROVEMA.OCRD c ON c.CardCode = i.CardCode WHERE i.DocDate >= :dataInicio " +
            "GROUP BY i.CardCode HAVING SUM(i.DocTotal) > 0"))
    }

    @Test
    fun `aceita limit e offset usados na paginacao do csv`() {
        aceito(definicao(sql = "SELECT A FROM SBOGRUPOROVEMA.OINV WHERE D = :dataInicio ORDER BY A LIMIT 100"))
    }

    @Test
    fun `sem allowlist configurada nao restringe tabela`() {
        val v = ReportUploadValidator(ReportProperties(allowedTables = emptyList()))
        val r = v.validar(definicao(sql = "SELECT A FROM QUALQUER.TABELA WHERE D = :dataInicio"), templateOk)
        assertEquals(emptyList(), r.problemas.filter { it.regra == Regra.TABELA_NAO_PERMITIDA })
    }
}
