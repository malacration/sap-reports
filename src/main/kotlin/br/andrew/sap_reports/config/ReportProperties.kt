package br.andrew.sap_reports.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Limites e politica de seguranca aplicados a todo relatorio enviado.
 *
 * Como os relatorios chegam por formulario em runtime, sem revisao no Git, estes
 * valores sao parte da barreira de seguranca - nao apenas ajuste de performance.
 */
@ConfigurationProperties(prefix = "reports")
data class ReportProperties(
    /** Tamanho maximo, em caracteres, do template Handlebars enviado. */
    val maxTemplateLength: Int = 200_000,
    /** Tamanho maximo, em caracteres, do YAML de definicao. */
    val maxDefinitionLength: Int = 50_000,
    /** Teto de linhas que um relatorio pode pedir ao sap-odbc. */
    val maxRows: Int = 10_000,
    /**
     * Teto de linhas de PDF/CSV quando o relatorio nao declara `consulta.maxRows`. Esses
     * formatos vem do sap-odbc em fluxo e sao montados em disco, entao nao ficam presos ao
     * [maxRows] (que continua valendo para HTML, que o navegador precisa carregar inteiro).
     * Nao passa do `query.stream-max-rows-limit` do sap-odbc.
     */
    val maxRowsArquivo: Int = 500_000,
    /**
     * Ate quantas linhas PDF/CSV seguem o caminho em memoria de sempre. Igual ao antigo teto
     * de proposito: tudo o que ja funcionava continua identico, inclusive agrupamento sem
     * ORDER BY. Acima disso, o arquivo e montado em disco e o agrupamento exige a consulta
     * ordenada pelas colunas agrupadas.
     */
    val linhasEmMemoria: Int = 10_000,
    /** Linhas por bloco do PDF grande: cada bloco e um PDF parcial, depois juntados. */
    val linhasPorBloco: Int = 2_000,
    /** Onde ficam os arquivos temporarios dos relatorios grandes. Vazio = temp do sistema. */
    val diretorioTemporario: String? = null,
    /** Tempo maximo de renderizacao, em segundos. */
    val renderTimeoutSeconds: Int = 120,
    /**
     * Tabelas que o SQL de um relatorio pode consultar, qualificadas com schema.
     * Aceita sufixo `*` (ex.: `SBOGRUPOROVEMA.O*`).
     *
     * Vazio = qualquer tabela que o sap-odbc aceitar. Em producao, preencha:
     * e o que impede um relatorio enviado de despejar uma tabela inteira.
     */
    val allowedTables: List<String> = emptyList(),
    /** Teto de validade, em dias, de um token de servico para IA. */
    val tokenMaxDias: Int = 90,
    /** Papel exigido para enviar, publicar e remover relatorios. */
    val authorRole: String = "admin",
)
