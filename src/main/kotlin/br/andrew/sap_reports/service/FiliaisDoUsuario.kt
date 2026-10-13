package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.odbc.paraDecimal
import br.andrew.sap_reports.security.UsuarioAutenticado
import org.springframework.stereotype.Component

/**
 * Filiais que o usuario pode consultar, lidas do CADASTRO dele no SAP.
 *
 * E a mesma regra do `/branch` do sap-service (BranchController), que preenche o
 * seletor de filial do front. As duas precisam andar juntas: se divergirem, o
 * seletor oferece uma filial que o relatorio corta.
 *  - `admin` e `vendedor_admin` veem todas (`User.superVendedor()` no sap-service);
 *  - colaborador ativo com vendedor vinculado (`OHEM.salesPrson`) opera como o
 *    vendedor: filiais de `OSLP.U_filial` -> `@RO_FILIAL_LINHA`;
 *  - os demais: filiais do colaborador, em HEM10.
 *
 * Os papeis `filial-N` do Keycloak ([UsuarioAutenticado.filiais]) NAO entram: o
 * seletor nao os usa.
 */
@Component
class FiliaisDoUsuario(
    private val odbc: OdbcClient,
    private val properties: ReportProperties,
) {
    /** `null` = todas as filiais; conjunto vazio = nenhuma. */
    fun permitidas(usuario: UsuarioAutenticado): Set<Int>? {
        if (usuario.papeis.any { it in PAPEIS_TODAS_AS_FILIAIS }) return null
        // sap_code e o empID do colaborador; token de servico nao tem cadastro.
        val colaborador = usuario.sapId.toIntOrNull() ?: return emptySet()
        return odbc.consultar(sql(), mapOf("colaborador" to colaborador), LIMITE).rows
            .mapNotNull { it["BPLID"].paraDecimal()?.toInt() }
            .toSet()
    }

    internal fun sql(): String {
        val prefixo = properties.schemaCadastro?.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
        val vendedor = """COALESCE(E."Active", 'N') = 'Y' AND COALESCE(E."salesPrson", 0) > 0"""
        return """
            SELECT B."BPLId" AS "BPLID"
            FROM ${prefixo}OHEM E
            JOIN ${prefixo}OSLP S ON S."SlpCode" = E."salesPrson"
            JOIN ${prefixo}"@RO_FILIAL_LINHA" R ON R."DocEntry" = S."U_filial"
            JOIN ${prefixo}OBPL B ON B."BPLId" = R."U_filial"
            WHERE E."empID" = :colaborador AND $vendedor
            UNION
            SELECT B."BPLId" AS "BPLID"
            FROM ${prefixo}OHEM E
            JOIN ${prefixo}HEM10 H ON H."empID" = E."empID"
            JOIN ${prefixo}OBPL B ON B."BPLId" = H."BPLId"
            WHERE E."empID" = :colaborador AND NOT ($vendedor)
        """.trimIndent()
    }

    companion object {
        val PAPEIS_TODAS_AS_FILIAIS = setOf("admin", "vendedor_admin")
        private const val LIMITE = 1_000
    }
}
