package com.example.clubfocus

import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.clubfocus.data.entity.Tarifas
import com.example.clubfocus.helperDB.TarifasDBHelper
import com.example.clubfocus.helperDB.UsuariosDBHelper
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TarifasActivity : AppCompatActivity() {
    private val usuario: String
        get() = getSharedPreferences("ClubFocusPrefs", MODE_PRIVATE).getString("usuarioLogueado", "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tarifas)
        val mensual = findViewById<EditText>(R.id.etTarifaMensual)
        val diaria = findViewById<EditText>(R.id.etTarifaDiaria)
        val guardar = findViewById<Button>(R.id.btnGuardarTarifas)
        findViewById<TextView>(R.id.txtVolverTarifas).setOnClickListener { finish() }

        try {
            val autorizado = UsuariosDBHelper(this).use {
                it.obtenerRolPorUsuario(usuario).equals("SuperAdmin", ignoreCase = true)
            }
            if (!autorizado) {
                Toast.makeText(this, R.string.admin_panel_solo_superadmin, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            if (savedInstanceState == null) {
                val tarifas = TarifasDBHelper(this).use { it.obtenerTarifas() }
                mensual.setText(BigDecimal.valueOf(tarifas.mensualCentavos, 2).toPlainString().replace('.', ','))
                diaria.setText(BigDecimal.valueOf(tarifas.diariaCentavos, 2).toPlainString().replace('.', ','))
            }
        } catch (_: SQLiteException) {
            Toast.makeText(this, R.string.login_error_base, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        guardar.setOnClickListener {
            val precioMensual = Tarifas.parsearCentavos(mensual.text.toString())
            val precioDiaria = Tarifas.parsearCentavos(diaria.text.toString())
            mensual.error = if (precioMensual == null) getString(R.string.tarifas_importe_invalido) else null
            diaria.error = if (precioDiaria == null) getString(R.string.tarifas_importe_invalido) else null
            if (precioMensual == null || precioDiaria == null) return@setOnClickListener

            guardar.isEnabled = false
            val usuarioActual = usuario
            lifecycleScope.launch {
                val guardado = withContext(Dispatchers.IO) {
                    try {
                        TarifasDBHelper(applicationContext).use {
                            it.guardarTarifas(usuarioActual, Tarifas(precioMensual, precioDiaria))
                        }
                    } catch (_: SQLiteException) {
                        false
                    }
                }
                Toast.makeText(
                    this@TarifasActivity,
                    if (guardado) R.string.tarifas_guardadas else R.string.tarifas_error,
                    Toast.LENGTH_LONG
                ).show()
                if (guardado) finish() else guardar.isEnabled = true
            }
        }
    }
}
