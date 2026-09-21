package br.andrew.sap_reports.storage

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.definition.Consulta
import br.andrew.sap_reports.definition.ReportDefinition
import br.andrew.sap_reports.repositorioDeTeste
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportRepositoryTest {
    private val def = ReportDefinition(
        nome = "Teste",
        papeis = listOf("admin"),
        consulta = Consulta("SELECT 1 AS VALOR"),
        colunas = listOf(Coluna("VALOR", "Valor")),
    )

    @Test
    fun `versoes sao imutaveis e publicar e rollback movem somente o ponteiro`() {
        val (repository, _) = repositorioDeTeste()
        val id = repository.criar(def, "fonte-v1", "template-v1", "ana").id
        repository.novaVersao(id, def.copy(nome = "Teste v2"), "fonte-v2", "template-v2", "bia")

        assertEquals("fonte-v1", repository.buscarVersao(id, 1)!!.definicao)
        assertEquals("template-v1", repository.buscarVersao(id, 1)!!.template)
        assertEquals("fonte-v2", repository.buscarVersao(id, 2)!!.definicao)

        repository.publicar(id, 2, "bia")
        assertEquals(2, repository.buscar(id)!!.versaoPublicada)
        repository.rollback(id, 1, "carlos")
        assertEquals(1, repository.buscar(id)!!.versaoPublicada)

        assertEquals(
            listOf("CRIOU", "NOVA_VERSAO", "PUBLICOU", "ROLLBACK"),
            repository.listarAuditoria(id).reversed().map { it.acao.name },
        )
    }

    @Test
    fun `id e gerado pelo banco e nome pode repetir`() {
        val (repository, _) = repositorioDeTeste()
        val primeiro = repository.criar(def, "fonte", "template", "ana")
        val segundo = repository.criar(def, "fonte", "template", "ana")

        assertTrue(segundo.id > primeiro.id)
        assertEquals(primeiro.nome, segundo.nome)
    }

    @Test
    fun `remocao tambem grava auditoria`() {
        val (repository, jdbc) = repositorioDeTeste()
        val id = repository.criar(def, "fonte", "template", "ana").id
        repository.remover(id, "ana")

        assertEquals(null, repository.buscar(id))
        assertEquals("REMOVEU", jdbc.queryForObject(
            "SELECT ACAO FROM RELATORIO_AUDITORIA ORDER BY ID DESC LIMIT 1",
            String::class.java,
        ))
    }
}
