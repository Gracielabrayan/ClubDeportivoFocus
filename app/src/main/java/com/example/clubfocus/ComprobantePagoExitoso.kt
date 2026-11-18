package com.example.clubfocus

import android.os.Bundle
import android.database.sqlite.SQLiteException
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.clubfocus.data.DocumentoFocusPdf
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.CuotasDBHelper
import com.example.clubfocus.helperDB.UsuariosDBHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ComprobantePagoExitoso : AppCompatActivity() {

    companion object {
        const val EXTRA_CUOTA_ID = "cuotaId"
        const val EXTRA_CLAVE_PAGO = "clavePago"
    }

    private val usuario: String
        get() = getSharedPreferences("ClubFocusPrefs", MODE_PRIVATE).getString("usuarioLogueado", "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comprobante_pago_exitoso)

        val cuotaId = intent.getIntExtra(EXTRA_CUOTA_ID, -1)
        val claveEsperada = intent.getStringExtra(EXTRA_CLAVE_PAGO)
        val pago = CuotasDBHelper(this).use { it.obtenerPagoParaComprobante(cuotaId, claveEsperada) }
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
        ) {
            CuotasDBHelper(applicationContext).use {
                it.obtenerPagoParaComprobante(cuotaId, pago.clavePago.orEmpty())
            }?.let { documento }
        }

        val anular = findViewById<Button>(R.id.btnAnularCobro)
        anular.isVisible = UsuariosDBHelper(this).use {
            it.obtenerRolPorUsuario(usuario).equals("SuperAdmin", ignoreCase = true)
        }
        anular.setOnClickListener { confirmarAnulacion(cuotaId, pago, anular) }
    }

    private fun confirmarAnulacion(cuotaId: Int, pago: CuotasDBHelper.PagoParaComprobante, boton: Button) {
        val motivo = EditText(this).apply {
            setHint(R.string.pago_anular_motivo)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(250))
        }
        val contenedor = FrameLayout(this).apply {
            val margen = (24 * resources.displayMetrics.density).toInt()
            setPadding(margen, 0, margen, 0)
            addView(motivo, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val mensaje = getString(R.string.pago_anular_confirmacion) + if (pago.vencimientoAnteriorConocido) ""
            else "\n\n" + getString(R.string.pago_anular_vencimiento_anterior)
        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.pago_anular)
            .setMessage(mensaje)
            .setView(contenedor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pago_anular_confirmar, null)
            .show()
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val razon = motivo.text.toString().trim()
            if (razon.isEmpty()) {
                motivo.error = getString(R.string.pago_anular_motivo)
                return@setOnClickListener
            }
            dialogo.dismiss()
            boton.isEnabled = false
            val usuarioActual = usuario
            lifecycleScope.launch {
                val anulado = withContext(Dispatchers.IO) {
                    try {
                        CuotasDBHelper(applicationContext).use {
                            it.anularPago(cuotaId, pago.clavePago, usuarioActual, razon)
                        }
                    } catch (_: SQLiteException) {
                        false
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
                val mensajeResultado = if (anulado) R.string.pago_anular_exito else R.string.pago_anular_error
                Toast.makeText(this@ComprobantePagoExitoso, mensajeResultado, Toast.LENGTH_LONG).show()
                if (anulado) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    boton.isEnabled = true
                }
            }
        }
    }
}
