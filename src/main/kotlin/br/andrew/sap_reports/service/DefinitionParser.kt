package br.andrew.sap_reports.service

import br.andrew.sap_reports.definition.ReportDefinition
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

@Component
class DefinitionParser {
    private val mapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun ler(yaml: String): ReportDefinition = mapper.readValue(yaml, ReportDefinition::class.java)
}
