package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.Formato
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.render.AgregadoEmFluxo
import br.andrew.sap_reports.render.Agrupamento
import br.andrew.sap_reports.render.CsvRenderer
import br.andrew.sap_reports.render.HtmlRenderer
import br.andrew.sap_reports.render.PdfRenderer
import br.andrew.sap_reports.render.ResultadoEmDisco
import br.andrew.sap_reports.web.RelatorioDesordenadoException
import br.andrew.sap_reports.web.ResultadoTruncadoException
import br.andrew.sap_reports.web.SaidaRenderizada
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * PDF e CSV sem limite de memoria: a consulta chega do sap-odbc em fluxo, vai para um
 * arquivo temporario e o relatorio e montado a partir dele.
 *
 * Duas passadas sobre o disco, nunca sobre o banco:
 *  1. fluxo do sap-odbc -> [ResultadoEmDisco], calculando ao mesmo tempo o [AgregadoEmFluxo]
 *     (totais e grupos). Uma unica execucao da consulta: os totais batem com as linhas.
 *  2. ate [ReportProperties.linhasEmMemoria] linhas, o caminho de sempre, em memoria, com
 *     resultado identico ao de antes; acima disso, CSV escrito linha a linha ou PDF em
 *     blocos de [ReportProperties.linhasPorBloco] juntados em arquivo.
 *
 * Tudo de um relatorio fica num diretorio temporario proprio, apagado quando a resposta
 * termina de ser enviada ([SaidaRenderizada.close]) ou na primeira falha.
 */
@Component
class RenderEmDisco(
    private val odbc: OdbcClient,
    private val htmlRenderer: HtmlRenderer,
    private val pdfRenderer: PdfRenderer,
    private val csvRenderer: CsvRenderer,
    private val properties: ReportProperties,
) {
    private val log = LoggerFactory.getLogger(RenderEmDisco::class.java)

    fun gerar(
        def: ReportDefinition,
        template: String,
        tipo: Formato,
        params: Map<String, Any?>,
        logo: String?,
    ): SaidaRenderizada {
        require(tipo != Formato.HTML) { "HTML e montado em memoria pelo RenderService." }
        val limite = def.consulta.maxRows ?: properties.maxRowsArquivo
        val diretorio = Files.createTempDirectory(base(), "relatorio-")
        var entregue = false
        try {
            val disco = ResultadoEmDisco(diretorio)
            val agregado = AgregadoEmFluxo(def)
            disco.use {
                val fim = odbc.consultarFluxo(
                    def.consulta.sql, params, limite,
                    aoIniciar = { disco.colunas = it },
                    aoLer = { linha ->
                        disco.gravar(linha)
                        agregado.somar(linha)
                    },
                )
                if (fim.truncated) throw ResultadoTruncadoException(limite)
            }

            if (disco.quantidade <= properties.linhasEmMemoria) return emMemoria(def, template, tipo, params, logo, disco)

            agregado.desordenado?.let { throw RelatorioDesordenadoException(it, properties.linhasEmMemoria) }
            log.info("Relatorio '{}' em disco: {} linhas, formato {}", def.nome, disco.quantidade, tipo)
            val arquivo = when (tipo) {
                Formato.CSV -> csv(def, disco, agregado, diretorio)
                else -> pdf(def, template, params, logo, disco, agregado, diretorio)
            }
            return SaidaRenderizada.deArquivo(arquivo, tipo.contentType(), tipo.yaml, diretorio).also { entregue = true }
        } finally {
            if (!entregue) SaidaRenderizada.apagarDiretorio(diretorio)
        }
    }

    /** Ate o limite em memoria: exatamente o que era feito antes do modo em disco. */
    private fun emMemoria(
        def: ReportDefinition, template: String, tipo: Formato,
        params: Map<String, Any?>, logo: String?, disco: ResultadoEmDisco,
    ): SaidaRenderizada {
        val resposta = disco.emMemoria()
        val bytes = when (tipo) {
            Formato.CSV -> csvRenderer.renderizar(def, resposta)
            else -> pdfRenderer.renderizar(htmlRenderer.renderizar(template, def, resposta, params, logo))
        }
        return SaidaRenderizada(bytes, tipo.contentType(), tipo.yaml)
    }

    private fun csv(def: ReportDefinition, disco: ResultadoEmDisco, agregado: AgregadoEmFluxo, diretorio: Path): Path {
        val destino = diretorio.resolve("relatorio.csv")
        Files.newOutputStream(destino).buffered().use { csvRenderer.renderizarEmFluxo(def, disco, agregado, it) }
        return destino
    }

    private fun pdf(
        def: ReportDefinition, template: String, params: Map<String, Any?>, logo: String?,
        disco: ResultadoEmDisco, agregado: AgregadoEmFluxo, diretorio: Path,
    ): Path {
        val preparado = htmlRenderer.preparar(template, def)
        val geradoEm = LocalDateTime.now()
        val partes = ArrayList<Path>()
        val bloco = ArrayList<Map<String, Any?>>()
        var inicio = 0

        fun emitir(ultimo: Boolean) {
            val parte = diretorio.resolve("parte-%06d.pdf".format(partes.size + 1))
            val html = htmlRenderer.renderizarBloco(
                preparado, def, bloco, inicio, partes.size + 1, ultimo, agregado, params, logo, geradoEm,
            )
            Files.newOutputStream(parte).buffered().use { pdfRenderer.renderizar(html, it) }
            partes.add(parte)
            inicio += bloco.size
            bloco.clear()
        }

        disco.ler { linhas ->
            linhas.forEach { linha ->
                if (deveCortar(def, bloco, linha)) emitir(ultimo = false)
                bloco.add(linha)
            }
        }
        emitir(ultimo = true)

        val destino = diretorio.resolve("relatorio.pdf")
        pdfRenderer.juntar(partes, destino)
        partes.forEach { Files.deleteIfExists(it) }
        return destino
    }

    /**
     * Corta o bloco ao atingir o tamanho, de preferencia na troca do grupo de primeiro nivel
     * (o grupo fica inteiro no mesmo bloco). Grupo maior que o dobro do bloco e partido
     * assim mesmo, para a memoria continuar limitada - ai o template recebe `continua` /
     * `continuacao` e o subtotal so no bloco onde o grupo termina.
     */
    private fun deveCortar(def: ReportDefinition, bloco: List<Map<String, Any?>>, proxima: Map<String, Any?>): Boolean {
        val tamanho = properties.linhasPorBloco
        if (bloco.size < tamanho) return false
        val campo = def.agrupar.firstOrNull() ?: return true
        if (bloco.size >= 2 * tamanho) return true
        return Agrupamento.chaveDoGrupo(proxima[campo]) != Agrupamento.chaveDoGrupo(bloco.last()[campo])
    }

    private fun base(): Path {
        val configurado = properties.diretorioTemporario?.takeIf { it.isNotBlank() }
            ?: return Path.of(System.getProperty("java.io.tmpdir"))
        return Path.of(configurado).also { Files.createDirectories(it) }
    }

    private fun Formato.contentType() = when (this) {
        Formato.CSV -> "text/csv;charset=UTF-8"
        Formato.PDF -> "application/pdf"
        Formato.HTML -> "text/html;charset=UTF-8"
    }
}
