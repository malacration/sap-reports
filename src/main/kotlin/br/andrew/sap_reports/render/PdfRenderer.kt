package br.andrew.sap_reports.render

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Path

@Component
class PdfRenderer {

    fun renderizar(html: String): ByteArray =
        ByteArrayOutputStream().also { renderizar(html, it) }.toByteArray()

    /** Grava o PDF direto em [saida] (arquivo temporario, no relatorio grande). */
    fun renderizar(html: String, saida: OutputStream) {
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
    }

    /**
     * Junta os PDFs dos blocos, na ordem, em [destino].
     *
     * Cache so em ARQUIVO temporario: o padrao do PDFBox guarda os documentos em memoria,
     * e juntar um relatorio de milhares de paginas voltaria a ter o estouro que os blocos
     * evitaram.
     */
    fun juntar(partes: List<Path>, destino: Path) {
        val juntador = PDFMergerUtility()
        partes.forEach { juntador.addSource(it.toFile()) }
        juntador.destinationFileName = destino.toString()
        juntador.mergeDocuments(IOUtils.createTempFileOnlyStreamCache())
    }
}
