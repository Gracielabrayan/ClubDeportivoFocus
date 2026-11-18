package com.example.clubfocus

import android.content.Intent
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.clubfocus.data.Contrasenas
import com.example.clubfocus.helperDB.UsuariosDBHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val tvOlvidoPass = findViewById<TextView>(R.id.tvOlvidasteContrasena)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)
        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etContrasena = findViewById<EditText>(R.id.etContrasena)
        val etConfirmacion = findViewById<EditText>(R.id.etConfirmacionContrasena)
        val avisoInicial = findViewById<TextView>(R.id.tvConfigurarSuperAdmin)
        var configuracionInicial: Boolean? = null

        fun cargarModo() {
            btnIngresar.isEnabled = false
            lifecycleScope.launch {
                try {
                    val requiereAlta = withContext(Dispatchers.IO) {
                        UsuariosDBHelper(applicationContext).use { it.requiereSuperAdmin() }
                    }
                    configuracionInicial = requiereAlta
                    avisoInicial.visibility = if (requiereAlta) View.VISIBLE else View.GONE
                    etConfirmacion.visibility = if (requiereAlta) View.VISIBLE else View.GONE
                    tvOlvidoPass.visibility = if (requiereAlta) View.GONE else View.VISIBLE
                    btnIngresar.setText(if (requiereAlta) R.string.login_crear_superadmin else R.string.btn_ingresar)
                } catch (_: SQLiteException) {
                    configuracionInicial = null
                    btnIngresar.setText(R.string.login_reintentar)
                    Toast.makeText(this@LoginActivity, R.string.login_error_base, Toast.LENGTH_LONG).show()
                } finally {
                    btnIngresar.isEnabled = true
                }
            }
        }

        btnIngresar.setOnClickListener {
            val configurando = configuracionInicial
            if (configurando == null) {
                cargarModo()
                return@setOnClickListener
            }

            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString()
            if (usuario.isBlank() || contrasena.isBlank()) {
                Toast.makeText(this, R.string.admin_panel_campos_obligatorios, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (configurando && !Contrasenas.esValidaParaAlta(contrasena)) {
                etContrasena.error = getString(R.string.contrasena_requisitos)
                return@setOnClickListener
            }
            if (configurando && contrasena != etConfirmacion.text.toString()) {
                etConfirmacion.error = getString(R.string.login_contrasenas_distintas)
                return@setOnClickListener
            }

            btnIngresar.isEnabled = false
            lifecycleScope.launch {
                try {
                    val rol = withContext(Dispatchers.IO) {
                        UsuariosDBHelper(applicationContext).use { helper ->
                            if (configurando) {
                                if (helper.crearSuperAdminInicial(usuario, contrasena) != -1L) "SuperAdmin" else null
                            } else {
                                helper.obtenerRol(usuario, contrasena)
                            }
                        }
                    }
                    if (rol != null) {
                        abrirMenu(usuario, rol)
                    } else if (configurando) {
                        Toast.makeText(this@LoginActivity, R.string.login_error_alta_superadmin, Toast.LENGTH_SHORT).show()
                        cargarModo()
                    } else {
                        Toast.makeText(this@LoginActivity, R.string.login_datos_incorrectos, Toast.LENGTH_SHORT).show()
                        btnIngresar.isEnabled = true
                    }
                } catch (_: SQLiteException) {
                    Toast.makeText(this@LoginActivity, R.string.login_error_base, Toast.LENGTH_LONG).show()
                    btnIngresar.isEnabled = true
                }
            }
        }

        tvOlvidoPass.setOnClickListener {
            Toast.makeText(this, R.string.login_contactar_soporte, Toast.LENGTH_LONG).show()
        }

        cargarModo()
    }

    private fun abrirMenu(usuario: String, rol: String) {
        getSharedPreferences("ClubFocusPrefs", MODE_PRIVATE).edit()
            .putString("usuarioLogueado", usuario)
            .putString("rolUsuario", rol)
            .apply()

        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("usuarioLogueado", usuario)
            putExtra("rolUsuario", rol)
        })
        finish()
    }
}
