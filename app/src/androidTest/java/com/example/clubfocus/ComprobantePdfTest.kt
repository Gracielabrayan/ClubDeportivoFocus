package com.example.clubfocus

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.DocumentoFocusPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComprobantePdfTest {
    @Test
    fun generaUnPdfLegibleYCompartibleConElNombreDeLaCuota() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cuotaId = 42
        val datos = listOf(
            "Fecha: 10/10/2026",
            "A nombre de: Cliente de prueba con un nombre largo para verificar el salto a una segunda línea",
            "DNI: 10000001",
            "En concepto de: Cuota mensual",
            "Medio de pago: Tarjeta Crédito",
            "Detalle: 3 Cuotas 15% OFF"
        )
        val documento = DocumentoFocusPdf(
            R.string.comprobante_titulo,
            context.getString(R.string.comprobante_referencia, cuotaId),
            datos,
            "$ 38.250,00"
        )
        val archivo = documento.crearTemporal(context, "comprobantes")
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.comprobantes", archivo, "Comprobante-Focus-$cuotaId.pdf"
            )
            val resolver = context.contentResolver
            assertEquals(DocumentoFocusPdf.MIME, resolver.getType(uri))
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Comprobante-Focus-42.pdf", cursor.getString(0))
            } ?: error("No se pudo consultar el nombre del comprobante")

            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r"))
            PdfRenderer(descriptor).use { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.openPage(0).use { pagina ->
                    assertEquals(595, pagina.width)
                    assertEquals(842, pagina.height)
                    val imagen = Bitmap.createBitmap(pagina.width, pagina.height, Bitmap.Config.ARGB_8888)
                    try {
                        pagina.render(imagen, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertEquals(Color.WHITE, imagen.getPixel(20, 20))
                        assertTrue(imagen.getPixel(100, 162) != Color.WHITE)
                    } finally {
                        imagen.recycle()
                    }
                }
            }
        } finally {
            if (InstrumentationRegistry.getArguments().getString("conservarPdfParaRevision") != "true") {
                archivo.delete()
            }
        }
    }
}
