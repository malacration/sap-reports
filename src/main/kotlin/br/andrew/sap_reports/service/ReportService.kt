package br.andrew.sap_reports.service

import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.definition.ValidacaoException
import br.andrew.sap_reports.storage.RelatorioRegistro
import br.andrew.sap_reports.storage.ReportRepository
import br.andrew.sap_reports.web.EventoAuditoriaDto
import br.andrew.sap_reports.web.RelatorioAdmin
import br.andrew.sap_reports.web.RelatorioAdminDetalhe
import br.andrew.sap_reports.web.RelatorioDetalhe
import br.andrew.sap_reports.web.RelatorioResumo
import br.andrew.sap_reports.web.UploadRequest
import br.andrew.sap_reports.web.ValidacaoOkDto
import br.andrew.sap_reports.web.VersaoDto
import org.springframework.stereotype.Service

@Service
class ReportService(
    private val repository: ReportRepository,
    private val validator: ReportUploadValidator,
    private val parser: DefinitionParser,
) {
    fun validar(upload: UploadRequest): ValidacaoOkDto {
        val resultado = validator.validar(upload.definicao, upload.template)
        if (!resultado.valido) throw ValidacaoException(resultado)
        return ValidacaoOkDto(tabelas = resultado.tabelas, parametros = resultado.parametros)
    }

    fun criar(upload: UploadRequest, quem: String): RelatorioAdmin {
        val def = validarDefinicao(upload)
        return repository.criar(def, upload.definicao, upload.template, quem).admin()
    }

    fun novaVersao(id: Long, upload: UploadRequest, quem: String): RelatorioAdmin {
        val def = validarDefinicao(upload)
        return repository.novaVersao(id, def, upload.definicao, upload.template, quem).admin()
    }

    fun publicar(id: Long, versao: Int, quem: String) = repository.publicar(id, versao, quem).admin()
    fun rollback(id: Long, versao: Int, quem: String) = repository.rollback(id, versao, quem).admin()
    fun remover(id: Long, quem: String) = repository.remover(id, quem)
    fun listarAdmin() = repository.listarTodos().map { it.admin() }

    fun detalheAdmin(id: Long, versao: Int?): RelatorioAdminDetalhe {
        val relatorio = repository.buscar(id) ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id)
        val fonte = repository.buscarVersao(id, versao)
            ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id, versao)
        val base = relatorio.admin()
        return RelatorioAdminDetalhe(
            id = base.id, nome = base.nome, descricao = base.descricao, status = base.status,
            ultimaVersao = base.ultimaVersao, versaoPublicada = base.versaoPublicada,
            papeis = base.papeis, formatos = base.formatos, criadoPor = base.criadoPor,
            criadoEm = base.criadoEm, atualizadoEm = base.atualizadoEm,
            versao = fonte.versao, definicao = fonte.definicao, template = fonte.template,
        )
    }

    fun listarVersoes(id: Long): List<VersaoDto> {
        val relatorio = repository.buscar(id)
            ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id)
        return repository.listarVersoes(id).map {
            VersaoDto(it.versao, it.criadoPor, it.criadoEm, it.publicadoEm, it.versao == relatorio.versaoPublicada)
        }
    }

    fun listarAuditoria(id: Long) = repository.listarAuditoria(id).map {
        EventoAuditoriaDto(it.quem, it.quando, it.acao.name, it.versao)
    }

    fun listarPublicados(papeis: Set<String>): List<RelatorioResumo> =
        repository.listarPublicados().mapNotNull { (registro, fonte) ->
            val def = parser.ler(fonte.definicao)
            if (def.papeis.none { it in papeis }) null else RelatorioResumo(
                id = registro.id, nome = def.nome, descricao = def.descricao, formatos = def.formatos,
                versaoPublicada = fonte.versao, atualizadoEm = registro.atualizadoEm,
            )
        }

    fun detalhePublicado(id: Long, papeis: Set<String>): RelatorioDetalhe {
        val (registro, def) = publicado(id)
        if (def.papeis.none { it in papeis }) {
            throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id)
        }
        return RelatorioDetalhe(
            id, def.nome, def.descricao, def.formatos, registro.versaoPublicada!!,
            registro.atualizadoEm, def.parametros, def.colunas,
        )
    }

    internal fun publicado(id: Long): Pair<RelatorioRegistro, ReportDefinition> {
        val registro = repository.buscar(id)
            ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id)
        val versao = registro.versaoPublicada
            ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id)
        val fonte = repository.buscarVersao(id, versao)
            ?: throw br.andrew.sap_reports.storage.RelatorioNaoEncontradoException(id, versao)
        return registro to parser.ler(fonte.definicao)
    }

    private fun validarDefinicao(upload: UploadRequest): ReportDefinition =
        validator.validarOuFalhar(upload.definicao, upload.template)

    private fun RelatorioRegistro.admin() = RelatorioAdmin(
        id, nome, descricao, if (versaoPublicada == null) "RASCUNHO" else "PUBLICADO",
        ultimaVersao, versaoPublicada, papeis, formatos, criadoPor, criadoEm, atualizadoEm,
    )
}
