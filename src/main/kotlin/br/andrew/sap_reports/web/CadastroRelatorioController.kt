package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.CadastrosRelatorio
import br.andrew.sap_reports.service.ItemCadastro
import br.andrew.sap_reports.service.ReportService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.security.Principal

/** Uso exclusivo dos filtros de parâmetro de relatório; telas de venda/cadastro usam as buscas do sap-service. */
@RestController
@RequestMapping("/api/v1/relatorios/cadastros")
class CadastroRelatorioController(
    private val cadastros: CadastrosRelatorio,
    private val reports: ReportService,
    private val properties: ReportProperties,
) {
    @GetMapping("/{tipo}")
    fun buscar(
        @PathVariable tipo: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") pagina: Int,
        principal: Principal,
    ): PaginaCadastro {
        // Quem não enxerga nenhum relatório não tem filtro para preencher; o autor preenche o do preview do rascunho.
        val papeis = (principal as UsuarioAutenticado).papeis
        if (properties.authorRole !in papeis && reports.listarPublicados(papeis).isEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario sem relatorio publicado.")
        }
        if (pagina !in CadastrosRelatorio.PAGINAS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagina deve estar entre 0 e ${CadastrosRelatorio.PAGINAS.last}.")
        }
        val resultado = cadastros.buscar(tipo, q, pagina)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de cadastro desconhecido.")
        // Relativo a /api/v1/relatorios/: o front monta a URL com o host dele. Última página aceita não aponta além.
        val proxima = if (resultado.temProxima && pagina < CadastrosRelatorio.PAGINAS.last) {
            "cadastros/$tipo?q=${URLEncoder.encode(q.orEmpty(), Charsets.UTF_8)}&pagina=${pagina + 1}"
        } else ""
        return PaginaCadastro(resultado.itens, proxima)
    }
}

/** Formato `Page` que o `app-search` do front lê; `nextLink` vazio = sem próxima página. */
data class PaginaCadastro(val content: List<ItemCadastro>, val nextLink: String = "")
