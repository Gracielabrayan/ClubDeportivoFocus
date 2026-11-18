package com.example.clubfocus

import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CobrarCuotaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientesHelper = ClientesDBHelper(context)
    private val cuotasHelper = CuotasDBHelper(context)
    private lateinit var dni: String
    private var clienteId = -1

    @Before
    fun crearClienteSinCuotas() {
        dni = dniDisponible()
        clienteId = clientesHelper.agregarCliente(
            nombre = "Cliente",
            apellido = "De prueba",
            dni = dni,
            fechaNacimiento = "01/01/2000",
            email = "cliente@example.invalid",
            aptoFisico = true,
            esSocio = false
        ).toInt()
        assertTrue(clienteId > 0)
    }

    @After
    fun eliminarDatosDePrueba() {
        if (clienteId > 0) {
            val id = arrayOf(clienteId.toString())
            cuotasHelper.writableDatabase.delete("carnets", "nro_socio IN (SELECT nro_socio FROM clientes WHERE id = ?)", id)
            cuotasHelper.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientesHelper.writableDatabase.delete("clientes", "id = ?", id)
        }
        cuotasHelper.close()
        clientesHelper.close()
    }

    @Test
    fun registraEnElPrimerIntentoLaCuotaRecienGenerada() {
        ActivityScenario.launch(CobrarCuotaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val buscador = activity.findViewById<EditText>(R.id.etBuscador)
                val confirmar = activity.findViewById<Button>(R.id.btnRegistrarPago)
                buscador.setText(dni)
                buscador.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                assertTrue(confirmar.isEnabled)
                confirmar.performClick()
            }

            assertEquals(EstadoCuota.PAGADA, cuotasHelper.obtenerCuotasPorCliente(clienteId).single().estado)
        }
    }

    @Test
    fun bloqueaElCobroAnteriorAlEditarElDniYBuscarUnoInexistente() {
        val dniInexistente = dniDisponible()
        ActivityScenario.launch(CobrarCuotaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val buscador = activity.findViewById<EditText>(R.id.etBuscador)
                val confirmar = activity.findViewById<Button>(R.id.btnRegistrarPago)
                buscador.setText(dni)
                buscador.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                assertTrue(confirmar.isEnabled)

                buscador.setText(dniInexistente)
                assertFalse(confirmar.isEnabled)
                buscador.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                assertFalse(confirmar.isEnabled)
            }

            assertEquals(EstadoCuota.PENDIENTE, cuotasHelper.obtenerCuotasPorCliente(clienteId).single().estado)
        }
    }

    private fun dniDisponible(): String = generateSequence {
        Random.nextInt(10_000_000, 100_000_000).toString()
    }.first { !clientesHelper.existeCliente(it) }
}
