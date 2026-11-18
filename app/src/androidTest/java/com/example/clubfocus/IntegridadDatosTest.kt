package com.example.clubfocus

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.CopiaNoValida
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.CarnetDBHelper
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import com.example.clubfocus.helperDB.DatosCopiaClub
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegridadDatosTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombreDb = "integridad-${UUID.randomUUID()}.db"
    private val cuotas = CuotasDBHelper(context, nombreDb)
    private val clientes = ClientesDBHelper(context, nombreDb)
    private val hoy = LocalDate.now()
    private var clienteId = -1

    @Before
    fun preparar() {
        clienteId = clientes.agregarCliente(
            "Cliente", "De prueba", "10000001", "01/01/2000", "cliente@example.invalid", true, true
        ).toInt()
        cuotas.writableDatabase.execSQL(
            "INSERT INTO usuarios(usuario,password,rol) VALUES('propietario',?,'SuperAdmin')",
            arrayOf(UUID.randomUUID().toString())
        )
    }

    @After
    fun cerrar() {
        clientes.close()
        cuotas.close()
        context.deleteDatabase(nombreDb)
    }

    @Test
    fun mantieneLasRelacionesYAgregaIndicesAlActualizarUnaBaseAnterior() {
        val cuota = crearCuota()
        val db = cuotas.writableDatabase
        db.rawQuery("PRAGMA foreign_keys", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        assertEquals(-1L, cuotas.generarCuotaInicial(99999, TipoCuota.MENSUAL, 1000.0, 1000.0, hoy))
        assertThrows(SQLiteException::class.java) {
            db.delete("clientes", "id = ?", arrayOf(clienteId.toString()))
        }
        listOf("idx_cuotas_cliente_estado", "idx_cuotas_estado_fecha_pago", "idx_pagos_anulados_cuota").forEach {
            db.execSQL("DROP INDEX $it")
        }
        db.version = 5
        cuotas.close()
        assertEquals(6, cuotas.readableDatabase.version)
        cuotas.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN " +
                "('idx_cuotas_cliente_estado','idx_cuotas_estado_fecha_pago','idx_pagos_anulados_cuota')", null
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
        }
        assertEquals(cuota, cuotas.obtenerCuotaImpagaPorCliente(clienteId)?.id)
        cuotas.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }

    @Test
    fun unFalloDelCarnetRevierteElCobroCompletoYPermiteReintentar() {
        val cuota = crearCuota()
        val db = cuotas.writableDatabase
        db.execSQL(
            "CREATE TRIGGER fallo_carnet BEFORE INSERT ON carnets BEGIN SELECT RAISE(ABORT,'fallo de prueba'); END"
        )
        assertThrows(SQLiteException::class.java) {
            cuotas.registrarPago(cuota, clienteId, hoy, 1000.0, MedioPago.EFECTIVO, null)
        }
        assertEquals(EstadoCuota.PENDIENTE, cuotas.obtenerCuotaImpagaPorCliente(clienteId)?.estado)
        assertNull(cuotas.obtenerPagoParaComprobante(cuota))
        assertFalse(requireNotNull(clientes.buscarPorId(clienteId)).activo)
        db.execSQL("DROP TRIGGER fallo_carnet")
        assertEquals(1, cuotas.registrarPago(cuota, clienteId, hoy, 1000.0, MedioPago.EFECTIVO, null))
        CarnetDBHelper(context, nombreDb).use {
            val carnet = requireNotNull(it.buscarCarnetPorDni("10000001"))
            assertEquals(hoy.plusMonths(1), carnet.fechaVencimiento)
        }
    }

    @Test
    fun evitaDuplicarUnaPendienteYRechazaUnCobroPreparadoConOtroImporte() {
        val cuota = crearCuota()
        assertEquals(cuota, cuotas.generarCuotaParaCliente(clienteId, true)?.id)
        cuotas.writableDatabase.execSQL("UPDATE cuotas SET monto=2000, monto_final=2000 WHERE id=?", arrayOf(cuota))
        assertEquals(0, cuotas.registrarPago(
            cuota, clienteId, hoy, 1000.0, MedioPago.EFECTIVO, null, montoBaseEsperado = 1000.0
        ))
        assertEquals(1, cuotas.registrarPago(
            cuota, clienteId, hoy, 2000.0, MedioPago.EFECTIVO, null, montoBaseEsperado = 2000.0
        ))
        assertNull(cuotas.generarCuotaParaCliente(clienteId, true))
        assertEquals(1, cuotas.obtenerCuotasPorCliente(clienteId).size)
    }

    @Test
    fun cambiaUnaCuotaVencidaALaNuevaCondicionYLeeLasFechasAnterioresEnIso() {
        val cuota = crearCuota()
        cuotas.writableDatabase.execSQL(
            "UPDATE cuotas SET estado='VENCIDA',fecha_vencimiento=? WHERE id=?",
            arrayOf<Any>(hoy.minusDays(1).toString(), cuota)
        )
        cuotas.writableDatabase.execSQL(
            "UPDATE clientes SET fecha_nacimiento='2000-01-01',fecha_inscripcion='2026-01-01' WHERE id=?",
            arrayOf(clienteId)
        )
        assertEquals(LocalDate.of(2000, 1, 1), clientes.buscarPorId(clienteId)?.fechaNacimiento)
        assertEquals(LocalDate.of(2026, 1, 1), clientes.buscarPorDni("10000001")?.fechaInscripcion)
        assertEquals(clienteId, clientes.buscarPorDniParcial("100").single().id)
        assertEquals(1, clientes.darDeBajaSocio(clienteId))
        val diaria = requireNotNull(cuotas.obtenerCuotaImpagaPorCliente(clienteId))
        assertEquals(TipoCuota.DIARIA, diaria.tipo)
        assertEquals(EstadoCuota.PENDIENTE, diaria.estado)
        assertEquals(hoy, diaria.fechaVencimiento)
    }

    @Test
    fun rechazaUnaCopiaConUnCarnetSinClienteRelacionado() {
        val copia = """
            {"clientes":[],"cuotas":[],"carnets":[[1,"CAR999","01/01/2026","01/02/2026"]]}
        """.trimIndent()
        assertThrows(CopiaNoValida::class.java) { DatosCopiaClub.leer(copia.toByteArray()) }
        assertEquals(clienteId, clientes.buscarPorDni("10000001")?.id)
    }

    private fun crearCuota(): Int =
        cuotas.generarCuotaInicial(clienteId, TipoCuota.MENSUAL, 1000.0, 1000.0, hoy).toInt()
}
