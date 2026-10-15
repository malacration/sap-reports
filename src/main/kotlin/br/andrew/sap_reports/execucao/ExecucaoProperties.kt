package br.andrew.sap_reports.execucao

import org.springframework.boot.context.properties.ConfigurationProperties

/** Execucao assincrona dos relatorios: fila e estado no Redis, arquivo em disco. */
@ConfigurationProperties(prefix = "reports.execucao")
data class ExecucaoProperties(
    /**
     * Onde ficam os arquivos prontos. Vazio = `<temp do sistema>/sap-reports-execucoes`.
     * Em conteiner, aponte para um volume: sem ele o arquivo some ao recriar o conteiner.
     */
    val diretorio: String = "",
    /**
     * Execucoes simultaneas. Cada uma segura uma consulta no sap-odbc: nao passe do
     * `query.max-concurrent-streams` de la. Zero desliga o consumo da fila (testes).
     */
    val workers: Int = 2,
    /** Por quanto tempo o arquivo pronto fica disponivel. */
    val retencaoHoras: Long = 24,
    /**
     * Por quanto tempo a execucao continua na lista do usuario. Maior que a retencao
     * de proposito: depois que o arquivo e apagado a tela ainda mostra "excluido" e
     * oferece gerar de novo, com os mesmos parametros.
     */
    val historicoDias: Long = 7,
    /** Execucoes na fila ou rodando por usuario. Evita que um so usuario lote a fila. */
    val maxAtivasPorUsuario: Int = 5,
    /** Prefixo das chaves no Redis, para dividir uma instancia com outros servicos. */
    val prefixoRedis: String = "sap-reports",
)
