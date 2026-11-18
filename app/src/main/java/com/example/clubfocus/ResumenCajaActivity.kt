package com.example.clubfocus

import android.app.DatePickerDialog
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clubfocus.adapter.HistorialPagoAdapter
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResumenCajaActivity : AppCompatActivity() {
    private val formatoFecha = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val formatoMonto = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private lateinit var fecha: LocalDate
    private lateinit var btnFecha: Button
    private lateinit var detalle: TextView
    private lateinit var total: TextView
    private lateinit var cantidad: TextView
    private lateinit var adapter: HistorialPagoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resumen_caja)
        fecha = savedInstanceState?.getString("fechaCaja")?.let(LocalDate::parse) ?: LocalDate.now()

        btnFecha = findViewById(R.id.btnFechaCaja)
        detalle = findViewById(R.id.txtDetalleCaja)
        total = findViewById(R.id.txtTotalCaja)
        cantidad = findViewById(R.id.txtCantidadCaja)
        findViewById<TextView>(R.id.txtVolverCaja).setOnClickListener { finish() }

        adapter = HistorialPagoAdapter(emptyList()) { pago ->
            startActivity(Intent(this, ComprobantePagoExitoso::class.java).apply {
                putExtra(ComprobantePagoExitoso.EXTRA_CUOTA_ID, pago.cuotaId)
                putExtra(ComprobantePagoExitoso.EXTRA_CLAVE_PAGO, pago.clavePago.orEmpty())
            })
        }
        findViewById<RecyclerView>(R.id.rvPagosCaja).apply {
            layoutManager = LinearLayoutManager(this@ResumenCajaActivity)
            adapter = this@ResumenCajaActivity.adapter
        }

        btnFecha.setOnClickListener {
            DatePickerDialog(this, { _, anio, mes, dia ->
                fecha = LocalDate.of(anio, mes + 1, dia)
                cargarResumen()
            }, fecha.year, fecha.monthValue - 1, fecha.dayOfMonth).show()
        }
    }

    override fun onResume() {
        super.onResume()
        cargarResumen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("fechaCaja", fecha.toString())
        super.onSaveInstanceState(outState)
    }

    private fun cargarResumen() {
        if (!btnFecha.isEnabled) return
        val dia = fecha
        btnFecha.text = getString(R.string.caja_fecha, dia.format(formatoFecha))
        btnFecha.isEnabled = false
        detalle.visibility = View.GONE
        total.visibility = View.GONE
        cantidad.visibility = View.GONE
        adapter.actualizarLista(emptyList())

        lifecycleScope.launch {
            try {
                val resumen = withContext(Dispatchers.IO) {
                    val usuario = getSharedPreferences("ClubFocusPrefs", MODE_PRIVATE)
                        .getString("usuarioLogueado", "").orEmpty()
                    CuotasDBHelper(applicationContext).use { it.obtenerResumenCaja(usuario, dia) }
                }
                if (resumen == null) {
                    Toast.makeText(this@ResumenCajaActivity, R.string.admin_panel_solo_superadmin, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                detalle.text = buildList {
                    add(getString(R.string.caja_efectivo, importe(resumen.subtotal(MedioPago.EFECTIVO))))
                    add(getString(R.string.caja_debito, importe(resumen.subtotal(MedioPago.TARJETA_DEBITO))))
                    add(getString(R.string.caja_credito, importe(resumen.subtotal(MedioPago.TARJETA_CREDITO))))
                    if (resumen.hayPagosSinMedio) add(getString(R.string.caja_sin_medio, importe(resumen.subtotal(null))))
                }.joinToString("\n")
                total.text = getString(R.string.caja_total, importe(resumen.total))
                val cobros = resumen.pagos.size
                cantidad.text = resources.getQuantityString(R.plurals.caja_cobros, cobros, cobros)
                detalle.visibility = View.VISIBLE
                total.visibility = View.VISIBLE
                cantidad.visibility = View.VISIBLE
                adapter.actualizarLista(resumen.pagos)
            } catch (_: SQLiteException) {
                Toast.makeText(this@ResumenCajaActivity, R.string.caja_error, Toast.LENGTH_SHORT).show()
            } finally {
                if (!isFinishing) btnFecha.isEnabled = true
            }
        }
    }

    private fun importe(valor: BigDecimal): String = getString(R.string.monto_formato, formatoMonto.format(valor))
}
