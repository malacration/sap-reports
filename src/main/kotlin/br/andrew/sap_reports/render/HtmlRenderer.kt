package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.QueryResponse
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.io.TemplateLoader
import com.github.jknack.handlebars.io.TemplateSource
import org.springframework.stereotype.Component
import org.owasp.html.HtmlPolicyBuilder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class HtmlRenderer {

    // Sanitiza depois da interpolacao: regex no template nao cobre atributos
    // sem aspas, entidades HTML nem URLs vindas dos dados.
    private val policy = HtmlPolicyBuilder()
        .allowElements("h1", "h2", "h3", "h4", "h5", "h6",
            "p", "div", "span", "br", "hr", "strong", "em", "b", "i", "u", "small", "sup", "sub",
            "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col",
            "ul", "ol", "li", "pre", "code", "blockquote", "img", "a")
        .allowAttributes("class", "id").globally()
        .allowAttributes("colspan", "rowspan", "align", "width", "height").onElements("td", "th", "table", "col", "img")
        .allowAttributes("alt").onElements("img")
        .allowUrlProtocols("data", "http", "https")
        .allowAttributes("src").matching { _, _, value ->
            value.takeIf { it.startsWith("data:image/", ignoreCase = true) }
        }.onElements("img")
        .allowAttributes("href").matching { _, _, value ->
            value.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) || it.startsWith("#") }
        }.onElements("a")
        .allowStyling()
        .toFactory()

    private val handlebars = Handlebars(SemPartials()).apply {
        setPrettyPrint(false)
        setInfiniteLoops(false)

        // `lookup` permite acesso dinamico a propriedades e nao faz parte da DSL
        // de relatorios. `log` permitiria que o template escrevesse no log do servidor.
        helpers().removeIf { it.key == "lookup" || it.key == "log" }
        registerHelper("moeda", Helper<Any?> { valor, _ -> ValueFormatter.moeda(valor) })
        registerHelper("data", Helper<Any?> { valor, _ -> ValueFormatter.data(valor) })
        registerHelper("numero", Helper<Any?> { valor, _ -> ValueFormatter.numero(valor) })
        registerHelper("percentual", Helper<Any?> { valor, _ -> ValueFormatter.percentual(valor) })
        registerHelperMissing(Helper<Any?> { _, options ->
            throw IllegalArgumentException("Helper '${options.helperName}' nao e permitido.")
        })
    }

    fun renderizar(
        template: String,
        definicao: ReportDefinition,
        resposta: QueryResponse,
        params: Map<String, Any?>,
        logo: String? = null,
        geradoEm: LocalDateTime = LocalDateTime.now(),
    ): String {
        val linhas = formatarLinhas(definicao, resposta.rows)
        // Calculado sobre os valores BRUTOS; `linhas` ja esta formatado para exibicao.
        val agrupamento = Agrupamento.calcular(definicao, resposta.rows)
        val contexto = mapOf(
            "linhas" to linhas,
            "colunas" to definicao.colunas,
            "params" to params,
            "totais" to agrupamento.totais,
            "grupos" to agrupamento.grupos.map { grupoParaContexto(it, linhas) },
            "resumos" to definicao.resumos.map { resumo ->
                mapOf(
                    "titulo" to Agrupamento.tituloResumo(definicao, resumo),
                    "grupos" to Agrupamento.resumo(definicao, resposta.rows, resumo.por).grupos
                        .map { grupoParaContexto(it, linhas) },
                )
            },
            "meta" to meta(definicao, params, logo, geradoEm, linhas.size, bloco = 1, ultimo = true),
        )
        return preparar(template, definicao).aplicar(contexto)
    }

    /**
     * Template compilado uma vez, aplicado a varios contextos - um por bloco do PDF grande.
     * Recompilar a cada bloco repetiria a extracao de CSS e o parse do Handlebars sem ganho.
     */
    inner class TemplatePreparado internal constructor(
        private val compilado: com.github.jknack.handlebars.Template,
        private val css: String,
        private val titulo: String,
    ) {
        fun aplicar(contexto: Map<String, Any?>): String =
            documentoCompleto(policy.sanitize(compilado.apply(contexto)), css, titulo)
    }

    fun preparar(template: String, definicao: ReportDefinition): TemplatePreparado {
        // O CSS sai do TEMPLATE, antes da interpolacao. O sanitizador OWASP nao
        // sanitiza CSS: ele descartava a tag <style> e mantinha o conteudo como
        // texto, e o PDF saia com "@page { ... }" impresso no topo e sem estilo.
        // Extraindo antes de interpolar, nenhum dado de cliente chega ao CSS.
        val css = BLOCO_STYLE.findAll(template).map { it.groupValues[1] }.filter(::cssSeguro).joinToString("\n")
        val corpo = BLOCO_STYLE.replace(BLOCO_TITLE.replace(template, ""), "")
        return TemplatePreparado(handlebars.compileInline(corpo), css, definicao.nome)
    }

    /**
     * HTML de UM bloco de um relatorio grande ([linhas] sao so as deste bloco, a partir da
     * posicao [inicio] do resultado inteiro).
     *
     * Mesmo contexto do [renderizar], com a regra de que total so aparece onde fica certo:
     *  - `totais` e `resumos` so no ultimo bloco; nos demais, vazios;
     *  - cada grupo traz `quantidade` do grupo INTEIRO, e `totais` do grupo inteiro apenas
     *    no bloco onde ele termina - antes disso, vazio (`continua` = true);
     *  - grupo que veio do bloco anterior tem `continuacao` = true.
     * `meta.quantidade` e o total de linhas do relatorio; `meta.bloco`/`primeiroBloco`/
     * `ultimoBloco` deixam o template esconder cabecalho ou totais conforme o bloco.
     */
    fun renderizarBloco(
        preparado: TemplatePreparado,
        definicao: ReportDefinition,
        linhas: List<Map<String, Any?>>,
        inicio: Int,
        numero: Int,
        ultimo: Boolean,
        agregado: AgregadoEmFluxo,
        params: Map<String, Any?>,
        logo: String?,
        geradoEm: LocalDateTime,
    ): String {
        val formatadas = formatarLinhas(definicao, linhas)
        val fim = inicio + linhas.size
        val grupos = Agrupamento.calcular(definicao, linhas).grupos
        val contexto = mapOf(
            "linhas" to formatadas,
            "colunas" to definicao.colunas,
            "params" to params,
            "totais" to if (ultimo) agregado.totais else emptyMap(),
            "grupos" to grupos.map { grupoDoBloco(it, formatadas, linhas, agregado, inicio, fim) },
            "resumos" to if (!ultimo) emptyList() else definicao.resumos.mapIndexed { i, resumo ->
                mapOf(
                    "titulo" to Agrupamento.tituloResumo(definicao, resumo),
                    "grupos" to agregado.gruposDoResumo(i).map { resumoParaContexto(it, agregado) },
                )
            },
            "meta" to meta(definicao, params, logo, geradoEm, agregado.quantidade, numero, ultimo),
        )
        return preparado.aplicar(contexto)
    }

    private fun formatarLinhas(definicao: ReportDefinition, linhas: List<Map<String, Any?>>) = linhas.map { linha ->
        linha.toMutableMap().apply {
            definicao.colunas.forEach { coluna ->
                if (linha.containsKey(coluna.campo)) this[coluna.campo] = ValueFormatter.porColuna(linha[coluna.campo], coluna)
            }
        }
    }

    private fun meta(
        definicao: ReportDefinition,
        params: Map<String, Any?>,
        logo: String?,
        geradoEm: LocalDateTime,
        quantidade: Int,
        bloco: Int,
        ultimo: Boolean,
    ) = mapOf(
        "nome" to definicao.nome,
        "periodo" to periodo(params),
        "geradoEm" to geradoEm.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        "quantidade" to quantidade,
        "bloco" to bloco,
        "primeiroBloco" to (bloco == 1),
        "ultimoBloco" to ultimo,
        // Ausente quando nao veio (ou veio invalida): use {{#if meta.logo}} no template.
        // SafeString: sem isso o Handlebars escaparia o '=' do base64 como &#61;.
        // O valor ja passou pelo LogoRelatorio (so data:image/... e base64).
        "logo" to LogoRelatorio.validar(logo)?.let { Handlebars.SafeString(it) },
    )

    /** Grupo do bloco com os numeros do grupo INTEIRO, vindos do [AgregadoEmFluxo]. */
    private fun grupoDoBloco(
        grupo: Agrupamento.Grupo,
        formatadas: List<Map<String, Any?>>,
        brutas: List<Map<String, Any?>>,
        agregado: AgregadoEmFluxo,
        inicio: Int,
        fim: Int,
        nivel: Int = 0,
    ): Map<String, Any?> {
        val no = agregado.caminho(brutas[grupo.indices.first()])[nivel]
        val termina = no.ultima < fim
        return mapOf(
            "campo" to grupo.coluna.campo,
            "titulo" to grupo.coluna.titulo,
            "valor" to ValueFormatter.porColuna(grupo.valorBruto, grupo.coluna),
            "quantidade" to no.quantidade,
            "totais" to if (termina) no.totais else emptyMap(),
            "continuacao" to (no.primeira < inicio),
            "continua" to !termina,
            "linhas" to grupo.indices.map { formatadas[it] },
            "grupos" to grupo.filhos.map { grupoDoBloco(it, formatadas, brutas, agregado, inicio, fim, nivel + 1) },
        )
    }

    /** Resumo do relatorio grande: so totais - as linhas de cada grupo nao ficam em memoria. */
    private fun resumoParaContexto(no: AgregadoEmFluxo.No, agregado: AgregadoEmFluxo): Map<String, Any?> = mapOf(
        "campo" to no.coluna!!.campo,
        "titulo" to no.coluna.titulo,
        "valor" to ValueFormatter.porColuna(no.valorBruto, no.coluna),
        "quantidade" to no.quantidade,
        "totais" to no.totais,
        "continuacao" to false,
        "continua" to false,
        "linhas" to emptyList<Map<String, Any?>>(),
        "grupos" to agregado.filhosOrdenados(no).map { resumoParaContexto(it, agregado) },
    )

    /**
     * O CSS vai para o <head> sem passar pelo sanitizador, entao tem filtro
     * proprio. Bloco suspeito e descartado inteiro: melhor relatorio sem estilo
     * do que o gerador de PDF buscando recurso externo (SSRF) ou `</style>`
     * reabrindo o documento.
     */
    private fun cssSeguro(css: String): Boolean {
        val c = css.lowercase()
        if ("<" in c) return false
        if ("@import" in c || "expression(" in c || "behavior:" in c || "javascript:" in c) return false
        return URL_CSS.findAll(c).all { it.groupValues[1].trim().trim('\'', '"').startsWith("data:") }
    }

    /**
     * Forma exposta ao template. Cada grupo traz as proprias `linhas` (todas as do
     * grupo) e os `grupos` do nivel seguinte - vazio no ultimo nivel.
     */
    private fun grupoParaContexto(grupo: Agrupamento.Grupo, linhas: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "campo" to grupo.coluna.campo,
        "titulo" to grupo.coluna.titulo,
        "valor" to ValueFormatter.porColuna(grupo.valorBruto, grupo.coluna),
        "quantidade" to grupo.indices.size,
        "totais" to grupo.totais,
        "continuacao" to false,
        "continua" to false,
        "linhas" to grupo.indices.map { linhas[it] },
        "grupos" to grupo.filhos.map { grupoParaContexto(it, linhas) },
    )

    private fun periodo(params: Map<String, Any?>): String {
        val datas = params.entries
            .filter { it.key.contains("data", ignoreCase = true) || it.key.contains("periodo", ignoreCase = true) }
            .mapNotNull { it.value?.toString() }
        return datas.joinToString(" a ")
    }

    private fun documentoCompleto(corpo: String, css: String, titulo: String): String {
        val tituloSeguro = titulo.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\" />" +
            "<title>$tituloSeguro</title>" +
            (if (css.isNotBlank()) "<style>$css</style>" else "") +
            "</head><body>$corpo</body></html>"
    }

    private companion object {
        val BLOCO_STYLE = Regex("<style[^>]*>(.*?)</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val BLOCO_TITLE = Regex("<title[^>]*>.*?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val URL_CSS = Regex("url\\s*\\(([^)]*)\\)")
    }

    /** O mesmo bloqueio de includes usado na validacao, repetido em runtime. */
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
}
