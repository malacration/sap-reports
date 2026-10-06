package br.andrew.sap_reports.definition

import br.andrew.sap_reports.config.ReportProperties
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O exemplo publicado em skill/claude/exemplos/ e o que a IA vai copiar como
 * ponto de partida. Se ele nao passar no validador, a skill ensina a escrever
 * relatorio invalido - e o erro se multiplica por todo relatorio gerado.
 *
 * Este teste falha de proposito se alguem editar o exemplo e quebra-lo.
 *
 * A fonte e a copia empacotada no classpath (versionada neste repo), pra rodar
 * em qualquer ambiente - CI/nuvem inclusive. A pasta ../report-skill fica fora
 * do projeto e so existe na maquina de quem edita a skill; quando ela esta
 * presente, um teste a parte confere que as duas copias nao divergiram.
 */
class ExemploDaSkillTest {

    private val exemplos = listOf(
        "vendas-por-cliente",
        "vendas-por-cadastro",
        "vendas-agrupadas-por-vendedor",
        "vendas-por-filial-e-vendedor",
    )

    private val skillExterna = File("../report-skill/exemplos")

    private val validator = ReportUploadValidator(
        ReportProperties(allowedTables = listOf("SBOGRUPOROVEMA.*")),
    )

    private fun empacotado(nome: String): String =
        requireNotNull(javaClass.getResource("/skill/claude/exemplos/$nome")) {
            "exemplo nao encontrado no classpath: /skill/claude/exemplos/$nome"
        }.readText()

    @Test
    fun `todos os exemplos da skill passam no validador`() {
        for (nome in exemplos) {
            val resultado = validator.validar(empacotado("$nome.yaml"), empacotado("$nome.hbs"))
            assertTrue(
                resultado.valido,
                "o exemplo $nome foi RECUSADO: " +
                    resultado.problemas.joinToString("; ") { "${it.caminho}=${it.mensagem}" },
            )
        }
    }

    @Test
    fun `o exemplo declara as tabelas que realmente usa`() {
        val r = validator.validar(empacotado("vendas-por-cliente.yaml"), empacotado("vendas-por-cliente.hbs"))
        assertTrue(r.tabelas.any { it.uppercase().contains("OINV") }, "tabelas: ${r.tabelas}")
    }

    @Test
    fun `copia distribuida esta sincronizada com report-skill quando a pasta existe`() {
        assumeTrue(skillExterna.isDirectory, "../report-skill ausente - sincronia so e conferida localmente")
        for (nome in exemplos) {
            for (ext in listOf("yaml", "hbs")) {
                assertEquals(
                    File(skillExterna, "$nome.$ext").readText(),
                    empacotado("$nome.$ext"),
                    "$nome.$ext diverge entre ../report-skill/exemplos e src/main/resources/skill/claude/exemplos",
                )
            }
        }
    }
}
