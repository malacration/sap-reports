package br.andrew.sap_reports.render

import br.andrew.sap_reports.odbc.ColumnMeta
import br.andrew.sap_reports.odbc.QueryResponse
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resultado da consulta gravado em disco (NDJSON, uma linha por registro) conforme chega
 * do sap-odbc, para ser relido quantas vezes for preciso sem ficar inteiro em memoria.
 *
 * Por que gravar em vez de renderizar direto do fluxo: o arquivo so e entregue depois que
 * o sap-odbc confirma o fim. Renderizando direto, uma queda no meio ja teria mandado o
 * 200 e metade do CSV ao usuario - e relatorio incompleto e pior que erro. Tambem da ao
 * PDF a segunda passada de que ele precisa (totais dos grupos antes de desenhar os blocos).
 *
 * Os valores relidos tem os mesmos tipos do caminho em memoria: os dois saem do mesmo
 * parse JSON do que o sap-odbc enviou.
 */
class ResultadoEmDisco(diretorio: Path) : AutoCloseable {
    val arquivo: Path = Files.createTempFile(diretorio, "linhas-", ".ndjson")
    private val escritor: BufferedWriter = Files.newBufferedWriter(arquivo, Charsets.UTF_8)
    private var fechado = false

    var colunas: List<ColumnMeta> = emptyList()
    var quantidade: Int = 0
        private set

    fun gravar(linha: Map<String, Any?>) {
        escritor.write(JSON.writeValueAsString(linha))
        escritor.write('\n'.code)
        quantidade++
    }

    /** Termina a gravacao. Idempotente. */
    override fun close() {
        if (!fechado) {
            fechado = true
            escritor.close()
        }
    }

    /** Relê as linhas em ordem, sem carregar o arquivo. Use dentro do bloco. */
    fun <T> ler(bloco: (Sequence<Map<String, Any?>>) -> T): T {
        close()
        return Files.newBufferedReader(arquivo, Charsets.UTF_8).useLines { linhas ->
            bloco(linhas.filter { it.isNotEmpty() }.map { lerLinha(it) })
        }
    }

    /** Para o caminho em memoria (resultado pequeno): mesmas linhas, como a resposta comum. */
    fun emMemoria(): QueryResponse {
        val linhas = ler { it.toList() }
        return QueryResponse(colunas, linhas, linhas.size, truncated = false, elapsedMs = 0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun lerLinha(texto: String) = JSON.readValue(texto, LinkedHashMap::class.java) as Map<String, Any?>

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
