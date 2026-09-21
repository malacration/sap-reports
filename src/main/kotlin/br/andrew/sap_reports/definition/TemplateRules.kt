package br.andrew.sap_reports.definition

/**
 * Regras sobre o template Handlebars enviado.
 *
 * O Handlebars ja e *logic-less* - nao avalia expressao arbitraria como o SpEL do
 * Thymeleaf -, o que elimina execucao de codigo. Sobram dois vetores, fechados aqui:
 *
 *  1. Saida nao escapada (`{{{ }}}` e `{{& }}`), que reintroduz XSS.
 *  2. Carregamento de recurso externo, que no renderizador de PDF vira leitura de
 *     arquivo local (`file:///etc/passwd`) ou requisicao para host interno (SSRF).
 */
object TemplateRules {

    /** `{{{x}}}` e `{{&x}}` interpolam sem escapar. So `{{x}}` e aceito. */
    private val SEM_ESCAPE = Regex("""\{\{\{|\{\{\s*&""")

    /** Tags que nao fazem sentido em relatorio e servem de vetor. */
    private val TAG_PROIBIDA = Regex(
        """<\s*(script|iframe|object|embed|applet|form|base|meta|link)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Handlers inline: onclick, onerror, onload... */
    private val ATRIBUTO_EVENTO = Regex("""<[^>]*?\s(on[a-z]+)\s*=""", RegexOption.IGNORE_CASE)

    /** Atributos que fazem o renderizador BUSCAR algo. */
    private val ATRIBUTO_RECURSO = Regex("""\s(src|poster|data)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** `url(...)` em CSS inline ou bloco `<style>`. */
    private val CSS_URL = Regex("""url\s*\(\s*["']?([^"')]*)["']?\s*\)""", RegexOption.IGNORE_CASE)

    /** `<a href>`: nao e buscado pelo renderizador, mas `javascript:` e proibido. */
    private val HREF = Regex("""\shref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    private fun linhaDe(texto: String, indice: Int) = texto.take(indice).count { it == '\n' } + 1

    /**
     * Um recurso so pode ser embutido: `data:` literal, ou exatamente `{{meta.logo}}` -
     * a logo do cliente, que o servidor valida como imagem raster em `data:` antes de
     * expor ao template (LogoRelatorio). Qualquer outra expressao em `src` segue proibida.
     */
    private fun recursoAceito(valor: String) =
        valor.trim().startsWith("data:", ignoreCase = true) || LOGO.matches(valor)

    private val LOGO = Regex("""^\s*\{\{\s*meta\.logo\s*\}\}\s*$""")

    fun validar(template: String): List<Problema> {
        val problemas = mutableListOf<Problema>()

        SEM_ESCAPE.findAll(template).forEach {
            problemas += Problema(
                caminho = "template",
                regra = Regra.ESCAPE_DESABILITADO,
                mensagem = "Uso de '${it.value.trim()}' na linha ${linhaDe(template, it.range.first)}. " +
                    "Use {{ }} - as formas com {{{ ou & nao escapam HTML e permitem XSS.",
                linha = linhaDe(template, it.range.first),
            )
        }

        TAG_PROIBIDA.findAll(template).forEach {
            val tag = it.groupValues[1].lowercase()
            problemas += Problema(
                caminho = "template",
                regra = Regra.TAG_PROIBIDA,
                mensagem = "Tag <$tag> nao e permitida em template de relatorio.",
                linha = linhaDe(template, it.range.first),
            )
        }

        ATRIBUTO_EVENTO.findAll(template).forEach {
            problemas += Problema(
                caminho = "template",
                regra = Regra.TAG_PROIBIDA,
                mensagem = "Atributo de evento '${it.groupValues[1]}' nao e permitido.",
                linha = linhaDe(template, it.range.first),
            )
        }

        // Recursos externos: o renderizador de PDF os buscaria do lado do servidor.
        ATRIBUTO_RECURSO.findAll(template).forEach {
            val (atributo, valor) = it.groupValues[1] to it.groupValues[2]
            if (!recursoAceito(valor)) {
                problemas += Problema(
                    caminho = "template",
                    regra = Regra.RECURSO_EXTERNO_PROIBIDO,
                    mensagem = "Atributo '$atributo' aponta para recurso externo ('${valor.take(60)}'). " +
                        "Somente data: URI e aceito - o servidor nunca busca recurso de fora ao gerar o PDF.",
                    linha = linhaDe(template, it.range.first),
                )
            }
        }

        CSS_URL.findAll(template).forEach {
            val valor = it.groupValues[1]
            if (valor.isNotBlank() && !recursoAceito(valor)) {
                problemas += Problema(
                    caminho = "template",
                    regra = Regra.RECURSO_EXTERNO_PROIBIDO,
                    mensagem = "url(${valor.take(60)}) no CSS busca recurso externo. Somente data: URI e aceito.",
                    linha = linhaDe(template, it.range.first),
                )
            }
        }

        HREF.findAll(template).forEach {
            val valor = it.groupValues[1].trim()
            if (valor.startsWith("javascript:", ignoreCase = true)) {
                problemas += Problema(
                    caminho = "template",
                    regra = Regra.TAG_PROIBIDA,
                    mensagem = "href com 'javascript:' nao e permitido.",
                    linha = linhaDe(template, it.range.first),
                )
            }
        }

        return problemas
    }
}
