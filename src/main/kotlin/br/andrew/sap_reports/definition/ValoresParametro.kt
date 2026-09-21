package br.andrew.sap_reports.definition

import br.andrew.sap_reports.odbc.paraDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Mesmas regras para valores padrão no upload e valores recebidos na execução. */
object ValoresParametro {
    val INVALIDO = Any()
    val tiposMultiplos = setOf("lista", "filial", "vendedor", "parceiro_negocio", "item", "localidade")

    fun converter(param: Parametro, valor: Any?): Any? {
        if (valor == null) return null
        if (param.multiplo) {
            if (param.tipo !in tiposMultiplos || valor !is List<*> || valor.isEmpty()) return INVALIDO
            val valores = valor.map { if (it == null) INVALIDO else converter(param.copy(multiplo = false), it) }
            return if (valores.any { it === INVALIDO }) INVALIDO else valores.distinct()
        }
        return when (param.tipo) {
            "filial", "vendedor" -> if (valor is Number) {
                valor.paraDecimal()?.takeIf { runCatching { it.intValueExact() }.isSuccess } ?: INVALIDO
            } else INVALIDO
            "parceiro_negocio", "item", "localidade" -> (valor as? String)?.takeIf { it.isNotBlank() } ?: INVALIDO
            "texto" -> valor as? String ?: INVALIDO
            "numero" -> if (valor is Number) valor.paraDecimal() ?: INVALIDO else INVALIDO
            "date" -> (valor as? String)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: INVALIDO
            "datetime" -> (valor as? String)?.takeIf {
                runCatching { LocalDateTime.parse(it) }.isSuccess || runCatching { OffsetDateTime.parse(it) }.isSuccess
            } ?: INVALIDO
            "booleano" -> valor as? Boolean ?: INVALIDO
            "lista" -> (valor as? String)?.takeIf { candidato -> param.opcoes.orEmpty().any { it.valor == candidato } } ?: INVALIDO
            else -> INVALIDO
        }
    }
}
