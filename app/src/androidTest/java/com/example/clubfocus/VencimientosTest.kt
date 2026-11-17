package com.example.clubfocus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VencimientosTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientes = ClientesDBHelper(context)
    private val cuotas = CuotasDBHelper(context)
    private val clientesDePrueba = mutableListOf<Int>()

    @After
    fun eliminarDatosDePrueba() {
        clientesDePrueba.forEach { clienteId ->
            val id = arrayOf(clienteId.toString())
            cuotas.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientes.writableDatabase.delete("clientes", "id = ?", id)
        }
        cuotas.close()
        clientes.close()
    }

    @Test
    fun soloLasCuotasImpagasPasanAVencidasDespuesDeLaFechaLimite() {
        val hoy = LocalDate.now()
        val socio = crearCliente(esSocio = true)
        val noSocio = crearCliente(esSocio = false)
        val vencida = crearCuota(socio, hoy.minusDays(1))
        val venceHoy = crearCuota(socio, hoy)
        val futura = crearCuota(socio, hoy.plusDays(1))
        val pagadaAnterior = crearCuota(socio, hoy.minusDays(1))
        val pagadaQueVenceHoy = crearCuota(socio, hoy)
        val diariaVencida = crearCuota(noSocio, hoy.minusDays(1), TipoCuota.DIARIA)

        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        listOf(pagadaAnterior to hoy.minusDays(1), pagadaQueVenceHoy to hoy).forEach { (cuotaId, vencimiento) ->
            assertEquals(1, cuotas.registrarPago(cuotaId, socio, vencimiento.minusMonths(1), 45_000.0, MedioPago.EFECTIVO, null))
            // Los pagos de instalaciones anteriores conservan su fecha guardada.
            cuotas.writableDatabase.execSQL(
                "UPDATE cuotas SET fecha_vencimiento = ? WHERE id = ?",
                arrayOf(vencimiento.format(formato), cuotaId.toString())
            )
        }

        val estados = cuotas.obtenerHistorialPagos().associate { it.cuotaId to it.estado }
        assertEquals(EstadoCuota.VENCIDA, estados[vencida])
        assertEquals(EstadoCuota.PENDIENTE, estados[venceHoy])
        assertEquals(EstadoCuota.PENDIENTE, estados[futura])
        assertEquals(EstadoCuota.PAGADA, estados[pagadaAnterior])
        assertEquals(EstadoCuota.VENCIDA, estados[diariaVencida])
        assertEquals(EstadoCuota.VENCIDA, cuotas.obtenerCuotaImpagaPorCliente(socio)?.estado)

        val listado = clientes.obtenerVencimientosSocios()
            .filter { it.clienteId in clientesDePrueba }
            .associate { it.cuotaId to it.estadoCuota }
        assertEquals(
            mapOf(pagadaQueVenceHoy to EstadoCuota.PAGADA.name, vencida to EstadoCuota.VENCIDA.name),
            listado
        )

        assertEquals(1, cuotas.registrarPago(vencida, socio, hoy, 45_000.0, MedioPago.EFECTIVO, null))
        val paga = cuotas.obtenerHistorialPagos().first { it.cuotaId == vencida }
        assertEquals(EstadoCuota.PAGADA, paga.estado)
        assertEquals(hoy.plusMonths(1), paga.fechaVencimiento)
        assertTrue(clientes.obtenerVencimientosSocios().none { it.cuotaId == vencida })
    }

    @Test
    fun unPagoMensualElUltimoDiaVenceElUltimoDiaDisponibleDelMesSiguiente() {
        val socio = crearCliente(esSocio = true)
        val noSocio = crearCliente(esSocio = false)
        val vencimientoOriginal = LocalDate.of(2026, 1, 20)
        val fechaPago = LocalDate.of(2026, 1, 31)
        val mensual = crearCuota(socio, vencimientoOriginal)
        val diaria = crearCuota(noSocio, vencimientoOriginal, TipoCuota.DIARIA)

        assertEquals(1, cuotas.registrarPago(mensual, socio, fechaPago, 45_000.0, MedioPago.EFECTIVO, null))
        assertEquals(1, cuotas.registrarPago(diaria, noSocio, fechaPago, 20_000.0, MedioPago.EFECTIVO, null))
        assertEquals(LocalDate.of(2026, 2, 28), cuotas.obtenerCuotasPorCliente(socio).single().fechaVencimiento)
        assertEquals(vencimientoOriginal, cuotas.obtenerCuotasPorCliente(noSocio).single().fechaVencimiento)
    }

    private fun crearCliente(esSocio: Boolean): Int {
        val dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
            .first { !clientes.existeCliente(it) }
        val id = clientes.agregarCliente("Cliente", "De prueba", dni, "01/01/2000", "cliente@example.invalid", true, esSocio)
        assertTrue(id > 0L)
        return id.toInt().also { clientesDePrueba.add(it) }
    }

    private fun crearCuota(clienteId: Int, vencimiento: LocalDate, tipo: TipoCuota = TipoCuota.MENSUAL): Int {
        val monto = if (tipo == TipoCuota.MENSUAL) 45_000.0 else 20_000.0
        val id = cuotas.generarCuotaInicial(clienteId, tipo, monto, monto, vencimiento)
        assertTrue(id > 0L)
        return id.toInt()
    }
}
