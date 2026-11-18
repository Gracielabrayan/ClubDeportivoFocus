package com.example.clubfocus

import android.app.Activity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.time.LocalDate
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegistroClienteTest {
    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentacion.targetContext
    private val clientes = ClientesDBHelper(context)
    private val cuotas = CuotasDBHelper(context)
    private val pantallas = mutableListOf<Activity>()
    private val dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
        .first { !clientes.existeCliente(it) }

    @After
    fun limpiarDatosDePrueba() {
        instrumentacion.runOnMainSync { pantallas.asReversed().forEach { it.finish() } }
        clientes.buscarPorDni(dni)?.let { cliente ->
            val id = arrayOf(cliente.id.toString())
            cuotas.writableDatabase.delete("carnets", "nro_socio IN (SELECT nro_socio FROM clientes WHERE id = ?)", id)
            cuotas.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientes.writableDatabase.delete("clientes", "id = ?", id)
        }
        cuotas.close()
        clientes.close()
    }

    @Test
    fun rechazaFechasDeNacimientoImposiblesYFuturas() {
        ActivityScenario.launch(RegistrarSocioActivity::class.java).use { escenario ->
            escenario.onActivity { activity ->
                completarFormulario(activity, esSocio = true)
                val fecha = activity.findViewById<EditText>(R.id.etFechaNacimiento)
                fecha.setText("31/02/2000")
                activity.findViewById<Button>(R.id.btnGuardarRegistro).performClick()
                assertNull(clientes.buscarPorDni(dni))
                fecha.setText(LocalDate.now().plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                activity.findViewById<Button>(R.id.btnGuardarRegistro).performClick()
                assertNull(clientes.buscarPorDni(dni))
            }
        }
    }

    @Test
    fun altaNoSocioPermiteVolverAlMenuYCobrarLaMismaCuotaDespues() {
        val registro = ActivityScenario.launch(RegistrarSocioActivity::class.java).use { scenario ->
            abrirPantalla(RegistroExitosoActivity::class.java) {
                scenario.onActivity { activity ->
                    completarFormulario(activity, esSocio = false)
                    activity.findViewById<Button>(R.id.btnGuardarRegistro).performClick()
                }
            }
        }

        val cliente = requireNotNull(clientes.buscarPorDni(dni))
        val pendiente = cuotas.obtenerCuotasPorCliente(cliente.id).single()
        assertEquals(cliente.id, registro.intent.getIntExtra("clienteId", -1))
        assertEquals(TipoCuota.DIARIA, pendiente.tipo)
        assertEquals(EstadoCuota.PENDIENTE, pendiente.estado)
        assertEquals(20_000.0, pendiente.montoFinal, 0.0)
        assertNull(pendiente.fecha)

        val menu = abrirPantalla(HomeActivity::class.java) {
            instrumentacion.runOnMainSync { registro.findViewById<TextView>(R.id.btnIrInicio).performClick() }
        }
        val cobro = abrirPantalla(CobrarCuotaActivity::class.java) {
            instrumentacion.runOnMainSync { menu.findViewById<Button>(R.id.btnCobroCuotas).performClick() }
        }
        abrirPantalla(ConfirmarPagoActivity::class.java) {
            instrumentacion.runOnMainSync {
                val buscador = cobro.findViewById<EditText>(R.id.etBuscador)
                buscador.setText(dni)
                buscador.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                cobro.findViewById<Button>(R.id.btnRegistrarPago).performClick()
            }
        }

        val pagada = cuotas.obtenerCuotasPorCliente(cliente.id).single()
        assertEquals(pendiente.id, pagada.id)
        assertEquals(EstadoCuota.PAGADA, pagada.estado)
    }

    @Test
    fun siFallaLaCuotaElAltaSeRevierteYSePuedeReintentarConElMismoDni() {
        val db = clientes.writableDatabase
        val trigger = "prueba_fallo_cuota_$dni"
        db.execSQL(
            """
                CREATE TRIGGER $trigger BEFORE INSERT ON cuotas
                WHEN EXISTS (SELECT 1 FROM clientes WHERE id = NEW.cliente_id AND dni = '$dni')
                BEGIN SELECT RAISE(IGNORE); END
            """.trimIndent()
        )

        ActivityScenario.launch(RegistrarSocioActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    completarFormulario(activity, esSocio = true)
                    activity.findViewById<Button>(R.id.btnGuardarRegistro).performClick()
                }
                assertNull(clientes.buscarPorDni(dni))
            } finally {
                db.execSQL("DROP TRIGGER $trigger")
            }

            abrirPantalla(RegistroExitosoActivity::class.java) {
                scenario.onActivity { it.findViewById<Button>(R.id.btnGuardarRegistro).performClick() }
            }
        }

        val cliente = requireNotNull(clientes.buscarPorDni(dni))
        val cuota = cuotas.obtenerCuotasPorCliente(cliente.id).single()
        assertTrue(cliente.nroSocio.isNotBlank())
        assertEquals(EstadoCuota.PENDIENTE, cuota.estado)
        assertEquals(TipoCuota.MENSUAL, cuota.tipo)
        assertEquals(45_000.0, cuota.montoFinal, 0.0)
        assertEquals(LocalDate.now().plusMonths(1), cuota.fechaVencimiento)
    }

    private fun completarFormulario(activity: Activity, esSocio: Boolean) {
        val condicion = if (esSocio) R.id.rbSocio else R.id.rbNoSocio
        activity.findViewById<RadioButton>(condicion).isChecked = true
        activity.findViewById<CheckBox>(R.id.cbAptoFisico).isChecked = true
        activity.findViewById<EditText>(R.id.etNombre).setText("Cliente")
        activity.findViewById<EditText>(R.id.etApellido).setText("De prueba")
        activity.findViewById<EditText>(R.id.etDni).setText(dni)
        activity.findViewById<EditText>(R.id.etFechaNacimiento).setText("01/01/2000")
        activity.findViewById<EditText>(R.id.etCorreo).setText("cliente@example.invalid")
    }

    private fun <T : Activity> abrirPantalla(clase: Class<T>, accion: () -> Unit): T {
        val monitor = instrumentacion.addMonitor(clase.name, null, false)
        try {
            accion()
            val pantalla = requireNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 20_000))
            pantallas.add(pantalla)
            return clase.cast(pantalla)!!
        } finally {
            instrumentacion.removeMonitor(monitor)
        }
    }
}
