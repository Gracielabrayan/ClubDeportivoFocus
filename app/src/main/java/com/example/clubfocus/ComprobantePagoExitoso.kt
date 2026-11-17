package com.example.clubfocus

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.clubfocus.data.DocumentoFocusPdf
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.util.Locale

class ComprobantePagoExitoso : AppCompatActivity() {

    companion object {
        const val EXTRA_CUOTA_ID = "cuotaId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comprobante_pago_exitoso)

        val cuotaId = intent.getIntExtra(EXTRA_CUOTA_ID, -1)
        val pago = CuotasDBHelper(this).use { it.obtenerPagoParaComprobante(cuotaId) }
        if (pago == null) {
            Toast.makeText(this, R.string.comprobante_pago_no_encontrado, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val concepto = when (pago.tipo) {
            TipoCuota.MENSUAL -> R.string.comprobante_cuota_mensual
            TipoCuota.DIARIA -> R.string.comprobante_cuota_diaria
        }
        val medio = when (pago.medioPago) {
            MedioPago.EFECTIVO -> R.string.pago_efectivo
            MedioPago.TARJETA_CREDITO -> R.string.pago_tarjeta_credito
            MedioPago.TARJETA_DEBITO -> R.string.pago_tarjeta_debito
        }
        val detalle = when (pago.promocion) {
            Promocion.TRES_CUOTAS -> R.string.promo_3_cuotas
            Promocion.SEIS_CUOTAS -> R.string.promo_6_cuotas
            Promocion.SIN_PROMOCION, null -> R.string.comprobante_sin_promocion
        }

        val campos = listOf(
            R.id.txtFecha to getString(R.string.comprobante_fecha, pago.fechaPago),
            R.id.txtNombre to getString(R.string.comprobante_nombre, pago.nombreCompleto),
            R.id.txtDni to getString(R.string.comprobante_dni, pago.dni),
            R.id.txtConcepto to getString(R.string.comprobante_concepto, getString(concepto)),
            R.id.txtFormaPago to getString(R.string.comprobante_medio, getString(medio)),
            R.id.txtDetalle to getString(R.string.comprobante_detalle, getString(detalle))
        )
        val total = getString(R.string.monto_formato, String.format(Locale.getDefault(), "%.2f", pago.montoFinal))
        campos.forEach { (id, texto) -> findViewById<TextView>(id).text = texto }
        findViewById<TextView>(R.id.txtTotal).text = total
        findViewById<TextView>(R.id.txtVolver).setOnClickListener { finish() }

        val documento = DocumentoFocusPdf(
            R.string.comprobante_titulo,
            getString(R.string.comprobante_referencia, cuotaId),
            campos.map { it.second },
            total
        )
        configurarExportacionPdf(
            findViewById<Button>(R.id.btnOpcionesComprobantePdf),
            R.string.comprobante_pdf_opciones,
            "Comprobante-Focus-$cuotaId.pdf",
            "comprobantes"
        ) { documento }
    }
}
