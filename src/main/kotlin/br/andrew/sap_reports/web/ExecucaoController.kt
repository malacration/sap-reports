package br.andrew.sap_reports.web

import br.andrew.sap_reports.execucao.ExecucaoService
import br.andrew.sap_reports.security.UsuarioAutenticado
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

/**
 * Execucoes assincronas do usuario logado: listar, acompanhar, baixar, remover.
 * Cada um enxerga so as proprias - execucao alheia responde 404.
 *
 * Os pedidos sao feitos em `POST /relatorios/{id}/execucoes` (consumo) e
 * `POST /admin/relatorios/{id}/preview/execucoes` (previa de autoria).
 */
@RestController
@RequestMapping("/api/v1/execucoes")
class ExecucaoController(private val execucoes: ExecucaoService) {

    @GetMapping
    fun listar(principal: Principal) = execucoes.listar(principal.usuario())

    @GetMapping("/{id}")
    fun detalhe(@PathVariable id: String, principal: Principal) = execucoes.buscar(id, principal.usuario())

    @GetMapping("/{id}/arquivo")
    fun arquivo(@PathVariable id: String, principal: Principal, response: HttpServletResponse) {
        val (execucao, caminho) = execucoes.arquivo(id, principal.usuario())
        val arquivo = execucao.arquivo!!
        // Sem temporario: o arquivo continua disponivel ate a retencao vencer.
        enviar(
            execucao.relatorioId,
            SaidaRenderizada.deArquivo(caminho, arquivo.contentType, arquivo.extensao),
            response,
            arquivo.nome,
        )
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remover(@PathVariable id: String, principal: Principal) {
        execucoes.remover(id, principal.usuario())
    }

    private fun Principal.usuario() = this as UsuarioAutenticado
}
