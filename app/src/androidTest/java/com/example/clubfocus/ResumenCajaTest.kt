package com.example.clubfocus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumenCajaTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombreDb = "caja-${UUID.randomUUID()}.db"
    private val cuotas = CuotasDBHelper(context, nombreDb)
    private val dia = LocalDate.of(2026, 11, 17)

    @Before
    fun crearDatos() {
        val db = cuotas.writableDatabase
        db.execSQL(
            "INSERT INTO usuarios(usuario,password,rol) VALUES('propietario',?,'SuperAdmin'), ('operador',?,'Administrador')",
            arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        )
        db.execSQL(
            """
                INSERT INTO clientes(id,nombre,apellido,dni,fecha_nacimiento,email,apto_fisico,fecha_inscripcion,es_socio)
                VALUES(10,'María','González','10000001','01/01/2000','cliente@example.invalid',1,'01/11/2026',0)
            """.trimIndent()
        )
        guardarCuota(db, 1, "17/11/2026", "EFECTIVO", 1200.10)
        guardarCuota(db, 2, "17/11/2026", "EFECTIVO", 0.20)
        guardarCuota(db, 3, "17/11/2026", "TARJETA_DEBITO", 380.20)
        guardarCuota(db, 4, "2026-11-17", "TARJETA_CREDITO", 99.99)
        guardarCuota(db, 5, "17/11/2026", null, 8.15)
        guardarCuota(db, 6, "16/11/2026", "EFECTIVO", 4000.0)
        guardarCuota(db, 7, null, null, 5500.0, "PENDIENTE")
        guardarCuota(db, 8, null, null, 6500.0, "VENCIDA")
    }

    @After
    fun cerrar() {
        cuotas.close()
        context.deleteDatabase(nombreDb)
    }

    @Test
    fun agrupaSoloLosCobrosDeLaFechaElegidaPorMedioDePago() {
        val resumen = requireNotNull(cuotas.obtenerResumenCaja("propietario", dia))
        assertEquals(setOf(1, 2, 3, 4, 5), resumen.pagos.map { it.cuotaId }.toSet())
        assertEquals(BigDecimal("1200.30"), resumen.subtotal(MedioPago.EFECTIVO))
        assertEquals(BigDecimal("380.20"), resumen.subtotal(MedioPago.TARJETA_DEBITO))
        assertEquals(BigDecimal("99.99"), resumen.subtotal(MedioPago.TARJETA_CREDITO))
        assertTrue(resumen.hayPagosSinMedio)
        assertEquals(BigDecimal("8.15"), resumen.subtotal(null))
        assertEquals(BigDecimal("1688.64"), resumen.total)

        val vacio = requireNotNull(cuotas.obtenerResumenCaja("propietario", dia.plusDays(1)))
        assertTrue(vacio.pagos.isEmpty())
        assertEquals(BigDecimal.ZERO, vacio.total)
    }

    @Test
    fun reservaLaConsultaAlSuperAdmin() {
        assertNull(cuotas.obtenerResumenCaja("operador", dia))
        assertNull(cuotas.obtenerResumenCaja("", dia))
    }

    private fun guardarCuota(
        db: SQLiteDatabase,
        id: Int,
        fecha: String?,
        medio: String?,
        monto: Double,
        estado: String = "PAGADA"
    ) {
        db.execSQL(
            """
                INSERT INTO cuotas(id,cliente_id,tipo,monto,monto_final,fecha_pago,fecha_vencimiento,medio_pago,estado)
                VALUES(?,10,'DIARIA',?, ?, ?, '17/11/2026', ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(id, monto, monto, fecha, medio, estado)
        )
    }
}
