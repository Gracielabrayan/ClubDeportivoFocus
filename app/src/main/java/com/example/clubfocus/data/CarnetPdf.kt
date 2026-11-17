package com.example.clubfocus.data

import android.content.Context
import com.example.clubfocus.R
import com.example.clubfocus.data.entity.CarnetListado
import java.time.format.DateTimeFormatter

internal fun CarnetListado.documentoPdf(context: Context): DocumentoFocusPdf {
    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    return DocumentoFocusPdf(
        R.string.carnet_titulo,
        context.getString(R.string.carnet_pdf_numero, nroSocio),
        listOf(
            context.getString(R.string.carnet_pdf_socio, "$nombre $apellido".trim()),
            context.getString(R.string.carnet_pdf_emision, fechaEmision.format(formato)),
            context.getString(R.string.carnet_pdf_vencimiento, fechaVencimiento.format(formato))
        )
    )
}
