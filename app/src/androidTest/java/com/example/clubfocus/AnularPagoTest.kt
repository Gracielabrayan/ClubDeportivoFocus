package com.example.clubfocus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.helperDB.CopiaClubDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import com.example.clubfocus.helperDB.DatosCopiaClub
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnularPagoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombreDb = "anulaciones-${UUID.randomUUID()}.db"
    private val cuotas = CuotasDBHelper(context, nombreDb)
    private val hoy = LocalDate.now()
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    @Before
    fun preparar() {
        val db = cuotas.writableDatabase
        db.execSQL(
            "INSERT INTO usuarios(usuario,password,rol) VALUES('propietario',?,'SuperAdmin'), ('operador',?,'Administrador')",
            arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        )
        db.execSQL(
            """
                INSERT INTO clientes(id,nombre,apellido,dni,fecha_nacimiento,email,apto_fisico,fecha_inscripcion,es_socio,nro_socio)
                VALUES(1,'Cliente','De prueba','10000001','01/01/2000','cliente@example.invalid',1,'01/01/2026',1,'CAR1001')
            """.trimIndent()
        )
    }

    @After
    fun cerrar() {
        cuotas.close()
        context.deleteDatabase(nombreDb)
    }

    @Test
    fun anulaSoloElCobroElegidoYConservaElHistorialAlRecobrarYRestaurar() {
        val vencimiento = hoy.minusDays(1)
        crearCuota(10, vencimiento)
        pagar(10, hoy, "primer-cobro")

        assertFalse(cuotas.anularPago(10, "primer-cobro", "operador", "Error"))
        assertFalse(cuotas.anularPago(10, "primer-cobro", "propietario", "  "))
        assertEquals(1, cuotas.obtenerResumenCaja("propietario", hoy)?.pagos?.size)
        assertTrue(cuotas.anularPago(10, "primer-cobro", "propietario", " Importe incorrecto "))

        val disponible = requireNotNull(cuotas.obtenerCuotaImpagaPorCliente(1))
        assertEquals(EstadoCuota.VENCIDA, disponible.estado)
        assertEquals(vencimiento, disponible.fechaVencimiento)
        assertNull(cuotas.obtenerPagoParaComprobante(10, "primer-cobro"))
        assertEquals(0, cuotas.obtenerResumenCaja("propietario", hoy)?.pagos?.size)
        val cobroAnulado = cuotas.obtenerHistorialPagos().single { it.anulacion != null }
        assertEquals("Importe incorrecto", cobroAnulado.anulacion?.motivo)
        assertEquals("propietario", cobroAnulado.anulacion?.usuario)
        assertEquals(950.0, cobroAnulado.montoFinal, 0.01)

        pagar(10, hoy, "segundo-cobro")
        assertFalse(cuotas.anularPago(10, "primer-cobro", "propietario", "Comprobante viejo"))
        assertNull(cuotas.obtenerPagoParaComprobante(10, "primer-cobro"))
        assertEquals("segundo-cobro", cuotas.obtenerPagoParaComprobante(10, "segundo-cobro")?.clavePago)
        assertEquals(1, cuotas.obtenerResumenCaja("propietario", hoy)?.pagos?.size)

        CopiaClubDBHelper(context, nombreDb).use { copias ->
            val copia = DatosCopiaClub.leer(copias.exportar("propietario"))
            assertEquals(1, copia.anulaciones)
            copias.restaurar("propietario", copia)
        }
        assertEquals("Importe incorrecto", cuotas.obtenerHistorialPagos().single { it.anulacion != null }.anulacion?.motivo)
        assertEquals("segundo-cobro", cuotas.obtenerPagoParaComprobante(10, "segundo-cobro")?.clavePago)
    }

    @Test
    fun conservaElCarnetMientrasQuedeOtraMensualidadVigente() {
        crearCuota(10, hoy)
        crearCuota(11, hoy)
        pagar(10, hoy.minusDays(4), "pago-anterior")
        pagar(11, hoy, "ultimo-pago")
        val db = cuotas.writableDatabase
        db.execSQL("UPDATE carnets SET id=3,fecha_emision=?,fecha_vencimiento=? WHERE nro_socio='CAR1001'",
            arrayOf(hoy.format(formato), hoy.plusMonths(1).format(formato)))

        assertTrue(cuotas.anularPago(11, "ultimo-pago", "propietario", "Cobro duplicado"))
        db.rawQuery("SELECT id,fecha_emision,fecha_vencimiento FROM carnets WHERE nro_socio='CAR1001'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
            assertEquals(hoy.minusDays(4).format(formato), it.getString(1))
            assertEquals(hoy.minusDays(4).plusMonths(1).format(formato), it.getString(2))
        }
        assertTrue(cuotas.tieneCuotaVigente("10000001"))
        assertEquals(EstadoCuota.PENDIENTE, cuotas.obtenerCuotaImpagaPorCliente(1)?.estado)

        assertTrue(cuotas.anularPago(10, "pago-anterior", "propietario", "Otro error"))
        assertFalse(cuotas.tieneCuotaVigente("10000001"))
        db.rawQuery("SELECT activo FROM clientes WHERE id=1", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun restauraUnaCopiaAnteriorYConservaElVencimientoDisponibleAlAnular() {
        val copiaAnterior = """
            {"clientes":[[1,"Cliente","De prueba","10000001","01/01/2000","cliente@example.invalid",1,"01/01/2026",1,1,"CAR1001"]],
             "cuotas":[[10,1,"MENSUAL",1000,950,"01/11/2026","01/12/2026","EFECTIVO",null,"PAGADA"]],
             "carnets":[]}
        """.trimIndent()
        val copia = DatosCopiaClub.leer(copiaAnterior.toByteArray())
        assertEquals(0, copia.anulaciones)
        CopiaClubDBHelper(context, nombreDb).use { it.restaurar("propietario", copia) }
        assertFalse(requireNotNull(cuotas.obtenerPagoParaComprobante(10, "")).vencimientoAnteriorConocido)
        assertTrue(cuotas.anularPago(10, null, "propietario", "Corrección de cobro anterior"))
        assertEquals(LocalDate.of(2026, 12, 1), cuotas.obtenerCuotaImpagaPorCliente(1)?.fechaVencimiento)
    }

    @Test
    fun alRecobrarUnaDiariaLaFechaYLaVigenciaSonDelNuevoPago() {
        val fechaAnterior = hoy.minusDays(7)
        cuotas.writableDatabase.execSQL("UPDATE clientes SET es_socio=0, nro_socio=NULL WHERE id=1")
        cuotas.writableDatabase.execSQL(
            """
                INSERT INTO cuotas(id,cliente_id,tipo,monto,monto_final,fecha_vencimiento,estado)
                VALUES(10,1,'DIARIA',1000,1000,?,'PENDIENTE')
            """.trimIndent(), arrayOf(fechaAnterior.minusDays(1).format(formato))
        )
        pagar(10, fechaAnterior, "diaria-anterior")
        val anterior = requireNotNull(cuotas.obtenerPagoParaComprobante(10, "diaria-anterior"))
        assertEquals(fechaAnterior.format(formato), anterior.fechaPago)
        assertEquals(fechaAnterior, cuotas.obtenerHistorialPagos().single().fechaVencimiento)

        assertTrue(cuotas.anularPago(10, "diaria-anterior", "propietario", "Cobro equivocado"))
        pagar(10, hoy, "diaria-nueva")

        val nuevo = requireNotNull(cuotas.obtenerPagoParaComprobante(10, "diaria-nueva"))
        assertEquals(hoy.format(formato), nuevo.fechaPago)
        assertTrue(cuotas.tieneCuotaVigente("10000001"))
        val historial = cuotas.obtenerHistorialPagos()
        val vigente = historial.single { it.anulacion == null }
        assertEquals(hoy, vigente.fechaPago)
        assertEquals(hoy, vigente.fechaVencimiento)
        assertEquals(fechaAnterior, historial.single { it.anulacion != null }.fechaPago)
        assertEquals(fechaAnterior, historial.single { it.anulacion != null }.fechaVencimiento)
        assertEquals(1, cuotas.obtenerResumenCaja("propietario", hoy)?.pagos?.size)
        assertEquals(0, cuotas.obtenerResumenCaja("propietario", fechaAnterior)?.pagos?.size)
    }

    @Test
    fun actualizaLaVigenciaDeDiariasYaCobradasYConservaLosCobrosAnulados() {
        val fechaAnterior = hoy.minusDays(7).format(formato)
        val db = cuotas.writableDatabase
        db.execSQL(
            """
                INSERT INTO cuotas(id,cliente_id,tipo,monto,monto_final,fecha_pago,fecha_vencimiento,medio_pago,estado)
                VALUES(10,1,'DIARIA',1000,1000,?,?,'EFECTIVO','PAGADA')
            """.trimIndent(), arrayOf(hoy.format(formato), fechaAnterior)
        )
        db.execSQL(
            """
                INSERT INTO pagos_anulados(cuota_id,tipo,monto_final,fecha_pago,fecha_vencimiento,medio_pago,
                    fecha_anulacion,usuario,motivo)
                VALUES(10,'DIARIA',1000,?,?,'EFECTIVO',?,'propietario','Cobro equivocado')
            """.trimIndent(), arrayOf(fechaAnterior, fechaAnterior, hoy.format(formato))
        )
        db.version = 3
        cuotas.close()

        assertEquals(6, cuotas.readableDatabase.version)
        val historial = cuotas.obtenerHistorialPagos()
        val vigente = historial.single { it.anulacion == null }
        assertEquals(hoy, vigente.fechaPago)
        assertEquals(hoy, vigente.fechaVencimiento)
        assertEquals(hoy.minusDays(7), historial.single { it.anulacion != null }.fechaVencimiento)
    }

    private fun crearCuota(id: Int, vencimiento: LocalDate) {
        cuotas.writableDatabase.execSQL(
            """
                INSERT INTO cuotas(id,cliente_id,tipo,monto,monto_final,fecha_vencimiento,estado)
                VALUES(?,1,'MENSUAL',1000,1000,?,'PENDIENTE')
            """.trimIndent(), arrayOf(id, vencimiento.format(formato))
        )
    }

    private fun pagar(id: Int, fecha: LocalDate, clave: String) {
        assertEquals(1, cuotas.registrarPago(id, 1, fecha, 950.0, MedioPago.EFECTIVO, null, clave))
    }
}
