package br.andrew.sap_reports.web

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Monta o pacote de instrucoes para IA, servido pelo proprio servico.
 *
 * E o backend que serve, e nao um asset do front, para o conteudo acompanhar a
 * versao da API IMPLANTADA. Documentacao de API que mora noutro projeto
 * diverge - e documentacao de API errada faz a IA gerar chamada errada.
 *
 * O pacote tem duas pastas porque as duas ferramentas funcionam de forma
 * diferente:
 *  - `claude/` e uma skill de verdade (SKILL.md com frontmatter);
 *  - `chatgpt/` traz Instructions + OpenAPI para um GPT personalizado com Action.
 */
@Service
class SkillPackageService {

    private val resolver = PathMatchingResourcePatternResolver()

    fun montarZip(urlPublica: String): ByteArray {
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { zip ->
            resolver.getResources("classpath:skill/**").forEach { recurso ->
                if (!recurso.isReadable) return@forEach
                val uri = recurso.uri.toString()
                val caminho = uri.substringAfter("skill/", "").ifEmpty { return@forEach }
                if (caminho.endsWith("/")) return@forEach

                val conteudo = when (caminho) {
                    // O contrato canonico nao serve ao ChatGPT como esta: falta
                    // operationId em toda operacao, que o Actions exige.
                    "openapi.yaml" -> return@forEach
                    else -> recurso.inputStream.readBytes()
                }
                zip.putNextEntry(ZipEntry(caminho))
                zip.write(conteudo)
                zip.closeEntry()
            }

            // Gerado a partir do openapi.yaml em tempo de download: assim nunca
            // diverge do contrato real.
            zip.putNextEntry(ZipEntry("chatgpt/openapi-actions.yaml"))
            zip.write(openApiParaActions(urlPublica).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("LEIA-ME.md"))
            zip.write(leiaMe().toByteArray())
            zip.closeEntry()
        }
        return saida.toByteArray()
    }

    /**
     * Adapta o contrato para o ChatGPT Actions:
     *  - acrescenta `operationId` em cada operacao (obrigatorio no Actions; sem
     *    ele a importacao falha ou gera nomes inutilizaveis);
     *  - fixa um unico `servers` com a URL publica;
     *  - troca o esquema de seguranca para Bearer, que e o token de servico.
     */
    fun openApiParaActions(urlPublica: String): String {
        val original = resolver.getResource("classpath:skill/openapi.yaml")
            .inputStream.bufferedReader().readText()

        val linhas = original.lines().toMutableList()
        var rotaAtual = ""
        val saida = StringBuilder()
        var i = 0
        while (i < linhas.size) {
            val linha = linhas[i]
            Regex("^  (/[^:]+):\\s*$").find(linha)?.let { rotaAtual = it.groupValues[1] }

            saida.appendLine(linha)
            Regex("^    (get|post|put|delete):\\s*$").find(linha)?.let { m ->
                val metodo = m.groupValues[1]
                saida.appendLine("      operationId: ${operationId(metodo, rotaAtual)}")
            }
            i++
        }

        var texto = saida.toString()
        texto = texto.replace(
            Regex("^servers:\\n(  - .*\\n|    .*\\n)+", RegexOption.MULTILINE),
            "servers:\n  - url: $urlPublica\n",
        )
        texto = texto.replace(
            Regex("^    jwtSemBearer:\\n(      .*\\n)+", RegexOption.MULTILINE),
            "    jwtSemBearer:\n      type: http\n      scheme: bearer\n" +
                "      description: Token de servico (rpt_...) gerado na tela de relatorios.\n",
        )
        return texto
    }

    /** `POST /api/v1/admin/relatorios/{id}/preview` -> `postAdminRelatoriosIdPreview`. */
    private fun operationId(metodo: String, rota: String): String {
        val partes = rota.trim('/').split('/')
            .filter { it !in listOf("api", "v1") }
            .map { it.trim('{', '}') }
            .filter { it.isNotBlank() }
            .map { parte -> parte.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) } }
        return metodo + partes.joinToString("")
    }

    private fun leiaMe() = """
        # Instruções para IA — sap-reports

        Duas pastas, porque as ferramentas funcionam de forma diferente:

        ## claude/
        Skill do Claude Code. Instale com:

            ln -s <caminho>/claude ~/.claude/skills/report-skill

        Roda na sua máquina e alcança a rede interna — não exige expor a API.

        ## chatgpt/
        GPT personalizado com Action. Siga `chatgpt/COMO-CONFIGURAR.md`.

        Exige que o `sap-reports` esteja acessível pela internet, porque o ChatGPT
        roda na nuvem da OpenAI. Leia a seção de infraestrutura antes de decidir.

        ## Token

        Os dois usam um token de serviço gerado na tela de relatórios. Ele cria
        rascunho, valida e pré-visualiza — **não publica**. Publicar decide quem
        enxerga faturamento, e continua sendo decisão de uma pessoa.
    """.trimIndent()
}
