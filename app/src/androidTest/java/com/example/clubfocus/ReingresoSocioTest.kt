package com.example.clubfocus

import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.CarnetDBHelper
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReingresoSocioTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientes = ClientesDBHelper(context)
    private val cuotas = CuotasDBHelper(context)
    private val carnets = CarnetDBHelper(context)
    private val hoy = LocalDate.now()
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private var socioId = -1
    private lateinit var dni: String
    private lateinit var nroSocio: String

    @Before
    fun crearSocio() {
        dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
            .first { !clientes.existeCliente(it) }
        socioId = clientes.agregarCliente(
            "Cliente", "De prueba", dni, "01/01/2000", "cliente@example.invalid", true, true
        ).toInt()
        assertTrue(socioId > 0)
        nroSocio = requireNotNull(clientes.buscarPorId(socioId)).nroSocio
    }

    @After
    fun eliminarDatosDePrueba() {
        if (socioId > 0) {
            val id = arrayOf(socioId.toString())
            carnets.writableDatabase.delete("carnets", "nro_socio = ?", arrayOf(nroSocio))
            cuotas.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientes.writableDatabase.delete("clientes", "id = ?", id)
        }
        carnets.close()
        cuotas.close()
        clientes.close()
    }

    @Test
    fun inhabilitaElCarnetEnLaBajaYRecuperaSuVigenciaAlReingresar() {
        registrarPagoAnterior(TipoCuota.MENSUAL, hoy.minusDays(15), hoy)
        carnets.emitirORenovarPorPago(nroSocio, hoy)
        val carnet = requireNotNull(carnets.buscarCarnetPorDni(dni))

        assertEquals(1, clientes.darDeBajaSocio(socioId))
        assertEquals(nroSocio, clientes.buscarPorId(socioId)?.nroSocio)
        assertNull(carnets.buscarCarnetPorDni(dni))
        assertNull(carnets.buscarCarnetConDatosPorId(carnet.id))
        assertFalse(carnets.obtenerCarnetsConDatosSocio().any { it.id == carnet.id })

        assertEquals(1, clientes.asociarCliente(socioId))
        val recuperado = requireNotNull(carnets.buscarCarnetPorDni(dni))
        assertEquals(carnet.id, recuperado.id)
        assertEquals(nroSocio, recuperado.nroSocio)
        assertEquals(hoy.minusDays(15), recuperado.fechaEmision)
        assertEquals(hoy, recuperado.fechaVencimiento)
        assertTrue(cuotas.tieneCuotaVigente(dni))
    }

    @Test
    fun conMensualVencidaElMismoCarnetSeRenuevaAlCobrarLaNuevaCuota() {
        registrarPagoAnterior(TipoCuota.MENSUAL, hoy.minusMonths(1), hoy.minusDays(1))
        carnets.emitirORenovarPorPago(nroSocio, hoy)
        val carnetId = requireNotNull(carnets.buscarCarnetPorDni(dni)).id

        assertEquals(1, clientes.darDeBajaSocio(socioId))
        registrarPagoAnterior(TipoCuota.DIARIA, hoy, hoy)
        assertEquals(1, clientes.asociarCliente(socioId))
        assertNull(carnets.buscarCarnetPorDni(dni))
        assertFalse(cuotas.tieneCuotaVigente(dni))
        val cuotaAtrasada = cuotas.generarCuotaInicial(socioId, TipoCuota.MENSUAL, 45_000.0, 45_000.0, hoy.minusDays(1))
        assertTrue(cuotaAtrasada > 0)

        ActivityScenario.launch(CobrarCuotaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val buscador = activity.findViewById<EditText>(R.id.etBuscador)
                val confirmar = activity.findViewById<Button>(R.id.btnRegistrarPago)
                buscador.setText(dni)
                buscador.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                assertTrue(confirmar.isEnabled)
                confirmar.performClick()
            }
        }

        val renovado = requireNotNull(carnets.buscarCarnetPorDni(dni))
        assertEquals(carnetId, renovado.id)
        assertEquals(nroSocio, renovado.nroSocio)
        assertEquals(hoy.plusMonths(1), renovado.fechaVencimiento)
        val cuotaPagada = cuotas.obtenerCuotasPorCliente(socioId).single { it.id.toLong() == cuotaAtrasada }
        assertEquals(hoy, cuotaPagada.fecha)
        assertEquals(renovado.fechaVencimiento, cuotaPagada.fechaVencimiento)
    }

    private fun registrarPagoAnterior(tipo: TipoCuota, fechaPago: LocalDate, vencimiento: LocalDate) {
        val monto = if (tipo == TipoCuota.MENSUAL) 45_000.0 else 20_000.0
        val cuotaId = cuotas.generarCuotaInicial(socioId, tipo, monto, monto, vencimiento).toInt()
        assertTrue(cuotaId > 0)
        assertEquals(1, cuotas.registrarPago(cuotaId, socioId, fechaPago, monto, MedioPago.EFECTIVO, null))
        // Representa cuotas pagadas antes del cambio de regla.
        cuotas.writableDatabase.execSQL(
            "UPDATE cuotas SET fecha_vencimiento = ? WHERE id = ?",
            arrayOf(vencimiento.format(formato), cuotaId.toString())
        )
    }
}
