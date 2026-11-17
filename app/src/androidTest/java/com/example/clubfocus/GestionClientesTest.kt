package com.example.clubfocus

import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.adapter.ClienteBusquedaAdapter
import com.example.clubfocus.helperDB.ClientesDBHelper
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestionClientesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientes = ClientesDBHelper(context)
    private var clienteId = -1

    @After
    fun eliminarDatosDePrueba() {
        if (clienteId > 0) {
            clientes.writableDatabase.delete("clientes", "id = ?", arrayOf(clienteId.toString()))
        }
        clientes.close()
    }

    @Test
    fun exigeCorreoValidoYAptoParaEditarYAsociar() {
        val dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
            .first { !clientes.existeCliente(it) }
        clienteId = clientes.agregarCliente(
            nombre = "Cliente",
            apellido = "De prueba",
            dni = dni,
            fechaNacimiento = "01/01/2000",
            email = "",
            aptoFisico = false,
            esSocio = false
        ).toInt()
        assertTrue(clienteId > 0)

        ActivityScenario.launch(ClientesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etBuscarDni).setText(dni)
                val resultados = activity.findViewById<RecyclerView>(R.id.rvCoincidencias)
                val adapter = resultados.adapter as ClienteBusquedaAdapter
                assertEquals(1, adapter.itemCount)
                val fila = adapter.createViewHolder(resultados, adapter.getItemViewType(0))
                adapter.bindViewHolder(fila, 0)
                fila.itemView.performClick()

                val asociar = activity.findViewById<Button>(R.id.btnAsociar)
                asociar.performClick()
                assertFalse(requireNotNull(clientes.buscarPorId(clienteId)).esSocio)

                activity.findViewById<Button>(R.id.btnEditarDatos).performClick()
                val guardar = activity.findViewById<Button>(R.id.btnGuardarCambios)
                val correo = activity.findViewById<EditText>(R.id.etEmail)
                val apto = activity.findViewById<RadioButton>(R.id.rbAptoFisico)

                correo.setText("cliente@example.invalid")
                guardar.performClick()
                assertEquals("", requireNotNull(clientes.buscarPorId(clienteId)).email)

                apto.isChecked = true
                correo.setText("correo inválido")
                guardar.performClick()
                assertFalse(requireNotNull(clientes.buscarPorId(clienteId)).aptoFisico)

                correo.setText("cliente@example.invalid")
                guardar.performClick()
                val ficha = requireNotNull(clientes.buscarPorId(clienteId))
                assertEquals("cliente@example.invalid", ficha.email)
                assertTrue(ficha.aptoFisico)

                asociar.performClick()
                assertTrue(requireNotNull(clientes.buscarPorId(clienteId)).esSocio)
            }
        }
    }
}
