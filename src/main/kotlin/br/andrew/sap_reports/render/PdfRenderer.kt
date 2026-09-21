package br.andrew.sap_reports.render

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class PdfRenderer {

    fun renderizar(html: String): ByteArray {
        val saida = ByteArrayOutputStream()
        PdfRendererBuilder()
            .withHtmlContent(html, null)
            .useUriResolver { _, uri ->
                uri.takeIf { it.startsWith("data:", ignoreCase = true) }
            }
            // Primeira barreira no proprio motor: nenhum URI externo chega a ser
            // resolvido. data: continua aceito por ser conteudo ja embutido.
            .useExternalResourceAccessControl(
                { uri, _ -> uri.startsWith("data:", ignoreCase = true) },
                ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI,
            )
            // Segunda verificacao cobre URIs que o motor eventualmente normalize.
            .useExternalResourceAccessControl(
                { uri, _ -> uri.startsWith("data:", ignoreCase = true) },
                ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI,
            )
            .useFastMode()
            .toStream(saida)
            .run()
        return saida.toByteArray()
    }
}
