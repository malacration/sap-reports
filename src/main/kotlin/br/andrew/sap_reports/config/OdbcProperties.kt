package br.andrew.sap_reports.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Acesso ao sap-odbc, o unico caminho deste servico ate os dados de negocio. */
@ConfigurationProperties(prefix = "odbc")
data class OdbcProperties(
    val baseUrl: String = "http://localhost:8080",
    /** Enviada no header `X-API-Key`. */
    val apiKey: String? = null,
    /** Timeout de conexao, em segundos. */
    val connectTimeoutSeconds: Int = 10,
    /**
     * Timeout de leitura. Deve ser MAIOR que o `timeoutSeconds` enviado na consulta,
     * senao o cliente desiste antes de o sap-odbc responder o proprio timeout - e o
     * erro chega como falha de rede em vez de 504.
     */
    val readTimeoutSeconds: Int = 150,
    /** Timeout padrao pedido ao sap-odbc para cada consulta. */
    val queryTimeoutSeconds: Int = 120,
)
