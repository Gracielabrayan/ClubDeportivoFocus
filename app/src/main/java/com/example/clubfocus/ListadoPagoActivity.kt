package com.example.clubfocus

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clubfocus.adapter.HistorialPagoAdapter
import com.example.clubfocus.data.entity.PagoHistorialItem
import com.example.clubfocus.helperDB.CuotasDBHelper

class ListadoPagoActivity : AppCompatActivity() {

    private lateinit var rvHistorial: RecyclerView
    private lateinit var adapter: HistorialPagoAdapter
    private lateinit var cuotasHelper: CuotasDBHelper
    private var todosLosPagos: List<PagoHistorialItem> = emptyList()
    private var dniFiltrado = ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_listado_pago)

        cuotasHelper = CuotasDBHelper(this)
        dniFiltrado = savedInstanceState?.getString("dniFiltrado").orEmpty()

        val btnVolver = findViewById<TextView>(R.id.txtVolver)
        val etBuscador = findViewById<EditText>(R.id.etBuscador)
        rvHistorial = findViewById(R.id.rvHistorialPagos)

        rvHistorial.layoutManager = LinearLayoutManager(this)
        adapter = HistorialPagoAdapter(emptyList()) { pago ->
            startActivity(Intent(this, ComprobantePagoExitoso::class.java).apply {
                putExtra(ComprobantePagoExitoso.EXTRA_CUOTA_ID, pago.cuotaId)
                putExtra(ComprobantePagoExitoso.EXTRA_CLAVE_PAGO, pago.clavePago.orEmpty())
            })
        }
        rvHistorial.adapter = adapter

        btnVolver.setOnClickListener {
            finish()
        }

        // 2. FILTRAR POR DNI
        fun filtrarPorDni() {
            dniFiltrado = etBuscador.text.toString().trim()
            val resultado = pagosFiltrados()
            adapter.actualizarLista(resultado)

            if (dniFiltrado.isNotEmpty() && resultado.isEmpty()) {
                Toast.makeText(this, "No se encontraron pagos para ese DNI", Toast.LENGTH_SHORT).show()
            }
        }

        // Clic en la lupa
        etBuscador.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = etBuscador.compoundDrawables[2]

                if (drawableEnd != null) {
                    val drawableWidth = drawableEnd.bounds.width()

                    if (event.x >= etBuscador.width - etBuscador.paddingEnd - drawableWidth - 40) {
                        v.performClick()
                        filtrarPorDni()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Presionar la tecla de búsqueda en el teclado
        etBuscador.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filtrarPorDni()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        todosLosPagos = cuotasHelper.obtenerHistorialPagos()
        adapter.actualizarLista(pagosFiltrados())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("dniFiltrado", dniFiltrado)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cuotasHelper.close()
        super.onDestroy()
    }

    private fun pagosFiltrados() = todosLosPagos.filter { it.dni.contains(dniFiltrado, ignoreCase = true) }
}
