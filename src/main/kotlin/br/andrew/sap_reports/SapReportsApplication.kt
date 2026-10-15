package br.andrew.sap_reports

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.execucao.ExecucaoProperties
import br.andrew.sap_reports.security.KeycloakProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    ReportProperties::class,
    ExecucaoProperties::class,
    OdbcProperties::class,
    KeycloakProperties::class,
)
class SapReportsApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<SapReportsApplication>(*args)
}
