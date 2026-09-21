package br.andrew.sap_reports.security

import java.security.Principal

data class UsuarioAutenticado(
    val usuario: String,
    val sapId: String,
    val papeis: Set<String>,
    val filiais: Set<Int>,
    /**
     * true quando a identidade veio de um TOKEN DE SERVICO (agente de IA), e nao
     * de um login humano no Keycloak.
     *
     * Token de servico faz autoria - validar, criar rascunho, pre-visualizar -
     * mas **nao publica**. Publicar decide quem passa a enxergar faturamento, e
     * essa e uma decisao de negocio que fica com uma pessoa.
     */
    val tokenDeServico: Boolean = false,
) : Principal {
    override fun getName(): String = usuario
}
