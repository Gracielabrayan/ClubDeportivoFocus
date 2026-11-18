package com.example.clubfocus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.Tarifas
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CopiaClubDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import com.example.clubfocus.helperDB.DatosCopiaClub
import com.example.clubfocus.helperDB.TarifasDBHelper
import java.time.LocalDate
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
class TarifasTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombreDb = "tarifas-${UUID.randomUUID()}.db"
    private val tarifas = TarifasDBHelper(context, nombreDb)
    private val clientes = ClientesDBHelper(context, nombreDb)
    private val cuotas = CuotasDBHelper(context, nombreDb)
    private val nuevas = Tarifas(5_500_050, 2_500_075)

    @Before
    fun preparar() {
        tarifas.writableDatabase.execSQL(
            "INSERT INTO usuarios(usuario,password,rol) VALUES('propietario',?,'SuperAdmin'),('operador',?,'Administrador')",
            arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        )
    }

    @After
    fun cerrar() {
        tarifas.close()
        clientes.close()
        cuotas.close()
        context.deleteDatabase(nombreDb)
    }

    @Test
    fun lasTarifasNuevasSeAplicanAlGenerarYConservanLasCuotasExistentes() {
        val socioAnterior = registrar("10000001", true)
        val pendienteAnterior = registrar("10000002", false)
        val pagada = requireNotNull(cuotas.obtenerCuotaImpagaPorCliente(socioAnterior))
        assertEquals(1, cuotas.registrarPago(
            pagada.id, socioAnterior, LocalDate.now(), pagada.monto, MedioPago.EFECTIVO, null, "pago-anterior"
        ))
        assertTrue(tarifas.guardarTarifas("propietario", nuevas))

        assertEquals(45000.0, cuotas.obtenerCuotasPorCliente(socioAnterior).single().monto, 0.001)
        assertEquals(45000.0, cuotas.obtenerCuotasPorCliente(socioAnterior).single().montoFinal, 0.001)
        assertEquals(20000.0, cuotas.obtenerCuotasPorCliente(pendienteAnterior).single().monto, 0.001)

        val socioNuevo = registrar("10000003", true)
        val noSocioNuevo = registrar("10000004", false)
        assertEquals(55000.50, cuotas.obtenerCuotasPorCliente(socioNuevo).single().monto, 0.001)
        assertEquals(25000.75, cuotas.obtenerCuotasPorCliente(noSocioNuevo).single().monto, 0.001)

        val sinCuota = clientes.agregarCliente(
            "Cliente", "Sin cuota", "10000005", "01/01/2000", "cliente@example.invalid", true, true
        ).toInt()
        val nuevaCuota = requireNotNull(cuotas.generarCuotaParaCliente(sinCuota, true))
        assertEquals(55000.50, nuevaCuota.monto, 0.001)
        assertEquals(1, clientes.darDeBajaSocio(socioNuevo))
        assertEquals(25000.75, cuotas.obtenerCuotasPorCliente(socioNuevo).single().monto, 0.001)
        assertEquals(1, clientes.asociarCliente(socioNuevo))
        assertEquals(55000.50, cuotas.obtenerCuotasPorCliente(socioNuevo).single().monto, 0.001)

        assertTrue(cuotas.anularPago(pagada.id, "pago-anterior", "propietario", "Corrección"))
        assertEquals(45000.0, cuotas.obtenerCuotaImpagaPorCliente(socioAnterior)?.monto ?: 0.0, 0.001)
    }

    @Test
    fun validaImportesYReservaElCambioAlSuperAdmin() {
        val anteriores = tarifas.obtenerTarifas()
        assertFalse(tarifas.guardarTarifas("operador", nuevas))
        assertFalse(tarifas.guardarTarifas("", nuevas))
        assertFalse(tarifas.guardarTarifas("propietario", Tarifas(0, 1)))
        assertFalse(tarifas.guardarTarifas("propietario", Tarifas(1, -1)))
        assertFalse(tarifas.guardarTarifas("propietario", Tarifas(Tarifas.MAX_CENTAVOS + 1, 1)))
        assertEquals(anteriores, tarifas.obtenerTarifas())
        assertEquals(5500050L, Tarifas.parsearCentavos("55000,50"))
        assertEquals(2500075L, Tarifas.parsearCentavos("25000.75"))
        assertEquals(1L, Tarifas.parsearCentavos("0,01"))
        listOf("", "0", "-1", "1.001", "1,2,3", "NaN", "1000000000").forEach {
            assertNull(Tarifas.parsearCentavos(it))
        }
    }

    @Test
    fun lasCopiasNuevasRestauranTarifasYLasAnterioresConservanLasActuales() {
        assertTrue(tarifas.guardarTarifas("propietario", nuevas))
        CopiaClubDBHelper(context, nombreDb).use { copias ->
            val datos = copias.exportar("propietario")
            val copia = DatosCopiaClub.leer(datos)
            assertTrue(copia.incluyeTarifas)
            val anteriores = datos.toString(Charsets.UTF_8).substringBeforeLast(",\"tarifas\"") + "}"
            val copiaAnterior = DatosCopiaClub.leer(anteriores.toByteArray())
            assertFalse(copiaAnterior.incluyeTarifas)

            val otras = Tarifas(6_000_000, 3_000_000)
            assertTrue(tarifas.guardarTarifas("propietario", otras))
            copias.restaurar("propietario", copiaAnterior)
            assertEquals(otras, tarifas.obtenerTarifas())
            copias.restaurar("propietario", copia)
            assertEquals(nuevas, tarifas.obtenerTarifas())
        }
    }

    @Test
    fun actualizaUnaBaseAnteriorConservandoLosImportesDelClub() {
        val cliente = registrar("10000001", true)
        val db = tarifas.writableDatabase
        db.execSQL("DROP TABLE tarifas")
        db.version = 4
        tarifas.close()
        assertEquals(Tarifas(4_500_000, 2_000_000), tarifas.obtenerTarifas())
        assertEquals(6, tarifas.readableDatabase.version)
        assertEquals(45000.0, cuotas.obtenerCuotasPorCliente(cliente).single().monto, 0.001)
    }

    private fun registrar(dni: String, socio: Boolean): Int {
        val id = clientes.registrarClienteConCuotaInicial(
            "Cliente", "De prueba", dni, "01/01/2000", "cliente@example.invalid", true, socio
        )
        assertTrue(id > 0)
        return id.toInt()
    }
}
