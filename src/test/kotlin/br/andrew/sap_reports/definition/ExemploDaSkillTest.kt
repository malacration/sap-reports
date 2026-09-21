package br.andrew.sap_reports.definition

import br.andrew.sap_reports.config.ReportProperties
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * O exemplo publicado em report-skill/exemplos/ e o que a IA vai copiar como
 * ponto de partida. Se ele nao passar no validador, a skill ensina a escrever
 * relatorio invalido - e o erro se multiplica por todo relatorio gerado.
 *
 * Este teste falha de proposito se alguem editar o exemplo e quebra-lo.
 */
class ExemploDaSkillTest {

    @Test
    fun `exemplo de cadastros e copia distribuida sao validos e sincronizados`() {
        for (nome in listOf("vendas-por-cliente", "vendas-por-cadastro", "vendas-agrupadas-por-vendedor", "vendas-por-filial-e-vendedor")) {
            val yaml = File(skill, "$nome.yaml").readText()
            val hbs = File(skill, "$nome.hbs").readText()
            for ((ext, texto) in listOf("yaml" to yaml, "hbs" to hbs)) {
                val empacotado = javaClass.getResource("/skill/claude/exemplos/$nome.$ext")!!.readText()
                kotlin.test.assertEquals(texto, empacotado)
            }
            val resultado = validator.validar(yaml, hbs)
            assertTrue(resultado.valido, resultado.problemas.toString())
        }
    }

    private val skill = File("../report-skill/exemplos")

    private val validator = ReportUploadValidator(
        ReportProperties(allowedTables = listOf("SBOGRUPOROVEMA.*")),
    )

    @Test
    fun `o exemplo da skill passa no validador`() {
        val yaml = File(skill, "vendas-por-cliente.yaml")
        val hbs = File(skill, "vendas-por-cliente.hbs")
        assertTrue(yaml.exists(), "exemplo nao encontrado em ${yaml.absolutePath}")
        assertTrue(hbs.exists(), "template nao encontrado em ${hbs.absolutePath}")

        val r = validator.validar(yaml.readText(), hbs.readText())
        assertTrue(
            r.valido,
            "o exemplo da skill foi RECUSADO: " +
                r.problemas.joinToString("; ") { "${it.caminho}=${it.mensagem}" },
        )
    }

    @Test
    fun `o exemplo declara as tabelas que realmente usa`() {
        val r = validator.validar(
            File(skill, "vendas-por-cliente.yaml").readText(),
            File(skill, "vendas-por-cliente.hbs").readText(),
        )
        assertTrue(r.tabelas.any { it.uppercase().contains("OINV") }, "tabelas: ${r.tabelas}")
    }
}
