package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Formato
import br.andrew.sap_reports.definition.Parametro
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.definition.ValoresParametro.converter
import br.andrew.sap_reports.definition.ValoresParametro.INVALIDO
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.storage.RelatorioNaoEncontradoException
import br.andrew.sap_reports.storage.ReportRepository
import br.andrew.sap_reports.web.AcessoRelatorioNegadoException
import br.andrew.sap_reports.web.FormatoInvalidoException
import br.andrew.sap_reports.web.ParametrosInvalidosException
import br.andrew.sap_reports.web.ResultadoTruncadoException
import br.andrew.sap_reports.web.SaidaRenderizada
import org.springframework.stereotype.Service

@Service
class RenderService(
    private val repository: ReportRepository,
    private val parser: DefinitionParser,
    private val odbc: OdbcClient,
    private val htmlRenderer: HtmlRenderer,
    private val pdfRenderer: PdfRenderer,
    private val csvRenderer: CsvRenderer,
    private val properties: ReportProperties,
) {
    fun renderizarPublicado(
        id: Long,
        formato: String,
        params: Map<String, Any?>,
        papeis: Set<String>,
        logo: String? = null,
    ): SaidaRenderizada {
        val registro = repository.buscar(id) ?: throw RelatorioNaoEncontradoException(id)
        val numero = registro.versaoPublicada ?: throw RelatorioNaoEncontradoException(id)
        val fonte = repository.buscarVersao(id, numero) ?: throw RelatorioNaoEncontradoException(id, numero)
        val def = parser.ler(fonte.definicao)
        if (def.papeis.none { it in papeis }) throw AcessoRelatorioNegadoException()
        return executar(def, fonte.template, formato, params, logo)
    }

    fun preview(
        id: Long,
        versao: Int?,
        formato: String,
        params: Map<String, Any?>,
        logo: String? = null,
    ): SaidaRenderizada {
        val fonte = repository.buscarVersao(id, versao)
            ?: throw RelatorioNaoEncontradoException(id, versao)
        return executar(parser.ler(fonte.definicao), fonte.template, formato, params, logo)
    }

    private fun executar(
        def: br.andrew.sap_reports.definition.ReportDefinition,
        template: String,
        formato: String,
        recebidos: Map<String, Any?>,
        logo: String? = null,
    ): SaidaRenderizada {
        val tipo = Formato.de(formato)
            ?.takeIf { formato in def.formatos }
            ?: throw FormatoInvalidoException(formato)
        val params = validarParametros(def.parametros, recebidos)
        val limite = def.consulta.maxRows ?: properties.maxRows
        val resposta = odbc.consultar(def.consulta.sql, params, limite)
        if (resposta.truncated) throw ResultadoTruncadoException(limite)

        return when (tipo) {
            Formato.HTML -> SaidaRenderizada(
                htmlRenderer.renderizar(template, def, resposta, params, logo).toByteArray(Charsets.UTF_8),
                "text/html;charset=UTF-8",
                "html",
            )
            Formato.PDF -> {
                val html = htmlRenderer.renderizar(template, def, resposta, params, logo)
                SaidaRenderizada(pdfRenderer.renderizar(html), "application/pdf", "pdf")
            }
            Formato.CSV -> SaidaRenderizada(
                csvRenderer.renderizar(def, resposta),
                "text/csv;charset=UTF-8",
                "csv",
            )
        }
    }

    internal fun validarParametros(
        declarados: List<Parametro>,
        recebidos: Map<String, Any?>,
    ): Map<String, Any?> {
        val problemas = mutableListOf<String>()
        val nomes = declarados.map { it.nome }.toSet()
        val extras = recebidos.keys - nomes
        if (extras.isNotEmpty()) problemas += "Parametros desconhecidos: ${extras.sorted().joinToString(", ")}."

        val saida = linkedMapOf<String, Any?>()
        declarados.forEach { param ->
            val presente = recebidos.containsKey(param.nome)
            val valor = when {
                presente -> recebidos[param.nome]
                param.padrao != null -> param.padrao
                !param.obrigatorio -> null
                else -> {
                    problemas += "Parametro obrigatorio ausente: ${param.nome}."
                    null
                }
            }
            if (!presente && param.obrigatorio && param.padrao == null) return@forEach
            if (valor == null && param.obrigatorio) {
                problemas += "Parametro obrigatorio nulo: ${param.nome}."
                return@forEach
            }
            val convertido = converter(param, valor)
            if (valor != null && convertido == INVALIDO) {
                problemas += "Parametro '${param.nome}' nao corresponde ao tipo ${param.tipo}."
            } else {
                saida[param.nome] = if (convertido == INVALIDO) null else convertido
            }
        }
        if (problemas.isNotEmpty()) throw ParametrosInvalidosException(problemas.joinToString(" "))
        return saida
    }

}
