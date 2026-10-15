package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Formato
import br.andrew.sap_reports.definition.Parametro
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.TipoParametro
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.odbc.paraDecimal
import br.andrew.sap_reports.definition.ValoresParametro.converter
import br.andrew.sap_reports.definition.ValoresParametro.INVALIDO
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.security.UsuarioAutenticado
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
    private val properties: ReportProperties,
    private val emDisco: RenderEmDisco,
    private val filiais: FiliaisDoUsuario,
) {
    fun renderizarPublicado(
        id: Long,
        formato: String,
        params: Map<String, Any?>,
        usuario: UsuarioAutenticado,
        logo: String? = null,
    ): SaidaRenderizada {
        val registro = repository.buscar(id) ?: throw RelatorioNaoEncontradoException(id)
        val numero = registro.versaoPublicada ?: throw RelatorioNaoEncontradoException(id)
        val fonte = repository.buscarVersao(id, numero) ?: throw RelatorioNaoEncontradoException(id, numero)
        val def = parser.ler(fonte.definicao)
        if (def.papeis.none { it in usuario.papeis }) throw AcessoRelatorioNegadoException()
        return executar(def, fonte.template, formato, params, logo, usuario)
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
        // Sem corte de filial: a pre-visualizacao e do autor, que escreve o SQL que quiser.
        return executar(parser.ler(fonte.definicao), fonte.template, formato, params, logo)
    }

    private fun executar(
        def: ReportDefinition,
        template: String,
        formato: String,
        recebidos: Map<String, Any?>,
        logo: String? = null,
        usuario: UsuarioAutenticado? = null,
    ): SaidaRenderizada {
        val tipo = Formato.de(formato)
            ?.takeIf { formato in def.formatos }
            ?: throw FormatoInvalidoException(formato)
        val params = validarParametros(def.parametros, recebidos).let { validados ->
            if (usuario == null) validados
            else restringirFiliais(def.parametros, validados) { filiais.permitidas(usuario) }
        }
        // PDF e CSV vem em fluxo e sao montados em disco, sem o teto de memoria do HTML.
        if (tipo != Formato.HTML) return emDisco.gerar(def, template, tipo, params, logo)

        // HTML continua em memoria e com o teto antigo: o navegador carrega a pagina inteira.
        val limite = def.consulta.maxRows ?: properties.maxRows
        val resposta = odbc.consultar(def.consulta.sql, params, limite)
        if (resposta.truncated) throw ResultadoTruncadoException(limite)
        return SaidaRenderizada(
            htmlRenderer.renderizar(template, def, resposta, params, logo).toByteArray(Charsets.UTF_8),
            "text/html;charset=UTF-8",
            "html",
        )
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

    /**
     * Corta as filiais pedidas as do cadastro do usuario ([FiliaisDoUsuario]), como o painel
     * de vendas v2 do sap-service: so falha se nao sobrar nenhuma. Cortar, e nao recusar, e o
     * que mantem funcionando o `padrao` com todas as filiais (`[2, 3, ..., 19]`) dos
     * relatorios - quem tem parte delas recebe so a sua parte. O valor cortado segue para o
     * SQL e para o template.
     *
     * Opcional sem selecao (null) passa como esta: `IN (NULL)` nao traz linha.
     */
    internal fun restringirFiliais(
        declarados: List<Parametro>,
        params: Map<String, Any?>,
        permitidas: () -> Set<Int>?,
    ): Map<String, Any?> {
        val deFilial = declarados.filter { it.tipo == TipoParametro.FILIAL.yaml && params[it.nome] != null }
        if (deFilial.isEmpty()) return params
        val liberadas = permitidas() ?: return params
        if (liberadas.isEmpty()) {
            throw AcessoRelatorioNegadoException(
                "Seu usuario nao tem filial liberada no cadastro. Peca a liberacao ao administrador.",
            )
        }
        val saida = LinkedHashMap(params)
        deFilial.forEach { param ->
            val pedidas = params[param.nome] as? List<*> ?: listOf(params[param.nome])
            val cortadas = pedidas.filter { it.paraDecimal()?.toInt() in liberadas }
            if (cortadas.isEmpty()) {
                throw AcessoRelatorioNegadoException(
                    if (param.multiplo) "Nenhuma das filiais selecionadas esta liberada no seu cadastro."
                    else "A filial selecionada nao esta liberada no seu cadastro.",
                )
            }
            saida[param.nome] = if (param.multiplo) cortadas else cortadas.single()
        }
        return saida
    }

}
