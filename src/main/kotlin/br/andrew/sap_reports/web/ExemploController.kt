package br.andrew.sap_reports.web

import br.andrew.sap_reports.service.DefinitionParser
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ExemploDto(
    /** Nome base do arquivo (`vendas-por-cliente`); identifica o exemplo, nao um relatorio. */
    val id: String,
    val nome: String,
    val descricao: String?,
    val definicao: String,
    val template: String,
)

/**
 * Exemplos para a tela de autoria carregar no editor.
 *
 * A fonte e a mesma pasta de exemplos da skill de IA (`skill/claude/exemplos`):
 * um exemplo novo aparece nos dois lugares, e o `ExemploDaSkillTest` garante que
 * todos passam no validador - exemplo que nao salva seria pior que nenhum.
 */
@RestController
@RequestMapping("/api/v1/admin/exemplos")
class ExemploController(private val parser: DefinitionParser) {

    private val exemplos: List<ExemploDto> by lazy { carregar() }

    @GetMapping
    fun listar(): List<ExemploDto> = exemplos

    private fun carregar(): List<ExemploDto> {
        val resolver = PathMatchingResourcePatternResolver()
        return resolver.getResources("classpath:skill/claude/exemplos/*.yaml")
            .mapNotNull { yaml ->
                val base = yaml.filename?.removeSuffix(".yaml") ?: return@mapNotNull null
                val hbs = yaml.createRelative("$base.hbs")
                if (!hbs.exists()) return@mapNotNull null
                val definicao = yaml.inputStream.use { it.readBytes().decodeToString() }
                val def = parser.ler(definicao)
                ExemploDto(
                    id = base,
                    nome = def.nome,
                    descricao = def.descricao,
                    definicao = definicao,
                    template = hbs.inputStream.use { it.readBytes().decodeToString() },
                )
            }
            .sortedBy { it.nome }
    }
}
