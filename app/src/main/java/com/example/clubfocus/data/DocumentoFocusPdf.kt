package com.example.clubfocus.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.example.clubfocus.R
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class DocumentoFocusPdf(
    private val titulo: Int,
    private val referencia: String,
    private val datos: List<String>,
    private val total: String? = null
) {
    fun crearTemporal(context: Context, subcarpeta: String): File {
        val carpeta = File(context.cacheDir, subcarpeta)
        if (!carpeta.isDirectory && !carpeta.mkdirs() && !carpeta.isDirectory) {
            throw IOException("No se pudo crear la carpeta de PDF")
        }

        val archivo = File.createTempFile("focus-", ".pdf", carpeta)
        try {
            archivo.outputStream().use { escribir(context, it) }
            return archivo
        } catch (error: IOException) {
            archivo.delete()
            throw error
        }
    }

    fun escribir(context: Context, salida: OutputStream) {
        val anchoContenido = ANCHO - MARGEN * 2
        val colorTexto = Color.rgb(31, 42, 55)
        val colorSecundario = Color.rgb(78, 90, 104)
        val colorAcento = Color.rgb(173, 65, 14)
        val texto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            color = colorTexto
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val filas = datos.map { dato ->
            StaticLayout.Builder.obtain(dato, 0, dato.length, texto, anchoContenido)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
        }
        val anchoPagina = if (total != null) ANCHO else 420
        val escala = anchoPagina.toFloat() / ANCHO
        val altoContenido = 190 + filas.sumOf { it.height + 17 } + (if (total != null) 82 else 0) + MARGEN
        val altoMinimo = if (total != null) 842 else 298
        val altoPagina = maxOf(altoMinimo, kotlin.math.ceil(altoContenido * escala).toInt())
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

        val documento = PdfDocument()
        try {
            val pagina = documento.startPage(PdfDocument.PageInfo.Builder(anchoPagina, altoPagina, 1).create())
            try {
                val canvas = pagina.canvas
                val x = MARGEN.toFloat()
                val derecha = (ANCHO - MARGEN).toFloat()
                canvas.drawColor(Color.WHITE)
                canvas.scale(escala, escala)

                ContextCompat.getDrawable(context, R.drawable.logo)?.apply {
                    setBounds(MARGEN, 48, MARGEN + 60, 108)
                    draw(canvas)
                }
                pincel.color = colorTexto
                pincel.textSize = 16f
                canvas.drawText(context.getString(R.string.app_name), x + 76, 72f, pincel)
                pincel.textSize = 25f
                canvas.drawText(context.getString(titulo), x + 76, 102f, pincel)

                pincel.color = colorSecundario
                pincel.textSize = 13f
                pincel.typeface = Typeface.DEFAULT
                canvas.drawText(referencia, x, 144f, pincel)
                pincel.color = colorAcento
                canvas.drawRect(x, 161f, derecha, 163f, pincel)

                var y = 190f
                filas.forEach { fila ->
                    canvas.save()
                    canvas.translate(x, y)
                    fila.draw(canvas)
                    canvas.restore()
                    y += fila.height + 17
                }

                if (total != null) {
                    pincel.color = Color.rgb(251, 241, 234)
                    canvas.drawRoundRect(x, y, derecha, y + 65, 8f, 8f, pincel)
                    pincel.color = colorAcento
                    pincel.typeface = Typeface.DEFAULT_BOLD
                    pincel.textSize = 15f
                    canvas.drawText(context.getString(R.string.comprobante_total_abonado), x + 16, y + 39, pincel)
                    pincel.textSize = 20f
                    pincel.textAlign = Paint.Align.RIGHT
                    canvas.drawText(total, derecha - 16, y + 39, pincel)
                }
            } finally {
                documento.finishPage(pagina)
            }
            documento.writeTo(salida)
        } finally {
            documento.close()
        }
    }

    companion object {
        const val MIME = "application/pdf"
        private const val ANCHO = 595
        private const val MARGEN = 48
    }
}
