package br.andrew.sap_reports.execucao

import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.RenderService
import br.andrew.sap_reports.service.ReportService
import br.andrew.sap_reports.web.FormatoInvalidoException
import br.andrew.sap_reports.web.RenderRequest
import br.andrew.sap_reports.web.erroConhecido
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Relatorio em segundo plano: o pedido so enfileira e responde na hora; um
 * worker executa; o usuario consulta o estado e baixa o arquivo depois.
 *
 * Existe porque o proxy na frente da API corta requisicao demorada - e uma
 * consulta grande no HANA demora. Nenhuma regra de relatorio mora aqui: a
 * execucao chama o mesmo [RenderService] das rotas sincronas.
 */
@Service
class ExecucaoService(
    private val store: ExecucaoStore,
    private val reports: ReportService,
    private val renderer: RenderService,
    private val props: ExecucaoProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val retencao get() = Duration.ofHours(props.retencaoHoras)

    fun iniciarRender(id: Long, formato: String, pedido: RenderRequest, usuario: UsuarioAutenticado): ExecucaoDto {
        // Acesso, formato e parametros sao conferidos AGORA: erro de preenchimento
        // aparece na hora, e nao minutos depois como execucao falha.
        val relatorio = reports.detalhePublicado(id, usuario.papeis)
        if (formato !in relatorio.formatos) throw FormatoInvalidoException(formato)
        renderer.validarParametros(relatorio.parametros, pedido.params)
        return iniciar(
            novaExecucao(usuario, TipoExecucao.RENDER, id, relatorio.nome, relatorio.versaoPublicada, formato, pedido)
                .copy(papeis = usuario.papeis),
        )
    }

    fun iniciarPreview(
        id: Long, versao: Int?, formato: String, pedido: RenderRequest, usuario: UsuarioAutenticado,
    ): ExecucaoDto {
        val relatorio = reports.detalheAdmin(id, versao)
        return iniciar(novaExecucao(usuario, TipoExecucao.PREVIEW, id, relatorio.nome, relatorio.versao, formato, pedido))
    }

    fun listar(usuario: UsuarioAutenticado): List<ExecucaoDto> = store.listar(usuario.dono, LIMITE_LISTA).map(::dto)

    fun buscar(id: String, usuario: UsuarioAutenticado): ExecucaoDto = dto(doDono(id, usuario))

    /** Arquivo pronto da execucao. EXCLUIDO vira [ExecucaoExcluidaException] (410). */
    fun arquivo(id: String, usuario: UsuarioAutenticado): Pair<ExecucaoDto, Path> {
        val execucao = doDono(id, usuario)
        val resposta = dto(execucao)
        when (resposta.status) {
            StatusExecucao.CONCLUIDO -> return resposta to caminho(execucao)
            StatusExecucao.EXCLUIDO -> throw ExecucaoExcluidaException()
            StatusExecucao.FALHOU -> throw ExecucaoEmAndamentoException("A execucao falhou: ${execucao.falha?.mensagem}")
            else -> throw ExecucaoEmAndamentoException("O relatorio ainda esta sendo gerado.")
        }
    }

    fun remover(id: String, usuario: UsuarioAutenticado) {
        val execucao = doDono(id, usuario)
        if (execucao.ativa) throw ExecucaoEmAndamentoException("Nao e possivel remover uma execucao em andamento.")
        execucao.arquivo?.let { Files.deleteIfExists(caminho(execucao)) }
        store.remover(execucao)
    }

    /** Chamado pelo worker. Idempotente: execucao que nao esta PENDENTE e ignorada. */
    fun executar(id: String) {
        val pendente = store.buscar(id)?.takeIf { it.status == StatusExecucao.PENDENTE } ?: return
        val execucao = pendente.copy(status = StatusExecucao.EXECUTANDO, iniciadoEm = agora())
        store.salvar(execucao)
        log.info("Executando relatorio {} ({}, {}) para {}", execucao.relatorioId, execucao.tipo, execucao.formato, execucao.dono)

        val final = try {
            val saida = when (execucao.tipo) {
                TipoExecucao.RENDER -> renderer.renderizarPublicado(
                    execucao.relatorioId, execucao.formato, execucao.params, execucao.papeis, execucao.logo,
                )
                TipoExecucao.PREVIEW -> renderer.preview(
                    execucao.relatorioId, execucao.versao, execucao.formato, execucao.params, execucao.logo,
                )
            }
            val arquivo = saida.use { gravar(execucao, it) }
            execucao.copy(status = StatusExecucao.CONCLUIDO, arquivo = arquivo)
        } catch (ex: Exception) {
            val conhecido = erroConhecido(ex)
            if (conhecido == null) log.error("Erro inesperado na execucao {}", id, ex)
            val falha = conhecido?.let { FalhaExecucao(it.status, it.erro, it.mensagem) }
                ?: FalhaExecucao(500, "erro_interno", "Erro inesperado ao gerar o relatorio. O detalhe foi registrado no log do servidor.")
            execucao.copy(status = StatusExecucao.FALHOU, falha = falha)
        }
        store.salvar(final.copy(concluidoEm = agora(), logo = null, papeis = emptySet()))
    }

    /**
     * Ao subir: o que estava EXECUTANDO morreu com o processo anterior. Marca
     * como falha, para a tela nao ficar esperando para sempre. PENDENTE continua
     * na fila do Redis e sera executado normalmente.
     */
    fun recuperarInterrompidas() {
        store.ativas().forEach { id ->
            val execucao = store.buscar(id) ?: return@forEach
            if (execucao.status == StatusExecucao.EXECUTANDO) {
                store.salvar(
                    execucao.copy(
                        status = StatusExecucao.FALHOU, concluidoEm = agora(), logo = null, papeis = emptySet(),
                        falha = FalhaExecucao(503, "execucao_interrompida", "O servico reiniciou durante a execucao. Gere novamente."),
                    ),
                )
            }
        }
    }

    /** Apaga os arquivos com a retencao vencida. O estado no Redis expira sozinho (TTL). */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    fun limparArquivosVencidos() {
        val pasta = diretorio()
        if (!Files.isDirectory(pasta)) return
        val corte = agora().minus(retencao)
        Files.list(pasta).use { arquivos ->
            arquivos.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toInstant().isBefore(corte) }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun iniciar(execucao: Execucao): ExecucaoDto {
        val ativas = store.listar(execucao.dono, LIMITE_LISTA).count { it.ativa }
        if (ativas >= props.maxAtivasPorUsuario) throw MuitasExecucoesException(props.maxAtivasPorUsuario)
        store.salvar(execucao)
        store.enfileirar(execucao.id)
        return dto(execucao)
    }

    private fun novaExecucao(
        usuario: UsuarioAutenticado, tipo: TipoExecucao, id: Long, nome: String, versao: Int?,
        formato: String, pedido: RenderRequest,
    ) = Execucao(
        id = UUID.randomUUID().toString(), dono = usuario.dono, tipo = tipo,
        relatorioId = id, relatorioNome = nome, versao = versao, formato = formato,
        params = pedido.params, logo = pedido.logo, criadoEm = agora(),
    )

    /** Grava num `.part` e move: o download nunca enxerga arquivo pela metade. */
    private fun gravar(execucao: Execucao, saida: br.andrew.sap_reports.web.SaidaRenderizada): ArquivoExecucao {
        val pasta = Files.createDirectories(diretorio())
        val parcial = pasta.resolve("${execucao.id}.part")
        Files.newOutputStream(parcial).use { saida.escreverEm(it) }
        val destino = pasta.resolve("${execucao.id}.${saida.extensao}")
        Files.move(parcial, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val data = DateTimeFormatter.BASIC_ISO_DATE.format(agora().atZone(ZoneId.systemDefault()))
        val sufixo = if (execucao.tipo == TipoExecucao.PREVIEW) "-previa" else ""
        return ArquivoExecucao(
            nome = "relatorio-${execucao.relatorioId}$sufixo-$data.${saida.extensao}",
            contentType = saida.contentType, extensao = saida.extensao, tamanho = Files.size(destino),
        )
    }

    private fun doDono(id: String, usuario: UsuarioAutenticado): Execucao =
        store.buscar(id)?.takeIf { it.dono == usuario.dono } ?: throw ExecucaoNaoEncontradaException(id)

    private fun dto(e: Execucao): ExecucaoDto {
        val expiraEm = e.concluidoEm?.takeIf { e.status == StatusExecucao.CONCLUIDO }?.plus(retencao)
        val excluido = e.status == StatusExecucao.CONCLUIDO &&
            (expiraEm!!.isBefore(agora()) || !Files.exists(caminho(e)))
        return ExecucaoDto(
            id = e.id, tipo = e.tipo, relatorioId = e.relatorioId, relatorioNome = e.relatorioNome,
            versao = e.versao, formato = e.formato, params = e.params,
            status = if (excluido) StatusExecucao.EXCLUIDO else e.status,
            criadoEm = e.criadoEm, iniciadoEm = e.iniciadoEm, concluidoEm = e.concluidoEm,
            expiraEm = expiraEm, erro = e.falha, arquivo = e.arquivo?.takeUnless { excluido },
        )
    }

    private fun caminho(e: Execucao): Path = diretorio().resolve("${e.id}.${e.arquivo?.extensao ?: e.formato}")

    internal fun diretorio(): Path =
        props.diretorio.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("java.io.tmpdir"), "sap-reports-execucoes")

    private fun agora(): Instant = clock.instant()

    companion object {
        const val LIMITE_LISTA = 50
    }
}
