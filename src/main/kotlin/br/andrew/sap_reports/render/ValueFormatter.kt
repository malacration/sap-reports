package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.Coluna
import br.andrew.sap_reports.odbc.paraDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ValueFormatter {
    private val locale = Locale.forLanguageTag("pt-BR")
    private val data = DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)
    private val dataHora = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", locale)

    fun moeda(valor: Any?): String {
        val decimal = valor.paraDecimal() ?: return valor?.toString().orEmpty()
        return NumberFormat.getCurrencyInstance(locale).apply {
            roundingMode = RoundingMode.HALF_UP
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(decimal)
    }

    fun numero(valor: Any?): String {
        val decimal = valor.paraDecimal() ?: return valor?.toString().orEmpty()
        return NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            maximumFractionDigits = 20
            roundingMode = RoundingMode.HALF_UP
        }.format(decimal)
    }

    fun percentual(valor: Any?): String {
        val decimal = valor.paraDecimal() ?: return valor?.toString().orEmpty()
        return NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }.format(decimal)
    }

    fun data(valor: Any?): String = when (valor) {
        null -> ""
        is LocalDate -> valor.format(data)
        is LocalDateTime -> valor.toLocalDate().format(data)
        is java.sql.Date -> valor.toLocalDate().format(data)
        is java.util.Date -> valor.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(data)
        else -> parseData(valor.toString()) ?: valor.toString()
    }

    fun dataHora(valor: Any?): String = when (valor) {
        null -> ""
        is LocalDateTime -> valor.format(dataHora)
        is OffsetDateTime -> valor.toLocalDateTime().format(dataHora)
        is Instant -> LocalDateTime.ofInstant(valor, ZoneId.systemDefault()).format(dataHora)
        is java.util.Date -> LocalDateTime.ofInstant(valor.toInstant(), ZoneId.systemDefault()).format(dataHora)
        else -> parseDataHora(valor.toString()) ?: valor.toString()
    }

    fun porColuna(valor: Any?, coluna: Coluna): String = when (coluna.tipo) {
        "numero" -> numero(valor)
        "moeda" -> moeda(valor)
        "data" -> data(valor)
        "datahora" -> dataHora(valor)
        "percentual" -> percentual(valor)
        else -> valor?.toString().orEmpty()
    }

    private fun parseData(valor: String): String? = runCatching {
        LocalDate.parse(valor.take(10), DateTimeFormatter.ISO_LOCAL_DATE).format(data)
    }.getOrNull()

    private fun parseDataHora(valor: String): String? =
        runCatching { OffsetDateTime.parse(valor).toLocalDateTime().format(dataHora) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(valor).format(dataHora) }.getOrNull()
}
