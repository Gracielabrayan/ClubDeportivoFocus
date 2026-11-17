package com.example.clubfocus

import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComprobantePagoTest {
    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentacion.targetContext
    private val clientes = ClientesDBHelper(context)
    private val cuotas = CuotasDBHelper(context)
    private var clienteId = -1

    @After
    fun eliminarDatosDePrueba() {
        if (clienteId > 0) {
            val id = arrayOf(clienteId.toString())
            cuotas.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientes.writableDatabase.delete("clientes", "id = ?", id)
        }
        cuotas.close()
        clientes.close()
    }

    @Test
    fun conservaLosDatosDelPagoCuandoHayOtroCobroYCambiaLaCondicionDelCliente() {
        val dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
            .first { !clientes.existeCliente(it) }
        clienteId = clientes.agregarCliente(
            "Cliente", "De prueba", dni, "01/01/2000", "cliente@example.invalid", true, true
        ).toInt()
        assertTrue(clienteId > 0)

        val mensual = crearCuota(TipoCuota.MENSUAL, 45_000.0)
        assertEquals(
            1, cuotas.registrarPago(mensual, clienteId, LocalDate.of(2026, 10, 10), 38_250.0, MedioPago.TARJETA_CREDITO, Promocion.TRES_CUOTAS)
        )
        assertEquals(1, clientes.darDeBajaSocio(clienteId))

        val diaria = crearCuota(TipoCuota.DIARIA, 20_000.0)
        val fechaPagoDiario = LocalDate.of(2026, 11, 12)
        assertEquals(1, cuotas.registrarPago(diaria, clienteId, fechaPagoDiario, 20_000.0, MedioPago.EFECTIVO, null))
        assertEquals(0, cuotas.registrarPago(mensual, clienteId, fechaPagoDiario, 1.0, MedioPago.EFECTIVO, null))
        assertNull(cuotas.obtenerPagoParaComprobante(crearCuota(TipoCuota.DIARIA, 20_000.0)))

        val confirmacion = Intent(context, ConfirmarPagoActivity::class.java)
            .putExtra(ComprobantePagoExitoso.EXTRA_CUOTA_ID, mensual)
        ActivityScenario.launch<ConfirmarPagoActivity>(confirmacion).use { scenario ->
            val monitor = instrumentacion.addMonitor(ComprobantePagoExitoso::class.java.name, null, false)
            try {
                scenario.onActivity { it.findViewById<TextView>(R.id.btnImprimirComprobante).performClick() }
                val comprobante = requireNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 15_000))
                instrumentacion.runOnMainSync {
                    try {
                        assertEquals("Fecha: 10/10/2026", comprobante.findViewById<TextView>(R.id.txtFecha).text.toString())
                        assertEquals("DNI: $dni", comprobante.findViewById<TextView>(R.id.txtDni).text.toString())
                        assertEquals("En concepto de: Cuota mensual", comprobante.findViewById<TextView>(R.id.txtConcepto).text.toString())
                        assertEquals("Medio de pago: Tarjeta Crédito", comprobante.findViewById<TextView>(R.id.txtFormaPago).text.toString())
                        assertEquals("Detalle: 3 Cuotas 15% OFF", comprobante.findViewById<TextView>(R.id.txtDetalle).text.toString())
                        val total = String.format(Locale.getDefault(), "%.2f", 38_250.0)
                        assertEquals("$ $total", comprobante.findViewById<TextView>(R.id.txtTotal).text.toString())
                    } finally {
                        comprobante.finish()
                    }
                }
            } finally {
                instrumentacion.removeMonitor(monitor)
            }
        }
    }

    private fun crearCuota(tipo: TipoCuota, monto: Double): Int {
        val id = cuotas.generarCuotaInicial(clienteId, tipo, monto, monto, LocalDate.now())
        assertTrue(id > 0L)
        return id.toInt()
    }
}
